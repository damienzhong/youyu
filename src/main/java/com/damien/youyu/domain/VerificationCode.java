package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 邮箱验证码实体，对应 {@code verification_code} 表。
 *
 * <p>验证码存 MySQL（不引入 Redis），承载防刷四件套所需的状态：
 * 过期时刻（{@code expiresAt}）、单次消费标记（{@code consumed}）、
 * 失败累计次数（{@code attemptCount}）与来源 IP（{@code ip}，用于限流/审计）。
 * 一条记录唯一标识某邮箱在某用途下的一次发码。</p>
 *
 * @see EmailCodePurpose
 */
@Entity
@Table(name = "verification_code")
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 目标邮箱。 */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** 用途：LOGIN/BIND/DELETE，以大写字符串存储，与枚举名一致。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 16)
    private EmailCodePurpose purpose;

    /** 验证码（6 位数字）。 */
    @Column(name = "code", nullable = false, length = 8)
    private String code;

    /** 过期时刻（创建 + 10 分钟）。 */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 是否已消费/失效（单次消费）。 */
    @Column(name = "consumed", nullable = false)
    private boolean consumed = false;

    /** 校验失败累计次数（达到上限后失效）。 */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    /** 请求来源 IP（限流/审计），可空。 */
    @Column(name = "ip", length = 45)
    private String ip;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public VerificationCode() {
        // JPA / 服务层构造
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public EmailCodePurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(EmailCodePurpose purpose) {
        this.purpose = purpose;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void setConsumed(boolean consumed) {
        this.consumed = consumed;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
