package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
 * Feature: recurring-transactions, Property 9: 待确认项查询过滤、排序确定性与批量隔离
 *
 * <p>{@link RecurringPendingItemService#queryPendingItems} /
 * {@link RecurringPendingItemService#batchConfirm} / {@link RecurringPendingItemService#batchSkip}
 * 的属性测试，覆盖 design.md「Correctness Properties」Property 9：</p>
 *
 * <p><em>对任意</em>混合状态、跨规则跨账本的待确认项数据，当前账本的查询结果<b>恰为</b>归属当前账本且
 * 状态为 {@link PendingStatus#PENDING} 的项集合，每项携带来源规则标识、到期日与模板字段；结果<b>严格按</b>
 * 「到期日升序 → 规则创建时间升序 → 项 id 升序」排列，任意两次查询对相同数据返回<b>完全一致</b>的顺序；
 * 批量确认 / 跳过<b>逐条独立</b>处理，成功条改为目标状态、失败条（含已处理条与跨租户条）保持原状态且不影响
 * 其余，返回的成功 / 失败计数<b>等于逐条结果的聚合</b>。</p>
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实提交、不用测试级事务</h2>
 * <p>与本包 {@link RecurringGenerationIdempotencePropertyTest} /
 * {@link RecurringLazyGenerationSnapshotPropertyTest} 同源：查询内部先调
 * {@link RecurringPendingItemService#lazyGenerate}（{@code REQUIRES_NEW} 独立事务写入），批量确认 / 跳过则
 * 逐条经本 bean 的 Spring 代理各自开启独立事务并<b>真实提交 / 回滚</b>——只有经真实事务代理并真实提交，
 * 「成功条提交存活、失败条各自回滚保持原状态、彼此不牵连」的逐条隔离（需求 5.4、5.5）才可被观测。故用全栈
 * 上下文、不加测试级 {@code @Transactional}（那会在方法结束回滚、掩盖真实提交），清理改为每次迭代前显式
 * 清库（{@link #resetAndInject()}），并用独立命名内存库避免污染其它切片测试。</p>
 *
 * <p>jqwik 属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效：依赖注入由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文静态缓存复用，多次迭代只加载一次），
 * 同一钩子内随即硬清相关表并重播种账户 / 分类，使各迭代（真实提交、无回滚）互不串味。时钟用 {@code @Primary}
 * 的固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15）使 {@code today} 与排序键可确定性推导。</p>
 *
 * <h2>测试构造</h2>
 * <p>每次迭代随机生成跨 2 个账本（当前 {@code LEDGER} 与 {@code OTHER_LEDGER}）、含跨用户
 * （{@code ALICE} 与 {@code OTHER_USER}）、创建时间各异的<b>全部 {@link RuleStatus#PAUSED}</b> 规则——PAUSED
 * 使 {@code queryPendingItems} 触发的懒生成<b>零新增</b>，从而待确认项集合完全由播种控制、查询过滤断言可精确
 * 对照。每条规则挂若干混合状态（{@code PENDING}/{@code CONFIRMED}/{@code SKIPPED}）、到期日互异的待确认项。
 * 播种后：①断言查询过滤 + 三级确定性排序 + 两次查询顺序一致；②对随机子集执行批量确认或批量跳过，断言逐条
 * 隔离（成功 → 目标状态、失败 → 原状态不变、非子集项不受影响）与计数聚合。</p>
 *
 * <p><strong>Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-query-batch-pbt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringQueryBatchPropertyTest {

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
        new TestContextManager(RecurringQueryBatchPropertyTest.class).prepareTestInstance(this);
        // 清理不靠回滚（真实提交）：每次迭代前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        // 有效账户 + 分类落在当前账本，供当前账本有效 PENDING 项确认成功（避免 TARGET_MISSING）。
        accountId = saveAccount(new BigDecimal("100000000.00"));
        linkAccountToLedger(accountId, LEDGER);
        categoryId = saveCategory(LEDGER, "房租");
    }

    // =====================================================================
    // Property 9
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 9: 待确认项查询过滤、排序确定性与批量隔离
     *
     * <p><strong>Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6</strong></p>
     */
    @Property(tries = 100)
    void queryFiltersOrdersDeterministicallyAndBatchIsolates(@ForAll("scenarios") Scenario scenario) {
        // 1) 播种规则（全部 PAUSED，使查询触发的懒生成零新增）与其混合状态待确认项。
        List<Seeded> seeded = new ArrayList<>();
        for (RuleGen g : scenario.rules()) {
            long ruleUser = (g.currentLedger() && !g.aliceOwned()) ? OTHER_USER : ALICE;
            long ruleLedger = g.currentLedger() ? LEDGER : OTHER_LEDGER;
            LocalDateTime ruleCreatedAt = CREATED_BASE.plusDays(g.createdAtOffset()).atStartOfDay();
            RecurringRule rule = saveRule(ruleUser, ruleLedger, ruleCreatedAt);

            Set<LocalDate> usedDates = new HashSet<>();
            for (ItemGen ig : g.items()) {
                LocalDate occ = DATE_BASE.plusDays(ig.dayOffset());
                if (!usedDates.add(occ)) {
                    continue; // 同规则到期日须互异（唯一键 uk_recurring_pending_rule_date）。
                }
                RecurringPendingItem item = savePendingItem(rule, occ, ig.status());
                seeded.add(new Seeded(item.getId(), rule.getId(), g.currentLedger(),
                        ruleUser == ALICE, occ, ig.status(), ruleCreatedAt, ig.inBatch()));
            }
        }

        // 2) 查询过滤 + 确定性排序（需求 5.1、5.2、5.3、8.4）。
        //    期望：恰为归属当前账本且 PENDING 的项，按 (到期日 → 规则 createdAt → 项 id) 升序。
        Comparator<Seeded> ordering = Comparator
                .comparing(Seeded::occurrenceDate)
                .thenComparing(Seeded::ruleCreatedAt)
                .thenComparing(Seeded::itemId);
        List<Long> expectedIds = seeded.stream()
                .filter(s -> s.currentLedger() && s.status() == PendingStatus.PENDING)
                .sorted(ordering)
                .map(Seeded::itemId)
                .toList();

        List<RecurringPendingItem> firstQuery = service.queryPendingItems(LEDGER);

        // (a) 过滤正确：结果 id 集合与顺序恰等于期望（只含当前账本 PENDING，跨账本 / 非 PENDING 均排除）。
        assertThat(firstQuery).extracting(RecurringPendingItem::getId)
                .as("查询应恰返回当前账本 PENDING 项，且按三级键确定性排序")
                .containsExactlyElementsOf(expectedIds);
        // (a') 每项均属当前账本且状态 PENDING（需求 5.1、8.4）。
        assertThat(firstQuery).allSatisfy(i -> {
            assertThat(i.getLedgerId()).isEqualTo(LEDGER);
            assertThat(i.getStatus()).isEqualTo(PendingStatus.PENDING);
            // 每项携带来源规则 id、到期日与模板快照字段（需求 5.1、5.3）。
            assertThat(i.getRuleId()).isNotNull();
            assertThat(i.getOccurrenceDate()).isNotNull();
            assertThat(i.getType()).isEqualTo("expense");
            assertThat(i.getAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(i.getCategoryId()).isEqualTo(categoryId);
            assertThat(i.getAccountId()).isEqualTo(accountId);
            assertThat(i.getNote()).isEqualTo(NOTE);
        });
        // (b) 排序显式满足三级键单调（不依赖 containsExactly 的隐式校验）。
        assertOrdered(firstQuery, seeded);

        // (c) 两次查询对相同数据返回完全一致的顺序（需求 5.2）。
        List<RecurringPendingItem> secondQuery = service.queryPendingItems(LEDGER);
        assertThat(secondQuery).extracting(RecurringPendingItem::getId)
                .as("两次查询对相同数据返回完全一致的顺序")
                .containsExactlyElementsOf(expectedIds);

        // 3) 批量确认 / 跳过：逐条隔离 + 计数聚合（需求 5.4、5.5、5.6）。
        List<Long> batchIds = seeded.stream().filter(Seeded::inBatch).map(Seeded::itemId).toList();
        boolean useConfirm = scenario.useConfirm();
        PendingStatus targetStatus = useConfirm ? PendingStatus.CONFIRMED : PendingStatus.SKIPPED;

        // 逐条期望结果（按请求顺序 = 播种顺序中被选中的项）。
        List<Long> expectedSucceeded = new ArrayList<>();
        List<RecurringBatchResult.Failure> expectedFailed = new ArrayList<>();
        for (Seeded s : seeded) {
            if (!s.inBatch()) {
                continue;
            }
            String failureCode = expectedFailureCode(s);
            if (failureCode == null) {
                expectedSucceeded.add(s.itemId());
            } else {
                expectedFailed.add(new RecurringBatchResult.Failure(s.itemId(), failureCode));
            }
        }

        RecurringBatchResult result = useConfirm
                ? service.batchConfirm(ALICE, LEDGER, batchIds)
                : service.batchSkip(ALICE, LEDGER, batchIds);

        // (d) 逐条结果与请求顺序一致（成功 id 列表 / 失败明细逐一相等，需求 5.6）。
        assertThat(result.succeededIds())
                .as("成功 id 应恰为子集中的有效 PENDING 当前账本项，按请求顺序")
                .containsExactlyElementsOf(expectedSucceeded);
        assertThat(result.failed())
                .as("失败明细应恰为子集中已处理 / 跨租户项及其错误码，按请求顺序")
                .containsExactlyElementsOf(expectedFailed);

        // (e) 计数等于逐条结果的聚合（需求 5.6）。
        assertThat(result.successCount()).isEqualTo(result.succeededIds().size());
        assertThat(result.failureCount()).isEqualTo(result.failed().size());
        assertThat(result.successCount() + result.failureCount())
                .as("成功 + 失败计数应等于本次批量的待处理条目数")
                .isEqualTo(batchIds.size());

        // (f) 逐条隔离（需求 5.4、5.5）：成功条 → 目标状态；失败条 → 原状态不变；非子集项 → 原状态不变。
        Set<Long> succeededSet = new HashSet<>(expectedSucceeded);
        for (Seeded s : seeded) {
            PendingStatus actual = reload(s.itemId()).getStatus();
            if (s.inBatch() && succeededSet.contains(s.itemId())) {
                assertThat(actual)
                        .as("成功处理的条目应迁移到目标状态 itemId=%s", s.itemId())
                        .isEqualTo(targetStatus);
            } else {
                assertThat(actual)
                        .as("失败条目与非子集条目应保持原状态 itemId=%s", s.itemId())
                        .isEqualTo(s.status());
            }
        }

        // (g) 批量跳过全程零触账（需求 5.5）：跳过不生成任何流水。
        if (!useConfirm) {
            assertThat(transactionRepository.count())
                    .as("批量跳过不得生成任何流水")
                    .isZero();
        } else {
            // 批量确认恰为每个成功条生成一条流水（需求 5.4 侧证：成功条独立提交入账）。
            assertThat(transactionRepository.count())
                    .as("批量确认应恰为每个成功条生成一条流水")
                    .isEqualTo(expectedSucceeded.size());
        }
    }

    /**
     * 单条在批量确认 / 跳过下的期望失败码；{@code null} 表示期望成功。
     * <ul>
     *   <li>跨账本（项不在当前账本）→ {@code NOT_FOUND}（需求 8.4、8.5）。</li>
     *   <li>跨用户（规则不属当前用户）→ {@code NOT_FOUND}（需求 8.5）。</li>
     *   <li>已处理（非 {@code PENDING}）→ {@code RECURRING_ITEM_ALREADY_PROCESSED}（需求 4.5、5.5）。</li>
     *   <li>当前账本、当前用户、{@code PENDING} 且目标有效 → 成功。</li>
     * </ul>
     */
    private String expectedFailureCode(Seeded s) {
        if (!s.currentLedger()) {
            return "NOT_FOUND";
        }
        if (!s.aliceOwned()) {
            return "NOT_FOUND";
        }
        if (s.status() != PendingStatus.PENDING) {
            return "RECURRING_ITEM_ALREADY_PROCESSED";
        }
        return null;
    }

    /** 显式校验查询结果按 (到期日 → 规则 createdAt → 项 id) 单调不减。 */
    private void assertOrdered(List<RecurringPendingItem> result, List<Seeded> seeded) {
        java.util.Map<Long, LocalDateTime> ruleCreatedAt = new java.util.HashMap<>();
        for (Seeded s : seeded) {
            ruleCreatedAt.put(s.ruleId(), s.ruleCreatedAt());
        }
        for (int i = 1; i < result.size(); i++) {
            RecurringPendingItem prev = result.get(i - 1);
            RecurringPendingItem cur = result.get(i);
            int byDate = prev.getOccurrenceDate().compareTo(cur.getOccurrenceDate());
            assertThat(byDate).as("到期日升序").isLessThanOrEqualTo(0);
            if (byDate == 0) {
                int byRule = ruleCreatedAt.get(prev.getRuleId())
                        .compareTo(ruleCreatedAt.get(cur.getRuleId()));
                assertThat(byRule).as("同到期日按规则 createdAt 升序").isLessThanOrEqualTo(0);
                if (byRule == 0) {
                    assertThat(prev.getId()).as("同到期日同规则 createdAt 按项 id 升序")
                            .isLessThan(cur.getId());
                }
            }
        }
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

    /** 待确认项快照字段固定用有效分类 / 账户，使当前账本有效 PENDING 项确认可成功。 */
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

    private RecurringPendingItem reload(Long itemId) {
        return pendingItemRepository.findById(itemId).orElseThrow();
    }

    // =====================================================================
    // 生成器
    // =====================================================================

    /** 播种项的元数据（用于期望过滤 / 排序 / 批量结果对照）。 */
    record Seeded(Long itemId, Long ruleId, boolean currentLedger, boolean aliceOwned,
            LocalDate occurrenceDate, PendingStatus status, LocalDateTime ruleCreatedAt,
            boolean inBatch) {
    }

    /** 一条待确认项的生成参数：到期日偏移、状态、是否纳入批量子集。 */
    record ItemGen(int dayOffset, PendingStatus status, boolean inBatch) {
    }

    /** 一条规则的生成参数：账本归属、用户归属、创建时间偏移（排序中间键）、其待确认项。 */
    record RuleGen(boolean currentLedger, boolean aliceOwned, int createdAtOffset,
            List<ItemGen> items) {
    }

    /** 一个场景：跨账本跨用户的规则集 + 批量操作类型（确认 / 跳过）。 */
    record Scenario(List<RuleGen> rules, boolean useConfirm) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<RuleGen>> rules = ruleGen().list().ofMinSize(1).ofMaxSize(4);
        Arbitrary<Boolean> useConfirm = Arbitraries.of(true, false);
        return Combinators.combine(rules, useConfirm).as(Scenario::new);
    }

    private Arbitrary<RuleGen> ruleGen() {
        Arbitrary<Boolean> currentLedger = Arbitraries.of(true, true, false); // 偏向当前账本
        Arbitrary<Boolean> aliceOwned = Arbitraries.of(true, true, false);    // 偏向本人
        Arbitrary<Integer> createdOffset = Arbitraries.integers().between(0, 30);
        Arbitrary<List<ItemGen>> items = itemGen().list().ofMinSize(1).ofMaxSize(3);
        return Combinators.combine(currentLedger, aliceOwned, createdOffset, items).as(RuleGen::new);
    }

    private Arbitrary<ItemGen> itemGen() {
        Arbitrary<Integer> dayOffset = Arbitraries.integers().between(0, 330);
        Arbitrary<PendingStatus> status = Arbitraries.of(
                PendingStatus.PENDING, PendingStatus.PENDING,
                PendingStatus.CONFIRMED, PendingStatus.SKIPPED);
        Arbitrary<Boolean> inBatch = Arbitraries.of(true, true, false);
        return Combinators.combine(dayOffset, status, inBatch).as(ItemGen::new);
    }
}
