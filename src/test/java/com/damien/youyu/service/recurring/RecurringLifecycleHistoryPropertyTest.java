package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
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
 * Feature: recurring-transactions, Property 10: 生命周期历史不可变
 *
 * <p>{@link RecurringRuleService#pause} / {@link RecurringRuleService#resume} /
 * {@link RecurringRuleService#update} / {@link RecurringRuleService#delete} 与
 * {@link RecurringPendingItemService#lazyGenerate} 的属性测试，覆盖 design.md「Correctness Properties」
 * Property 10：</p>
 *
 * <p><em>对任意</em>规则与任意暂停 / 恢复 / 编辑 / 删除操作序列，已 {@link PendingStatus#CONFIRMED} 的历史
 * 流水取值、已发生的账户余额变动、已 {@link PendingStatus#SKIPPED} 的期次记录均保持不变、不被回滚；删除
 * 规则后其全部 {@link PendingStatus#PENDING} 项从查询中消失，而其 {@code CONFIRMED} 流水与 {@code SKIPPED}
 * 记录仍保留；恢复后仅为到期日 ≥ 恢复当日的期次生成待确认项，暂停区间内的期次不被补生成。</p>
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实提交、不用测试级事务</h2>
 * <p>与 {@link RecurringConfirmTest} / {@link RecurringGenerationIdempotencePropertyTest} 同源：确认入账
 * 复用真实 {@link com.damien.youyu.service.TransactionService}（账户加锁 + 单事务原子余额更新），且懒生成把
 * 每条期次插入下沉到 {@link RecurringPendingItemGenerator#generate} 的 {@code REQUIRES_NEW} 独立事务——
 * 只有经真实 Spring 事务代理且<b>真实提交</b>后回读，才能验证「历史流水 / 余额不被生命周期操作回滚」与
 * 「恢复后不回补暂停区间期次」。故用全栈上下文、不加测试级 {@code @Transactional}（那会在方法结束回滚并
 * 掩盖真实提交），清理改为每个 try 前显式清库（{@link #resetAndInject()}），并用独立命名的内存库避免
 * 污染其它切片测试。</p>
 *
 * <p>时钟用 {@code @Primary} 的固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15），使 {@code today}
 * 与「恢复当日」可确定性推导：任一 {@code pause}/{@code resume}/{@code update} 都把规则 {@code updated_at}
 * 置为固定 {@link #NOW}（即 {@link #TODAY}），于是懒生成的生成下界
 * {@code max(startDate, updatedAt.toLocalDate())} 被推进到 {@link #TODAY}——暂停区间内（早于 {@code TODAY}）
 * 的期次因低于下界永不被回补（需求 6.2）。</p>
 *
 * <h2>测试构造</h2>
 * <ol>
 *   <li>落库一条 {@code DAILY} 规则，开始日期早于 {@code TODAY}（{@code TODAY-6}），{@code updated_at}
 *       置为开始日（模拟已运行一段时间），期次为 {@code [TODAY-6 .. TODAY]} 共 7 个自然日。</li>
 *   <li>对随机选取的期次直接播种 {@code PENDING} 项，再经真实 {@code service.confirm}（生成真实流水 + 改余额）
 *       确认一部分、经 {@code service.skip} 跳过一部分、留一部分 {@code PENDING}；快照确认流水 / 余额 /
 *       跳过记录 / 全部已有项 id。</li>
 *   <li>施加一段随机的 {@code pause}/{@code resume}/{@code update}/{@code delete} 操作序列
 *       （删除后对同规则的其余操作按 {@code NOT_FOUND} 视为幂等空操作）。</li>
 *   <li>经 {@code service.queryPendingItems}（内部先触发懒生成）取最终状态并断言。</li>
 * </ol>
 *
 * <h2>断言</h2>
 * <ul>
 *   <li><b>历史不可变（需求 6.6）：</b>每条 {@code CONFIRMED} 项及其真实流水逐字段不变、账户余额等于确认后
 *       快照、总流水数不变；每条 {@code SKIPPED} 记录逐字段不变。</li>
 *   <li><b>删除语义（需求 6.5）：</b>删除后规则行消失、其 {@code PENDING} 项从查询与表中消失，而其
 *       {@code CONFIRMED} 流水与 {@code SKIPPED} 记录仍保留。</li>
 *   <li><b>恢复不回补（需求 6.2）：</b>操作序列后（非空序列使 {@code updated_at→TODAY}）任何到期日
 *       {@code < TODAY} 的待确认项都必是操作前已存在的（无新行被补生成于暂停区间）。</li>
 * </ul>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效：依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 上下文静态缓存复用，多次迭代只加载
 * 一次），同一钩子内随即硬清相关表并重新播种账户 / 分类，使各 try 互不串味。</p>
 *
 * <p><strong>Validates: Requirements 6.2, 6.5, 6.6</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-lifecycle-history-pbt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringLifecycleHistoryPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);
    /** 期次窗口天数：期次为 [TODAY-6 .. TODAY] 共 7 个自然日（全部 ≤ today，含 today）。 */
    private static final int WINDOW_DAYS = 6;
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    /** 账户初始余额足够大，避免任意确认组合触发余额约束（每项 ≤ 500.00，至多 7 项）。 */
    private static final BigDecimal SEED_BALANCE = new BigDecimal("1000000.00");

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
        // jqwik 不经 SpringExtension：手工注入 bean（上下文静态缓存，多次迭代只加载一次）。
        new TestContextManager(RecurringLifecycleHistoryPropertyTest.class).prepareTestInstance(this);
        // 清理不靠回滚（真实提交）：每个 try 前硬清相关表，使各迭代互不串味。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        accountId = seedAccount();
        linkAccountToLedger(accountId, LEDGER);
        categoryId = seedCategory(LEDGER, "房租");
    }

    // =====================================================================
    // Property 10
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 10: 生命周期历史不可变
     *
     * <p><strong>Validates: Requirements 6.2, 6.5, 6.6</strong></p>
     */
    @Property(tries = 100)
    void lifecycleOperationsNeverMutateHistoryAndResumeDoesNotBackfill(
            @ForAll("scenarios") Scenario scenario) {

        // 1) 落库一条 DAILY 规则，开始日期早于 today，updatedAt 置为开始日（模拟已运行一段时间）。
        LocalDate startDate = TODAY.minusDays(WINDOW_DAYS);
        RecurringRule rule = saveDailyRule(startDate);
        List<LocalDate> occurrences = new ArrayList<>();
        for (int i = 0; i <= WINDOW_DAYS; i++) {
            occurrences.add(startDate.plusDays(i));
        }

        // 2) 按处置逐期次播种并确认 / 跳过 / 留 PENDING（真实链路：确认建流水 + 改余额）。
        List<Long> confirmedIds = new ArrayList<>();
        List<Long> skippedIds = new ArrayList<>();
        for (int i = 0; i <= WINDOW_DAYS; i++) {
            Disp disp = scenario.dispositions().get(i);
            if (disp == Disp.NONE) {
                continue; // 该期次不播种任何记录（留作「暂停区间未生成期次」的空位）。
            }
            BigDecimal amount = BigDecimal.valueOf(scenario.amountsCents().get(i), 2);
            RecurringPendingItem item = seedPending(rule, occurrences.get(i), amount);
            switch (disp) {
                case CONFIRM -> {
                    RecurringPendingItem confirmed = pendingItemService.confirm(
                            ALICE, LEDGER, item.getId(), null, null, null, null, null);
                    confirmedIds.add(confirmed.getId());
                }
                case SKIP -> {
                    pendingItemService.skip(ALICE, LEDGER, item.getId());
                    skippedIds.add(item.getId());
                }
                case PENDING -> { /* 留 PENDING，待生命周期操作。 */ }
                default -> { /* NONE 已处理。 */ }
            }
        }

        // 3) 快照：确认后余额 / 总流水数 / 全部已有项 id / 确认项与其流水 / 跳过项。
        BigDecimal balanceAfterConfirm = balanceOf(accountId);
        long txCountAfterConfirm = transactionRepository.count();
        assertThat(txCountAfterConfirm)
                .as("确认应恰生成 confirmedIds.size() 条流水")
                .isEqualTo(confirmedIds.size());
        Set<Long> beforeItemIds = allItemIds();
        Map<Long, ItemSnap> confirmedSnaps = snapshotItems(confirmedIds);
        Map<Long, ItemSnap> skippedSnaps = snapshotItems(skippedIds);
        Map<Long, TxSnap> txSnaps = snapshotConfirmedTransactions(confirmedIds);

        // 4) 施加随机生命周期操作序列（删除后同规则其余操作按 NOT_FOUND 视为幂等空操作）。
        boolean deleted = false;
        for (Op op : scenario.ops()) {
            try {
                switch (op) {
                    case PAUSE -> ruleService.pause(ALICE, LEDGER, rule.getId());
                    case RESUME -> ruleService.resume(ALICE, LEDGER, rule.getId());
                    case EDIT -> ruleService.update(ALICE, LEDGER, rule.getId(), "expense",
                            new BigDecimal("777.00"), categoryId, accountId, "edited",
                            Frequency.DAILY, null, null, false, null, null, null,
                            EndCondition.NEVER, null, null);
                    case DELETE -> {
                        ruleService.delete(ALICE, LEDGER, rule.getId());
                        deleted = true;
                    }
                    default -> { /* 无。 */ }
                }
            } catch (ApiException e) {
                // 删除后对已不存在的规则再操作 → NOT_FOUND，属预期幂等空操作；其余错误码视为真失败。
                assertThat(e.getCode())
                        .as("生命周期操作仅允许删除后的 NOT_FOUND 幂等空操作，op=%s", op)
                        .isEqualTo("NOT_FOUND");
                assertThat(deleted)
                        .as("仅删除后才允许 NOT_FOUND，op=%s", op)
                        .isTrue();
            }
        }

        // 5) 查询（内部先触发懒生成）取最终状态。
        List<RecurringPendingItem> pendingNow = pendingItemService.queryPendingItems(LEDGER);

        // ---- 断言 A：确认历史流水与项逐字段不变（需求 6.6）。----
        for (Long id : confirmedIds) {
            RecurringPendingItem item = pendingItemRepository.findById(id).orElseThrow();
            assertThat(ItemSnap.of(item))
                    .as("已 CONFIRMED 的待确认项不应因生命周期操作而改变 itemId=%s", id)
                    .isEqualTo(confirmedSnaps.get(id));
            assertThat(item.getConfirmedTransactionId())
                    .as("CONFIRMED 项应仍持有其流水引用 itemId=%s", id)
                    .isNotNull();
            Transaction tx = transactionRepository.findById(item.getConfirmedTransactionId())
                    .orElseThrow();
            assertThat(TxSnap.of(tx))
                    .as("已确认入账的真实流水不应因生命周期操作而改变 / 回滚 itemId=%s", id)
                    .isEqualTo(txSnaps.get(id));
        }

        // ---- 断言 B：跳过记录逐字段不变（需求 6.6）。----
        for (Long id : skippedIds) {
            RecurringPendingItem item = pendingItemRepository.findById(id).orElseThrow();
            assertThat(ItemSnap.of(item))
                    .as("已 SKIPPED 的期次记录不应因生命周期操作而改变 / 回滚 itemId=%s", id)
                    .isEqualTo(skippedSnaps.get(id));
        }

        // ---- 断言 C：账户余额不被生命周期操作改变 / 回滚（需求 6.6）。----
        assertThat(balanceOf(accountId))
                .as("已发生的账户余额变动不应被生命周期操作改变 / 回滚")
                .isEqualByComparingTo(balanceAfterConfirm);

        // ---- 断言 D：总流水数不变（无流水被删除，懒生成不建流水）（需求 6.6）。----
        assertThat(transactionRepository.count())
                .as("生命周期操作与懒生成不应改变真实流水总数")
                .isEqualTo(txCountAfterConfirm);

        // ---- 断言 E：删除语义（需求 6.5）。----
        if (deleted) {
            assertThat(ruleRepository.findById(rule.getId()))
                    .as("删除后规则行应消失")
                    .isEmpty();
            // 该规则的全部 PENDING 从表中消失（级联移除，且懒生成不再为已删除规则生成）。
            assertThat(pendingItemRepository.findAll())
                    .as("删除后该规则不应残留任何 PENDING 项")
                    .noneMatch(i -> i.getRuleId().equals(rule.getId())
                            && i.getStatus() == PendingStatus.PENDING);
            // 且不出现在查询结果中。
            assertThat(pendingNow)
                    .as("删除后该规则的 PENDING 项应从查询中消失")
                    .noneMatch(i -> i.getRuleId().equals(rule.getId()));
            // CONFIRMED / SKIPPED 记录仍保留（断言 A、B 已逐条校验其存在与不变）。
        } else {
            assertThat(ruleRepository.findById(rule.getId()))
                    .as("未删除时规则行应仍存在")
                    .isPresent();
        }

        // ---- 断言 F：恢复不回补——无到期日 < TODAY 的新待确认项被补生成（需求 6.2）。----
        // 非空操作序列使规则 updated_at→TODAY，生成下界推进到 TODAY，暂停区间（< TODAY）期次永不回补；
        // 因此表中任何到期日 < TODAY 的项都必是操作前已存在者（其 id 在 beforeItemIds 内）。
        for (RecurringPendingItem item : pendingItemRepository.findAll()) {
            if (item.getOccurrenceDate().isBefore(TODAY)) {
                assertThat(beforeItemIds)
                        .as("恢复 / 懒生成不得为暂停区间（到期日 %s < %s）补生成新待确认项 itemId=%s",
                                item.getOccurrenceDate(), TODAY, item.getId())
                        .contains(item.getId());
            }
        }
    }

    // =====================================================================
    // 快照
    // =====================================================================

    private Set<Long> allItemIds() {
        Set<Long> ids = new HashSet<>();
        for (RecurringPendingItem item : pendingItemRepository.findAll()) {
            ids.add(item.getId());
        }
        return ids;
    }

    private Map<Long, ItemSnap> snapshotItems(List<Long> ids) {
        Map<Long, ItemSnap> map = new HashMap<>();
        for (Long id : ids) {
            map.put(id, ItemSnap.of(pendingItemRepository.findById(id).orElseThrow()));
        }
        return map;
    }

    private Map<Long, TxSnap> snapshotConfirmedTransactions(List<Long> confirmedIds) {
        Map<Long, TxSnap> map = new HashMap<>();
        for (Long id : confirmedIds) {
            RecurringPendingItem item = pendingItemRepository.findById(id).orElseThrow();
            Transaction tx = transactionRepository.findById(item.getConfirmedTransactionId())
                    .orElseThrow();
            map.put(id, TxSnap.of(tx));
        }
        return map;
    }

    /** 待确认项不可变快照（值语义相等，用于逐字段不变断言）。 */
    private record ItemSnap(Long ruleId, Long ledgerId, LocalDate occurrenceDate, PendingStatus status,
            String type, long amountCents, Long categoryId, Long accountId, String note,
            Long confirmedTransactionId) {
        static ItemSnap of(RecurringPendingItem i) {
            return new ItemSnap(i.getRuleId(), i.getLedgerId(), i.getOccurrenceDate(), i.getStatus(),
                    i.getType(), i.getAmount().movePointRight(2).longValueExact(), i.getCategoryId(),
                    i.getAccountId(), i.getNote(), i.getConfirmedTransactionId());
        }
    }

    /** 真实流水不可变快照（值语义相等，用于逐字段不变断言）。 */
    private record TxSnap(Long id, Long ledgerId, TransactionType type, long amountCents,
            Long accountId, Long categoryId, LocalDateTime occurredAt, String note) {
        static TxSnap of(Transaction t) {
            return new TxSnap(t.getId(), t.getLedgerId(), t.getType(),
                    t.getAmount().movePointRight(2).longValueExact(), t.getAccountId(),
                    t.getCategoryId(), t.getOccurredAt(), t.getNote());
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

    private void linkAccountToLedger(Long accountId, long ledgerId) {
        AccountLedger link = new AccountLedger();
        link.setAccountId(accountId);
        link.setLedgerId(ledgerId);
        link.setVisibleToOthers(true);
        link.setShowBalance(true);
        link.setCreatedAt(LocalDateTime.ofInstant(NOW, ZONE));
        accountLedgerRepository.save(link);
    }

    private Long seedCategory(long ledgerId, String name) {
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

    /** 落库一条 ACTIVE 每日规则；updatedAt 置为开始日（早于 today），模拟已运行一段时间。 */
    private RecurringRule saveDailyRule(LocalDate startDate) {
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ALICE);
        rule.setLedgerId(LEDGER);
        rule.setType("expense");
        rule.setAmount(new BigDecimal("300.00"));
        rule.setCategoryId(categoryId);
        rule.setAccountId(accountId);
        rule.setNote("房租");
        rule.setFrequency(Frequency.DAILY);
        rule.setMonthEnd(false);
        rule.setStartDate(startDate);
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.ACTIVE);
        rule.setCreatedAt(startDate.atStartOfDay());
        rule.setUpdatedAt(startDate.atStartOfDay());
        return ruleRepository.save(rule);
    }

    /** 为某期次直接播种一条 PENDING 待确认项（快照 type=expense、指定金额、真实分类 / 账户）。 */
    private RecurringPendingItem seedPending(RecurringRule rule, LocalDate occurrenceDate,
            BigDecimal amount) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(rule.getLedgerId());
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(PendingStatus.PENDING);
        item.setType("expense");
        item.setAmount(amount);
        item.setCategoryId(categoryId);
        item.setAccountId(accountId);
        item.setNote("房租");
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return pendingItemRepository.save(item);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    // =====================================================================
    // 生成器
    // =====================================================================

    /** 单期次的处置：不播种 / 留 PENDING / 确认 / 跳过。 */
    enum Disp { NONE, PENDING, CONFIRM, SKIP }

    /** 生命周期操作。 */
    enum Op { PAUSE, RESUME, EDIT, DELETE }

    /**
     * 一个场景：7 个期次各自的处置、7 个期次的金额（分）、1–5 个生命周期操作。操作序列<b>非空</b>，
     * 以保证任一场景都会把规则 {@code updated_at} 推进到 {@code TODAY}（或删除规则），从而生成下界推进到
     * {@code TODAY}、暂停区间期次不回补（需求 6.2 的可判定前提）。
     */
    record Scenario(List<Disp> dispositions, List<Integer> amountsCents, List<Op> ops) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<Disp>> dispositions =
                Arbitraries.of(Disp.class).list().ofSize(WINDOW_DAYS + 1);
        // 金额以「分」构造：1..50000 分（0.01–500.00），保证恰 2 位小数、精确可比、余额充裕。
        Arbitrary<List<Integer>> amounts =
                Arbitraries.integers().between(1, 50_000).list().ofSize(WINDOW_DAYS + 1);
        Arbitrary<List<Op>> ops = Arbitraries.of(Op.class).list().ofMinSize(1).ofMaxSize(5);
        return Combinators.combine(dispositions, amounts, ops).as(Scenario::new);
    }
}
