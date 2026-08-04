package com.damien.youyu.service;

import java.time.LocalDate;

/**
 * 对一份完整记账日历做一次扫描得出的四项派生取值（需求 4.7、4.9、4.10）。
 *
 * <p>四项取值构成 {@code user_growth} 的四个物化列：{@code total_record_days}、
 * {@code current_streak_days}、{@code max_streak_days}、{@code last_record_date}。
 * 由 {@link GrowthCalendarService#scan(java.util.List)} 一次算出，因此四者天然自洽，
 * 不存在「累计天数已更新、连续段还是上一次的取值」这种半更新状态。</p>
 *
 * <p>由构造过程保证的不变式（需求 4.9、4.10）：</p>
 * <ul>
 *   <li>{@code maxStreak >= currentSegment}</li>
 *   <li>{@code totalDays == 0} 时 {@code currentSegment == 0 && maxStreak == 0 && lastDate == null}</li>
 *   <li>{@code totalDays > 0} 时 {@code lastDate} 非空且等于日历中的最大日期</li>
 * </ul>
 *
 * @param totalDays      累计记账天数，即记账日历中的日期个数（去重后）
 * @param currentSegment 连续段长度：以 {@code lastDate} 为终点向前逐日回溯的连续自然日个数。
 *                       注意这<b>不是</b>对外返回的「当前连续天数」——「连续是否已中断」由读取侧
 *                       按判定日实时判定（需求 4.11、4.15），本记录不读时钟、无从判断
 * @param maxStreak      历史最长连续天数：日历中最长连续自然日区间所含的日期个数
 * @param lastDate       最近记账日；日历为空时为 {@code null}
 */
public record CalendarScan(int totalDays, int currentSegment, int maxStreak, LocalDate lastDate) {
}
