package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 成长事件，对应 {@code growth_events} 表。只追加表：经验事件与徽章共用，一旦写入不改不删
 * （删交易 / 清回收站 / 改预算 / 被邀请人注销一律不扣经验、不熄灭徽章），只在注销时被硬删。
 *
 * <p>幂等由 {@code (user_id, event_key)} 唯一索引在数据库层承担，不依赖应用层的「先查再写」。</p>
 *
 * <p><b>{@code userId} 刻意声明为裸 {@link Long}，不得改成 {@code @ManyToOne User}：</b></p>
 * <ol>
 *   <li>表上刻意没有任何指向 {@code users(id)} 的外键（需求 11.9，注销时由
 *       {@code AccountDeletionService} 在同一事务内显式删除）。映射成关联实体会诱导后续开发者
 *       ——以及测试环境的 {@code ddl-auto}——顺手补上外键，而外键会给注销路径再压一层删除顺序约束。</li>
 *   <li>本实体的读取路径（经验明细分页、徽章解锁时刻）完全不需要用户对象。关联映射在这里只会带来
 *       两样东西：翻页时逐行加载用户的 N+1 查询，以及 {@code user_id} 指向已删除行时的
 *       {@code EntityNotFoundException}。</li>
 * </ol>
 *
 * <p><b>{@code eventType} 用 {@link String} 而非 {@code @Enumerated}：</b>写入路径全部走
 * {@code JdbcTemplate} 批量语句，本实体只服务读取；用字符串可以在库里出现意外取值时仍然读得出来，
 * 而不是在映射阶段抛异常、连经验明细都打不开。取值集合的正确性由数据库侧的
 * {@code ck_growth_events_type}（区分大小写）与应用侧的 {@link GrowthEventType} 常量类共同保证。</p>
 *
 * <p>{@code @Table} 上声明唯一约束 {@code uk_growth_events_user_key} 与两个索引，与迁移脚本
 * {@code V32__user_growth.sql} 同名同列：生产由 Flyway 建表（{@code ddl-auto=validate} 不校验索引，
 * 故此声明对生产无影响），而测试环境的 H2 表结构由 Hibernate 依本实体生成——不声明这个唯一约束，
 * H2 上就没有唯一索引，「同一 {@code event_key} 重复写入」的幂等断言会静默变成两行都写入。</p>
 */
@Entity
@Table(name = "growth_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_growth_events_user_key",
                columnNames = {"user_id", "event_key"}),
        indexes = {
                @Index(name = "idx_growth_events_user_type", columnList = "user_id, event_type"),
                @Index(name = "idx_growth_events_user_id", columnList = "user_id, id")
        })
public class GrowthEvent {

    /** 自增主键，经验明细按其倒序翻页。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 用户 id。裸 id，无外键，无关联映射（原因见类级 Javadoc）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 事件类型，取值见 {@link GrowthEventType}；刻意用字符串而非枚举。 */
    @Column(name = "event_type", nullable = false, length = 16)
    private String eventType;

    /** 幂等键，如 {@code DAILY_RECORD:2025-06-01} / {@code BUDGET_MET:2025-05} / {@code BADGE:RECORD_100}。 */
    @Column(name = "event_key", nullable = false, length = 64)
    private String eventKey;

    /** 经验值，恒 {@code >= 0}；徽章行恒为 0。 */
    @Column(name = "exp_amount", nullable = false)
    private int expAmount;

    /** 写入时间；徽章的解锁时刻即此列。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public GrowthEvent() {
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

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public int getExpAmount() {
        return expAmount;
    }

    public void setExpAmount(int expAmount) {
        this.expAmount = expAmount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
