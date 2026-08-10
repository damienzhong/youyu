package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
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
 * Feature: recurring-transactions, Property 11: 账本与用户隔离
 *
 * <p>{@link RecurringRuleService} 的列表 / 详情 / 编辑 / 暂停 / 恢复 / 删除与
 * {@link RecurringPendingItemService#queryPendingItems} / {@link RecurringPendingItemService#confirm} /
 * {@link RecurringPendingItemService#skip} 的属性测试，覆盖 design.md「Correctness Properties」Property 11：</p>
 *
 * <p><em>对任意</em>多用户、多账本的规则与待确认项数据，当前用户在当前账本的查询<b>仅返回</b>归属当前
 * 用户（规则）/ 归属当前账本（待确认项）的数据，<b>绝不泄漏</b>其它账本的数据；以规则 / 待确认项标识对
 * <b>不属于当前用户或当前账本</b>的对象执行确认 / 跳过 / 暂停 / 恢复 / 编辑 / 删除时<b>一律返回
 * {@code NOT_FOUND}</b>，且<b>不改动任何数据、不生成任何流水、不改变任何账户余额</b>。</p>
 *
 * <h2>读隔离口径（与 design.md Property 9 一致）</h2>
 * <ul>
 *   <li><b>规则读（{@link RecurringRuleService#list}）：用户 + 账本双重作用域</b>——仓库
 *       {@link RecurringRuleRepository#findByUserIdAndLedgerIdOrderByCreatedAtAsc} 同时按 {@code userId} 与
 *       {@code ledgerId} 过滤，故列表恰为「归属当前用户且归属当前账本」的规则（需求 8.4）。本测试据此断言
 *       列表<b>排除</b>他人规则（含同账本他人规则）与他账本规则。</li>
 *   <li><b>待确认项读（{@link RecurringPendingItemService#queryPendingItems}）：账本作用域</b>——账本是本
 *       系统的共享安全边界，查询按 {@code ledgerId} 过滤返回当前账本全部 {@code PENDING} 项（Property 9
 *       既有语义）。本测试据此断言查询结果<b>恰为</b>当前账本 {@code PENDING} 项、<b>排除</b>他账本项；对
 *       待确认项的<b>按用户</b>边界由写操作（{@code confirm} / {@code skip}）的规则归属校验承担（见下）。</li>
 * </ul>
 *
 * <h2>写隔离口径（跨租户一律 {@code NOT_FOUND} 且零副作用）</h2>
 * <p>规则的 {@code get} / {@code update} / {@code pause} / {@code resume} / {@code delete} 均先经
 * {@link RecurringRuleRepository#findByIdAndUserIdAndLedgerId} 按 (id, 当前用户, 当前账本) 定位，任一不匹配
 * 即 {@code NOT_FOUND}；待确认项的 {@code confirm} / {@code skip} 先校验项归属当前账本，再校验其来源规则
 * 归属当前用户 + 当前账本，任一不满足即 {@code NOT_FOUND}。故对任意「不属于 (ALICE, LEDGER)」的规则 /
 * 待确认项执行上述写操作都应 {@code NOT_FOUND}——本测试对全部外域对象逐一验证，并在全部越权写之后断言
 * 数据库快照（规则状态 / 待确认项状态 / 流水条数 / 账户余额）与越权前<b>逐一相同</b>（需求 6.7、8.5）。</p>
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实提交、不用测试级事务</h2>
 * <p>与本包 {@link RecurringQueryBatchPropertyTest} 同源：{@code queryPendingItems} 内部先调
 * {@link RecurringPendingItemService#lazyGenerate}（{@code REQUIRES_NEW} 独立事务写入），{@code confirm} /
 * {@code skip} / {@code delete} 各自经 Spring 代理开启事务并<b>真实提交 / 回滚</b>——只有经真实事务代理并真实
 * 提交，「越权写全部 {@code NOT_FOUND} 且零副作用」才可被跨事务观测。故用全栈上下文、不加测试级
 * {@code @Transactional}（那会在方法结束回滚、掩盖真实提交），清理改为每次迭代前显式清库
 * （{@link #resetAndInject()}），并用独立命名内存库避免污染其它切片测试。</p>
 *
 * <p>jqwik 属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效：依赖注入由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文静态缓存复用，多次迭代只加载一次）。
 * 时钟用 {@code @Primary} 固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15），使规则均以
 * {@link RuleStatus#PAUSED} 播种从而 {@code queryPendingItems} 触发的懒生成<b>零新增</b>，待确认项集合完全
 * 由播种控制、隔离断言可精确对照。</p>
 *
 * <h2>测试构造</h2>
 * <p>每次迭代随机生成跨 2 个用户（{@code ALICE}=当前 / {@code OTHER_USER}）× 2 个账本（{@code LEDGER}=当前 /
 * {@code OTHER_LEDGER}）四种归属组合的<b>全部 {@link RuleStatus#PAUSED}</b> 规则，每条挂若干混合状态
 * （{@code PENDING}/{@code CONFIRMED}/{@code SKIPPED}）、到期日互异的待确认项。以 (ALICE, LEDGER) 为当前
 * 上下文，验证：①规则列表恰为当前用户 + 当前账本规则；②待确认项查询恰为当前账本 {@code PENDING} 项；
 * ③对全部外域规则 / 待确认项的确认 / 跳过 / 暂停 / 恢复 / 编辑 / 删除全部 {@code NOT_FOUND}，且越权后
 * 数据库快照零变化。</p>
 *
 * <p><strong>Validates: Requirements 6.7, 8.1, 8.4, 8.5</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-isolation-pbt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringIsolationPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long OTHER_USER = 2L;
    private static final long LEDGER = 100L;
    private static final long OTHER_LEDGER = 200L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final String NOTE = "房租";
    private static final LocalDate DATE_BASE = LocalDate.of(2025, 1, 1);
    private static final LocalDate CREATED_BASE = LocalDate.of(2024, 1, 1);
    private static final String NOT_FOUND = "NOT_FOUND";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZONE);
        }
    }

    @Autowired
    private RecurringRuleService ruleService;
    @Autowired
    private RecurringPendingItemService pendingItemService;
    @Autowired
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private Long accountId;
    private Long categoryId;

    @BeforeTry
    void resetAndInject() throws Exception {
        // jqwik 不走 SpringExtension：手工触发依赖注入（上下文缓存复用）。
        new TestContextManager(RecurringIsolationPropertyTest.class).prepareTestInstance(this);
        // 清理不靠回滚（真实提交）：每次迭代前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        // 有效账户 + 分类落在当前账本（供越权写路径在 NOT_FOUND 前不因目标缺失而走别的分支）。
        accountId = saveAccount(new BigDecimal("100000000.00"));
        linkAccountToLedger(accountId, LEDGER);
        categoryId = saveCategory(LEDGER, "房租");
    }

    // =====================================================================
    // Property 11
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 11: 账本与用户隔离
     *
     * <p><strong>Validates: Requirements 6.7, 8.1, 8.4, 8.5</strong></p>
     */
    @Property(tries = 100)
    void queriesScopedAndCrossTenantMutationsNotFoundWithNoSideEffects(
            @ForAll("scenarios") Scenario scenario) {

        // 1) 播种跨用户 / 跨账本的规则（全部 PAUSED，使查询触发的懒生成零新增）与其混合状态待确认项。
        List<SeededRule> seededRules = new ArrayList<>();
        List<SeededItem> seededItems = new ArrayList<>();
        for (RuleGen g : scenario.rules()) {
            long ruleUser = g.aliceOwned() ? ALICE : OTHER_USER;
            long ruleLedger = g.currentLedger() ? LEDGER : OTHER_LEDGER;
            LocalDateTime createdAt = CREATED_BASE.plusDays(g.createdAtOffset()).atStartOfDay();
            RecurringRule rule = saveRule(ruleUser, ruleLedger, createdAt);
            boolean owned = (ruleUser == ALICE) && (ruleLedger == LEDGER);
            seededRules.add(new SeededRule(rule.getId(), owned, ruleLedger));

            Set<LocalDate> usedDates = new HashSet<>();
            for (ItemGen ig : g.items()) {
                LocalDate occ = DATE_BASE.plusDays(ig.dayOffset());
                if (!usedDates.add(occ)) {
                    continue; // 同规则到期日须互异（唯一键 uk_recurring_pending_rule_date）。
                }
                RecurringPendingItem item = savePendingItem(rule, occ, ig.status());
                // 待确认项对当前上下文是否「本域」：项归属当前账本 且 其来源规则归属当前用户 + 当前账本。
                boolean ownedItem = owned && (ruleLedger == LEDGER);
                seededItems.add(new SeededItem(
                        item.getId(), ruleLedger, ownedItem, ig.status()));
            }
        }

        // 2) 读隔离：规则列表恰为当前用户 + 当前账本的规则（需求 8.4）。
        List<Long> expectedRuleIds = seededRules.stream()
                .filter(SeededRule::owned)
                .map(SeededRule::ruleId)
                .toList();
        List<RecurringRule> listed = ruleService.list(ALICE, LEDGER);
        assertThat(listed).extracting(RecurringRule::getId)
                .as("规则列表应恰为归属当前用户且当前账本的规则，排除他人 / 他账本规则")
                .containsExactlyInAnyOrderElementsOf(expectedRuleIds);
        assertThat(listed).allSatisfy(r -> {
            assertThat(r.getUserId()).as("列出规则均归属当前用户").isEqualTo(ALICE);
            assertThat(r.getLedgerId()).as("列出规则均归属当前账本").isEqualTo(LEDGER);
        });

        // 3) 读隔离：待确认项查询恰为当前账本 PENDING 项，排除他账本项（需求 8.4；账本作用域，同 Property 9）。
        List<Long> expectedItemIds = seededItems.stream()
                .filter(s -> s.ledgerId() == LEDGER && s.status() == PendingStatus.PENDING)
                .map(SeededItem::itemId)
                .toList();
        List<RecurringPendingItem> queried = pendingItemService.queryPendingItems(LEDGER);
        assertThat(queried).extracting(RecurringPendingItem::getId)
                .as("待确认项查询应恰为当前账本 PENDING 项，排除他账本项")
                .containsExactlyInAnyOrderElementsOf(expectedItemIds);
        assertThat(queried).allSatisfy(i -> {
            assertThat(i.getLedgerId()).as("查询项均归属当前账本").isEqualTo(LEDGER);
            assertThat(i.getStatus()).as("查询项均为 PENDING").isEqualTo(PendingStatus.PENDING);
        });

        // 4) 越权写零副作用基线：越权操作前的完整数据库快照。
        Map<Long, RuleStatus> ruleStatusBefore = new HashMap<>();
        Map<Long, LocalDateTime> ruleUpdatedBefore = new HashMap<>();
        for (RecurringRule r : ruleRepository.findAll()) {
            ruleStatusBefore.put(r.getId(), r.getStatus());
            ruleUpdatedBefore.put(r.getId(), r.getUpdatedAt());
        }
        Map<Long, PendingStatus> itemStatusBefore = new HashMap<>();
        for (RecurringPendingItem i : pendingItemRepository.findAll()) {
            itemStatusBefore.put(i.getId(), i.getStatus());
        }
        long txCountBefore = transactionRepository.count();
        Map<Long, BigDecimal> balancesBefore = new HashMap<>();
        for (Account a : accountRepository.findAll()) {
            balancesBefore.put(a.getId(), a.getCurrentBalance());
        }

        // 5) 写隔离：对全部外域规则的 get/update/pause/resume/delete → 一律 NOT_FOUND（需求 6.7、8.5）。
        for (SeededRule sr : seededRules) {
            if (sr.owned()) {
                continue; // 只验越权对象。
            }
            Long ruleId = sr.ruleId();
            assertNotFound(() -> ruleService.get(ALICE, LEDGER, ruleId), "get 外域规则");
            assertNotFound(() -> ruleService.pause(ALICE, LEDGER, ruleId), "pause 外域规则");
            assertNotFound(() -> ruleService.resume(ALICE, LEDGER, ruleId), "resume 外域规则");
            assertNotFound(() -> ruleService.update(ALICE, LEDGER, ruleId, "expense", AMOUNT,
                    categoryId, accountId, NOTE, Frequency.MONTHLY, null, 5, false, null, null,
                    LocalDate.of(2025, 1, 5), EndCondition.NEVER, null, null), "update 外域规则");
            assertNotFound(() -> ruleService.delete(ALICE, LEDGER, ruleId), "delete 外域规则");
        }

        // 6) 写隔离：对全部外域待确认项的 confirm/skip → 一律 NOT_FOUND（需求 8.5）。
        //    外域项 = 项在他账本，或项在当前账本但其来源规则不归属当前用户（confirm/skip 的规则归属校验兜住）。
        for (SeededItem si : seededItems) {
            if (si.owned()) {
                continue; // 只验越权对象。
            }
            Long itemId = si.itemId();
            assertNotFound(() -> pendingItemService.confirm(ALICE, LEDGER, itemId,
                    null, null, null, null, null), "confirm 外域待确认项");
            assertNotFound(() -> pendingItemService.skip(ALICE, LEDGER, itemId), "skip 外域待确认项");
        }

        // 7) 零副作用断言：越权写之后数据库快照逐一等于越权前（需求 6.7、8.5）。
        List<RecurringRule> rulesAfter = ruleRepository.findAll();
        assertThat(rulesAfter).extracting(RecurringRule::getId)
                .as("越权删除不得移除任何规则")
                .containsExactlyInAnyOrderElementsOf(ruleStatusBefore.keySet());
        for (RecurringRule r : rulesAfter) {
            assertThat(r.getStatus())
                    .as("越权 pause/resume 不得改动规则状态 ruleId=%s", r.getId())
                    .isEqualTo(ruleStatusBefore.get(r.getId()));
            assertThat(r.getUpdatedAt())
                    .as("越权写不得刷新规则 updatedAt ruleId=%s", r.getId())
                    .isEqualTo(ruleUpdatedBefore.get(r.getId()));
        }
        List<RecurringPendingItem> itemsAfter = pendingItemRepository.findAll();
        assertThat(itemsAfter).extracting(RecurringPendingItem::getId)
                .as("越权确认 / 跳过不得移除任何待确认项")
                .containsExactlyInAnyOrderElementsOf(itemStatusBefore.keySet());
        for (RecurringPendingItem i : itemsAfter) {
            assertThat(i.getStatus())
                    .as("越权确认 / 跳过不得改动待确认项状态 itemId=%s", i.getId())
                    .isEqualTo(itemStatusBefore.get(i.getId()));
        }
        assertThat(transactionRepository.count())
                .as("越权写不得生成任何流水")
                .isEqualTo(txCountBefore);
        for (Account a : accountRepository.findAll()) {
            assertThat(a.getCurrentBalance())
                    .as("越权写不得改变账户余额 accountId=%s", a.getId())
                    .isEqualByComparingTo(balancesBefore.get(a.getId()));
        }
    }

    /** 断言给定操作抛 {@link ApiException} 且错误码为 {@code NOT_FOUND}。 */
    private void assertNotFound(Runnable op, String desc) {
        ApiException ex = catchThrowableOfType(op::run, ApiException.class);
        assertThat(ex).as("%s 应抛 ApiException", desc).isNotNull();
        assertThat(ex.getCode()).as("%s 应返回 NOT_FOUND", desc).isEqualTo(NOT_FOUND);
    }

    // =====================================================================
    // 持久化辅助
    // =====================================================================

    private Long saveAccount(BigDecimal balance) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Account account = new Account();
        account.setUserId(ALICE);
        account.setName("现金");
        account.setType(AccountType.CASH);
        account.setInitialBalance(balance);
        account.setCurrentBalance(balance);
        account.setSortOrder(0);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account).getId();
    }

    private void linkAccountToLedger(Long accountId, long ledgerId) {
        AccountLedger link = new AccountLedger();
        link.setAccountId(accountId);
        link.setLedgerId(ledgerId);
        link.setVisibleToOthers(true);
        link.setShowBalance(true);
        link.setCreatedAt(LocalDateTime.ofInstant(NOW, ZONE));
        accountLedgerRepository.save(link);
    }

    private Long saveCategory(long ledgerId, String name) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Category category = new Category();
        category.setUserId(ALICE);
        category.setLedgerId(ledgerId);
        category.setParentId(null);
        category.setKind(CategoryKind.EXPENSE);
        category.setName(name);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        return categoryRepository.save(category).getId();
    }

    /** 直接落库一条 PAUSED 每月规则（绕过创建校验；PAUSED 使查询触发的懒生成零新增）。 */
    private RecurringRule saveRule(long userId, long ledgerId, LocalDateTime createdAt) {
        RecurringRule rule = new RecurringRule();
        rule.setUserId(userId);
        rule.setLedgerId(ledgerId);
        rule.setType("expense");
        rule.setAmount(AMOUNT);
        rule.setCategoryId(categoryId);
        rule.setAccountId(accountId);
        rule.setNote(NOTE);
        rule.setFrequency(Frequency.MONTHLY);
        rule.setMonthDay(5);
        rule.setMonthEnd(false);
        rule.setStartDate(LocalDate.of(2025, 1, 5));
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.PAUSED);
        rule.setCreatedAt(createdAt);
        rule.setUpdatedAt(createdAt);
        return ruleRepository.save(rule);
    }

    private RecurringPendingItem savePendingItem(RecurringRule rule, LocalDate occurrenceDate,
            PendingStatus status) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(rule.getLedgerId());
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(status);
        item.setType("expense");
        item.setAmount(AMOUNT);
        item.setCategoryId(categoryId);
        item.setAccountId(accountId);
        item.setNote(NOTE);
        if (status == PendingStatus.CONFIRMED) {
            item.setConfirmedTransactionId(9999L);
        }
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return pendingItemRepository.save(item);
    }

    // =====================================================================
    // 生成器
    // =====================================================================

    /** 播种规则的元数据：是否归属当前 (ALICE, LEDGER)、其账本 id。 */
    record SeededRule(Long ruleId, boolean owned, long ledgerId) {
    }

    /** 播种待确认项的元数据：所在账本、是否本域（当前用户 + 当前账本）、状态。 */
    record SeededItem(Long itemId, long ledgerId, boolean owned, PendingStatus status) {
    }

    /** 一条待确认项的生成参数：到期日偏移、状态。 */
    record ItemGen(int dayOffset, PendingStatus status) {
    }

    /** 一条规则的生成参数：账本归属、用户归属、创建时间偏移、其待确认项。 */
    record RuleGen(boolean currentLedger, boolean aliceOwned, int createdAtOffset,
            List<ItemGen> items) {
    }

    /** 一个场景：跨用户跨账本的规则集。 */
    record Scenario(List<RuleGen> rules) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return ruleGen().list().ofMinSize(1).ofMaxSize(5).map(Scenario::new);
    }

    private Arbitrary<RuleGen> ruleGen() {
        Arbitrary<Boolean> currentLedger = Arbitraries.of(true, true, false); // 偏向当前账本
        Arbitrary<Boolean> aliceOwned = Arbitraries.of(true, true, false);    // 偏向本人
        Arbitrary<Integer> createdOffset = Arbitraries.integers().between(0, 60);
        Arbitrary<List<ItemGen>> items = itemGen().list().ofMinSize(0).ofMaxSize(3);
        return Combinators.combine(currentLedger, aliceOwned, createdOffset, items).as(RuleGen::new);
    }

    private Arbitrary<ItemGen> itemGen() {
        Arbitrary<Integer> dayOffset = Arbitraries.integers().between(0, 330);
        Arbitrary<PendingStatus> status = Arbitraries.of(
                PendingStatus.PENDING, PendingStatus.PENDING,
                PendingStatus.CONFIRMED, PendingStatus.SKIPPED);
        return Combinators.combine(dayOffset, status).as(ItemGen::new);
    }
}
