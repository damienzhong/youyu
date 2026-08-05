package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.damien.youyu.repository.TransactionRepository;

/**
 * 储蓄月判定：给定用户与结算日，返回应发放 {@code SAVING_MONTH} 的自然月集合（需求 4）。
 *
 * <h2>与 {@link GrowthBudgetEvaluator} 同构</h2>
 *
 * <p>同样 {@code LOOKBACK_MONTHS = 3}、同样只判已结束自然月、同样接收 {@code existingKeys} 做跳过判定，
 * 因此存在性判定<b>零新增查询</b>（需求 4.11）。差别只有两处：本类的读查询恒为 <b>1 条</b>
 * （一条分组查询覆盖 3 个回看月 × 2 个交易类型，见
 * {@link TransactionRepository#sumMonthlyAmountsByCreatedByGroupByMonthAndType}），
 * 且返回的月份按<b>升序</b>排列——结算的第 ④ 步按此顺序写入事件，使同批 {@code SAVING_MONTH} 事件的
 * {@code id} 序与月份序一致。</p>
 *
 * <h2>回看窗口与月边界（需求 4.1、4.6、4.10）</h2>
 *
 * <p>回看窗口 = {@code settleDate.withDayOfMonth(1)} 往前 1 / 2 / 3 个月，共 3 个已结束自然月，
 * <b>不判定结算日所属的自然月</b>（未结束的自然月不参与）。跨年由 {@link LocalDate#minusMonths} 天然处理
 * （1 月 → 上一年的 10 / 11 / 12 月），窗口长度与该用户注册时刻、本次结算的触发来源无关。</p>
 *
 * <p>月归属用 {@code occurred_at ∈ [该月 1 日 00:00:00.000, 次月 1 日 00:00:00.000)}：
 * {@code fromInclusive} 与 {@code toExclusive} 都由 {@code atStartOfDay()} 取得，区间半开，
 * 因此<b>恰好落在右边界的交易归次月</b>。<b>不用 {@code created_at}</b>——那是记账日历的口径，
 * 「这笔钱花在哪个月」与「哪天来记账」刻意不同（需求 4.6 对比需求 3.5）。</p>
 *
 * <h2>归属与排除（需求 4.7）</h2>
 *
 * <p>归属只认 {@code transactions.created_by}，跨该用户记账的全部账本合并计算；
 * {@code deleted_at} 非空的行、{@code ledger_id} 为 NULL 的行与 {@code type} 为 {@code transfer} 的行
 * 三类排除在两项合计之外。三条排除都写在 SQL 的 {@code WHERE} 里，本类不再二次过滤。</p>
 *
 * <h2>判定与舍入（需求 4.3、4.4、4.5、4.8）</h2>
 *
 * <pre>
 * 月度收入合计 = SUM(income)，查询无结果按 0.00
 * 月度支出合计 = SUM(expense)，查询无结果按 0.00
 * 月度结余     = 收入 − 支出                        （可为负）
 * 储蓄门槛值   = (收入 × 0.2).setScale(2, HALF_UP)   （第 3 位小数四舍五入）
 * 是储蓄月     ⟺ 收入 ≥ 0.01 且 结余 ≥ 储蓄门槛值   （取等号即成立）
 * </pre>
 *
 * <p>全程 {@link BigDecimal} + {@link BigDecimal#compareTo}，<b>不使用 {@code double} / {@code float}</b>。
 * 查询无结果的月份两项合计均按 {@code 0.00} 计，收入 {@code 0.00 < 0.01}，故判为不是储蓄月。</p>
 *
 * <h2>幂等与无负向标记（需求 4.19、4.20）</h2>
 *
 * <p>{@code event_key = "SAVING_MONTH:" + YYYY-MM}，长度恒为 20 字符（前缀 13 + {@code YYYY-MM} 7）。
 * 已由 {@code existingKeys} 命中的月份直接跳过。判定不成立时不返回该月、<b>不写任何负向标记或缓存行</b>，
 * 继续判定其余回看月——本类不做任何写入，写入一律由 {@code GrowthSettlementService} 的第 ④ 步统一完成。</p>
 */
@Component
public class GrowthSavingMonthEvaluator {

