package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 每用户一次性订阅剩余额度，对应 {@code reminder_quota} 表（迁移脚本 {@code V35__custom_reminder.sql}）。
 * 每用户至多一行：上报订阅授权累加、成功发送扣减、微信报额度不足（{@code 43101}）归零，取值恒 {@code [0,50]}。
 *
 * <p><b>主键刻意不加 {@code @GeneratedValue}。</b>{@code user_id} 是业务 id（等于 {@code users.id}），
 * 由服务层以令牌用户 id 显式写入，不是数据库生成的代理键（与 {@link UserGrowth} 同一取舍）。加上
 * {@code @GeneratedValue} 会让 Hibernate 认为该值由库分配，从而在 {@code persist} 时忽略我们设定的
 * {@code userId}，并要求表上存在自增列——而迁移脚本的 {@code user_id} 刻意<b>不带</b>
 * {@code AUTO_INCREMENT}，两者直接冲突。额度的原子增减走仓储层的 UPSERT / 条件更新语句，
 * 不走 {@code save()} 的 merge 语义，避免「先查后写」的并发丢更新。</p>
 *
 * <p>表上刻意没有指向 {@code users(id)} 的外键：注销时由 {@code AccountDeletionService} 在同一事务内
 * 显式删除本表的行，故 {@code userId} 同样是裸 {@link Long}，不映射成 {@code @OneToOne User}。</p>
 */
@Entity
@Table(name = "reminder_quota")
public class ReminderQuota {

    /**
     * 用户 id，即主键，等于 {@code users.id}。
     *
     * <p>刻意不加 {@code @GeneratedValue}，原因见类级 Javadoc。</p>
     */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 剩余一次性订阅额度，恒 {@code [0,50]}；由 {@code ck_reminder_quota_remaining} 在库侧兜底非负。 */
    @Column(name = "remaining", nullable = false)
    private int remaining;

    /** 首次授权上报时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 最后一次增减时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ReminderQuota() {
        // JPA / 服务层构造
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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
