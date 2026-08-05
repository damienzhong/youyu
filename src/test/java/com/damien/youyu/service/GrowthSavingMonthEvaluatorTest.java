package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link GrowthSavingMonthEvaluator#savingMonths} 的示例/边界单元测试（关联需求 4.1、4.2、4.3、4.4、
 * 4.5、4.6、4.8、4.10、4.11、4.19、4.20）。
 *
 * <p>沿用 {@link GrowthBudgetEvaluatorTest} 的范式：储蓄月判定是无外部状态的纯算术 + 窗口逻辑，故不起
 * Spring 上下文，{@link TransactionRepository} 用 Mockito 桩、以内存中的「月份 → (收入, 支出)」映射驱动。
 * 桩<b>自己实现 SQL 的半开区间过滤</b>（{@code occurred_at ∈ [fromInclusive, toExclusive)} 落到
 * 「月首落在窗口内」），因此「结算日所属月不参与判定」「第 4 个更早的月在窗口外」这两条能在这里被真正
 * 证伪，而不是只断言一个被喂好的返回值。</p>
 *
 * <p><b>与本类分工</b>：三条排除口径（{@code deleted_at} 非空 / {@code ledger_id} 为 NULL /
 * {@code type = 'transfer'}）与逐笔交易的月份归属写在 SQL 里，mock 无从验证，由
 * {@code com.damien.youyu.repository.GrowthSavingMonthQueryTest} 在真实 H2 上覆盖。</p>
 *
 * <p>结算日基准取 {@code 2025-06-15}，回看窗口为其前 1/2/3 个自然月：
 * {@code 2025-05}（M1，最近）、{@code 2025-04}（M2）、{@code 2025-03}（M3，最远）；
 * {@code 2025-02} 属于第 4 个更早的月，用于验证需求 4.10。</p>
 */
class GrowthSavingMonthEvaluatorTest {

    private static final long USER = 42L;
    private static final LocalDate SETTLE_DATE = LocalDate.of(2025, 6, 15);

    private static final String M1 = "2025-05"; // 前 1 月（最近）
    private static final String M2 = "2025-04"; // 前 2 月
    private static final String M3 = "2025-03"; // 前 3 月（最远，仍在窗口内）
    private static final String M4_TOO_OLD = "2025-02"; // 前 4 月（超出窗口）
    private static final String SETTLE_MONTH = "2025-06"; // 结算日所属月（不参与判定）

    private TransactionRepository transactionRepository;
    private GrowthSavingMonthEvaluator evaluator;

    /** 读查询计数：需求 4.11 要求本判定恒为 1 条查询、不按月循环。 */
    private AtomicInteger readQueryCount;

    /** 桩收到的窗口边界，用于断言 {@code [月首, 结算月首)} 的半开区间（需求 4.6）。 */
    private LocalDateTime capturedFrom;
    private LocalDateTime capturedTo;

    /** 内存数据源：monthKey -> [收入合计, 支出合计]，null 表示该类型该月无行（查询结果缺行）。 */
    private final Map<String, String[]> amountsByMonth = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        evaluator = new GrowthSavingMonthEvaluator(transactionRepository);
        readQueryCount = new AtomicInteger(0);
        capturedFrom = null;
        capturedTo = null;
        amountsByMonth.clear();

