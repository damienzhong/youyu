package com.damien.youyu.api.dto;

/**
 * 绑定邮箱请求（需求 5）。
 *
 * <p>{@code POST /api/me/bind-email} 为令牌保护端点。服务端以
 * {@link com.damien.youyu.domain.EmailCodePurpose#BIND} 用途单次消费校验验证码，
 * 通过并经冲突检查后将该邮箱写入当前账号。</p>
 *
 * <ul>
 *   <li>{@code email}：待绑定邮箱。</li>
 *   <li>{@code code}：6 位数字验证码（purpose=BIND）。</li>
 * </ul>
 */
public record BindEmailRequest(String email, String code) {
}
