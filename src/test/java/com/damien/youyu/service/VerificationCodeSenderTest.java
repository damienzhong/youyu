package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.damien.youyu.domain.EmailCodePurpose;

/**
 * 验证码发送器与其注入选择逻辑的示例/边界单元测试（关联需求 1.5、1.6）。
 *
 * <p>不使用桩：SMTP 分支用真实的 {@link JavaMailSenderImpl}（离线，指向不可达主机以触发真实
 * 发送失败），降级分支用真实的 {@link LoggingVerificationCodeSender}，选择逻辑用真实的
 * {@link VerificationCodeSenderConfig}。</p>
 */
class VerificationCodeSenderTest {

    private final VerificationCodeSenderConfig config = new VerificationCodeSenderConfig();

    // ---- 选择逻辑（VerificationCodeSenderConfig） ----

    @Test
    void selectsLoggingSenderWhenNoMailSenderBean() {
        VerificationCodeSender sender =
                config.verificationCodeSender(providerOf(null), "", "");
        assertThat(sender).isInstanceOf(LoggingVerificationCodeSender.class);
    }

    @Test
    void selectsLoggingSenderWhenHostOrUsernameBlank() {
        JavaMailSender mailSender = new JavaMailSenderImpl();

        // host 为占位空值 → 降级
        assertThat(config.verificationCodeSender(providerOf(mailSender), "  ", "user@qq.com"))
                .isInstanceOf(LoggingVerificationCodeSender.class);

        // username 为占位空值 → 降级
        assertThat(config.verificationCodeSender(providerOf(mailSender), "smtp.qq.com", ""))
                .isInstanceOf(LoggingVerificationCodeSender.class);
    }

    @Test
    void selectsSmtpSenderWhenFullyConfigured() {
        JavaMailSender mailSender = new JavaMailSenderImpl();
        VerificationCodeSender sender = config.verificationCodeSender(
                providerOf(mailSender), "smtp.qq.com", "user@qq.com");
        assertThat(sender).isInstanceOf(SmtpVerificationCodeSender.class);
    }

    // ---- 降级发送器（LoggingVerificationCodeSender） ----

    @Test
    void loggingSenderDoesNotThrow() {
        VerificationCodeSender sender = new LoggingVerificationCodeSender();
        // 降级实现只打日志，任何用途都不应抛异常。
        sender.send("someone@example.com", "123456", EmailCodePurpose.LOGIN);
        sender.send("someone@example.com", "654321", EmailCodePurpose.BIND);
        sender.send("someone@example.com", "111222", EmailCodePurpose.DELETE);
    }

    // ---- SMTP 发送器（SmtpVerificationCodeSender） ----

    @Test
    void smtpSenderSurfacesFailureAsMailException() {
        // 真实 JavaMailSenderImpl 指向不可达主机：send 必失败。
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost("invalid.smtp.localhost.test");
        impl.setPort(2525);
        SmtpVerificationCodeSender sender = new SmtpVerificationCodeSender(impl, "from@qq.com");

        // 需求 1.5：发送失败必须抛出（不静默吞掉），且为 MailException 便于服务层翻译。
        assertThatThrownBy(() ->
                sender.send("to@example.com", "123456", EmailCodePurpose.LOGIN))
                .isInstanceOf(MailException.class);
    }

    /** 构造一个只返回给定实例（可为 null）的 ObjectProvider。 */
    private static ObjectProvider<JavaMailSender> providerOf(JavaMailSender instance) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject(Object... args) {
                return instance;
            }

            @Override
            public JavaMailSender getObject() {
                return instance;
            }

            @Override
            public JavaMailSender getIfAvailable() {
                return instance;
            }

            @Override
            public JavaMailSender getIfUnique() {
                return instance;
            }
        };
    }
}
