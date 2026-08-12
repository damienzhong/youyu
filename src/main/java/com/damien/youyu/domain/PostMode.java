package com.damien.youyu.domain;

/**
 * 周期规则的入账方式，对应 {@code recurring_rules.post_mode} 的两个取值（区分大小写，以枚举名存储）。
 *
 * <ul>
 *   <li>{@link #CONFIRM}：待确认（默认）——到期只生成 {@code PENDING} 待确认项，用户确认才入账。
 *       行为与 recurring-transactions 现状完全一致，存量规则一律视为本模式（向后兼容）。</li>
 *   <li>{@link #AUTO}：自动入账——到期直接走既有交易创建链路生成真实流水并更新账户余额、无需用户确认，
 *       并通知用户；目标失效 / 金额非法时降级为 {@code PENDING} 待确认项。</li>
 * </ul>
 *
 * <p>Feature: recurring-auto-post。</p>
 */
public enum PostMode {
    CONFIRM,
    AUTO
}
