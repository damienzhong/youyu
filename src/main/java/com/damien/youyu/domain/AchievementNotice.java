package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 成就播报游标，对应 {@code achievement_notices} 表（迁移脚本 {@code V33__achievement.sql}）。
 * 每用户至多一行，恰好 4 列，只记录该用户「已播报到哪一条成就事件」——即已播报过的最大
 * {@code growth_events.id}（其 {@code event_type = 'BADGE'}）。
 *
 * <p>待播报成就 = 该用户 {@code event_type = 'BADGE'} 且 {@code id} 大于本游标的成长事件；
 * 无游标行时按游标取值 0 处理。游标<b>只增不减</b>，因此播报语义是「至少一次」：确认丢失只会
 * 导致重播，绝不会漏播。本表刻意不存「已播报过哪些编码」这种集合——一个单调标量就够，
 * 而集合会带来读改写的竞态。</p>
 *
 * <p><b>主键刻意不加 {@code @GeneratedValue}</b>（与 {@link UserGrowth} 同构）。{@code user_id}
 * 是业务 id（等于 {@code users.id}），由服务层以令牌用户 id 显式写入，不是数据库生成的代理键。
 * 加上 {@code @GeneratedValue} 会让 Hibernate 认为该值由库分配，从而在 {@code persist} 时忽略
 * 我们设定的 {@code userId}，并要求表上存在一个自增列——而迁移脚本的 {@code user_id} 刻意
 * <b>不带</b> {@code AUTO_INCREMENT}（需求 10.1），两者直接冲突，
 * {@code ddl-auto=validate} 下应用会启动失败（需求 10.12）。</p>
 *
 * <p><b>因此推进游标不走 {@code save()}，而走 {@code JdbcTemplate} 的
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} 配 {@code GREATEST}</b>（见 design.md
 * 「5. 播报游标」）：一条 SQL 同时压住三条不变式——单调性（{@code GREATEST} 只会取大）、
 * 幂等性（重复确认传入 ≤ 当前值时两列都不变）与并发安全（终态恒为全部合法取值与原值的最大者，
 * 无需行锁、无需先读后写）。若改走 {@code save()}，由于 {@code @Id} 由应用赋值且不带
 * {@code @GeneratedValue}，Hibernate 会退化为 merge 语义（先 SELECT 再决定 insert / update），
 * 既多一次探测查询，又把「先读后写」的竞态重新引了回来。</p>
 *
 * <p>{@code userId} 声明为裸 {@link Long}，<b>不得映射为 {@code @ManyToOne User}</b>：表上刻意
 * 没有任何指向 {@code users(id)} 的外键（需求 10.4，与 {@code user_growth} 同一取舍——注销时由
 * {@code AccountDeletionService} 在同一事务内显式删除本行，需求 11.1）。关联映射会诱导后续开发者
 * ——以及测试环境的 {@code ddl-auto}——顺手补上外键，而外键只会给注销路径再压一层删除顺序约束。</p>
 *
 * <p>本实体只服务「读回当前游标」与注销时的硬删，故不提供任何写入便利方法。</p>
 */
@Entity
@Table(name = "achievement_notices")
public class AchievementNotice {

    /**
     * 用户 id，即主键，等于 {@code users.id}。
     *
     * <p>刻意不加 {@code @GeneratedValue}，原因见类级 Javadoc。</p>
     */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 已播报到的最大成就事件 id（{@code growth_events.id}）；只增不减，取值恒 {@code >= 0}
     * （数据库侧由 {@code ck_achievement_notices_event_id} 保证）。
     */
    @Column(name = "last_notified_event_id", nullable = false)
    private long lastNotifiedEventId;

    /** 创建时间，即该用户首次推进游标的服务端时刻。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 更新时间，仅在游标实际推进时同步更新（重复确认不推进，本列随之不变）。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AchievementNotice() {
        // JPA / 服务层构造
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public long getLastNotifiedEventId() {
        return lastNotifiedEventId;
    }

    public void setLastNotifiedEventId(long lastNotifiedEventId) {
        this.lastNotifiedEventId = lastNotifiedEventId;
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
