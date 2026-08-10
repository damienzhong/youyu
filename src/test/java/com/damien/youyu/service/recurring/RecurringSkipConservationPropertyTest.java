package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.TransactionRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Feature: recurring-transactions, Property 7: 跳过守恒
 *
 * <p>{@link RecurringPendingItemService#skip} 的属性测试，覆盖 design.md「Correctness Properties」
 * Property 7：</p>
 *
 * <p><em>对任意</em> {@link PendingStatus#PENDING} 待确认项，跳过后其状态置为 {@link PendingStatus#SKIPPED}，
 * 且<b>不生成任何流水</b>（{@code transactionRepository.count()} 恒为 0）、<b>不改变任何账户余额</b>——
 * 跳过是纯粹的状态迁移，不触碰交易 / 余额链路（需求 4.4）。</p>
 *
 * <h2>为什么走全栈 {@code @SpringBootTest} + 真实提交、不用测试级事务</h2>
 * <p>与 {@link RecurringSkipTest} / {@link RecurringLazyGenerationSnapshotPropertyTest} 同源：跳过经
 * {@link RecurringPendingItemRepository#markSkippedIfPending} 的条件更新完成，需<b>真实提交</b>后回读断言
 * 状态落库为 {@code SKIPPED}；「不生成流水、不改余额」也需真实提交后回读交易表与账户余额。故本测试用全栈
 * 上下文、不加测试级 {@code @Transactional}（那会在方法结束回滚并掩盖真实提交），清理改为每个 try 前显式
 * 清库（{@link #resetAndInject()}），并用独立命名的内存库避免污染其它切片测试。</p>
 *
 * <p>时钟用 {@code @Primary} 的固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15）。待确认项直接经
 * 仓库落库（绕过创建 / 生成链路），并以生成器随机化其模板快照字段（{@code type}/{@code amount}/
 * {@code categoryId}/{@code accountId}/{@code note}）以遍历输入空间；每条项挂在各自 ACTIVE 规则下
 * （归属当前用户 + 当前账本），使 {@code skip} 的归属定位通过、聚焦跳过守恒本身。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效：依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 上下文静态缓存复用，多次迭代只加载
 * 一次），同一 {@link BeforeTry} 内随即显式清库并重新播种账户，使各 try 互不串味。</p>
 *
 * <p><strong>Validates: Requirements 4.4</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-skip-conservation-pbt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringSkipConservationPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    private static final BigDecimal SEED_BALANCE = new BigDecimal("1234.56");
    /** 每条待确认项挂到各自规则、各自到期日的锚点，逐条 +1 天保证唯一键不撞。 */
    private static final LocalDate OCCURRENCE_ANCHOR = LocalDate.of(2025, 6, 5);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZONE);
        }
    }

    @Autowired
    private RecurringPendingItemService service;
    @Autowired
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    /** 每 try 播种的账户 id，用于余额守恒断言。 */
    private Long seededAccountId;

    @BeforeTry
    void resetAndInject() throws Exception {
        // jqwik 不走 SpringExtension：手工触发依赖注入（上下文缓存复用）。
        new TestContextManager(RecurringSkipConservationPropertyTest.class).prepareTestInstance(this);
        // 清理不靠回滚（skip 真实提交）：每个 try 前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        seededAccountId = seedAccount();
    }

    // =====================================================================
    // Property 7
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 7: 跳过守恒
     *
     * <p>对任意一组 {@code PENDING} 待确认项（模板快照字段随机），逐条跳过后：每条状态落库为
     * {@code SKIPPED} 且不回填确认流水引用；全程不生成任何流水、账户余额恒等于播种值（需求 4.4）。</p>
     *
     * <p><strong>Validates: Requirements 4.4</strong></p>
     */
    @Property(tries = 100)
    void skipMovesToSkippedWithoutTransactionOrBalanceChange(
            @ForAll("pendingItemDefs") List<PendingItemDef> defs) {

        // 落库每条 PENDING 待确认项（各挂各自 ACTIVE 规则、各自到期日，保证归属通过且唯一键不撞）。
        List<Long> itemIds = new ArrayList<>();
        int dayOffset = 0;
        for (PendingItemDef def : defs) {
            RecurringRule rule = saveRule(def);
            RecurringPendingItem item = savePendingItem(rule, OCCURRENCE_ANCHOR.plusDays(dayOffset++), def);
            itemIds.add(item.getId());
        }

        // 逐条跳过并断言守恒。
        for (Long itemId : itemIds) {
            RecurringPendingItem skipped = service.skip(ALICE, LEDGER, itemId);

            // ---- 断言 1：状态置 SKIPPED，不回填确认流水引用（需求 4.4）。----
            assertThat(skipped.getStatus())
                    .as("跳过后返回值应为 SKIPPED itemId=%s", itemId)
                    .isEqualTo(PendingStatus.SKIPPED);
            assertThat(skipped.getConfirmedTransactionId())
                    .as("跳过不回填确认流水引用 itemId=%s", itemId)
                    .isNull();
            RecurringPendingItem reloaded = pendingItemRepository.findById(itemId).orElseThrow();
            assertThat(reloaded.getStatus())
                    .as("跳过应真实落库为 SKIPPED itemId=%s", itemId)
                    .isEqualTo(PendingStatus.SKIPPED);

            // ---- 断言 2：不生成任何流水（需求 4.4）。----
            assertThat(transactionRepository.count())
                    .as("跳过不得创建任何流水 itemId=%s", itemId)
                    .isZero();

            // ---- 断言 3：账户余额零变动（需求 4.4）。----
            assertThat(accountRepository.findById(seededAccountId).orElseThrow().getCurrentBalance())
                    .as("跳过不得改变账户余额 itemId=%s", itemId)
                    .isEqualByComparingTo(SEED_BALANCE);
        }
    }

    // =====================================================================
    // 持久化辅助
    // =====================================================================

    private Long seedAccount() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Account account = new Account();
        account.setUserId(ALICE);
        account.setName("现金");
        account.setType(AccountType.CASH);
        account.setInitialBalance(SEED_BALANCE);
        account.setCurrentBalance(SEED_BALANCE);
        account.setSortOrder(0);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account).getId();
    }

    /** 直接落库一条 ACTIVE 每月规则（绕过创建校验，聚焦跳过），归属当前用户 + 当前账本。 */
    private RecurringRule saveRule(PendingItemDef def) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ALICE);
        rule.setLedgerId(LEDGER);
        rule.setType(def.type());
        rule.setAmount(def.amount());
        rule.setCategoryId(def.categoryId());
        rule.setAccountId(def.accountId());
        rule.setNote(def.note());
        rule.setFrequency(Frequency.MONTHLY);
        rule.setMonthDay(5);
        rule.setMonthEnd(false);
        rule.setStartDate(LocalDate.of(2025, 3, 5));
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.ACTIVE);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    private RecurringPendingItem savePendingItem(RecurringRule rule, LocalDate occurrenceDate,
            PendingItemDef def) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(rule.getLedgerId());
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(PendingStatus.PENDING);
        item.setType(def.type());
        item.setAmount(def.amount());
        item.setCategoryId(def.categoryId());
        item.setAccountId(def.accountId());
        item.setNote(def.note());
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return pendingItemRepository.save(item);
    }

    // =====================================================================
    // 生成器
    // =====================================================================

    /** 一条待确认项的模板快照字段（随机化，遍历输入空间）。 */
    record PendingItemDef(String type, BigDecimal amount, Long categoryId, Long accountId, String note) {
    }

    /** 每个场景 1–5 条 PENDING 待确认项，模板快照字段各异。 */
    @Provide
    Arbitrary<List<PendingItemDef>> pendingItemDefs() {
        return pendingItemDef().list().ofMinSize(1).ofMaxSize(5);
    }

    private Arbitrary<PendingItemDef> pendingItemDef() {
        Arbitrary<String> types = Arbitraries.of("expense", "income");
        // 金额以「分」构造，保证恰 2 位小数、覆盖边界 0.01 与 999999999.99。
        Arbitrary<BigDecimal> amounts = Arbitraries.longs().between(1L, 99_999_999_999L)
                .map(cents -> BigDecimal.valueOf(cents, 2));
        Arbitrary<Long> categoryIds = Arbitraries.longs().between(1L, 50L);
        Arbitrary<Long> accountIds = Arbitraries.longs().between(1L, 50L);
        Arbitrary<String> notes = Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.strings().ofMinLength(0).ofMaxLength(20));
        return Combinators.combine(types, amounts, categoryIds, accountIds, notes)
                .as(PendingItemDef::new);
    }
}
