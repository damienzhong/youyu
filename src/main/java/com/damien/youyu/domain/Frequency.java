package com.damien.youyu.domain;

/**
 * 周期规则的到期节律，对应 {@code recurring_rules.frequency} 的四个取值（区分大小写，以枚举名存储）。
 *
 * <ul>
 *   <li>{@link #DAILY}：每天——自开始日期起每个连续自然日各一期。</li>
 *   <li>{@link #WEEKLY}：每周——附星期几集合（{@code weekly_days}，1=周一..7=周日）。</li>
 *   <li>{@link #MONTHLY}：每月——附指定日（{@code month_day} 1–31）或「月末」标记（{@code month_end}）。</li>
 *   <li>{@link #YEARLY}：每年——附指定月与日（{@code year_month} + {@code year_day}）。</li>
 * </ul>
 *
 * <p>Feature: recurring-transactions。</p>
 */
public enum Frequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}
