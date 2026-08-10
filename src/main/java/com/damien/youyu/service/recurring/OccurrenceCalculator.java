package com.damien.youyu.service.recurring;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.damien.youyu.domain.EndCondition;

/**
 * 期次计算<strong>纯函数</strong>组件：给定一条 {@link RuleSpec} 与「今天」，算出其在时间轴上的期次
 * 到期日序列。无副作用、不读时钟、不查库、不触碰任何外部状态——同一输入恒得同一输出，可被属性测试完整覆盖
 * （见 design.md「期次计算算法（OccurrenceCalculator，纯函数）」）。
 *
 * <h2>时区口径</h2>
 * <p>全部以 {@code Asia/Shanghai}（UTC+08:00）自然日为单位；调用方以 {@code LocalDate.now(clock)}
 * （{@code clock} 为 {@code Asia/Shanghai}）传入「今天」。本类只对 {@link LocalDate} 做自然日运算，
 * 不涉及时刻与时区转换。</p>
 *
 * <h2>月末 / 平闰年边界</h2>
 * <p>MONTHLY 指定日 D 与 YEARLY 目标日在某月不存在时，统一落该月最后一日：目标日取
 * {@code min(D, YearMonth.lengthOfMonth())}；「月末」标记等价于 D=31（恒取当月最后一日）。
 * 平 / 闰年由 {@link YearMonth#lengthOfMonth()} 天然处理，不手写闰年判断（需求 2.4、2.5、2.6）。</p>
 *
 * <h2>统一开始 / 结束边界</h2>
 * <ul>
 *   <li>不生成到期日早于 {@link RuleSpec#startDate()} 的期次（需求 2.11、3.6）。</li>
 *   <li>{@link EndCondition#UNTIL_DATE}：生成到期日 ≤ {@code untilDate} 的期次（含端点，需求 2.8）。</li>
 *   <li>{@link EndCondition#COUNT}：按到期日升序累计至 {@code countN} 后不再生成；计数<strong>不区分</strong>
 *       待确认项状态（需求 2.9）。</li>
 * </ul>
 *
 * <p>Feature: recurring-transactions。</p>
 */
public class OccurrenceCalculator {

    /**
     * 返回该规则「到期日 ≤ {@code today} 且满足开始 / 结束条件」的全部期次到期日，按到期日<strong>升序、去重</strong>。
     *
     * <p>结果天然去重（同一频率下各候选日互不相同）且升序（迭代按时间轴推进）。因「≤ today」上界收敛，
     * 序列必然有限而终止：DAILY 至多 {@code today − startDate + 1} 项，MONTHLY / YEARLY 按月 / 年推进，
     * COUNT 至多 {@code countN} 项。</p>
     *
     * @param rule  频率配置与边界（不可为 {@code null}）
     * @param today 「今天」的自然日（{@code Asia/Shanghai} 口径，不可为 {@code null}）
     * @return 升序去重的期次到期日列表；无期次时返回空列表（不返回 {@code null}）
     */
    public List<LocalDate> occurrencesUpTo(RuleSpec rule, LocalDate today) {
        if (rule == null) {
            throw new IllegalArgumentException("rule must not be null");
        }
        if (today == null) {
            throw new IllegalArgumentException("today must not be null");
        }

        // 生成上界 = min(today, untilDate)（UNTIL_DATE 且结束日早于今天时以结束日为界）。
        LocalDate hardEnd = today;
        if (rule.endCondition() == EndCondition.UNTIL_DATE) {
            LocalDate until = rule.untilDate();
            if (until == null) {
                // UNTIL_DATE 缺结束日属非法配置（服务层已拒绝）；防御性返回空序列。
                return new ArrayList<>();
            }
            if (until.isBefore(hardEnd)) {
                hardEnd = until;
            }
        }

        Integer maxCount = rule.endCondition() == EndCondition.COUNT ? rule.countN() : null;
        if (maxCount != null && maxCount <= 0) {
            return new ArrayList<>();
        }

        List<LocalDate> result = new ArrayList<>();
        switch (rule.frequency()) {
            case DAILY -> collectDaily(rule, hardEnd, maxCount, result);
            case WEEKLY -> collectWeekly(rule, hardEnd, maxCount, result);
            case MONTHLY -> collectMonthly(rule, hardEnd, maxCount, result);
            case YEARLY -> collectYearly(rule, hardEnd, maxCount, result);
        }
        return result;
    }