    /** 回看结算日所属月的前 1/2/3 个自然月，固定 3 个已结束自然月（需求 4.1、4.10）。 */
    static final int LOOKBACK_MONTHS = 3;

    /** {@code SAVING_MONTH} 事件键前缀（需求 4.2）。 */
    private static final String SAVING_MONTH_PREFIX = "SAVING_MONTH:";

    /** 收入下限：低于此值视为「无收入」，不算存钱（需求 4.4）。 */
    private static final BigDecimal MIN_INCOME = new BigDecimal("0.01");

    /** 储蓄率 20%（需求 4.8）。 */
    private static final BigDecimal SAVING_RATE = new BigDecimal("0.2");

    /** 金额保留 2 位小数（需求 4.8）。 */
    private static final int SCALE = 2;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    /** 交易类型字面量，与 {@link TransactionRepository} 原生查询里的取值逐字一致（需求 4.7）。 */
    private static final String TYPE_EXPENSE = "expense";
    private static final String TYPE_INCOME = "income";

    private final TransactionRepository transactionRepository;

    public GrowthSavingMonthEvaluator(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * 回看结算日所属月的前 1/2/3 个自然月，返回应发放 {@code SAVING_MONTH} 的月份（{@code YYYY-MM}）。
     *
     * @param userId       令牌所标识的用户 id
     * @param settleDate   结算日；其所属自然月不参与判定（需求 4.1）
     * @param existingKeys 该用户已有的事件键，用于跳过已发放的月份（不额外查库，需求 4.11）；
     *                     {@code null} 按空集处理
     * @return 判定为储蓄月且尚无事件的月份键（{@code YYYY-MM}）列表，<b>按月份升序</b>，可能为空
     */
    public List<String> savingMonths(Long userId, LocalDate settleDate, Set<String> existingKeys) {
        Set<String> existing = (existingKeys == null) ? Set.of() : existingKeys;

        // 回看窗口：结算日所属月 1 日往前 1/2/3 个月。跨年由 minusMonths 天然处理（需求 4.1）。
        LocalDate firstDayOfSettleMonth = settleDate.withDayOfMonth(1);
        LocalDate firstDayOfWindow = firstDayOfSettleMonth.minusMonths(LOOKBACK_MONTHS);

        // 半开区间 [最早回看月 1 日 00:00:00.000, 结算月 1 日 00:00:00.000)：
        // 右开使恰好落在边界的交易归次月，同时把结算日所属月整月排除在窗口外（需求 4.6）。
        LocalDateTime fromInclusive = firstDayOfWindow.atStartOfDay();
        LocalDateTime toExclusive = firstDayOfSettleMonth.atStartOfDay();

        // 唯一的一条读查询：3 个回看月 × 2 个类型一次读完，条数为常量、不随账本/分类/交易量增长（需求 4.11）。
        Map<String, MonthlyAmounts> amountsByMonth = loadAmountsByMonth(userId, fromInclusive, toExclusive);

        List<String> result = new ArrayList<>(LOOKBACK_MONTHS);
        YearMonth settleMonth = YearMonth.from(settleDate);
        // 升序遍历：back 从 3 到 1 即最早回看月 → 最近回看月。
        for (int back = LOOKBACK_MONTHS; back >= 1; back--) {
            YearMonth month = settleMonth.minusMonths(back);
            String monthKey = month.toString(); // YearMonth.toString() 恒为 YYYY-MM（需求 4.2）

            // 已发放的月份直接跳过；存在性判定只用传入的事件键集合，零新增查询（需求 4.11）。
            if (existing.contains(SAVING_MONTH_PREFIX + monthKey)) {
                continue;
            }

            // 查询里没有该月的行 ⇒ 两项合计均按 0.00 计，收入 0.00 < 0.01，判为不是储蓄月（需求 4.4）。
            MonthlyAmounts amounts = amountsByMonth.getOrDefault(monthKey, MonthlyAmounts.EMPTY);
            if (isSavingMonth(amounts)) {
                result.add(monthKey);
            }
            // 判定不成立：不返回该月、不写任何负向标记或缓存行，继续判定其余回看月（需求 4.20）。
        }
        return result;
    }

    /**
     * 判定某已结束自然月是否为储蓄月：收入 ≥ {@code 0.01} 且 结余 ≥ 储蓄门槛值（取等号即成立，需求 4.3）。
     *
     * <p><b>「储蓄门槛值」刻意作为具名中间量出现，而不是内联进比较表达式</b>：门槛必须是
     * 「收入 × 0.2 对第 3 位小数四舍五入保留 2 位」之后的取值。收入 {@code 333.33} 时门槛是
     * {@code 66.67}<b>而非 {@code 66.666}</b>；若把乘法内联进 {@code compareTo}，边界上（结余恰好
     * {@code 66.67}）的结论会随实现者是否舍入而不同，两名测试者就会得出不同结论。具名中间量把这条舍入
     * 规则钉在一行代码上，测试可以直接对它取值断言。</p>
     *
     * <p>全程 {@link BigDecimal} + {@link BigDecimal#compareTo}：{@code compareTo} 忽略标度差异
     * （{@code 0.10} 与 {@code 0.1} 相等），而 {@code equals} 不忽略，故比较一律用 {@code compareTo}；
     * 不使用 {@code double} / {@code float}（需求 4.8）。</p>
     */
    private boolean isSavingMonth(MonthlyAmounts amounts) {
        BigDecimal income = amounts.income();
        // 无收入不算存钱（需求 4.4）。
        if (income.compareTo(MIN_INCOME) < 0) {
            return false;
        }
        // 结余可为负；负结余必然小于非负门槛，由下面同一条比较排除（需求 4.5）。
        BigDecimal balance = income.subtract(amounts.expense()).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal savingThreshold = income.multiply(SAVING_RATE).setScale(SCALE, RoundingMode.HALF_UP);
        return balance.compareTo(savingThreshold) >= 0;
    }

    /**
     * 把 {@code [year, month, type, sum]} 分组结果收敛为 {@code YYYY-MM -> (收入, 支出)} 的映射。
     *
     * <p>原生查询按「年 + 月 + 类型」分组，某月某类型没有有效记账交易时该行不出现，故读取端一律以
     * {@code 0.00} 兜底（需求 4.4）。金额用 {@code new BigDecimal(row[3].toString())} 构造而
     * <b>不经 {@code doubleValue()}</b>：JDBC 在 MySQL 与 H2 上对 {@code SUM(DECIMAL)} 的返回类型不同
     * （{@code BigDecimal} / {@code BigInteger} / {@code Long}），走字符串是唯一不引入浮点的构造路径。</p>
     */
    private Map<String, MonthlyAmounts> loadAmountsByMonth(
            Long userId, LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        List<Object[]> rows = transactionRepository
                .sumMonthlyAmountsByCreatedByGroupByMonthAndType(userId, fromInclusive, toExclusive);
        Map<String, MonthlyAmounts> amountsByMonth = new HashMap<>();
        for (Object[] row : rows) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            String type = (row[2] == null) ? "" : row[2].toString();
            BigDecimal sum = (row[3] == null)
                    ? ZERO
                    : new BigDecimal(row[3].toString()).setScale(SCALE, RoundingMode.HALF_UP);

            String monthKey = YearMonth.of(year, month).toString();
            MonthlyAmounts current = amountsByMonth.getOrDefault(monthKey, MonthlyAmounts.EMPTY);
            if (TYPE_INCOME.equals(type)) {
                amountsByMonth.put(monthKey, new MonthlyAmounts(sum, current.expense()));
            } else if (TYPE_EXPENSE.equals(type)) {
                amountsByMonth.put(monthKey, new MonthlyAmounts(current.income(), sum));
            }
            // 其余 type（如 transfer）已由 SQL 的 type IN ('expense','income') 排除，此处不做兜底累加。
        }
        return amountsByMonth;
    }

    /**
     * 某自然月的月度收入合计与月度支出合计，两项均为保留 2 位小数的 {@link BigDecimal}（需求 4.8）。
     *
     * <p>{@link #EMPTY} 表示该月没有任何有效记账交易，两项按 {@code 0.00} 计（需求 4.4）。</p>
     */
    private record MonthlyAmounts(BigDecimal income, BigDecimal expense) {
        static final MonthlyAmounts EMPTY = new MonthlyAmounts(ZERO, ZERO);
    }
}
