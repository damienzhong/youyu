package com.damien.youyu.domain;

import java.time.LocalDateTime;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 自定义提醒配置，对应 {@code custom_reminders} 表（迁移脚本 {@code V35__custom_reminder.sql}）。
 * 一行 = 用户创建的一条提醒，由频率、提醒时间与启用状态三项确定。
 *
 * <p><b>主键 {@code id} 带 {@code @GeneratedValue}</b>（与 {@link StreakSegment} / {@link GrowthEvent}
 * 同构，与 {@link UserGrowth} / {@link ReminderQuota} 的应用赋值主键刻意不同）：迁移脚本的
 * {@code reminder_id} 声明为 {@code BIGINT NOT NULL AUTO_INCREMENT}，是自增代理键，故此处该带
 * {@code @GeneratedValue}。同一用户同一频率同一时间的去重由唯一约束
 * {@code uk_custom_reminders_user_freq_time} 保护。</p>
 *
 * <p><b>{@code userId} 声明为裸 {@link Long}，不得映射为 {@code @ManyToOne User}：</b>表上刻意
 * 没有任何指向 {@code users(id)} 的外键（需求 9.10，与 {@code user_growth} / {@code growth_events} /
 * {@code streak_segments} 同一取舍——注销时由 {@code AccountDeletionService} 在同一事务内显式删除
 * 本用户的提醒行，需求 9.11）。关联映射会诱导后续开发者——以及测试环境的 {@code ddl-auto}——
 * 顺手补上外键，而外键只会给注销路径再压一层删除顺序约束。</p>
 *
 * <p>{@code @Table} 上声明唯一约束 {@code uk_custom_reminders_user_freq_time} 与索引
 * {@code idx_custom_reminders_enabled_time}，与迁移脚本同名同列：生产由 Flyway 建表
 * （{@code ddl-auto=validate} 不校验索引），而测试环境的 H2 表结构由 Hibernate 依本实体生成——
 * 不声明这个唯一约束，H2 上就没有唯一索引，「同一频率同一时间不重复」的去重断言会静默失效。</p>
 */
@Entity
@Table(name = "custom_reminders",
        uniqueConstraints = @UniqueConstraint(name = "uk_custom_reminders_user_freq_time",
                columnNames = {"user_id", "frequency", "remind_time"}),
        indexes = @Index(name = "idx_custom_reminders_enabled_time", columnList = "enabled, remind_time"))
public class CustomReminder {

    /** 自增主键，映射 {@code reminder_id} 列；提醒的自增代理键，对外即 {@code reminderId}。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reminder_id")
    private Long id;

    /** 用户 id。裸 id，无外键，无关联映射（原因见类级 Javadoc）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 提醒频率：{@code DAILY} / {@code WEEKDAY} / {@code WEEKEND}，以枚举名存储。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 16)
    private ReminderFrequency frequency;

    /** 每日触发时刻（分钟粒度，以 {@code Asia/Shanghai} 口径解释）。 */
    @Column(name = "remind_time", nullable = false)
    private LocalTime remindTime;

    /** 是否启用；停用的提醒保留配置但不参与触发。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CustomReminder() {
        // JPA / 服务层构造
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public ReminderFrequency getFrequency() {
        return frequency;
    }

    public void setFrequency(ReminderFrequency frequency) {
        this.frequency = frequency;
    }

    public LocalTime getRemindTime() {
        return remindTime;
    }

    public void setRemindTime(LocalTime remindTime) {
        this.remindTime = remindTime;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
