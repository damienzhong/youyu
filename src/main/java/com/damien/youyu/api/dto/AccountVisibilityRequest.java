package com.damien.youyu.api.dto;

/**
 * 账户在某账本的可见性设置（纳入/更新）。
 *
 * <p>{@code ledgerId} 目标账本；{@code visibleToOthers} 协作账本内是否对其他成员可见/可选；
 * {@code showBalance} 是否对其他成员显示真实余额（需求 3、4）。缺省 visibleToOthers=true、
 * showBalance=false（隐私优先：默认共享可记账但不露余额）。</p>
 */
public record AccountVisibilityRequest(
        Long ledgerId,
        Boolean visibleToOthers,
        Boolean showBalance) {
}
