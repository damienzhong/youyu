package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link GrowthCalendarService#segments(List)} 的示例/边界单元测试
 * （关联需求 4.1、4.2、4.3、4.5、10.8）。
 *
 * <p>纯静态函数、无外部依赖，故不起 Spring 上下文。逐例覆盖：空集 / 单点 / 全连续 / 全离散 /
 * 重复 / 乱序 / 跨月 / 跨年 / 闰日 / 含 {@code null} 抛异常。段一律以起始日升序返回，
 * 每段的 {@code days} 等于结束日与起始日之差加 1，相邻两段之间至少间隔一个不在日历中的自然日。</p>
 */
class GrowthCalendarServiceSegmentsTest {

    @Test
    void emptyCalendarReturnsEmptyList() {
        assertThat(GrowthCalendarService.segments(List.of())).isEmpty();
    }

    @Test
    void nullCalendarReturnsEmptyList() {
        assertThat(GrowthCalendarService.segments(null)).isEmpty();
    }

    @Test
    void singlePointReturnsOneSingleDaySegment() {
        LocalDate day = LocalDate.of(2024, 5, 10);

        List<StreakSegmentView> segments = GrowthCalendarService.segments(List.of(day));

        assertThat(segments).containsExactly(StreakSegmentView.of(day, day));
        assertThat(segments.get(0).days()).isEqualTo(1);
    }

    @Test
    void allContinuousDatesCollapseIntoOneSegment() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 5, 1),
                LocalDate.of(2024, 5, 2),
                LocalDate.of(2024, 5, 3),
                LocalDate.of(2024, 5, 4));

        List<StreakSegmentView> segments = GrowthCalendarService.segments(dates);

        assertThat(segments).containsExactly(
                StreakSegmentView.of(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 4)));
        assertThat(segments.get(0).days()).isEqualTo(4);
    }

    @Test
    void allDiscreteDatesEachBecomeOwnSegment() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 5, 1),
                LocalDate.of(2024, 5, 3),
                LocalDate.of(2024, 5, 5));

        List<StreakSegmentView> segments = GrowthCalendarService.segments(dates);

        assertThat(segments).containsExactly(
                StreakSegmentView.of(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 1)),
                StreakSegmentView.of(LocalDate.of(2024, 5, 3), LocalDate.of(2024, 5, 3)),
                StreakSegmentView.of(LocalDate.of(2024, 5, 5), LocalDate.of(2024, 5, 5)));
    }

    @Test
    void duplicatesAreDeduplicatedBeforeSegmenting() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 5, 1),
                LocalDate.of(2024, 5, 1),
                LocalDate.of(2024, 5, 2),
                LocalDate.of(2024, 5, 2),
                LocalDate.of(2024, 5, 2));

        List<StreakSegmentView> segments = GrowthCalendarService.segments(dates);

        assertThat(segments).containsExactly(
                StreakSegmentView.of(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 2)));
    }

    @Test
    void outOfOrderDatesAreSortedBeforeSegmenting() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 5, 3),
                LocalDate.of(2024, 5, 1),
                LocalDate.of(2024, 5, 5),
                LocalDate.of(2024, 5, 2));

        List<StreakSegmentView> segments = GrowthCalendarService.segments(dates);

        // 排序去重后 [5/1,5/2,5/3,5/5] → 段 [5/1~5/3]、[5/5]
        assertThat(segments).containsExactly(
                StreakSegmentView.of(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 3)),
                StreakSegmentView.of(LocalDate.of(2024, 5, 5), LocalDate.of(2024, 5, 5)));
    }

    @Test
    void continuousDatesSpanningMonthBoundaryStayOneSegment() {
        // 2024-01-30、31、02-01、02-02（闰年 1 月 31 天）
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 1, 30),
                LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 2, 1),
                LocalDate.of(2024, 2, 2));

        List<StreakSegmentView> segments = GrowthCalendarService.segments(dates);

        assertThat(segments).containsExactly(
                StreakSegmentView.of(LocalDate.of(2024, 1, 30), LocalDate.of(2024, 2, 2)));
        assertThat(segments.get(0).days()).isEqualTo(4);
    }

    @Test
    void continuousDatesSpanningYearBoundaryStayOneSegment() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2023, 12, 30),
                LocalDate.of(2023, 12, 31),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 2));

        List<StreakSegmentView> segments = GrowthCalendarService.segments(dates);

        assertThat(segments).containsExactly(
                StreakSegmentView.of(LocalDate.of(2023, 12, 30), LocalDate.of(2024, 1, 2)));
        assertThat(segments.get(0).days()).isEqualTo(4);
    }

    @Test
    void leapDayIsContiguousWithSurroundingDays() {
        // 2024 是闰年：2/28 → 2/29 → 3/1 是连续三天
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 2, 28),
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 1));

        List<StreakSegmentView> segments = GrowthCalendarService.segments(dates);

        assertThat(segments).containsExactly(
                StreakSegmentView.of(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1)));
        assertThat(segments.get(0).days()).isEqualTo(3);
    }

    @Test
    void calendarContainingNullThrows() {
        List<LocalDate> dates = new ArrayList<>(Arrays.asList(
                LocalDate.of(2024, 5, 1), null, LocalDate.of(2024, 5, 2)));

        assertThatThrownBy(() -> GrowthCalendarService.segments(dates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许包含空日期");
    }
}
