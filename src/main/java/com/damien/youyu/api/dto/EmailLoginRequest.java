package com.damien.youyu.api.dto;

/**
 * 邮箱验证码登录/注册合一请求（需求 2）。
 *
 * <p>{@code POST /api/auth/email-login} 为公开端点（需求 9.2）。服务端以
 * {@link com.damien.youyu.domain.EmailCodePurpose#LOGIN} 用途单次消费校验验证码：
 * 通过后若邮箱未注册则自动建号，否则直接登录，返回结构与微信登录一致（token + 用户摘要）。</p>
 *
 * <ul>
 *   <li>{@code email}：登录邮箱。</li>
 *   <li>{@code code}：6 位数字验证码。</li>
 * </ul>
 */
public record EmailLoginRequest(String email, String code) {
}
