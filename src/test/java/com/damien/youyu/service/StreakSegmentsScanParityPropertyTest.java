package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * {@link GrowthCalendarService#segments(List)} 与 {@link GrowthCalendarService#scan(List)} 逐项一致的
 * 回归锁（<b>Property 2：{@code segments} 与 {@code scan} 逐项一致</b>）。
 *
 * <h2>测试层级选择</h2>
 * <p>两者均为无依赖静态纯函数，直接以记账日历驱动、走纯 jqwik，不引入 Spring 上下文、不落库、不 mock。</p>
 *
 * <h2>这条属性锁住什么</h2>
 * <p>{@code segments(c)} 的四项聚合投影必须与 {@code scan(c)} 逐项相等：</p>
 * <ul>
 *   <li>{@code Σ days} ↔ {@code totalDays}；</li>
 *   <li>{@code max days} ↔ {@code maxStreak}（空集时 0）；</li>
 *   <li>末段 {@code days} ↔ {@code currentSegment}（空集时 0）；</li>
 *   <li>末段 {@code endDate} ↔ {@code lastDate}（空集时 {@code null}）。</li>
 * </ul>
 * <p>这条锁住需求 4.5「不实现第二套连续段划分算法」——两者共用同一条 {@code toEpochDay} 相邻判定与同一个
 * {@code normalize}，一旦有人改了其中一个的相邻判定规则，本测试立刻变红。生成器与 Property 1 同源。</p>
 *
 * <p>Feature: streak-system, Property 2: {@code segments} 与 {@code scan} 逐项一致</p>
 *
 * <p>Validates: Requirements 4.5, 3.2</p>
 */
class StreakSegmentsScanParityPropertyTest {

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
        return Combinators.combine(start, segments).as(StreakSegmentsScanParityPropertyTest::buildSegmented);
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

    // ---------------- Property 2 ----------------

    /**
     * Feature: streak-system, Property 2: {@code segments} 与 {@code scan} 逐项一致
     *
     * <p>对任意日历，{@code segments(c)} 的四项聚合投影与 {@code scan(c)} 逐项相等。</p>
     *
     * <p>Validates: Requirements 4.5, 3.2</p>
     */
    @Property(tries = 1000)
    void property2_segmentsProjectionsEqualScan(@ForAll("calendars") List<LocalDate> calendar) {
        List<StreakSegmentView> segments = GrowthCalendarService.segments(calendar);
        CalendarScan scan = GrowthCalendarService.scan(calendar);

        long sumDays = segments.stream().mapToLong(StreakSegmentView::days).sum();
        int maxDays = segments.stream().mapToInt(StreakSegmentView::days).max().orElse(0);
        int lastDays = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).days();
        LocalDate lastEnd = segments.isEmpty() ? null : segments.get(segments.size() - 1).endDate();

        assertThat(sumDays)
                .as("Σ days 应等于 scan.totalDays")
                .isEqualTo(scan.totalDays());
        assertThat(maxDays)
                .as("max days 应等于 scan.maxStreak（空集时 0）")
                .isEqualTo(scan.maxStreak());
        assertThat(lastDays)
                .as("末段 days 应等于 scan.currentSegment（空集时 0）")
                .isEqualTo(scan.currentSegment());
        assertThat(lastEnd)
                .as("末段 endDate 应等于 scan.lastDate（空集时 null）")
                .isEqualTo(scan.lastDate());
    }

    /** 空日历：四项投影退化为 (0, 0, 0, null)，与 scan 空集结果一致。 */
    @Example
    void property2_emptyCalendarParity() {
        List<StreakSegmentView> segments = GrowthCalendarService.segments(List.of());
        CalendarScan scan = GrowthCalendarService.scan(List.of());
        assertThat(segments).isEmpty();
        assertThat(scan).isEqualTo(new CalendarScan(0, 0, 0, null));
    }
}
