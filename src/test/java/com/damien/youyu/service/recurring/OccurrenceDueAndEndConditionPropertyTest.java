package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.damien.youyu.domain.EndCondition;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Feature: recurring-transactions, Property 2: 已到期判定与结束条件边界
 *
 * <p>{@link OccurrenceCalculator} 的属性测试，覆盖 design.md「Correctness Properties」Property 2：
 * <ul>
 *   <li><strong>已到期判定</strong>：对任意规则与任意「今天」，{@code isDue(rule, d, today)} 为真当且仅当
 *       到期日 {@code d ≤ today}（{@code Asia/Shanghai} 口径）<em>且</em> {@code d} 属于该规则的期次序列。</li>
 *   <li><strong>UNTIL_DATE 上界含端点</strong>：结束条件为 {@link EndCondition#UNTIL_DATE} 时，全部生成期次
 *       到期日 ≤ 结束日期、无一晚于结束日期（等于结束日期仍生成）。</li>
 *   <li><strong>COUNT 上界</strong>：结束条件为 {@link EndCondition#COUNT} 时，按到期日升序生成的期次总数
 *       不超过 N（纯算法层无状态，仅断言计数上界与「升序前缀」语义）。</li>
 * </ul>
 *
 * <p>纯函数、无 Spring / DB 依赖；每个 {@code @Property} ≥ 100 次迭代。</p>
 *
 * <p><strong>Validates: Requirements 2.7, 2.8, 2.9, 3.6</strong></p>
 */
class OccurrenceDueAndEndConditionPropertyTest {

    private final OccurrenceCalculator calculator = new OccurrenceCalculator();

    // ---------------------------------------------------------------------
    // Property 2 - facet A：已到期判定（isDue ⇔ 到期日 ≤ today 且属于期次序列）（需求 2.7）
    // ---------------------------------------------------------------------
    @Property(tries = 100)
    void isDue_trueIff_onOrBeforeToday_andBelongsToSequence(
            @ForAll("baseRules") RuleSpec base,
            @ForAll @IntRange(min = -30, max = 400) int todayGap,
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = -420, max = 60) Integer> probeOffsets) {

        LocalDate today = base.startDate().plusDays(todayGap);

        // 全部「到期日 ≤ today 且在序列内」的期次（NEVER 规则，序列即频率全序列）。
        List<LocalDate> due = calculator.occurrencesUpTo(base, today);

        // 序列升序、去重（同时为 Property 2 前置口径）。
        assertAscendingDistinct(due);

        // (1) 每个已到期期次都被判为 due。
        for (LocalDate d : due) {
            assertThat(calculator.isDue(base, d, today))
                    .as("occurrence %s should be due when today=%s", d, today)
                    .isTrue();
        }

        // (2) 任意探针日 c：isDue ⇔ (c ≤ today) 且 (c 属于序列)。
        //     due 恰为「≤ today 的全部序列成员」，故对 c ≤ today，属于序列 ⇔ due.contains(c)；
        //     对 c > today，isDue 必为 false。
        for (int offset : probeOffsets) {
            LocalDate c = today.plusDays(offset);
            boolean expected = !c.isAfter(today) && due.contains(c);
            assertThat(calculator.isDue(base, c, today))
                    .as("isDue(%s, today=%s) expected=%s", c, today, expected)
                    .isEqualTo(expected);
        }
    }

    // ---------------------------------------------------------------------
    // Property 2 - facet B：UNTIL_DATE 含端点上界，无一晚于结束日期（需求 2.8）
    // ---------------------------------------------------------------------
    @Property(tries = 100)
    void untilDate_inclusiveUpperBound_generatesNoneLaterThanEndDate(
            @ForAll("baseRules") RuleSpec base,
            @ForAll @IntRange(min = 0, max = 2000) int untilGap,
            @ForAll @IntRange(min = -30, max = 3000) int todayGap) {

        LocalDate until = base.startDate().plusDays(untilGap); // until ≥ startDate（需求 1.6）
        LocalDate today = base.startDate().plusDays(todayGap);
        RuleSpec untilRule = withUntil(base, until);

        List<LocalDate> actual = calculator.occurrencesUpTo(untilRule, today);
        assertAscendingDistinct(actual);

        // 无一晚于结束日期，且不晚于 today。
        for (LocalDate d : actual) {
            assertThat(d).as("occurrence must not exceed until date").isBeforeOrEqualTo(until);
            assertThat(d).as("occurrence must not exceed today").isBeforeOrEqualTo(today);
        }

        // 精确性（含端点 + 无遗漏）：UNTIL_DATE 序列恰等于 NEVER 序列取到 min(today, until)。
        LocalDate effectiveEnd = until.isBefore(today) ? until : today;
        List<LocalDate> expected = calculator.occurrencesUpTo(base, effectiveEnd);
        assertThat(actual)
                .as("UNTIL_DATE occurrences up to today=%s must equal unbounded occurrences up to min(today,until)=%s",
                        today, effectiveEnd)
                .isEqualTo(expected);
    }

    // ---------------------------------------------------------------------
    // Property 2 - facet C：COUNT 按升序累计至 N，总数不超过 N（需求 2.9、3.6）
    // ---------------------------------------------------------------------
    @Property(tries = 100)
    void count_capsTotalToN_asAscendingPrefix(
            @ForAll("baseRules") RuleSpec base,
            @ForAll @IntRange(min = 1, max = 60) int n,
            @ForAll @IntRange(min = -30, max = 4000) int todayGap) {

        LocalDate today = base.startDate().plusDays(todayGap);
        RuleSpec countRule = withCount(base, n);

        List<LocalDate> actual = calculator.occurrencesUpTo(countRule, today);
        assertAscendingDistinct(actual);

        // 总数不超过 N。
        assertThat(actual.size()).as("COUNT must cap occurrences at N=%d", n).isLessThanOrEqualTo(n);

        // 「按到期日升序累计至 N」：COUNT 序列恰为 NEVER 序列（截至 today）的前 min(N, size) 项。
        List<LocalDate> neverUpTo = calculator.occurrencesUpTo(base, today);
        List<LocalDate> expected = neverUpTo.subList(0, Math.min(n, neverUpTo.size()));
        assertThat(actual)
                .as("COUNT occurrences must be the ascending prefix (first %d) of the unbounded sequence", n)
                .isEqualTo(expected);

        // 全部期次不早于开始日期（需求 3.6）。
        for (LocalDate d : actual) {
            assertThat(d).as("occurrence must not be earlier than startDate").isAfterOrEqualTo(base.startDate());
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void assertAscendingDistinct(List<LocalDate> dates) {
        for (int i = 1; i < dates.size(); i++) {
            assertThat(dates.get(i))
                    .as("occurrences must be strictly ascending (distinct): index %d", i)
                    .isAfter(dates.get(i - 1));
        }
    }

    private static RuleSpec withUntil(RuleSpec base, LocalDate until) {
        return new RuleSpec(base.frequency(), base.weeklyDays(), base.monthDay(), base.monthEnd(),
                base.yearMonth(), base.yearDay(), base.startDate(), EndCondition.UNTIL_DATE, until, null);
    }

    private static RuleSpec withCount(RuleSpec base, int n) {
        return new RuleSpec(base.frequency(), base.weeklyDays(), base.monthDay(), base.monthEnd(),
                base.yearMonth(), base.yearDay(), base.startDate(), EndCondition.COUNT, null, n);
    }

    // ---------------------------------------------------------------------
    // Generators：随机频率配置（各类型 + 星期几集合 + 指定日 1–31 / 月末 + 年月日），
    //             开始日期跨平 / 闰年 / 小月 / 跨年，均以 NEVER 结束（由测试派生 UNTIL_DATE / COUNT）。
    // ---------------------------------------------------------------------

    @Provide
    Arbitrary<RuleSpec> baseRules() {
        Arbitrary<RuleSpec> daily =
                startDates().map(s -> RuleSpec.daily(s, EndCondition.NEVER, null, null));

        Arbitrary<RuleSpec> weekly =
                Combinators.combine(weeklyDaySets(), startDates())
                        .as((days, s) -> RuleSpec.weekly(days, s, EndCondition.NEVER, null, null));

        Arbitrary<RuleSpec> monthlyOnDay =
                Combinators.combine(Arbitraries.integers().between(1, 31), startDates())
                        .as((d, s) -> RuleSpec.monthlyOnDay(d, s, EndCondition.NEVER, null, null));

        Arbitrary<RuleSpec> monthlyOnLastDay =
                startDates().map(s -> RuleSpec.monthlyOnLastDay(s, EndCondition.NEVER, null, null));

        Arbitrary<RuleSpec> yearly =
                Combinators.combine(
                                Arbitraries.integers().between(1, 12),
                                Arbitraries.integers().between(1, 31),
                                startDates())
                        .as((m, d, s) -> RuleSpec.yearly(m, d, s, EndCondition.NEVER, null, null));

        return Arbitraries.oneOf(daily, weekly, monthlyOnDay, monthlyOnLastDay, yearly);
    }

    /** 非空的星期几集合（1=周一 .. 7=周日）。 */
    private Arbitrary<Set<Integer>> weeklyDaySets() {
        return Arbitraries.integers().between(1, 7).set().ofMinSize(1).ofMaxSize(7);
    }

    /** 开始日期：2018-01-01 .. 2030-12-31（覆盖平 / 闰年、小月、跨年）。 */
    private Arbitrary<LocalDate> startDates() {
        int min = (int) LocalDate.of(2018, 1, 1).toEpochDay();
        int max = (int) LocalDate.of(2030, 12, 31).toEpochDay();
        return Arbitraries.integers().between(min, max).map(epochDay -> LocalDate.ofEpochDay(epochDay));
    }
}
