package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 段与日历互为充要的回归锁（<b>Property 3：段与日历互为充要</b>）。
 *
 * <h2>测试层级选择</h2>
 * <p>{@code segments} 是无依赖静态纯函数，直接以「记账日历 × 探测日期」驱动、走纯 jqwik，
 * 不引入 Spring 上下文、不落库、不 mock。</p>
 *
 * <h2>这条属性锁住什么</h2>
 * <p>对任意日历与任意自然日 D（需求 4.3）：</p>
 * <ul>
 *   <li>D 落在段序列的某一项的 {@code [startDate, endDate]} 闭区间内 ⟺ D 在（去重后的）日历中；</li>
 *   <li>D 至多落在段序列的 1 项内（任意两段既不相交也不相邻，Property 1 不变式②的可观察推论）。</li>
 * </ul>
 *
 * <h2>探测日期的覆盖</h2>
 * <p>探测集刻意覆盖四类：日历内的每个日期、每个日期的相邻日（±1，压段两端与段间空隙）、每个日期的 ±2 日、
 * 以及若干全域随机日期（压日历外的远点）。这样「段两端相邻日」「段间空隙日」「日历内点」「日历外点」四种
 * 边界全部被压到。生成器与 Property 1 同源。</p>
 *
 * <p>Feature: streak-system, Property 3: 段与日历互为充要</p>
 *
 * <p>Validates: Requirements 4.3</p>
 */
class StreakSegmentCoveragePropertyTest {

    private static final long EPOCH_MIN = LocalDate.of(2000, 1, 1).toEpochDay();
    private static final long EPOCH_MAX = LocalDate.of(2035, 12, 31).toEpochDay();
    private static final int MAX_SIZE = 400;

    // ---------------- 生成器 ----------------

