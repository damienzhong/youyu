package com.damien.youyu.domain;

/**
 * 待确认生成项的状态，对应 {@code recurring_pending_items.status} 的三个取值（区分大小写，以枚举名存储）。
 *
 * <ul>
 *   <li>{@link #PENDING}：待确认——等待用户确认、修改后确认或跳过。</li>
 *   <li>{@link #CONFIRMED}：已确认入账——已走既有交易创建链路生成真实流水。</li>
 *   <li>{@link #SKIPPED}：已跳过本期——不生成流水、不改动余额。</li>
 * </ul>
 *
 * <p>Feature: recurring-transactions。</p>
 */
public enum PendingStatus {
    PENDING,
    CONFIRMED,
    SKIPPED
}
