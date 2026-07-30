package com.damien.youyu.api.dto;

/**
 * 账户在某账本的可见性状态（owner 视角）。
 *
 * <p>{@code participates} 表示该账户是否已纳入此账本；未纳入时两个标志取默认值 true 仅供展示。</p>
 */
public record AccountVisibilityResponse(
        Long ledgerId,
        boolean participates,
        boolean visibleToOthers,
        boolean showBalance) {
}
