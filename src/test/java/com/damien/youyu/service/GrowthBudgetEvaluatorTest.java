package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.damien.youyu.domain.Budget;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link GrowthBudgetEvaluator#metMonths} 的示例/边界单元测试（关联需求 5.4、5.5、5.6、5.7、5.10、
 * 5.13、5.15）。
 *
 * <p>预算判定是无外部状态的纯查询组合逻辑，故不起 Spring 上下文：三个仓储用 Mockito 桩、以内存中的
 * 「账本 → 月份 → 金额」映射驱动。所有 <b>读查询</b>都经一个 {@link AtomicInteger} 计数型间谍累加，用来
 * 断言：</p>
 *
 * <ul>
 *   <li><b>读查询数恒 ≤ 8</b>（需求 5.15）：1 次自有账本清单 + 3 个回看月各至多 2 次（预算行 + 月度支出
 *       合计）= 至多 7；未设预算的月份跳过第二次查询；</li>
 *   <li><b>查询数不随账本数增长</b>（需求 5.15）：把自有账本从 1 个增到 20 个，读查询数不变——因为两个
 *       按月查询都用 {@code ledger_id IN (:ids)} 一次取回、在应用层分组。</li>
 * </ul>
 *
 * <p>结算日固定取 {@code 2025-06-15}，故回看窗口为其前 1/2/3 个自然月：
 * {@code 2025-05}（M1，最近）、{@code 2025-04}（M2）、{@code 2025-03}（M3，最远）；
 * {@code 2025-02} 属于第 4 个更早的月，用于验证需求 5.10「4 个月及以上不发放」。</p>
 */
class GrowthBudgetEvaluatorTest {

    private static final long USER = 42L;
    private static final LocalDate SETTLE_DATE = LocalDate.of(2025, 6, 15);

    private static final String M1 = "2025-05"; // 前 1 月（最近）
    private static final String M2 = "2025-04"; // 前 2 月
    private static final String M3 = "2025-03"; // 前 3 月（最远，仍在窗口内）
    private static final String M4_TOO_OLD = "2025-02"; // 前 4 月（超出窗口）

    private LedgerRepository ledgerRepository;
    private BudgetRepository budgetRepository;
    private TransactionRepository transactionRepository;
    private GrowthBudgetEvaluator evaluator;

    /** 计数型间谍：三个仓储的每一次读查询都在此累加（需求 5.15 的查询预算据此可断言）。 */
    private AtomicInteger readQueryCount;

    // 内存数据源：ledgerId -> (monthKey -> 金额)。
    private List<Ledger> ownedLedgers;
    private final Map<Long, Map<String, BigDecimal>> budgetByLedgerMonth = new HashMap<>();
    private final Map<Long, Map<String, BigDecimal>> expenseByLedgerMonth = new HashMap<>();

