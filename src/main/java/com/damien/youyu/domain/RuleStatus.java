package com.damien.youyu.domain;

/**
 * 周期规则的启停状态，对应 {@code recurring_rules.status} 的两个取值（区分大小写，以枚举名存储）。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：启用——懒生成会为其补齐已到期期次的待确认项。</li>
 *   <li>{@link #PAUSED}：暂停——不再生成新的待确认项，既有 {@code PENDING} 项保持不变。</li>
 * </ul>
 *
 * <p>Feature: recurring-transactions。</p>
 */
public enum RuleStatus {
    ACTIVE,
    PAUSED
}