    @Provide
    Arbitrary<List<LocalDate>> segmentedSets() {
        Arbitrary<Long> start = Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX);
        Arbitrary<int[]> segment = Combinators.combine(
                        Arbitraries.integers().between(1, 60),
                        Arbitraries.integers().between(1, 30))
                .as((len, gap) -> new int[] {len, gap});
        Arbitrary<List<int[]>> segments = segment.list().ofMaxSize(20);
        return Combinators.combine(start, segments).as(StreakSegmentCoveragePropertyTest::buildSegmented);
    }

    @Provide
    Arbitrary<List<LocalDate>> randomSets() {
        return Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX)
                .map(LocalDate::ofEpochDay)
                .list().ofMaxSize(MAX_SIZE);
    }

    @Provide
    Arbitrary<List<LocalDate>> extremeSets() {
        Arbitrary<Long> start = Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX);
        Arbitrary<Integer> count = Arbitraries.integers().between(0, MAX_SIZE);
        Arbitrary<Boolean> consecutive = Arbitraries.of(true, false);
        return Combinators.combine(start, count, consecutive).as((s, n, cons) -> {
            List<LocalDate> dates = new ArrayList<>(n);
            long step = cons ? 1L : 2L;
            for (int i = 0; i < n; i++) {
                dates.add(LocalDate.ofEpochDay(s + i * step));
            }
            return dates;
        });
    }

    @Provide
    Arbitrary<List<LocalDate>> calendars() {
        return Arbitraries.oneOf(segmentedSets(), randomSets(), extremeSets());
    }

    private static List<LocalDate> buildSegmented(long start, List<int[]> segments) {
        List<LocalDate> dates = new ArrayList<>();
        long cursor = start;
        for (int[] seg : segments) {
            int len = seg[0];
            int gap = seg[1];
            for (int i = 0; i < len && dates.size() < MAX_SIZE; i++) {
                dates.add(LocalDate.ofEpochDay(cursor + i));
            }
            if (dates.size() >= MAX_SIZE) {
                break;
            }
            cursor += (long) len + gap;
        }
        return dates;
    }

    // ---------------- Property 3 ----------------

    /**
     * Feature: streak-system, Property 3: 段与日历互为充要
     *
     * <p>对任意日历 × 一批覆盖四类边界的探测日期：D 落在某段内 ⟺ D 在日历中，且 D 至多落在 1 段内。</p>
     *
     * <p>Validates: Requirements 4.3</p>
     */
    @Property(tries = 25)
    void property3_segmentCoverageIffInCalendar(
            @ForAll("calendars") List<LocalDate> calendar,
            @ForAll long probeSeed) {

        List<StreakSegmentView> segments = GrowthCalendarService.segments(calendar);
        Set<LocalDate> distinct = new HashSet<>(calendar);

        for (LocalDate probe : probes(calendar, probeSeed)) {
            int hits = 0;
            for (StreakSegmentView seg : segments) {
                boolean inSeg = !probe.isBefore(seg.startDate()) && !probe.isAfter(seg.endDate());
                if (inSeg) {
                    hits++;
                }
            }
            // 至多落在 1 段内（任意两段既不相交也不相邻）。
            assertThat(hits)
                    .as("探测日期 %s 至多落在 1 段内", probe)
                    .isLessThanOrEqualTo(1);
            // 落在某段内 ⟺ 在日历中。
            assertThat(hits == 1)
                    .as("探测日期 %s 落在某段内当且仅当它在日历中", probe)
                    .isEqualTo(distinct.contains(probe));
        }
    }

    /** 定点用例：段两端相邻日与段间空隙日一律不落在任何段内。 */
    @Example
    void property3_boundaryAndGapDaysAreUncovered() {
        // 两段：[d0, d0+2]（3 天）与 [d0+5, d0+6]（2 天），中间 d0+3、d0+4 为空隙。
        LocalDate d0 = LocalDate.of(2024, 2, 27); // 跨闰日 2-29
        List<LocalDate> calendar = List.of(
                d0, d0.plusDays(1), d0.plusDays(2),
                d0.plusDays(5), d0.plusDays(6));
        List<StreakSegmentView> segments = GrowthCalendarService.segments(calendar);
        assertThat(segments).hasSize(2);

        // 段前一日、段后一日、段间空隙日均不落在任何段内。
        for (LocalDate uncovered : List.of(
                d0.minusDays(1), d0.plusDays(3), d0.plusDays(4), d0.plusDays(7))) {
            long hits = segments.stream()
                    .filter(s -> !uncovered.isBefore(s.startDate()) && !uncovered.isAfter(s.endDate()))
                    .count();
            assertThat(hits).as("边界/空隙日 %s 不落在任何段内", uncovered).isZero();
        }
        // 日历内每日恰落在 1 段内。
        for (LocalDate covered : calendar) {
            long hits = segments.stream()
                    .filter(s -> !covered.isBefore(s.startDate()) && !covered.isAfter(s.endDate()))
                    .count();
            assertThat(hits).as("日历内日期 %s 恰落在 1 段内", covered).isEqualTo(1);
        }
    }

    // ---------------- 探测集：覆盖日历内 / 相邻 / ±2 / 全域随机 ----------------

    private static Set<LocalDate> probes(List<LocalDate> calendar, long seed) {
        Set<LocalDate> probes = new LinkedHashSet<>();
        for (LocalDate d : calendar) {
            probes.add(d);
            probes.add(d.minusDays(1));
            probes.add(d.plusDays(1));
            probes.add(d.minusDays(2));
            probes.add(d.plusDays(2));
        }
        Random random = new Random(seed);
        for (int i = 0; i < 8; i++) {
            probes.add(LocalDate.ofEpochDay(EPOCH_MIN + (long) (random.nextDouble() * (EPOCH_MAX - EPOCH_MIN))));
        }
        // 保证空日历也有探测点（此时全部应落在日历外）。
        probes.add(LocalDate.ofEpochDay(EPOCH_MIN));
        probes.add(LocalDate.ofEpochDay(EPOCH_MAX));
        return probes;
    }
}