        // 唯一的一条读查询：按「年 × 月 × 类型」分组返回 [year, month, type, sum]。
        // 桩内按半开区间 [fromInclusive, toExclusive) 过滤，等价于 SQL 的 occurred_at 条件。
        when(transactionRepository.sumMonthlyAmountsByCreatedByGroupByMonthAndType(eq(USER), any(), any()))
                .thenAnswer(inv -> {
                    readQueryCount.incrementAndGet();
                    capturedFrom = inv.getArgument(1);
                    capturedTo = inv.getArgument(2);
                    List<Object[]> rows = new ArrayList<>();
                    for (Map.Entry<String, String[]> entry : amountsByMonth.entrySet()) {
                        YearMonth month = YearMonth.parse(entry.getKey());
                        LocalDateTime monthStart = month.atDay(1).atStartOfDay();
                        if (monthStart.isBefore(capturedFrom) || !monthStart.isBefore(capturedTo)) {
                            continue; // 窗口外的月份在 SQL 里根本不会被取回
                        }
                        String income = entry.getValue()[0];
                        String expense = entry.getValue()[1];
                        if (income != null) {
                            rows.add(new Object[] { month.getYear(), month.getMonthValue(), "income",
                                    new BigDecimal(income) });
                        }
                        if (expense != null) {
                            rows.add(new Object[] { month.getYear(), month.getMonthValue(), "expense",
                                    new BigDecimal(expense) });
                        }
                    }
                    return rows;
                });
    }

    // ---- 收入下限（需求 4.3、4.4） ----

    /** 查询结果为空：三个回看月的两项合计均按 0.00 计，收入 0.00 &lt; 0.01，无储蓄月（需求 4.4）。 */
    @Test
    void emptyQueryResultYieldsNoSavingMonths() {
        assertThat(savingMonths()).isEmpty();
    }

    /** 收入合计恰好 0.00（有支出、无收入）：不是储蓄月（需求 4.4）。 */
    @Test
    void zeroIncomeIsNotSavingMonth() {
        setAmounts(M1, "0.00", "0.00");
        setAmounts(M2, null, "500.00");

        assertThat(savingMonths()).isEmpty();
    }

    /**
     * 收入合计恰好 0.01：越过收入下限（需求 4.4 取等号即通过），门槛为
     * {@code 0.01 × 0.2 = 0.002 → 0.00}，结余 0.01 ≥ 0.00，故为储蓄月。
     */
    @Test
    void incomeExactlyOneCentIsSavingMonth() {
        setAmounts(M1, "0.01", null);

        assertThat(savingMonths()).containsExactly(M1);
    }

    /** 收入 0.01 且支出 0.01：结余 0.00，门槛 0.00，取等号即成立（需求 4.3）。 */
    @Test
    void incomeOneCentFullySpentIsStillSavingMonthBecauseThresholdIsZero() {
        setAmounts(M1, "0.01", "0.01");

        assertThat(savingMonths()).containsExactly(M1);
    }

    /** 收入 0.01 且支出 0.02：结余 −0.01 为负，小于门槛 0.00，不是储蓄月（需求 4.5）。 */
    @Test
    void negativeBalanceIsNotSavingMonth() {
        setAmounts(M1, "0.01", "0.02");
        setAmounts(M2, "100.00", "500.00"); // 结余 −400.00

        assertThat(savingMonths()).isEmpty();
    }

    /**
     * 收入原始合计 {@code 0.009}：按需求 4.8「月度收入合计保留 2 位小数」四舍五入后为 {@code 0.01}，
     * 因此越过收入下限、判为储蓄月（门槛 0.00）。
     *
     * <p>这里断言的是实现在需求 4.8 与 4.4 交汇处的既有取舍：{@code transactions.amount} 是
     * {@code DECIMAL(18,2)}，{@code SUM} 不可能带第 3 位小数，故该输入在生产库上不可达，本用例只钉住
     * 「先按 2 位小数归一，再与 0.01 比较」这个顺序。低于半分位的原始取值由
     * {@link #subCentIncomeRoundingDownStaysBelowTheIncomeFloor} 覆盖。</p>
     */
    @Test
    void subCentIncomeRoundingUpReachesTheIncomeFloor() {
        setAmounts(M1, "0.009", null);

        assertThat(savingMonths()).containsExactly(M1);
    }

    /** 收入原始合计 {@code 0.004}：归一为 {@code 0.00} &lt; {@code 0.01}，不是储蓄月（需求 4.4、4.8）。 */
    @Test
    void subCentIncomeRoundingDownStaysBelowTheIncomeFloor() {
        setAmounts(M1, "0.004", null);

        assertThat(savingMonths()).isEmpty();
    }

    // ---- 门槛比较与等号边界（需求 4.3、4.5、4.8） ----

    /** 结余恰好等于门槛：判为储蓄月（需求 4.3 的等号边界）。 */
    @Test
    void balanceExactlyEqualToThresholdIsSavingMonth() {
        setAmounts(M1, "1000.00", "800.00"); // 结余 200.00 == 门槛 200.00

        assertThat(savingMonths()).containsExactly(M1);
    }

    /** 结余比门槛低 1 分：不是储蓄月（需求 4.5）。 */
    @Test
    void balanceOneCentBelowThresholdIsNotSavingMonth() {
        setAmounts(M1, "1000.00", "800.01"); // 结余 199.99 < 门槛 200.00

        assertThat(savingMonths()).isEmpty();
    }

    /** 结余比门槛高 1 分：是储蓄月。 */
    @Test
    void balanceOneCentAboveThresholdIsSavingMonth() {
        setAmounts(M1, "1000.00", "799.99"); // 结余 200.01 > 门槛 200.00

        assertThat(savingMonths()).containsExactly(M1);
    }

    // ---- 门槛的舍入（需求 4.8） ----

    /**
     * 收入 {@code 333.33} → 门槛 {@code 66.67}（{@code 66.666} 对第 3 位小数四舍五入进位）。
     *
     * <p>结余 {@code 66.67} 成立、{@code 66.66} 不成立，把门槛钉在 {@code 66.67} 这一个取值上。</p>
     */
    @Test
    void thresholdOfIncome333_33IsSixtySixPointSixSeven() {
        setAmounts(M1, "333.33", "266.66"); // 结余 66.67 == 门槛 66.67
        assertThat(savingMonths()).containsExactly(M1);

        setUp();
        setAmounts(M1, "333.33", "266.67"); // 结余 66.66 < 门槛 66.67
        assertThat(savingMonths()).isEmpty();
    }

    /**
     * 收入 {@code 333.32} → 门槛 {@code 66.66}（{@code 66.664} 舍去），结余恰好 {@code 66.66} 成立。
     *
     * <p>这是「门槛必须先舍入再比较」的<b>判别性</b>用例：若把 {@code 收入 × 0.2} 内联进比较、
     * 用未舍入的 {@code 66.664} 作门槛，本用例的 {@code 66.66} 就会被判为不成立。</p>
     */
    @Test
    void thresholdIsRoundedBeforeComparisonNotInlinedUnrounded() {
        setAmounts(M1, "333.32", "266.66"); // 结余 66.66 == 门槛 66.66（未舍入则为 66.664，会误判）

        assertThat(savingMonths()).containsExactly(M1);
    }

    /** 收入 {@code 0.05} → 门槛 {@code 0.01}（{@code 0.010} 保留 2 位）：结余 0.01 成立、0.00 不成立。 */
    @Test
    void thresholdOfIncome0_05IsOneCent() {
        setAmounts(M1, "0.05", "0.04"); // 结余 0.01 == 门槛 0.01
        assertThat(savingMonths()).containsExactly(M1);

        setUp();
        setAmounts(M1, "0.05", "0.05"); // 结余 0.00 < 门槛 0.01
        assertThat(savingMonths()).isEmpty();
    }

    // ---- 回看窗口（需求 4.1、4.6、4.10、4.11） ----

    /** 三个回看月都达成：按月份升序返回（最远 → 最近）。 */
    @Test
    void allThreeLookbackMonthsAreReturnedInAscendingOrder() {
        setAmounts(M1, "1000.00", "500.00");
        setAmounts(M2, "1000.00", "800.00");
        setAmounts(M3, "1000.00", "0.00");

        assertThat(savingMonths()).containsExactly(M3, M2, M1);
    }

    /** 结算日所属月即使达成也不判定（未结束的自然月不参与，需求 4.1）。 */
    @Test
    void settlementMonthItselfIsNeverJudged() {
        setAmounts(SETTLE_MONTH, "1000.00", "0.00");

        assertThat(savingMonths()).isEmpty();
        // 右边界即结算月 1 日 00:00:00.000，故结算月整月落在窗口之外。
        assertThat(capturedTo).isEqualTo(LocalDateTime.of(2025, 6, 1, 0, 0, 0, 0));
    }

    /**
     * 结算日为 3 月 1 日 {@code 00:00:00.000}：窗口右边界恰为 {@code 2025-03-01T00:00}，
     * 3 月不被判定，回看的是 2024-12 / 2025-01 / 2025-02（需求 4.1、4.6）。
     */
    @Test
    void settlementOnFirstDayOfMarchDoesNotJudgeMarch() {
        setAmounts("2025-03", "1000.00", "0.00"); // 3 月达成，但不参与判定
        setAmounts("2025-02", "1000.00", "500.00");
        setAmounts("2025-01", "1000.00", "500.00");
        setAmounts("2024-12", "1000.00", "500.00");

        List<String> months = evaluator.savingMonths(USER, LocalDate.of(2025, 3, 1), Set.of());

        assertThat(months).containsExactly("2024-12", "2025-01", "2025-02");
        assertThat(months).doesNotContain("2025-03");
        assertThat(capturedFrom).isEqualTo(LocalDateTime.of(2024, 12, 1, 0, 0, 0, 0));
        assertThat(capturedTo).isEqualTo(LocalDateTime.of(2025, 3, 1, 0, 0, 0, 0));
    }

    /** 结算日在 1 月：回看上一年的 10 / 11 / 12 月（跨年由 minusMonths 处理，需求 4.1）。 */
    @Test
    void januarySettlementLooksBackAtPriorYearOctoberNovemberDecember() {
        setAmounts("2025-12", "1000.00", "500.00");
        setAmounts("2025-11", "1000.00", "800.00");
        setAmounts("2025-10", "1000.00", "0.00");
        setAmounts("2026-01", "1000.00", "0.00"); // 结算月，不参与
        setAmounts("2025-09", "1000.00", "0.00"); // 第 4 个更早的月，窗口外

        List<String> months = evaluator.savingMonths(USER, LocalDate.of(2026, 1, 10), Set.of());

        assertThat(months).containsExactly("2025-10", "2025-11", "2025-12");
        assertThat(capturedFrom).isEqualTo(LocalDateTime.of(2025, 10, 1, 0, 0, 0, 0));
        assertThat(capturedTo).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 0, 0));
    }

    /** 第 4 个更早的月即使达成也不返回（回看窗口固定 3 个已结束自然月，需求 4.10）。 */
    @Test
    void fourthMonthOutsideWindowIsNotReturned() {
        setAmounts(M1, "1000.00", "500.00");
        setAmounts(M4_TOO_OLD, "1000.00", "0.00");

        List<String> months = savingMonths();

        assertThat(months).containsExactly(M1);
        assertThat(months).doesNotContain(M4_TOO_OLD);
    }

    /** 窗口边界是「最早回看月 1 日 00:00:00.000」到「结算月 1 日 00:00:00.000」的半开区间（需求 4.6）。 */
    @Test
    void windowBoundsAreMonthStartsAndRightOpen() {
        savingMonths();

        assertThat(capturedFrom).isEqualTo(LocalDateTime.of(2025, 3, 1, 0, 0, 0, 0));
        assertThat(capturedTo).isEqualTo(LocalDateTime.of(2025, 6, 1, 0, 0, 0, 0));
        assertThat(capturedFrom.getNano()).isZero();
        assertThat(capturedTo.getNano()).isZero();
    }

    /** 读查询恒为 1 条：3 个回看月一次取回，不按月循环（需求 4.11）。 */
    @Test
    void readQueryCountIsAlwaysOne() {
        setAmounts(M1, "1000.00", "500.00");
        setAmounts(M2, "1000.00", "500.00");
        setAmounts(M3, "1000.00", "500.00");

        savingMonths();

        assertThat(readQueryCount.get()).isEqualTo(1);
    }

    // ---- 幂等：已有事件的月份跳过（需求 4.19、4.11） ----

    /** {@code existingKeys} 已含某月时该月不再返回，其余月照常判定。 */
    @Test
    void monthAlreadyInExistingKeysIsNotReturnedAgain() {
        setAmounts(M1, "1000.00", "500.00");
        setAmounts(M2, "1000.00", "500.00");
        setAmounts(M3, "1000.00", "500.00");

        List<String> months = evaluator.savingMonths(USER, SETTLE_DATE, Set.of("SAVING_MONTH:" + M2));

        assertThat(months).containsExactly(M3, M1);
        assertThat(months).doesNotContain(M2);
    }

    /** 三个月全部已有事件：返回空，且仍不新增任何查询（存在性判定只用传入的键集合，需求 4.11）。 */
    @Test
    void allMonthsAlreadyPresentYieldsEmptyResult() {
        setAmounts(M1, "1000.00", "500.00");
        setAmounts(M2, "1000.00", "500.00");
        setAmounts(M3, "1000.00", "500.00");

        List<String> months = evaluator.savingMonths(USER, SETTLE_DATE,
                Set.of("SAVING_MONTH:" + M1, "SAVING_MONTH:" + M2, "SAVING_MONTH:" + M3));

        assertThat(months).isEmpty();
        assertThat(readQueryCount.get()).isEqualTo(1);
    }

    /** 其它前缀的同月事件键不构成跳过依据（命名空间隔离）。 */
    @Test
    void otherPrefixKeysDoNotSuppressSavingMonth() {
        setAmounts(M1, "1000.00", "500.00");

        assertThat(evaluator.savingMonths(USER, SETTLE_DATE, Set.of("BUDGET_MET:" + M1)))
                .containsExactly(M1);
    }

    /** {@code existingKeys} 为 {@code null} 按空集处理，不抛错。 */
    @Test
    void nullExistingKeysIsTreatedAsEmptySet() {
        setAmounts(M1, "1000.00", "500.00");

        assertThat(evaluator.savingMonths(USER, SETTLE_DATE, null)).containsExactly(M1);
    }

    // ---- event_key 形态（需求 4.2） ----

    /** 返回的月份键恒为 {@code YYYY-MM}，拼上前缀后长度恒为 20 个字符。 */
    @Test
    void eventKeyIsPrefixPlusMonthAndAlwaysTwentyCharsLong() {
        // 覆盖 1 位月份（需左侧补零）、2 位月份与跨年三种情形。
        List<LocalDate> settleDates = List.of(
                LocalDate.of(2025, 6, 15), // 回看 2025-03/04/05
                LocalDate.of(2026, 1, 31), // 回看 2025-10/11/12（跨年）
                LocalDate.of(2025, 4, 1)); // 回看 2025-01/02/03（月份需补零）

        for (LocalDate settleDate : settleDates) {
            setUp();
            YearMonth settleMonth = YearMonth.from(settleDate);
            for (int back = 1; back <= 3; back++) {
                setAmounts(settleMonth.minusMonths(back).toString(), "1000.00", "500.00");
            }

            List<String> months = evaluator.savingMonths(USER, settleDate, Set.of());

            assertThat(months).hasSize(3);
            for (String month : months) {
                String eventKey = "SAVING_MONTH:" + month;
                assertThat(eventKey).matches("SAVING_MONTH:\\d{4}-\\d{2}");
                assertThat(eventKey.codePointCount(0, eventKey.length())).isEqualTo(20);
                assertThat(eventKey).hasSize(20);
            }
        }
    }

    // ---- 辅助 ----

    private List<String> savingMonths() {
        return evaluator.savingMonths(USER, SETTLE_DATE, Collections.<String>emptySet());
    }

    /** 设定某月的收入 / 支出合计；传 {@code null} 表示该类型在查询结果里缺行。 */
    private void setAmounts(String monthKey, String income, String expense) {
        amountsByMonth.put(monthKey, new String[] { income, expense });
    }
}
