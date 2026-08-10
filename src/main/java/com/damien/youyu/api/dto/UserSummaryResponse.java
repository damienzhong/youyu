package com.damien.youyu.api.dto;

import java.time.LocalDateTime;

import com.damien.youyu.domain.User;

/**
 * 用户信息摘要（只读）：用于登录结果与 GET /api/me。
 *
 * <p>关联需求 4.5：包含 {@code id}、{@code nickname}、{@code email}（原样返回，见设计）、
 * 是否已绑定邮箱/微信的标志（{@code hasEmail}/{@code hasWechat}）以及 {@code plan}/{@code role}
 * 与套餐起止时间。{@code plan/role} 仅作展示，本期不做任何功能门控。</p>
 *
 * <p>无密码模型下不再有 {@code username}；展示名统一使用 {@code nickname}（可空、可重复、
 * 仅展示，不用于登录鉴权）。</p>
 */
public record UserSummaryResponse(
        Long id,
        String nickname,
        String email,
        boolean hasEmail,
        boolean hasWechat,
        String plan,
        String role,
        LocalDateTime planStartedAt,
        LocalDateTime planExpiresAt,
        String gender,
        String avatarColor) {

    public static UserSummaryResponse from(User user) {
        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();
        boolean hasWechat = user.getWxOpenid() != null && !user.getWxOpenid().isBlank();
        return new UserSummaryResponse(
                user.getId(),
                user.getNickname(),
                user.getEmail(),
                hasEmail,
                hasWechat,
                user.getPlan().getCode(),
                user.getRole().getCode(),
                user.getPlanStartedAt(),
                user.getPlanExpiresAt(),
                user.getGender(),
                user.getAvatarColor());
    }
}
