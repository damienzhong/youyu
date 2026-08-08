package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Budget;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 预算达成经验的<b>口径与多账本不叠加</b>回归锁（<b>Property 11：预算达成的口径与多账本不叠加</b>）。
 *
 * <p>对<i>任意</i>（自有账本集合、协作账本集合、各账本各月的总预算、各账本各月的支出集合、结算时刻）
 * 组合，锁住 {@link GrowthBudgetEvaluator}/{@link GrowthSettlementService#settle} 在预算侧的全部承诺：
 * {@code BUDGET_MET:M} 事件<b>当且仅当</b>「M 是结算日所属月的前 1/2/3 个自然月」且「存在<b>自有</b>账本在
 * M 有总预算行、M 内月度有效支出合计 &gt;0 且 ≤ 预算金额」时被写入；每月<b>恰好 1 条</b>（多账本命中不叠加、
 * 不可通过新建账本刷取）；每条 {@code exp_amount == 50}；未设总预算 / 零支出 / 超支三种情形均不写入；
 * 协作账本达成不为该成员写入；早于 4 个月的达成月不写入；结算日所属月永不判定；月度支出按 {@code occurred_at}
 * 半开区间 [月首 00:00, 次月首 00:00) 聚合、只计 {@code expense}、排除 {@code deleted_at} 非空的行；预算判定的
 * 读查询数 ≤8；{@code budgets} 与 {@code category_budgets} 两表在结算前后行数与全部列取值完全不变。</p>
 *
 * <h2>参考实现（对拍）</h2>
 * <p>用一份纯内存参考实现按需求 5.3 计算「应写入的月份集合」，再与库里实际写入的 {@code BUDGET_MET} 事件键
 * 集合<b>相等</b>比对（非包含）。参考实现刻意只把<b>自有账本</b>的<b>月内、未删、支出型</b>金额计入合计，
 * 边界（次月 00:00）、软删、收入三类一律排除——与被测口径一一对应。任一处口径漂移（比如误用
 * {@code created_by} 跨账本合并、把边界或软删算进去、把结算日所属月纳入判定、多账本叠加发放）都会让「相等」
 * 断言立刻变红。</p>
 *
 * <h2>驱动方式：全栈 {@code @SpringBootTest} + 真实提交，不用测试级事务</h2>
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有让它真正<b>提交</b>才能在库里观察到写入的
 * 事件。故本测试不加测试级 {@code @Transactional}（那会在方法结束时回滚、掩盖真实写入），而是直接调用
 * {@code settle} 并从库读回断言；清理不靠回滚，{@link #resetState()} 每次迭代前显式清库、用全局自增序号
 * {@link #SEQ} 保证 {@code userId}/{@code ledgerId} 每次迭代唯一（双重隔离）。时钟用一个 {@code @Primary}
 * 可推进 {@link MutableClock}，在属性方法内按 {@code settleMonthOffset} <b>跨月推进</b>结算日，使回看窗口
 * 在 {@code 2025-06} ~ {@code 2025-09} 之间移动、覆盖不同的 3 个自然月。</p>
 *
 * <p>jqwik 属性方法不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 手工完成（静态上下文缓存复用，多次迭代只加载一次上下文）。用独立命名的内存库避免污染
 * 兄弟切片测试。</p>
 *
 * <h2>查询预算（需求 5.15）</h2>
 * <p>用 JDK 动态代理把 {@link GrowthBudgetEvaluator} 依赖的三个仓储包成 {@code @Primary} 计数装饰器，只对
 * 预算判定专用的三个方法（自有账本清单 / 按月预算行 / 按月支出合计）计数：每次结算后断言 ≤8。「查询数不随
 * 账本数增长」这条更强的独立性由 {@link GrowthBudgetEvaluatorTest} 的专用单测锁住（那里把账本从 1 增到
 * 20、断言查询数不变）；本属性用 {@code ownedLedgerCount ∈ [1,4]} 的随机取值旁证 ≤8 恒成立。</p>
 *
 * <p>Feature: growth-level-system, Property 11: 预算达成的口径与多账本不叠加</p>
 *
 * <p>Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.9, 5.10, 5.11, 5.12, 5.13, 5.15</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-prop11-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(GrowthBudgetMetPropertyTest.CountingRepoConfig.class)
class GrowthBudgetMetPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 结算月的锚点；属性方法按 settleMonthOffset 在此基础上跨月推进（2025-06 ~ 2025-09）。 */
    private static final YearMonth ANCHOR = YearMonth.of(2025, 6);
    private static final Instant BASE = ANCHOR.atDay(15).atTime(8, 0).atZone(ZONE).toInstant();
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 回看窗口内的自然月数（结算月的前 1/2/3 个）。 */
    private static final int LOOKBACK = 3;
    /** 自有账本槽位上界（生成器网格宽度；实际取前 ownedLedgerCount 个）。 */
    private static final int MAX_OWNED = 4;

    /** 预算判定专用的三个仓储方法名：只对它们计数（需求 5.15）。 */
    private static final Set<String> COUNTED_METHODS = Set.of(
            "findByUserIdOrderBySortOrderAscIdAsc", // 查询 1：自有账本清单
            "findByLedgerIdInAndMonth",             // 查询 2k：按月预算行
            "sumMonthlyExpenseByLedgerIds");        // 查询 2k+1：按月支出合计
    private static final AtomicInteger BUDGET_QUERIES = new AtomicInteger(0);

    /** 全局自增序号：保证每次迭代 userId / ledgerId / 协作用户 id 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(11_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private BudgetRepository budgetRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthBudgetMetPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删相关表（成长两表无外键，删除顺序无约束）。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM budgets");
        jdbcTemplate.update("DELETE FROM category_budgets");
        jdbcTemplate.update("DELETE FROM ledgers");
    }

    // ---------------- 生成器 ----------------

    /** 某账本某月的总预算取值：NONE 表示未设总预算（需求 5.4 的「无从达成」）。 */
    enum BudgetKind {
        NONE(null),
        MIN(new BigDecimal("0.01")),
        MID(new BigDecimal("500.00")),
        LARGE(new BigDecimal("100000.00"));

        final BigDecimal amount;

        BudgetKind(BigDecimal amount) {
            this.amount = amount;
        }
    }

    /** 某账本某月的「月内有效支出」金额（0 表示无支出，触发需求 5.5 的零支出分支）。 */
    enum ExpenseKind {
        NONE(new BigDecimal("0.00")),
        TINY(new BigDecimal("0.01")),
        MID(new BigDecimal("250.00")),
        EQ_MID(new BigDecimal("500.00")),   // 与 MID 预算相等 → 达成边界（需求 5.6）
        OVER_MID(new BigDecimal("500.01")), // 略超 MID 预算 → 不达成（需求 5.6）
        LARGE(new BigDecimal("100000.00"));

        final BigDecimal amount;

        ExpenseKind(BigDecimal amount) {
            this.amount = amount;
        }
    }

    /**
     * 一个 (回看月, 账本槽位) 的支出/预算构造：除「月内有效支出」外，还可叠加三类<b>必须被排除</b>的干扰行，
     * 用来锁住聚合口径（需求 5.11）。
     */
    static final class Cell {
        final BudgetKind budget;
        final ExpenseKind expense;
        /** 在次月 1 日 00:00 放一笔支出（半开区间右开端，必须不计入本月）。 */
        final boolean boundaryExpense;
        /** 在月内放一笔已软删的支出（deleted_at 非空，必须排除）。 */
        final boolean deletedExpense;
        /** 在月内放一笔收入（type=income，必须排除）。 */
        final boolean incomeInMonth;

        Cell(BudgetKind budget, ExpenseKind expense,
             boolean boundaryExpense, boolean deletedExpense, boolean incomeInMonth) {
            this.budget = budget;
            this.expense = expense;
            this.boundaryExpense = boundaryExpense;
            this.deletedExpense = deletedExpense;
            this.incomeInMonth = incomeInMonth;
        }
    }

    /** 网格：monthIndex(0=前1月,1=前2月,2=前3月) × 账本槽位(0..MAX_OWNED-1)。 */
    @Provide
    Arbitrary<List<List<Cell>>> monthGrid() {
        Arbitrary<Cell> cell = Combinators.combine(
                Arbitraries.of(BudgetKind.class),
                Arbitraries.of(ExpenseKind.class),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false)).as(Cell::new);
        Arbitrary<List<Cell>> row = cell.list().ofSize(MAX_OWNED);
        return row.list().ofSize(LOOKBACK);
    }

    // ---------------- Property 11 ----------------

    /**
     * 对任意（自有账本数、协作账本数、结算月、各回看月各账本的预算/支出组合）：结算写入的 {@code BUDGET_MET}
     * 月份集合恰好等于内存参考实现按需求 5.3 算出的应写入集合；每月至多 1 条、每条 50 经验；结算月与超窗口月
     * 永不写入；协作账本达成不泄漏；预算两表结算前后逐行不变；预算判定读查询数 ≤8。
     */
    @Property(tries = 15)
    void property11_budgetMetScopeCaliberAndNoStacking(
            @ForAll @IntRange(min = 1, max = MAX_OWNED) int ownedLedgerCount,
            @ForAll @IntRange(min = 0, max = 3) int collabLedgerCount,
            @ForAll @IntRange(min = 0, max = 3) int settleMonthOffset,
            @ForAll("monthGrid") List<List<Cell>> grid) {

        long userId = SEQ.getAndIncrement();

        // 跨月推进结算日：回看窗口随之移动，覆盖不同的 3 个自然月（注入 MutableClock）。
        YearMonth settleMonth = ANCHOR.plusMonths(settleMonthOffset);
        LocalDate settleDate = settleMonth.atDay(15);
        CLOCK.reset(settleDate.atTime(8, 0).atZone(ZONE).toInstant());

        YearMonth m0 = settleMonth;                 // 结算月：永不判定（需求 5.1）
        YearMonth m1 = settleMonth.minusMonths(1);  // 前 1 月
        YearMonth m2 = settleMonth.minusMonths(2);  // 前 2 月
        YearMonth m3 = settleMonth.minusMonths(3);  // 前 3 月（窗口最远端）
        YearMonth m4 = settleMonth.minusMonths(4);  // 前 4 月：超窗口，永不写入（需求 5.10）
        YearMonth[] window = {m1, m2, m3};

        // ── 自有账本 ─────────────────────────────────────────────────────────────
        long[] owned = new long[ownedLedgerCount];
        for (int i = 0; i < ownedLedgerCount; i++) {
            owned[i] = createLedger(userId, Ledger.TYPE_PERSONAL, "own-" + userId + "-" + i);
        }

        // 参考模型：自有账本的「预算」与「月度有效支出合计」按 (ledgerId, 自然月) 累加，口径与被测完全一致
        // ——只计 expense 型、未软删的行，按 occurred_at 归月。边界行（次月 00:00）自然落到下一个月，软删与
        // 收入不进入该映射。据此判定任一月是否达成，避免手工推导跨月归属出错。
        Map<Long, Map<YearMonth, BigDecimal>> ownedBudget = new HashMap<>();
        Map<Long, Map<YearMonth, BigDecimal>> ownedValidExpense = new HashMap<>();

        // 按网格铺设自有账本在 3 个回看月的预算与支出。
        for (int mi = 0; mi < LOOKBACK; mi++) {
            YearMonth month = window[mi];
            for (int slot = 0; slot < ownedLedgerCount; slot++) {
                Cell c = grid.get(mi).get(slot);
                long ledgerId = owned[slot];

                if (c.budget != BudgetKind.NONE) {
                    insertBudget(userId, ledgerId, month, c.budget.amount);
                    recordBudget(ownedBudget, ledgerId, month, c.budget.amount);
                }
                // 月内有效支出（计入本月合计）。
                if (c.expense.amount.signum() > 0) {
                    insertExpense(userId, ledgerId, month.atDay(10).atTime(12, 0),
                            c.expense.amount, TransactionType.EXPENSE, false);
                    addExpense(ownedValidExpense, ledgerId, month, c.expense.amount);
                }
                // 边界行：落在次月 1 日 00:00 → 归属次月（半开区间右开端，本月不计、次月计入，需求 5.11）。
                if (c.boundaryExpense) {
                    insertExpense(userId, ledgerId, month.plusMonths(1).atDay(1).atStartOfDay(),
                            new BigDecimal("250.00"), TransactionType.EXPENSE, false);
                    addExpense(ownedValidExpense, ledgerId, month.plusMonths(1), new BigDecimal("250.00"));
                }
                // 软删支出：排除（不进入有效支出合计）。
                if (c.deletedExpense) {
                    insertExpense(userId, ledgerId, month.atDay(11).atTime(9, 0),
                            new BigDecimal("300.00"), TransactionType.EXPENSE, true);
                }
                // 收入：排除（只计 expense 型）。
                if (c.incomeInMonth) {
                    insertExpense(userId, ledgerId, month.atDay(12).atTime(9, 0),
                            new BigDecimal("300.00"), TransactionType.INCOME, false);
                }
            }
        }

        // 参考期望：仅在 3 个回看月内、仅看自有账本，存在某账本有预算 且 月度有效支出 >0 且 ≤ 预算 → 该月达成。
        Set<String> expectedMonths = new LinkedHashSet<>();
        for (YearMonth month : window) {
            boolean monthMet = false;
            for (long ledgerId : owned) {
                BigDecimal budget = ownedBudget.getOrDefault(ledgerId, Map.of()).get(month);
                if (budget == null) {
                    continue;
                }
                BigDecimal validSum = ownedValidExpense
                        .getOrDefault(ledgerId, Map.of())
                        .getOrDefault(month, BigDecimal.ZERO);
                if (validSum.signum() > 0 && validSum.compareTo(budget) <= 0) {
                    monthMet = true;
                    break;
                }
            }
            if (monthMet) {
                expectedMonths.add(month.toString());
            }
        }

        // ── 结算月(m0) 与 超窗口月(m4)：刻意各铺一个「本会达成」的自有账本，断言它们绝不写入 ──────
        seedDefinitelyMet(userId, owned[0], m0); // 结算月：需求 5.1
        seedDefinitelyMet(userId, owned[0], m4); // 前 4 月：需求 5.10

        // ── 协作账本（他人拥有，本用户作为成员在其中记账）：即便达成也不为本成员写入（需求 5.13）──
        for (int i = 0; i < collabLedgerCount; i++) {
            long otherUser = SEQ.getAndIncrement();
            long collabId = createLedger(otherUser, Ledger.TYPE_COLLABORATIVE, "collab-" + otherUser + "-" + i);
            // 预算属于账本拥有者，支出由本用户在协作账本内产生：若被测误用 created_by 合并就会泄漏。
            insertBudget(otherUser, collabId, m1, new BigDecimal("500.00"));
            insertExpense(userId, collabId, m1.atDay(10).atTime(12, 0),
                    new BigDecimal("250.00"), TransactionType.EXPENSE, false);
        }

        // 预算两表结算前快照（需求 5.12：结算不得修改这两表任何行）。
        List<Map<String, Object>> budgetsBefore = snapshot("budgets");
        List<Map<String, Object>> categoryBudgetsBefore = snapshot("category_budgets");

        // ── 结算（首次、无档案，不会被节流）──────────────────────────────────────────
        BUDGET_QUERIES.set(0);
        settlementService.settle(userId, TriggerSource.RECORD);
        int budgetQueries = BUDGET_QUERIES.get();

        // ── 断言 ────────────────────────────────────────────────────────────────
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT event_key, exp_amount FROM growth_events "
                        + "WHERE user_id = ? AND event_type = 'BUDGET_MET' ORDER BY event_key",
                userId);

        List<String> actualKeys = events.stream()
                .map(e -> (String) e.get("event_key"))
                .toList();
        Set<String> actualMonths = new LinkedHashSet<>();
        for (String key : actualKeys) {
            assertThat(key).startsWith("BUDGET_MET:");
            actualMonths.add(key.substring("BUDGET_MET:".length()));
        }

        // 口径与窗口：写入月份集合恰好等于参考期望（相等，非包含）。
        Set<String> expectedKeys = new LinkedHashSet<>();
        for (String month : expectedMonths) {
            expectedKeys.add("BUDGET_MET:" + month);
        }
        assertThat(actualMonths)
                .as("BUDGET_MET 月份必须恰好等于参考期望（口径/窗口/协作排除/多账本不叠加）")
                .containsExactlyInAnyOrderElementsOf(expectedMonths);

        // 每月至多 1 条（多账本命中不叠加）：event_key 无重复。
        assertThat(actualKeys)
                .as("每月至多 1 条 BUDGET_MET（多账本不叠加、不可刷取）")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(expectedKeys);

        // 每条 exp_amount == 50，且预算贡献的经验合计 = 50 × 达成月数（不叠加）。
        long budgetExpSum = 0L;
        for (Map<String, Object> e : events) {
            long exp = ((Number) e.get("exp_amount")).longValue();
            assertThat(exp).as("BUDGET_MET 事件的 exp_amount 恒为 50").isEqualTo(50L);
            budgetExpSum += exp;
        }
        assertThat(budgetExpSum)
                .as("预算达成经验合计 = 50 × 达成月数")
                .isEqualTo(50L * expectedMonths.size());

        // 结算月与超窗口月永不写入。
        assertThat(actualMonths)
                .as("结算月不参与判定（需求 5.1）、超窗口月不写入（需求 5.10）")
                .doesNotContain(m0.toString(), m4.toString());

        // 预算两表结算前后逐行不变（需求 5.12）。
        assertThat(snapshot("budgets"))
                .as("结算不得修改 budgets 表任何行")
                .isEqualTo(budgetsBefore);
        assertThat(snapshot("category_budgets"))
                .as("结算不得修改 category_budgets 表任何行")
                .isEqualTo(categoryBudgetsBefore);

        // 预算判定读查询数 ≤8（需求 5.15；不随账本数增长由 GrowthBudgetEvaluatorTest 锁死）。
        assertThat(budgetQueries)
                .as("预算判定读查询数必须 ≤8（含账本清单 + 至多 3 月 × 2）")
                .isLessThanOrEqualTo(8);
    }

    // ---------------- 事实源播种 ----------------

    /** 让某自有账本在某月「本会达成」（预算 500、月内支出 250），用于验证「不该写入」的月份。 */
    private void seedDefinitelyMet(long userId, long ledgerId, YearMonth month) {
        insertBudget(userId, ledgerId, month, new BigDecimal("500.00"));
        insertExpense(userId, ledgerId, month.atDay(10).atTime(12, 0),
                new BigDecimal("250.00"), TransactionType.EXPENSE, false);
    }

    /** 创建一个账本，返回其 id。type 决定个人/协作；owner 决定 ledgers.user_id（预算只看自有）。 */
    private long createLedger(long ownerUserId, String type, String name) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        Ledger ledger = new Ledger();
        ledger.setUserId(ownerUserId);
        ledger.setName(name);
        ledger.setType(type);
        ledger.setSortOrder(0);
        ledger.setDefault(false);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        return ledgerRepository.save(ledger).getId();
    }

    /** 插入一条月度总预算行（属于指定 owner 与 ledger 的指定自然月）。 */
    private void insertBudget(long ownerUserId, long ledgerId, YearMonth month, BigDecimal amount) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        Budget budget = new Budget();
        budget.setUserId(ownerUserId);
        budget.setLedgerId(ledgerId);
        budget.setMonth(month.toString());
        budget.setAmount(amount);
        budget.setCreatedAt(now);
        budget.setUpdatedAt(now);
        budgetRepository.save(budget);
    }

    /**
     * 插入一笔交易。预算判定按 {@code occurred_at} 聚合，故 {@code occurred_at} 为口径关键；此处
     * {@code created_at} 取同值（仅影响记账日历，不影响预算判定）。{@code deleted} 为真时置 {@code deleted_at}
     * 非空（应被排除）。走 JDBC 以便精确控制 {@code type}/{@code occurred_at}/{@code deleted_at}。
     */
    private void insertExpense(long createdBy, long ledgerId, LocalDateTime at,
                               BigDecimal amount, TransactionType type, boolean deleted) {
        LocalDateTime deletedAt = deleted ? at.plusHours(1) : null;
        jdbcTemplate.update(
                "INSERT INTO transactions "
                        + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                        + "occurred_at, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                createdBy, ledgerId, createdBy, type.getCode(), amount,
                ledgerId, ledgerId, at, at, at, deletedAt);
    }

    // ---------------- 读回工具 ----------------

    /** 参考模型：记录某自有账本某月的总预算（每账本每月至多一条）。 */
    private static void recordBudget(Map<Long, Map<YearMonth, BigDecimal>> map,
                                     long ledgerId, YearMonth month, BigDecimal amount) {
        map.computeIfAbsent(ledgerId, k -> new HashMap<>()).put(month, amount);
    }

    /** 参考模型：把某自有账本某月的一笔有效支出累加进合计（同月多笔按 occurred_at 归并）。 */
    private static void addExpense(Map<Long, Map<YearMonth, BigDecimal>> map,
                                   long ledgerId, YearMonth month, BigDecimal amount) {
        map.computeIfAbsent(ledgerId, k -> new HashMap<>())
                .merge(month, amount, BigDecimal::add);
    }

    /** 某表全部行的逐行快照（全部列，按 id 升序），用于逐行比对不变。 */
    private List<Map<String, Object>> snapshot(String table) {
        return jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id ASC");
    }

    // ---------------- 基础设施 ----------------

    /**
     * 计数装饰器：把 {@link GrowthBudgetEvaluator} 依赖的三个仓储包成 {@code @Primary} JDK 动态代理，
     * 委托给真实仓储的同时只对 {@link #COUNTED_METHODS} 三个方法计数（需求 5.15）。代理实现接口、纯委托，
     * 对全应用其它注入点透明。
     */
    @TestConfiguration
    static class CountingRepoConfig {

        @Bean
        @Primary
        LedgerRepository countingLedgerRepository(@Qualifier("ledgerRepository") LedgerRepository real) {
            return countingProxy(LedgerRepository.class, real);
        }

        @Bean
        @Primary
        BudgetRepository countingBudgetRepository(@Qualifier("budgetRepository") BudgetRepository real) {
            return countingProxy(BudgetRepository.class, real);
        }

        @Bean
        @Primary
        TransactionRepository countingTransactionRepository(
                @Qualifier("transactionRepository") TransactionRepository real) {
            return countingProxy(TransactionRepository.class, real);
        }

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @SuppressWarnings("unchecked")
        private static <T> T countingProxy(Class<T> iface, T real) {
            return (T) Proxy.newProxyInstance(
                    iface.getClassLoader(),
                    new Class<?>[] {iface},
                    (proxy, method, args) -> {
                        if (COUNTED_METHODS.contains(method.getName())) {
                            BUDGET_QUERIES.incrementAndGet();
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
        }
    }

    /** 可推进、可归位的时钟（供每次迭代跨月推进）。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void reset(Instant to) {
            this.instant = to;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