    @BeforeEach
    void setUp() {
        ledgerRepository = mock(LedgerRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        evaluator = new GrowthBudgetEvaluator(ledgerRepository, budgetRepository, transactionRepository);

        readQueryCount = new AtomicInteger(0);
        ownedLedgers = new ArrayList<>();
        budgetByLedgerMonth.clear();
        expenseByLedgerMonth.clear();

        // 查询 1：自有账本清单。
        when(ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(USER)).thenAnswer(inv -> {
            readQueryCount.incrementAndGet();
            return ownedLedgers;
        });

        // 查询 2k：某月自有账本的总预算行（ledger_id IN (:ids)），在桩里按传入的 ledgerIds 过滤。
        when(budgetRepository.findByLedgerIdInAndMonth(anyCollection(), anyString())).thenAnswer(inv -> {
            readQueryCount.incrementAndGet();
            @SuppressWarnings("unchecked")
            Collection<Long> ledgerIds = (Collection<Long>) inv.getArgument(0);
            String month = inv.getArgument(1);
            List<Budget> rows = new ArrayList<>();
            for (Long ledgerId : ledgerIds) {
                BigDecimal amount = budgetByLedgerMonth
                        .getOrDefault(ledgerId, Map.of())
                        .get(month);
                if (amount != null) {
                    rows.add(budget(ledgerId, month, amount));
                }
            }
            return rows;
        });

        // 查询 2k+1：某月自有账本的月度支出合计（GROUP BY ledger_id）。无支出的账本不出现在结果里。
        when(transactionRepository.sumMonthlyExpenseByLedgerIds(anyCollection(), any(), any()))
                .thenAnswer(inv -> {
                    readQueryCount.incrementAndGet();
                    @SuppressWarnings("unchecked")
                    Collection<Long> ledgerIds = (Collection<Long>) inv.getArgument(0);
                    LocalDateTime fromInclusive = inv.getArgument(1);
                    String month = YearMonth.from(fromInclusive.toLocalDate()).toString();
                    List<Object[]> rows = new ArrayList<>();
                    for (Long ledgerId : ledgerIds) {
                        BigDecimal spent = expenseByLedgerMonth
                                .getOrDefault(ledgerId, Map.of())
                                .get(month);
                        if (spent != null) {
                            rows.add(new Object[] { ledgerId, spent });
                        }
                    }
                    return rows;
                });
    }

    // ---- 达成判定的各分支（需求 5.4、5.5、5.6） ----

    /** 无任何总预算行：三个月都无从达成，返回空；不触发任何支出查询（需求 5.4）。 */
    @Test
    void noBudgetRowsYieldsNoMonths() {
        givenOwnedLedgers(1L);
        // 不设任何预算。

        assertThat(metMonths()).isEmpty();
        // 1 次账本清单 + 3 次按月预算查询；预算为空即跳过支出查询。
        assertThat(readQueryCount.get()).isEqualTo(4);
    }

    /** 已设预算但该月零支出：不算达成（需求 5.5）。 */
    @Test
    void zeroExpenseIsNotMet() {
        givenOwnedLedgers(1L);
        setBudget(1L, M1, "1000.00");
        // 不设支出 → GROUP BY 无该账本的行 → 合计视为 0.00。

        assertThat(metMonths()).isEmpty();
    }

    /** 支出超过预算：不算达成（需求 5.6）。 */
    @Test
    void overBudgetIsNotMet() {
        givenOwnedLedgers(1L);
        setBudget(1L, M1, "1000.00");
        setExpense(1L, M1, "1000.01");

        assertThat(metMonths()).isEmpty();
    }

    /** 支出恰好等于预算：视为达成（需求 5.6 的等号边界）。 */
    @Test
    void expenseExactlyEqualToBudgetIsMet() {
        givenOwnedLedgers(1L);
        setBudget(1L, M1, "1000.00");
        setExpense(1L, M1, "1000.00");

        assertThat(metMonths()).containsExactly(M1);
    }

    /** 支出低于预算且大于 0：达成。 */
    @Test
    void expenseUnderBudgetIsMet() {
        givenOwnedLedgers(1L);
        setBudget(1L, M1, "1000.00");
        setExpense(1L, M1, "500.00");

        assertThat(metMonths()).containsExactly(M1);
    }

    // ---- 多账本不叠加、协作账本不参与（需求 5.7、5.13） ----

    /** 同一月内多个自有账本都达成：只写 1 条该月事件，不叠加（需求 5.7）。 */
    @Test
    void multipleOwnedLedgersAllMetYieldsSingleMonth() {
        givenOwnedLedgers(1L, 2L);
        setBudget(1L, M1, "1000.00");
        setExpense(1L, M1, "800.00");
        setBudget(2L, M1, "2000.00");
        setExpense(2L, M1, "2000.00");

        assertThat(metMonths()).containsExactly(M1);
    }

    /**
     * 协作账本（用户为成员、非拥有者）达成其预算：不为该成员写入事件（需求 5.13）。
     *
     * <p>自有账本清单只含 {@code 1L}；协作账本 {@code 99L} 的预算与支出虽已达成，但它不在
     * {@code findByLedgerIdInAndMonth} 传入的 {@code ledgerIds} 内，故根本不会被查到、不参与判定。</p>
     */
    @Test
    void collaborativeLedgerMetIsNotReturned() {
        givenOwnedLedgers(1L); // 自有账本无预算
        setBudget(99L, M1, "1000.00"); // 协作账本达成，但不归该用户所有
        setExpense(99L, M1, "500.00");

        assertThat(metMonths()).isEmpty();
    }

    // ---- 回看窗口边界（需求 5.10） ----

    /** 3 个回看月都达成：返回 3 个月，顺序近→远。 */
    @Test
    void allThreeLookbackMonthsMet() {
        givenOwnedLedgers(1L);
        setBudget(1L, M1, "1000.00");
        setExpense(1L, M1, "900.00");
        setBudget(1L, M2, "1000.00");
        setExpense(1L, M2, "1000.00");
        setBudget(1L, M3, "1000.00");
        setExpense(1L, M3, "1.00");

        assertThat(metMonths()).containsExactly(M1, M2, M3);
    }

    /** 第 4 个更早的月即使达成也不返回（回看窗口固定 3 个月，需求 5.10）。 */
    @Test
    void fourthMonthOutsideWindowIsNotReturned() {
        givenOwnedLedgers(1L);
        // 窗口内 M1 达成、窗口外 M4 也达成。
        setBudget(1L, M1, "1000.00");
        setExpense(1L, M1, "500.00");
        setBudget(1L, M4_TOO_OLD, "1000.00");
        setExpense(1L, M4_TOO_OLD, "500.00");

        List<String> met = metMonths();
        assertThat(met).containsExactly(M1);
        assertThat(met).doesNotContain(M4_TOO_OLD);
    }

    // ---- 查询预算：≤8 且不随账本数增长（需求 5.15） ----

    /** 三个月全部有预算与支出（最坏情形）时读查询数仍 ≤ 8。 */
    @Test
    void readQueryCountNeverExceedsEight() {
        givenOwnedLedgers(1L);
        setAllThreeMonthsMet(1L);

        metMonths();

        // 1（账本清单）+ 3（预算）+ 3（支出）= 7 ≤ 8。
        assertThat(readQueryCount.get()).isLessThanOrEqualTo(8);
    }

    /** 账本数由 1 增到 20：读查询数不变（两个按月查询用 IN (:ids) 一次取回，需求 5.15）。 */
    @Test
    void readQueryCountDoesNotGrowWithLedgerCount() {
        // 单账本。
        givenOwnedLedgers(1L);
        setAllThreeMonthsMet(1L);
        metMonths();
        int countForOneLedger = readQueryCount.get();

        // 重置为 20 个账本，各账本三个月都达成。
        setUp();
        Long[] twentyLedgerIds = new Long[20];
        for (int i = 0; i < 20; i++) {
            twentyLedgerIds[i] = (long) (i + 1);
            setAllThreeMonthsMet((long) (i + 1));
        }
        givenOwnedLedgers(twentyLedgerIds);
        metMonths();
        int countForTwentyLedgers = readQueryCount.get();

        assertThat(countForTwentyLedgers).isEqualTo(countForOneLedger);
        assertThat(countForTwentyLedgers).isLessThanOrEqualTo(8);
    }

    // ---- 辅助 ----

    private List<String> metMonths() {
        return evaluator.metMonths(USER, SETTLE_DATE, Collections.<String>emptySet());
    }

    private void givenOwnedLedgers(Long... ids) {
        ownedLedgers = new ArrayList<>();
        int sort = 0;
        for (Long id : ids) {
            Ledger ledger = new Ledger();
            ledger.setId(id);
            ledger.setUserId(USER);
            ledger.setSortOrder(sort++);
            ownedLedgers.add(ledger);
        }
    }

    private void setBudget(long ledgerId, String month, String amount) {
        budgetByLedgerMonth
                .computeIfAbsent(ledgerId, k -> new HashMap<>())
                .put(month, new BigDecimal(amount));
    }

    private void setExpense(long ledgerId, String month, String amount) {
        expenseByLedgerMonth
                .computeIfAbsent(ledgerId, k -> new HashMap<>())
                .put(month, new BigDecimal(amount));
    }

    /** 让某账本在三个回看月都达成（预算 1000、支出 500）。 */
    private void setAllThreeMonthsMet(long ledgerId) {
        for (String month : new String[] { M1, M2, M3 }) {
            setBudget(ledgerId, month, "1000.00");
            setExpense(ledgerId, month, "500.00");
        }
    }

    private static Budget budget(long ledgerId, String month, BigDecimal amount) {
        Budget b = new Budget();
        b.setLedgerId(ledgerId);
        b.setMonth(month);
        b.setAmount(amount);
        return b;
    }
}
