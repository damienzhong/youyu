package com.damien.youyu.domain;

/**
 * 周期规则的结束方式，对应 {@code recurring_rules.end_condition} 的三个取值（区分大小写，以枚举名存储）。
 *
 * <ul>
 *   <li>{@link #NEVER}：永不结束。</li>
 *   <li>{@link #UNTIL_DATE}：到某自然日为止（{@code until_date}，含端点，不早于开始日期）。</li>
 *   <li>{@link #COUNT}：共生成 N 期后终止（{@code count_n}，1–9999）。</li>
 * </ul>
 *
 * <p>Feature: recurring-transactions。</p>
 */
public enum EndCondition {
    NEVER,
    UNTIL_DATE,
    COUNT
}
