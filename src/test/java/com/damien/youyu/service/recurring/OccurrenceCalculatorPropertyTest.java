package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.damien.youyu.domain.EndCondition;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * {@link OccurrenceCalculator} 期次计算纯函数的属性测试。
 *
 * <p>Feature: recurring-transactions, Property 1: 期次计算确定性与月末 / 年边界</p>
 *
 * <p>对任意频率配置（DAILY / WEEKLY 星期几集合 / MONTHLY 指定日或月末 / YEARLY 月+日）、任意开始日期与任意
 * 「今天」，{@code occurrencesUpTo} 产出的期次列表满足：升序且去重；每个 MONTHLY / YEARLY 期次的到期日等于
 * {@code min(指定日, 该月自然天数)}（月末标记等价指定日=31，平年 2 月落 28、闰年落 29）；每个 WEEKLY 期次的
 * 星期几都落在集合内且集合内应到日期无遗漏；DAILY 相邻期次恰差 1 个自然日；且所有期次到期日均不早于开始日期。</p>
 *
 * <p>纯 {@link LocalDate} 运算，无 Spring / DB 依赖。生成的日期范围有意收敛于「今天」附近若干年内，
 * 以覆盖平 / 闰年、小月与跨年边界的同时把 DAILY 等序列长度控制在可控范围。</p>
 *
 * <p><strong>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.11, 3.6</strong></p>
 */
class OccurrenceCalculatorPropertyTest {

    private final OccurrenceCalculator calculator = new OccurrenceCalculator();

    /** 承载一组「规则 + 今天」的场景输入。 */
    record Scenario(RuleSpec rule, LocalDate today) {
    }

    /** 结束条件及其参数（NEVER / UNTIL_DATE 的 until / COUNT 的 n）。 */
    private record EndSpec(EndCondition condition, LocalDate until, Integer count) {
    }

    // Feature: recurring-transactions, Property 1: 期次计算确定性与月末 / 年边界
    @Property(tries = 100)
    void occurrencesAreAscendingDistinctWithinBoundaries(@ForAll("scenarios") Scenario scenario) {
        RuleSpec rule = scenario.rule();
        LocalDate today = scenario.today();
        List<LocalDate> occ = calculator.occurrencesUpTo(rule, today);

        // (A) 全部 ≥ 开始日期；升序且去重（严格递增即同时保证去重）。
        for (int i = 0; i < occ.size(); i++) {
            assertThat(occ.get(i))
                    .as("occurrence %s must not precede start date %s", occ.get(i), rule.startDate())
                    .isAfterOrEqualTo(rule.startDate());
            if (i > 0) {
                assertThat(occ.get(i))
                        .as("occurrences must be strictly ascending (distinct): %s", occ)
                        .isAfter(occ.get(i - 1));
            }
        }

        switch (rule.frequency()) {
            case DAILY -> assertDaily(occ);
            case WEEKLY -> assertWeekly(rule, today, occ);
            case MONTHLY -> assertMonthly(rule, occ);
            case YEARLY -> assertYearly(rule, occ);
        }
    }

    /** DAILY：相邻期次恰差 1 个自然日（需求 2.1）。 */
    private void assertDaily(List<LocalDate> occ) {
        for (int i = 1; i < occ.size(); i++) {
            assertThat(occ.get(i))
                    .as("DAILY adjacent occurrences must differ by exactly 1 day")
                    .isEqualTo(occ.get(i - 1).plusDays(1));
        }
    }

    /**
     * WEEKLY：每个期次星期几都在集合内，且区间内所有匹配日期无遗漏（需求 2.2）。
     * 以独立的逐日扫描重建期望序列（受 COUNT 截断），与计算结果整体比对。
     */
    private void assertWeekly(RuleSpec rule, LocalDate today, List<LocalDate> occ) {
        LocalDate hardEnd = hardEnd(rule, today);
        List<LocalDate> expected = new ArrayList<>();
        for (LocalDate d = rule.startDate(); !d.isAfter(hardEnd); d = d.plusDays(1)) {
            if (rule.weeklyDays().contains(d.getDayOfWeek().getValue())) {
                expected.add(d);
            }
        }
        if (rule.endCondition() == EndCondition.COUNT) {
            expected = expected.subList(0, Math.min(rule.countN(), expected.size()));
        }
        // 无遗漏 + 无多余 + 顺序一致。
        assertThat(occ)
                .as("WEEKLY occurrences must equal all in-range weekday-matching dates (no gaps)")
                .isEqualTo(expected);
        // 星期几都在集合内（membership，冗余但直接对应验收标准）。
        for (LocalDate d : occ) {
            assertThat(rule.weeklyDays())
                    .as("WEEKLY occurrence %s weekday must be in the set", d)
                    .contains(d.getDayOfWeek().getValue());
        }
    }

