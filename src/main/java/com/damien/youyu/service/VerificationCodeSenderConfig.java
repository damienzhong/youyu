package com.damien.youyu.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 按配置选择注入 {@link VerificationCodeSender} 实现。
 *
 * <p>选择策略（需求 1.5、1.6）：</p>
 * <ul>
 *   <li>当 {@code spring.mail.host} 与 {@code spring.mail.username} 均已配置（非空白），
 *       且 Spring 已装配出可用的 {@link JavaMailSender} 时 → 注入
 *       {@link SmtpVerificationCodeSender}（真实 SMTP 发送）。</li>
 *   <li>否则（本地/内测占位、未配置）→ 注入 {@link LoggingVerificationCodeSender}，
 *       把验证码打印到服务端日志，便于联调。</li>
 * </ul>
 *
 * <p>之所以用 {@link ObjectProvider} 取 {@code JavaMailSender}：Spring Boot 的
 * 邮件自动装配仅在 {@code spring.mail.host} 存在时才创建 {@code JavaMailSender} Bean，
 * 因此未配置时该 Bean 不存在，用 {@code getIfAvailable()} 可安全地拿到 null 并降级。</p>
 */
@Configuration
public class VerificationCodeSenderConfig {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodeSenderConfig.class);

    @Bean
    public VerificationCodeSender verificationCodeSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.username:}") String username) {

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        boolean smtpConfigured = mailSender != null && isConfigured(host) && isConfigured(username);

        if (smtpConfigured) {
            log.info("验证码发送器：SMTP（host={}, from={}）", host, username);
            return new SmtpVerificationCodeSender(mailSender, username);
        }

        log.warn("验证码发送器：未配置可用 SMTP，启用日志降级（生产环境请务必配置 spring.mail.*）");
        return new LoggingVerificationCodeSender();
    }

    /** 判断配置项是否已真实配置（非 null 且去空白后非空，排除占位空值）。 */
    private static boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }
}
