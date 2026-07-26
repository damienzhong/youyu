package com.damien.youyu.api.dto;

import java.time.LocalDateTime;

import com.damien.youyu.domain.User;

/**
 * 用户信息摘要（只读）：用于注册结果与 GET /api/me。
 *
 * <p>{@code plan/role} 仅作展示，本期不做任何功能门控。</p>
 */
public record UserSummaryResponse(
        Long id,
        String username,
        String plan,
        String role,
        LocalDateTime planStartedAt,
        LocalDateTime planExpiresAt) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getPlan().getCode(),
                user.getRole().getCode(),
                user.getPlanStartedAt(),
                user.getPlanExpiresAt());
    }
}
