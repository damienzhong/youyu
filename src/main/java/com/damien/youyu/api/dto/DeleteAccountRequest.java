package com.damien.youyu.api.dto;

/**
 * 注销账号请求（需求 8）。
 *
 * <p>{@code POST /api/me/delete} 为令牌保护端点。二次验证按账号身份分流：邮箱身份用户提交
 * {@link com.damien.youyu.domain.EmailCodePurpose#DELETE} 用途的验证码 {@code code}；
 * 纯微信用户提交一次性微信授权码 {@code wxCode} 重新授权。两字段均可选，服务端按身份选取。</p>
 *
 * <ul>
 *   <li>{@code code}：邮箱身份用户的 DELETE 用途验证码（可选）。</li>
 *   <li>{@code wxCode}：纯微信用户的一次性微信授权码（可选）。</li>
 * </ul>
 */
public record DeleteAccountRequest(String code, String wxCode) {
}
