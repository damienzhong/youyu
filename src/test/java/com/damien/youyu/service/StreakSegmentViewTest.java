package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * {@link StreakSegmentView} 的示例/边界单元测试（关联需求 4.1、4.2、8.14）。
 *
 * <p>纯值对象、无外部依赖，故不起 Spring 上下文。锁住三条：</p>
 *
 * <ul>
 *   <li>{@code days} 由两端算出，恒等于 {@code 结束日 − 起始日 + 1}（不变式①在内存里无法构造出反例）；</li>
 *   <li>单日段（起止同日）的 {@code days} 恰为 1；</li>
 *   <li>{@code end < start} 时静态工厂抛 {@link IllegalArgumentException}。</li>
 * </ul>
 */
class StreakSegmentViewTest {

    @Test
    void ofComputesDaysAsInclusiveSpan() {
        StreakSegmentView view = StreakSegmentView.of(
                LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 10));

        assertThat(view.startDate()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(view.endDate()).isEqualTo(LocalDate.of(2024, 3, 10));
        assertThat(view.days()).isEqualTo(10);
    }

    @Test
    void ofComputesDaysAcrossMonthBoundary() {
        // 2024-01-30 ~ 2024-02-02 共 4 天（闰年 1 月 31 天 → 30、31、2/1、2/2）
        StreakSegmentView view = StreakSegmentView.of(
                LocalDate.of(2024, 1, 30), LocalDate.of(2024, 2, 2));

        assertThat(view.days()).isEqualTo(4);
    }

    @Test
    void singleDaySegmentHasDaysOne() {
        LocalDate day = LocalDate.of(2024, 2, 29);
        StreakSegmentView view = StreakSegmentView.of(day, day);

        assertThat(view.days()).isEqualTo(1);
        assertThat(view.startDate()).isEqualTo(view.endDate());
    }

    @Test
    void endBeforeStartThrows() {
        LocalDate start = LocalDate.of(2024, 3, 2);
        LocalDate end = LocalDate.of(2024, 3, 1);

        assertThatThrownBy(() -> StreakSegmentView.of(start, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("段跨度非法");
    }
}
