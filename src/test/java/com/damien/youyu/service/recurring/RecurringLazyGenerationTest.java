package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
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

/**
 * {@link RecurringPendingItemService#lazyGenerate} 的示例 / 边界集成测试（tasks 4.1）。
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实提交、不用测试级事务</h2>
 * <p>懒生成把每条期次的插入下沉到 {@link RecurringPendingItemGenerator#generate} 的
 * {@code REQUIRES_NEW} 独立事务——只有经<b>真实 Spring 事务代理</b>该注解才生效，且只有<b>真实提交</b>
 * 才能验证「一条撞唯一键的失败 flush 不毒化其余插入」这一 JPA 陷阱确已规避。故本测试用全栈上下文、
 * 不加测试级 {@code @Transactional}（那会在方法结束回滚并掩盖 REQUIRES_NEW 的真实提交），
 * 清理改为每个用例前显式清库（{@link #reset()}），并用独立命名的内存库避免污染其它切片测试。</p>
 *
 * <p>时钟用 {@code @Primary} 的固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15），使
 * {@code today} 与期次序列可确定性断言。规则直接经仓库落库（绕过 {@code RecurringRuleService} 的创建校验），
 * 聚焦懒生成本身的行为。</p>
 *
 * <p>覆盖：到期期次生成 {@code PENDING}；重复运行幂等不重复；{@code PAUSED} 规则不扫描；早于生成下界的期次
 * （暂停区间）不回补；既有 {@code CONFIRMED}/{@code SKIPPED} 期次不重生；生成不写交易 / 不改账户余额；
 * 唯一键冲突后持久化上下文仍可用（并发 / 重复生成路径）。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-lazygen-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringLazyGenerationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    private static final long OTHER_LEDGER = 200L;
    private static final long CATEGORY = 10L;

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
    private RecurringPendingItemGenerator generator;
    @Autowired
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void reset() {
        // 清理不靠回滚（REQUIRES_NEW 真实提交）：每个用例前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    // ---------------- fixtures ----------------

    /** 直接落库一条规则（绕过创建校验，聚焦懒生成）。start/updated 决定生成下界。 */
    private RecurringRule saveMonthlyRule(long ledgerId, int monthDay, LocalDate startDate,
            LocalDate updatedDate, RuleStatus status, EndCondition endCondition,
            LocalDate untilDate, Integer countN) {
        LocalDateTime updatedAt = updatedDate.atStartOfDay();
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ALICE);
        rule.setLedgerId(ledgerId);
        rule.setType("expense");
        rule.setAmount(new BigDecimal("3000.00"));
        rule.setCategoryId(CATEGORY);
        rule.setAccountId(1L);
        rule.setNote("房租");
        rule.setFrequency(Frequency.MONTHLY);
        rule.setMonthDay(monthDay);
        rule.setMonthEnd(false);
        rule.setStartDate(startDate);
        rule.setEndCondition(endCondition);
        rule.setUntilDate(untilDate);
        rule.setCountN(countN);
        rule.setStatus(status);
        rule.setCreatedAt(startDate.atStartOfDay());
        rule.setUpdatedAt(updatedAt);
        return ruleRepository.save(rule);
    }

    /** 常规：start=updated=开始日期，生成下界即开始日期。 */
    private RecurringRule saveMonthlyRule(long ledgerId, int monthDay, LocalDate startDate,
            RuleStatus status) {
        return saveMonthlyRule(ledgerId, monthDay, startDate, startDate, status,
                EndCondition.NEVER, null, null);
    }

    private void seedItem(RecurringRule rule, LocalDate occurrenceDate, PendingStatus status) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(rule.getLedgerId());
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(status);
        item.setType("expense");
        item.setAmount(new BigDecimal("3000.00"));
        item.setCategoryId(CATEGORY);
        item.setAccountId(1L);
        item.setNote("房租");
        if (status == PendingStatus.CONFIRMED) {
            item.setConfirmedTransactionId(9999L);
        }
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        pendingItemRepository.save(item);
    }

    private List<RecurringPendingItem> itemsOf(RecurringRule rule) {
        return pendingItemRepository.findAll().stream()
                .filter(i -> i.getRuleId().equals(rule.getId()))
                .toList();
    }

    // ---------------- 到期期次生成 PENDING ----------------

    @Test
    void lazyGenerate_createsPendingForEachDueOccurrence() {
        // 每月 5 号，开始 2025-03-05 → 到 today(2025-06-15) 应有 03-05/04-05/05-05/06-05 四期。
        RecurringRule rule = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 3, 5), RuleStatus.ACTIVE);

        service.lazyGenerate(LEDGER);

        List<RecurringPendingItem> items = itemsOf(rule);
        assertThat(items).extracting(RecurringPendingItem::getOccurrenceDate)
                .containsExactlyInAnyOrder(
                        LocalDate.of(2025, 3, 5), LocalDate.of(2025, 4, 5),
                        LocalDate.of(2025, 5, 5), LocalDate.of(2025, 6, 5));
        assertThat(items).allSatisfy(i -> {
            assertThat(i.getStatus()).isEqualTo(PendingStatus.PENDING);
            // 模板快照取自规则。
            assertThat(i.getType()).isEqualTo("expense");
            assertThat(i.getAmount()).isEqualByComparingTo("3000.00");
            assertThat(i.getCategoryId()).isEqualTo(CATEGORY);
            assertThat(i.getLedgerId()).isEqualTo(LEDGER);
            assertThat(i.getNote()).isEqualTo("房租");
        });
    }

    @Test
    void lazyGenerate_doesNotGenerateFutureOccurrences() {
        // 每月 20 号，开始 2025-06-01 → today=2025-06-15，20 号尚未到期，不生成。
        RecurringRule rule = saveMonthlyRule(LEDGER, 20, LocalDate.of(2025, 6, 1), RuleStatus.ACTIVE);

        service.lazyGenerate(LEDGER);

        assertThat(itemsOf(rule)).isEmpty();
    }

    // ---------------- 幂等：重复运行不重复 ----------------

    @Test
    void lazyGenerate_isIdempotentAcrossRuns() {
        RecurringRule rule = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 3, 5), RuleStatus.ACTIVE);

        service.lazyGenerate(LEDGER);
        long afterFirst = pendingItemRepository.count();
        service.lazyGenerate(LEDGER);
        service.lazyGenerate(LEDGER);

        assertThat(afterFirst).isEqualTo(4);
        assertThat(pendingItemRepository.count()).isEqualTo(4);
    }

    // ---------------- PAUSED 规则不扫描 ----------------

    @Test
    void lazyGenerate_skipsPausedRules() {
        RecurringRule paused =
                saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 3, 5), RuleStatus.PAUSED);

        service.lazyGenerate(LEDGER);

        assertThat(itemsOf(paused)).isEmpty();
    }

    // ---------------- 生成下界：暂停区间不回补（需求 6.2） ----------------

    @Test
    void lazyGenerate_skipsOccurrencesBeforeGenerationLowerBound() {
        // 开始 2025-03-05，但 updated_at（模拟恢复当日）= 2025-05-20 →
        // 生成下界 = max(03-05, 05-20) = 05-20；仅 06-05（≥ 下界）生成，03/04/05-05 不回补。
        RecurringRule rule = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 3, 5),
                LocalDate.of(2025, 5, 20), RuleStatus.ACTIVE, EndCondition.NEVER, null, null);

        service.lazyGenerate(LEDGER);

        assertThat(itemsOf(rule)).extracting(RecurringPendingItem::getOccurrenceDate)
                .containsExactly(LocalDate.of(2025, 6, 5));
    }

    // ---------------- 既有 CONFIRMED / SKIPPED 不重生 ----------------

    @Test
    void lazyGenerate_doesNotRegenerateExistingConfirmedOrSkippedOccurrences() {
        RecurringRule rule = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 3, 5), RuleStatus.ACTIVE);
        // 04-05 已确认、05-05 已跳过：懒生成不得改动或重生这两期。
        seedItem(rule, LocalDate.of(2025, 4, 5), PendingStatus.CONFIRMED);
        seedItem(rule, LocalDate.of(2025, 5, 5), PendingStatus.SKIPPED);

        service.lazyGenerate(LEDGER);

        List<RecurringPendingItem> items = itemsOf(rule);
        // 四期各恰一条：两条既有状态保持，两条新 PENDING（03-05、06-05）。
        assertThat(items).hasSize(4);
        RecurringPendingItem confirmed = items.stream()
                .filter(i -> i.getOccurrenceDate().equals(LocalDate.of(2025, 4, 5)))
                .findFirst().orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(PendingStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedTransactionId()).isEqualTo(9999L);
        RecurringPendingItem skipped = items.stream()
                .filter(i -> i.getOccurrenceDate().equals(LocalDate.of(2025, 5, 5)))
                .findFirst().orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(PendingStatus.SKIPPED);
        assertThat(items).filteredOn(i -> i.getStatus() == PendingStatus.PENDING)
                .extracting(RecurringPendingItem::getOccurrenceDate)
                .containsExactlyInAnyOrder(LocalDate.of(2025, 3, 5), LocalDate.of(2025, 6, 5));
    }

    // ---------------- 账本隔离：只扫当前账本 ----------------

    @Test
    void lazyGenerate_onlyScansGivenLedger() {
        RecurringRule mine = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 5, 5), RuleStatus.ACTIVE);
        RecurringRule other =
                saveMonthlyRule(OTHER_LEDGER, 5, LocalDate.of(2025, 5, 5), RuleStatus.ACTIVE);

        service.lazyGenerate(LEDGER);

        assertThat(itemsOf(mine)).isNotEmpty();
        assertThat(itemsOf(other)).isEmpty();
    }

    // ---------------- 生成不写交易、不改账户余额（需求 3.2） ----------------

    @Test
    void lazyGenerate_createsNoTransactionsAndDoesNotChangeAccountBalance() {
        Account account = new Account();
        account.setUserId(ALICE);
        account.setName("现金");
        account.setType(AccountType.CASH);
        account.setInitialBalance(new BigDecimal("1000.00"));
        account.setCurrentBalance(new BigDecimal("1000.00"));
        account.setSortOrder(0);
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        Account saved = accountRepository.save(account);

        saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 3, 5), RuleStatus.ACTIVE);

        service.lazyGenerate(LEDGER);

        // 生成只写待确认项，绝不建交易、不动余额。
        assertThat(pendingItemRepository.count()).isEqualTo(4);
        assertThat(transactionRepository.count()).isZero();
        assertThat(accountRepository.findById(saved.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("1000.00");
    }

    // ---------------- 唯一键冲突后持久化上下文仍可用（并发 / 重复生成路径） ----------------

    @Test
    void generator_afterUniqueKeyViolation_persistenceContextStaysUsable() {
        RecurringRule rule = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 3, 5), RuleStatus.ACTIVE);
        LocalDate dup = LocalDate.of(2025, 4, 5);
        LocalDate fresh = LocalDate.of(2025, 5, 5);

        // 第一次写入成功。
        generator.generate(rule, dup);
        assertThat(pendingItemRepository.existsByRuleIdAndOccurrenceDate(rule.getId(), dup)).isTrue();

        // 对同一 (rule, date) 再次写入：撞唯一键 uk_recurring_pending_rule_date → DataIntegrityViolationException。
        Throwable thrown = catchThrowable(() -> generator.generate(rule, dup));
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);

        // 关键：REQUIRES_NEW 独立事务只回滚失败的那一次，持久化上下文仍可用——另一期次照常写入成功。
        generator.generate(rule, fresh);
        assertThat(pendingItemRepository.existsByRuleIdAndOccurrenceDate(rule.getId(), fresh)).isTrue();

        // 且 lazyGenerate 在既有一条记录（dup）之上继续补齐其余期次，不因既有记录报错。
        service.lazyGenerate(LEDGER);
        assertThat(itemsOf(rule)).extracting(RecurringPendingItem::getOccurrenceDate)
                .containsExactlyInAnyOrder(
                        LocalDate.of(2025, 3, 5), LocalDate.of(2025, 4, 5),
                        LocalDate.of(2025, 5, 5), LocalDate.of(2025, 6, 5));
    }
}
