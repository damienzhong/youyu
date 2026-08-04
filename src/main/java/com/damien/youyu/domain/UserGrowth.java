package com.damien.youyu.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 用户成长档案，对应 {@code user_growth} 表。每用户至多一行，六个物化列可由
 * {@code growth_events} 与交易事实源完整重算（见 {@code GrowthSettlementService}）。
 *
 * <p><b>主键刻意不加 {@code @GeneratedValue}。</b>{@code user_id} 是业务 id（等于 {@code users.id}），
 * 由服务层以令牌用户 id 显式写入，不是数据库生成的代理键。加上 {@code @GeneratedValue} 会让 Hibernate
 * 认为该值由库分配，从而在 {@code persist} 时忽略我们设定的 {@code userId}，并要求表上存在一个自增列
 * ——而迁移脚本 {@code V32__user_growth.sql} 的 {@code user_id} 刻意<b>不带</b> {@code AUTO_INCREMENT}，
 * 两者直接冲突。</p>
 *
 * <p>代价是：由于 {@code @Id} 由应用赋值且不带 {@code @GeneratedValue}，{@code save()} 一个新实例时
 * Hibernate 走 {@code merge} 语义——先发一次 {@code SELECT} 判定该主键是 insert 还是 update。
 * <b>因此建档不走 {@code save()}，而走 {@code JdbcTemplate} 的
 * {@code INSERT ... ON DUPLICATE KEY UPDATE user_id = user_id}，避免 merge 语义的多余探测查询</b>，
 * 也顺手解决了并发建档的竞态（两个请求同时给同一用户建档时，后者退化为无副作用的更新而非唯一键冲突）。
 * 本实体在结算路径上只承担「加锁读 + 物化列写回」，不承担建档。</p>
 *
 * <p>表上刻意没有指向 {@code users(id)} 的外键：注销时由 {@code AccountDeletionService} 在同一事务内
 * 显式删除本表与 {@code growth_events} 的行，故 {@code userId} 同样是裸 {@link Long}，不映射成
 * {@code @OneToOne User}（关联映射会诱导后续开发者或 {@code ddl-auto} 补上外键）。</p>
 *
 * <p>{@code exp} 恒等于该用户全部成长事件 {@code exp_amount} 之和（由数据库聚合得出，不做内存累加），
 * {@code level} 恒等于 {@code GrowthLevelCurve.levelOf(exp)}；二者只增不减。</p>
 */
@Entity
@Table(name = "user_growth")
public class UserGrowth {

    /**
     * 用户 id，即主键，等于 {@code users.id}。
     *
     * <p>刻意不加 {@code @GeneratedValue}，原因见类级 Javadoc。</p>
     */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 经验值，等于该用户全部成长事件 {@code exp_amount} 之和；只增不减。 */
    @Column(name = "exp", nullable = false)
    private long exp;

    /** 等级 1–100，由 {@code exp} 按等级曲线换算；只增不减。 */
    @Column(name = "level", nullable = false)
    private int level;

    /** 累计记账天数，等于 {@code DAILY_RECORD} 事件条数。 */
    @Column(name = "total_record_days", nullable = false)
    private int totalRecordDays;

    /** 连续段长度；是否已中断在读取时按判定日实时判定，本列不因跨日自动归零。 */
    @Column(name = "current_streak_days", nullable = false)
    private int currentStreakDays;

    /** 历史最长连续天数，恒 {@code >= currentStreakDays}。 */
    @Column(name = "max_streak_days", nullable = false)
    private int maxStreakDays;

    /** 记账日历中的最大日期；日历为空时为 {@code null}。 */
    @Column(name = "last_record_date")
    private LocalDate lastRecordDate;

    /** 上次结算时刻，记账侧 60 秒节流的依据；从未结算时为 {@code null}。 */
    @Column(name = "last_settled_at")
    private LocalDateTime lastSettledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public UserGrowth() {
        // JPA / 服务层构造
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public long getExp() {
        return exp;
    }

    public void setExp(long exp) {
        this.exp = exp;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getTotalRecordDays() {
        return totalRecordDays;
    }

    public void setTotalRecordDays(int totalRecordDays) {
        this.totalRecordDays = totalRecordDays;
    }

    public int getCurrentStreakDays() {
        return currentStreakDays;
    }

    public void setCurrentStreakDays(int currentStreakDays) {
        this.currentStreakDays = currentStreakDays;
    }

    public int getMaxStreakDays() {
        return maxStreakDays;
    }

    public void setMaxStreakDays(int maxStreakDays) {
        this.maxStreakDays = maxStreakDays;
    }

    public LocalDate getLastRecordDate() {
        return lastRecordDate;
    }

    public void setLastRecordDate(LocalDate lastRecordDate) {
        this.lastRecordDate = lastRecordDate;
    }

    public LocalDateTime getLastSettledAt() {
        return lastSettledAt;
    }

    public void setLastSettledAt(LocalDateTime lastSettledAt) {
        this.lastSettledAt = lastSettledAt;
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
