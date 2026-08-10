package com.damien.youyu.api.dto;

/**
 * 更新个性化资料请求：性别与头像颜色。
 *
 * <p>{@code POST /api/me/profile}（需令牌）。两字段均可选：为 {@code null} 表示该项不修改；
 * 传空串表示清空（性别→保密、头像颜色→回退默认）。gender ∈ MALE/FEMALE/空；avatarColor 为 #RRGGBB。</p>
 */
public record UpdateProfileRequest(String gender, String avatarColor) {
}
