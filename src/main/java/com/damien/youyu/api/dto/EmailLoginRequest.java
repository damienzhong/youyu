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
 *   <li>{@code inviteCode}：可选邀请码，取值长度上限 64（需求 5.1）。</li>
 * </ul>
 *
 * <p>{@code inviteCode} 刻意<b>不加任何 Bean Validation 约束</b>（如 {@code @Size(max = 64)}）：
 * 需求 5.6 要求原始取值超过 64 字符时以未绑定原因 {@code CODE_NOT_FOUND} 正常完成登录，
 * 而不是让登录请求以 400 失败。长度上限、去空白转大写与格式判定统一由
 * {@link com.damien.youyu.service.InviteBindingService#bindOnRegister} 落地，
 * 字段缺失 / NULL / 去空白为空一律按 {@code NO_CODE} 处理。</p>
 */
public record EmailLoginRequest(String email, String code, String inviteCode) {
}