    /** MONTHLY：到期日 = 月末 ? 当月最后一日 : min(指定日, 当月天数)（需求 2.3、2.4、2.5）。 */
    private void assertMonthly(RuleSpec rule, List<LocalDate> occ) {
        for (LocalDate d : occ) {
            YearMonth ym = YearMonth.from(d);
            int len = ym.lengthOfMonth();
            int expectedDay = rule.monthEnd() ? len : Math.min(rule.monthDay(), len);
            assertThat(d.getDayOfMonth())
                    .as("MONTHLY occurrence %s day must equal %s (monthEnd=%s, len=%s)",
                            d, expectedDay, rule.monthEnd(), len)
                    .isEqualTo(expectedDay);
        }
    }

    /** YEARLY：落在指定月，日 = min(指定日, 当月天数)（需求 2.6）。 */
    private void assertYearly(RuleSpec rule, List<LocalDate> occ) {
        for (LocalDate d : occ) {
            assertThat(d.getMonthValue())
                    .as("YEARLY occurrence %s must fall in month %s", d, rule.yearMonth())
                    .isEqualTo(rule.yearMonth());
            YearMonth ym = YearMonth.of(d.getYear(), rule.yearMonth());
            assertThat(d.getDayOfMonth())
                    .as("YEARLY occurrence %s day must equal min(%s, %s)", d, rule.yearDay(), ym.lengthOfMonth())
                    .isEqualTo(Math.min(rule.yearDay(), ym.lengthOfMonth()));
        }
    }

    /** 生成上界 = min(today, untilDate)（与算法口径一致，用于重建 WEEKLY / DAILY 期望序列）。 */
    private LocalDate hardEnd(RuleSpec rule, LocalDate today) {
        if (rule.endCondition() == EndCondition.UNTIL_DATE
                && rule.untilDate() != null
                && rule.untilDate().isBefore(today)) {
            return rule.untilDate();
        }
        return today;
    }

    // ---------------------------------------------------------------------
    // 生成器：覆盖各频率 + 星期几集合 + 指定日 1–31 / 月末 + 年月日，
    // 开始日期落在 2019–2030（跨平 / 闰年、小月、跨年），今天为开始日期附近 [-30, +1100] 天。
    // ---------------------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        long minEpoch = LocalDate.of(2019, 1, 1).toEpochDay();
        long maxEpoch = LocalDate.of(2030, 12, 31).toEpochDay();
        Arbitrary<LocalDate> startDates = Arbitraries.longs().between(minEpoch, maxEpoch).map(LocalDate::ofEpochDay);

        return startDates.flatMap(start ->
                Arbitraries.integers().between(-30, 1100).flatMap(offset -> {
                    LocalDate today = start.plusDays(offset);
                    return ruleSpecs(start).map(spec -> new Scenario(spec, today));
                }));
    }

    private Arbitrary<RuleSpec> ruleSpecs(LocalDate start) {
        Arbitrary<EndSpec> ends = endSpecs(start);

        Arbitrary<RuleSpec> daily = ends.map(e ->
                RuleSpec.daily(start, e.condition(), e.until(), e.count()));

        Arbitrary<RuleSpec> weekly = Combinators.combine(weekdaySets(), ends).as((days, e) ->
                RuleSpec.weekly(days, start, e.condition(), e.until(), e.count()));

        Arbitrary<RuleSpec> monthlyOnDay = Combinators.combine(Arbitraries.integers().between(1, 31), ends).as((d, e) ->
                RuleSpec.monthlyOnDay(d, start, e.condition(), e.until(), e.count()));

        Arbitrary<RuleSpec> monthlyOnLastDay = ends.map(e ->
                RuleSpec.monthlyOnLastDay(start, e.condition(), e.until(), e.count()));

        Arbitrary<RuleSpec> yearly = Combinators.combine(
                Arbitraries.integers().between(1, 12),
                Arbitraries.integers().between(1, 31),
                ends).as((m, d, e) -> RuleSpec.yearly(m, d, start, e.condition(), e.until(), e.count()));

        return Arbitraries.oneOf(daily, weekly, monthlyOnDay, monthlyOnLastDay, yearly);
    }

    /** 星期几集合：1–7（周一至周日）的非空子集。 */
    private Arbitrary<Set<Integer>> weekdaySets() {
        return Arbitraries.integers().between(1, 7).set().ofMinSize(1).ofMaxSize(7);
    }

    /** 结束条件：NEVER / UNTIL_DATE（until ≥ start）/ COUNT（1–9999）。 */
    private Arbitrary<EndSpec> endSpecs(LocalDate start) {
        Arbitrary<EndSpec> never = Arbitraries.just(new EndSpec(EndCondition.NEVER, null, null));
        Arbitrary<EndSpec> until = Arbitraries.integers().between(0, 1100)
                .map(n -> new EndSpec(EndCondition.UNTIL_DATE, start.plusDays(n), null));
        Arbitrary<EndSpec> count = Arbitraries.integers().between(1, 9999)
                .map(n -> new EndSpec(EndCondition.COUNT, null, n));
        return Arbitraries.oneOf(never, until, count);
    }
}
