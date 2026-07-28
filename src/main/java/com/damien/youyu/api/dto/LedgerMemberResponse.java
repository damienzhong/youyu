package com.damien.youyu.api.dto;

/**
 * 账本成员响应体。{@code displayName} 为成员账号标识（微信用户可能为空，前端回退展示）。
 */
public record LedgerMemberResponse(Long userId, String displayName, String role) {
}
