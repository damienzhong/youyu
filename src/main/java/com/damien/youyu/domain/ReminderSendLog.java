package com.damien.youyu.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 提醒发送记录，对应 {@code reminder_send_logs} 表（迁移脚本 {@code V35__custom_reminder.sql}）。
 * 一行 = 一次提醒发送尝试的落表结果，记录用户、提醒、触发日、发送结果与微信错误码。
 *
 * <p><b>幂等由唯一约束 {@code uk_reminder_send_logs_reminder_date} 构造性保证：</b>
 * {@code (reminder_id, trigger_date)} 同一提醒同一触发日至多一条发送记录（需求 6.5），
 * 不依赖调度器不重叠这种时序巧合。并发触发时后写入者撞唯一键、静默放弃本次（需求 6.6）。</p>
 *
 * <p><b>主键 {@code id} 带 {@code @GeneratedValue}</b>：迁移脚本的 {@code id} 声明为
 * {@code BIGINT NOT NULL AUTO_INCREMENT}，是自增代理键、不承载业务语义。</p>
 *
 * <p><b>{@code reminderId} 与 {@code userId} 均为裸 {@link Long}，不映射关联：</b>表上无任何外键
 * （与三表其余两张同一取舍）。删除提醒时不删其历史发送记录（发送记录是已发生事实，需求 7.6），
 * 注销时才由 {@code AccountDeletionService} 在同一事务内按 {@code user_id} 显式删除。</p>
 */
@Entity
@Table(name = "reminder_send_logs",
        uniqueConstraints = @UniqueConstraint(name = "uk_reminder_send_logs_reminder_date",
                columnNames = {"reminder_id", "trigger_date"}))
public class ReminderSendLog {

    /** 自增主键；发送记录的代理键，不承载业务语义。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 来源提醒 id。裸 id，无外键（删除提醒不删本记录）。 */
    @Column(name = "reminder_id", nullable = false)
    private Long reminderId;

    /** 用户 id。裸 id，无外键（注销时由服务层按 user_id 显式删除）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 触发日（{@code Asia/Shanghai} 自然日）；与 {@code reminderId} 构成唯一键。 */
    @Column(name = "trigger_date", nullable = false)
    private LocalDate triggerDate;

    /** 发送结果：{@code SENT} / {@code SKIPPED_NO_QUOTA} / {@code SKIPPED_STALE} / {@code FAILED}，以枚举名存储。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 24)
    private ReminderSendResult result;

    /** 文案变体：{@code DONE} / {@code NOT_YET}，记录本次选用的文案分支。 */
    @Column(name = "message_variant", nullable = false, length = 16)
    private String messageVariant;

    /** 微信 errcode：{@code SENT} 为 0，{@code SKIPPED_*} 为空，{@code FAILED} 为微信码或空。 */
    @Column(name = "wx_errcode")
    private Integer wxErrcode;

    /** 发送尝试时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ReminderSendLog() {
        // JPA / 服务层构造
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReminderId() {
        return reminderId;
    }

    public void setReminderId(Long reminderId) {
        this.reminderId = reminderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDate getTriggerDate() {
        return triggerDate;
    }

    public void setTriggerDate(LocalDate triggerDate) {
        this.triggerDate = triggerDate;
    }

    public ReminderSendResult getResult() {
        return result;
    }

    public void setResult(ReminderSendResult result) {
        this.result = result;
    }

    public String getMessageVariant() {
        return messageVariant;
    }

    public void setMessageVariant(String messageVariant) {
        this.messageVariant = messageVariant;
    }

    public Integer getWxErrcode() {
        return wxErrcode;
    }

    public void setWxErrcode(Integer wxErrcode) {
        this.wxErrcode = wxErrcode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
