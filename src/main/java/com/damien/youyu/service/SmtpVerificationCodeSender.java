package com.damien.youyu.service;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.damien.youyu.domain.EmailCodePurpose;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * 基于 Spring {@link JavaMailSender} 的真实 SMTP 验证码发送器（生产走 QQ SMTP）。
 *
 * <p>发件人取 {@code spring.mail.username}（QQ 邮箱），主题固定「有余 验证码」，
 * 正文为含验证码与用途说明的 HTML。发送失败时抛出运行时异常向上传递，绝不静默吞掉，
 * 以保证请求不以「成功」状态返回（需求 1.5）。</p>
 *
 * <p>注意：本任务(3.1)阶段 {@code ApiException} 尚未引入 {@code EMAIL_SEND_FAILED} 错误码
 * （在任务 6 中补齐）。因此此处让底层的 {@link MailException} 直接传递，或将
 * {@link MessagingException} 包装为 {@link MailException} 抛出；由服务层在后续任务中
 * 统一翻译为 {@code EMAIL_SEND_FAILED}。</p>
 */
public class SmtpVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpVerificationCodeSender.class);

    /** 邮件主题（固定品牌文案）。 */
    static final String SUBJECT = "有余 验证码";

    private final JavaMailSender mailSender;
    private final String from;

    /**
     * @param mailSender Spring 自动装配的邮件发送器（依赖 {@code spring.mail.*} 配置）
     * @param from       发件人邮箱，取 {@code spring.mail.username}
     */
    public SmtpVerificationCodeSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String email, String code, EmailCodePurpose purpose) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(SUBJECT);
            helper.setText(buildHtmlBody(code, purpose), true);
        } catch (MessagingException ex) {
            // 组装邮件阶段失败：包装为 MailException 向上传递，服务层后续翻译为 EMAIL_SEND_FAILED。
            throw new MailSendFailedException("验证码邮件组装失败", ex);
        }

        // send 失败会抛出 MailException（RuntimeException），此处不捕获，直接向上传递，
        // 以确保请求不会以成功状态返回（需求 1.5）。
        mailSender.send(message);
        log.debug("已发送验证码邮件 to={} purpose={}", email, purpose);
    }

    /** 构建含验证码与用途说明的 HTML 正文。 */
    private String buildHtmlBody(String code, EmailCodePurpose purpose) {
        String action = describePurpose(purpose);
        return "<div style=\"font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;"
                + "font-size:14px;color:#333;line-height:1.6\">"
                + "<p>你正在进行「" + action + "」操作，验证码为：</p>"
                + "<p style=\"font-size:28px;font-weight:700;letter-spacing:4px;color:#111\">"
                + code + "</p>"
                + "<p>验证码 10 分钟内有效，请勿泄露给他人。如非本人操作，请忽略本邮件。</p>"
                + "<p style=\"color:#999;margin-top:24px\">— 有余</p>"
                + "</div>";
    }

    /** 将用途枚举翻译为面向用户的中文说明。 */
    private String describePurpose(EmailCodePurpose purpose) {
        if (purpose == null) {
            return "身份验证";
        }
        return switch (purpose) {
            case LOGIN -> "登录 / 注册";
            case BIND -> "绑定邮箱";
            case DELETE -> "注销账号";
        };
    }

    /**
     * 邮件组装/发送失败的运行时异常（{@link MailException} 子类）。
     *
     * <p>作为 {@code MailException} 的子类，可被服务层与既有的邮件异常处理逻辑统一捕获，
     * 后续任务中翻译为 {@code EMAIL_SEND_FAILED}。</p>
     */
    static class MailSendFailedException extends MailException {
        MailSendFailedException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
