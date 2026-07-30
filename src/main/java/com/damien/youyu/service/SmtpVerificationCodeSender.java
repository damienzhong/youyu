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
    static final String SUBJECT = "【有余】邮箱验证码";

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

    /**
     * 构建品牌化 HTML 邮件正文。
     *
     * <p>采用表格 + 全内联样式，兼容 QQ 邮箱 / Gmail / Outlook 等常见客户端（这些客户端会
     * 剥离 {@code <style>}、不支持 flex/部分 CSS）。渐变色带 solid 兜底色，验证码用大号
     * 字距 + 浅绿色块突出，便于识别与复制。</p>
     */
    private String buildHtmlBody(String code, EmailCodePurpose purpose) {
        String action = describePurpose(purpose);
        return "<!DOCTYPE html><html><body style=\"margin:0;padding:0;background:#f3f4f6;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"background:#f3f4f6;padding:32px 12px;\"><tr><td align=\"center\">"
            // 卡片
            + "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"width:480px;max-width:100%;background:#ffffff;border-radius:16px;overflow:hidden;"
            + "box-shadow:0 8px 30px rgba(20,24,28,0.08);\">"
            // 顶部品牌带（绿色，渐变+solid 兜底）
            + "<tr><td style=\"background:#16a34a;background:linear-gradient(135deg,#22c55e,#0b6b34);"
            + "padding:28px 32px;\">"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
            + "<td style=\"width:44px;height:44px;background:rgba(255,255,255,0.22);border-radius:12px;"
            + "text-align:center;font-size:26px;font-weight:800;color:#ffffff;\">&yen;</td>"
            + "<td style=\"padding-left:14px;font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;"
            + "font-size:22px;font-weight:800;color:#ffffff;\">有余</td>"
            + "</tr></table></td></tr>"
            // 正文
            + "<tr><td style=\"padding:32px;font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;"
            + "color:#16181c;\">"
            + "<p style=\"margin:0 0 8px;font-size:18px;font-weight:700;\">邮箱验证码</p>"
            + "<p style=\"margin:0 0 20px;font-size:14px;color:#6b7280;line-height:1.6;\">"
            + "你正在进行「" + action + "」，请在页面输入以下验证码完成验证：</p>"
            // 验证码块
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">"
            + "<tr><td align=\"center\" style=\"background:#eafaf1;border:1px solid #b7ebc9;"
            + "border-radius:12px;padding:18px 0;\">"
            + "<span style=\"font-size:36px;font-weight:800;letter-spacing:10px;color:#0e8a44;"
            + "font-family:Consolas,Menlo,monospace;\">" + code + "</span>"
            + "</td></tr></table>"
            + "<p style=\"margin:20px 0 0;font-size:13px;color:#9aa2ad;line-height:1.7;\">"
            + "验证码 <b style=\"color:#6b7280;\">10 分钟</b>内有效，请勿泄露给任何人。<br/>"
            + "如非本人操作，请忽略本邮件，你的账号仍然安全。</p>"
            + "</td></tr>"
            // 页脚
            + "<tr><td style=\"padding:18px 32px;border-top:1px solid #f0f1f3;"
            + "font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;font-size:12px;color:#b6bcc4;\">"
            + "有余 · 记好每一笔，日子有余</td></tr>"
            + "</table></td></tr></table></body></html>";
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
