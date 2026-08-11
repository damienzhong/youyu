package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 预算提醒偏好与独立订阅额度，对应 {@code budget_reminder_settings} 表（迁移脚本
 * {@code V43__budget_reminder.sql}）。每用户至多一行：{@code enabled} 是预算提醒偏好（无记录视为开启），
 * {@code remaining} 是<b>独立于记账提醒</b>的预算提醒剩余订阅次数（上报授权累加、成功发送扣减、微信
 * {@code 43101} 归零，取值恒 {@code [0,50]}）。
 *
 * <p><b>主键刻意不加 {@code @GeneratedValue}</b>：{@code user_id} 是业务 id（等于 {@code users.id}），
 * 由服务层以令牌用户 id 显式写入，不是数据库生成的代理键（与 {@link ReminderQuota} / {@link UserGrowth}
 * 同一取舍）。额度的原子增减走仓储层的 UPSERT / 条件更新语句，不走 {@code save()} 的「先查后写」，
 * 避免并发丢更新。</p>
 *
 * <p>表上刻意没有指向 {@code users(id)} 的外键：注销时由 {@code AccountDeletionService} 在同一事务内
 * 按 {@code user_id} 显式删除，故 {@code userId} 是裸 {@link Long}，不映射成关联。</p>
 */
@Entity
@Table(name = "budget_reminder_settings")
public class BudgetReminderSetting {

    /** 用户 id，即主键，等于 {@code users.id}；刻意不加 {@code @GeneratedValue}（原因见类级 Javadoc）。 */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 预算提醒偏好：{@code true} 开启（无记录视为开启）、{@code false} 关闭（不纳入收件人）。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 预算提醒剩余订阅次数，恒 {@code [0,50]}；由 {@code ck_budget_reminder_settings_remaining} 库侧兜底非负。 */
    @Column(name = "remaining", nullable = false)
    private int remaining;

    /** 首次建档时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 最后一次偏好 / 额度更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public BudgetReminderSetting() {
        // JPA / 服务层构造
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRemaining() {
        return remaining;
    }

    public void setRemaining(int remaining) {
        this.remaining = remaining;
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