    /**
     * 判定某到期日是否「已到期且属于该规则期次序列」：{@code occurrenceDate ≤ today} 且
     * {@code occurrenceDate} 是该规则（满足开始 / 结束与频率模式、且在 COUNT 前 N 期内）的一个期次到期日。
     *
     * @param rule           频率配置与边界（不可为 {@code null}）
     * @param occurrenceDate 待判定的到期日（不可为 {@code null}）
     * @param today          「今天」的自然日（不可为 {@code null}）
     * @return 已到期且属于序列时为 {@code true}
     */
    public boolean isDue(RuleSpec rule, LocalDate occurrenceDate, LocalDate today) {
        if (rule == null) {
            throw new IllegalArgumentException("rule must not be null");
        }
        if (occurrenceDate == null) {
            throw new IllegalArgumentException("occurrenceDate must not be null");
        }
        if (today == null) {
            throw new IllegalArgumentException("today must not be null");
        }
        if (occurrenceDate.isAfter(today)) {
            return false;
        }
        // 以 occurrenceDate 为上界收敛生成序列（同样受 COUNT / UNTIL_DATE / start 约束），
        // occurrenceDate 属于序列当且仅当它出现在该序列中——此时它必为序列的末项。
        List<LocalDate> upTo = occurrencesUpTo(rule, occurrenceDate);
        return !upTo.isEmpty() && upTo.get(upTo.size() - 1).equals(occurrenceDate);
    }

    // ---- 各频率的期次收集（均升序推进，受 startDate 下界、hardEnd 上界、maxCount 计数三重约束）----

    private void collectDaily(RuleSpec rule, LocalDate hardEnd, Integer maxCount, List<LocalDate> result) {
        LocalDate d = rule.startDate();
        while (!d.isAfter(hardEnd)) {
            if (reachedCount(maxCount, result)) {
                return;
            }
            result.add(d);
            d = d.plusDays(1);
        }
    }

    private void collectWeekly(RuleSpec rule, LocalDate hardEnd, Integer maxCount, List<LocalDate> result) {
        if (rule.weeklyDays().isEmpty()) {
            // 空集合为非法配置（服务层已拒绝）；无匹配星期几 → 空序列。
            return;
        }
        LocalDate d = rule.startDate();
        while (!d.isAfter(hardEnd)) {
            if (reachedCount(maxCount, result)) {
                return;
            }
            // DayOfWeek.getValue()：1=周一 .. 7=周日，与 weekly_days 口径一致。
            if (rule.weeklyDays().contains(d.getDayOfWeek().getValue())) {
                result.add(d);
            }
            d = d.plusDays(1);
        }
    }

    private void collectMonthly(RuleSpec rule, LocalDate hardEnd, Integer maxCount, List<LocalDate> result) {
        YearMonth ym = YearMonth.from(rule.startDate());
        while (true) {
            if (reachedCount(maxCount, result)) {
                return;
            }
            LocalDate d = monthlyOccurrence(ym, rule);
            if (d.isAfter(hardEnd)) {
                return;
            }
            // 开始日期所在月：仅当第 D 日（钳制后）不早于开始日期才生成该月期次（需求 2.3）。
            if (!d.isBefore(rule.startDate())) {
                result.add(d);
            }
            ym = ym.plusMonths(1);
        }
    }

    private void collectYearly(RuleSpec rule, LocalDate hardEnd, Integer maxCount, List<LocalDate> result) {
        int year = rule.startDate().getYear();
        while (true) {
            if (reachedCount(maxCount, result)) {
                return;
            }
            LocalDate d = yearlyOccurrence(year, rule);
            if (d.isAfter(hardEnd)) {
                return;
            }
            if (!d.isBefore(rule.startDate())) {
                result.add(d);
            }
            year++;
        }
    }

    /** 某自然月的到期日：{@code month_end} 取当月最后一日，否则取 {@code min(monthDay, 当月天数)}（需求 2.4、2.5）。 */
    private LocalDate monthlyOccurrence(YearMonth ym, RuleSpec rule) {
        int lengthOfMonth = ym.lengthOfMonth();
        int day = rule.monthEnd() ? lengthOfMonth : Math.min(rule.monthDay(), lengthOfMonth);
        return ym.atDay(day);
    }

    /** 某年的到期日：在 {@code year_month} 月取 {@code min(yearDay, 当月天数)}（目标日不存在则落当月最后一日，需求 2.6）。 */
    private LocalDate yearlyOccurrence(int year, RuleSpec rule) {
        YearMonth ym = YearMonth.of(year, rule.yearMonth());
        int day = Math.min(rule.yearDay(), ym.lengthOfMonth());
        return ym.atDay(day);
    }

    /** COUNT 计数达上界判定：已累计 {@code result.size()} 期是否达到 {@code maxCount}（{@code null} 表示不限）。 */
    private boolean reachedCount(Integer maxCount, List<LocalDate> result) {
        return maxCount != null && result.size() >= maxCount;
    }
}
