package com.damien.youyu.api.dto;

/**
 * 登录成功响应：返回会话令牌与用户摘要。
 *
 * <p>前端以 {@code Authorization: Bearer <token>} 携带令牌访问受保护接口。</p>
 */
public record LoginResponse(String token, String tokenType, UserSummaryResponse user) {

    public static LoginResponse of(String token, UserSummaryResponse user) {
        return new LoginResponse(token, "Bearer", user);
    }
}
