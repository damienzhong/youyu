package com.damien.youyu.service;

import com.damien.youyu.domain.EmailCodePurpose;

/**
 * 验证码发送器：把一枚已生成的验证码投递给目标邮箱。
 *
 * <p>该接口把「发送通道」与验证码的生成/防刷/存储逻辑（{@code VerificationCodeService}）解耦，
 * 存在两个实现：</p>
 * <ul>
 *   <li>{@link SmtpVerificationCodeSender}：基于 Spring {@code JavaMailSender} 的真实 SMTP 发送
 *       （生产走 QQ SMTP，复用授权码）。</li>
 *   <li>{@link LoggingVerificationCodeSender}：SMTP 未配置时的降级实现，把验证码打印到服务端日志，
 *       便于本地/内测联调（生产必须配置真实 SMTP）。</li>
 * </ul>
 *
 * <p>注入策略见 {@link VerificationCodeSenderConfig}：当 {@code spring.mail.host}/
 * {@code spring.mail.username} 未配置（占位为空）时选用日志降级实现，否则选用 SMTP 实现。</p>
 */
public interface VerificationCodeSender {

    /**
     * 发送验证码。
     *
     * @param email   目标邮箱
     * @param code    6 位数字验证码
     * @param purpose 验证码用途（LOGIN/BIND/DELETE），用于组织邮件正文文案
     */
    void send(String email, String code, EmailCodePurpose purpose);
}
