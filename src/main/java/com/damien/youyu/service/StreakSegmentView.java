package com.damien.youyu.service;

import java.time.LocalDate;

/**
 * 段的不可变值对象：一段极大连续自然日区间，由起始日、结束日与段天数三项确定
 * （需求 4.1、4.2、8.14）。是 {@link GrowthCalendarService#segments} 纯函数的返回元素。
 *
 * <p>{@code days} 在构造时由两端算出（{@code end − start + 1}），因此不变式①
 * （{@code days == 结束日 − 起始日 + 1}）在内存里<b>无法被构造出反例</b>——只要经过
 * {@link #of} 工厂创建，这条不变式就恒成立。</p>
 *
 * <p>{@code days} 冗余存一列（而不是每次由两端相减算）是为了让「取最长段」走
 * {@code idx_streak_segments_user_days} 索引：「两端相减」这个表达式无法走索引，
 * 且 MySQL 与 H2 {@code MODE=MySQL} 的日期差函数行为不完全一致，把它写进 SQL 会让核心不变式
 * 失去同一份自动化验证依据。冗余列的一致性由本工厂与 CHECK 约束 {@code ck_streak_segments_range} 双重保证。</p>
 */
public record StreakSegmentView(LocalDate startDate, LocalDate endDate, int days) {

    /**
     * 由起止日构造段：{@code days = end.toEpochDay() − start.toEpochDay() + 1}。
     * 跨度落在 {@code [1, Integer.MAX_VALUE]} 之外时抛 {@link IllegalArgumentException}。
     */
    public static StreakSegmentView of(LocalDate start, LocalDate end) {
        long span = end.toEpochDay() - start.toEpochDay() + 1L;
        if (span < 1L || span > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("段跨度非法：" + start + " ~ " + end);
        }
        return new StreakSegmentView(start, end, (int) span);
    }
}
