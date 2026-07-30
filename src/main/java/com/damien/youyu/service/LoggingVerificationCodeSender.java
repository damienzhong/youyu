package com.damien.youyu.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.damien.youyu.domain.EmailCodePurpose;

/**
 * 降级验证码发送器：当服务端未配置可用 SMTP（如本地/内测占位配置）时启用，
 * 把验证码打印到服务端日志而非真实发送邮件，便于内测联调（需求 1.6）。
 *
 * <p>生产环境必须配置真实 SMTP，从而由 {@link SmtpVerificationCodeSender} 取而代之；
 * 选择逻辑见 {@link VerificationCodeSenderConfig}。为提醒运维不要把降级实现带上生产，
 * 每次发送都会以 WARN 级别记录一行明确的日志。</p>
 */
public class LoggingVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationCodeSender.class);

    @Override
    public void send(String email, String code, EmailCodePurpose purpose) {
        // 内测降级：把验证码打印到日志，格式便于联调时快速定位。
        log.warn("[验证码降级-未配置SMTP] email code={} email={} purpose={}", code, email, purpose);
    }
}
