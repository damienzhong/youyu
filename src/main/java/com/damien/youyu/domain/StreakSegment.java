package com.damien.youyu.domain;

import java.time.LocalDate;
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
 * 历史连续区间（streak segment），对应 {@code streak_segments} 表（迁移脚本 {@code V34__streak.sql}）。
 * 一行 = 记账日历中一段极大的连续自然日区间（起始日、结束日、段天数）。
 *
 * <p><b>段是记账日历的派生视图，不是第二套事实源。</b>它唯一的输入是 {@code growth_events} 里
 * {@code event_type = 'DAILY_RECORD'} 的日期集合，段边界由 {@code GrowthCalendarService.segments}
 * 纯函数算出。落表只为让历史区间能走索引分页回看（每次请求重扫全量日历再在内存里分页，
 * 成本随历史线性增长且无法走索引）。删掉这张表，成长体系与成就系统的全部行为原样成立。</p>
 *
 * <p><b>段的写入只走 {@code StreakSegmentMaintainer} 的
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} 批量语句，不走 {@code save()}。</b>
 * 段维护每次结算做一次「全量重算 + 差异写入」的对账，值幂等由 {@code (user_id, start_date)}
 * 唯一索引在数据库层承担；放出 {@code save} 会诱导「先查后写」的竞态路径。因此本实体只服务
 * 读取（对账读全量、历史分页、概览聚合）与注销时的硬删，不提供任何单行写入便利方法
 * （沿用 {@link GrowthEvent} / {@link AchievementNotice} 的同一立场）。</p>
 *
 * <p><b>主键 {@code id} 带 {@code @GeneratedValue}</b>（与 {@link GrowthEvent} 同构，
 * 与 {@link UserGrowth} / {@link AchievementNotice} 的应用赋值主键刻意不同）：本表 {@code id}
 * 是自增代理键、不承载业务语义（段是派生数据），迁移脚本的 {@code id} 声明为
 * {@code BIGINT NOT NULL AUTO_INCREMENT}，故此处该带 {@code @GeneratedValue}。段序列的天然主键
 * 是 {@code (user_id, start_date)}，由唯一约束 {@code uk_streak_segments_user_start} 保护。</p>
 *
 * <p><b>{@code userId} 声明为裸 {@link Long}，不得映射为 {@code @ManyToOne User}：</b>表上刻意
 * 没有任何指向 {@code users(id)} 的外键（需求 8.7，与 {@code user_growth} / {@code growth_events} /
 * {@code achievement_notices} 同一取舍——注销时由 {@code AccountDeletionService} 在同一事务内显式
 * 删除本用户的段行，需求 8.8）。关联映射会诱导后续开发者——以及测试环境的 {@code ddl-auto}——
 * 顺手补上外键，而外键只会给注销路径再压一层删除顺序约束。</p>
 *
 * <p>{@code @Table} 上声明唯一约束 {@code uk_streak_segments_user_start} 与索引
 * {@code idx_streak_segments_user_days}，与迁移脚本 {@code V34__streak.sql} 同名同列：生产由
 * Flyway 建表（{@code ddl-auto=validate} 不校验索引，故此声明对生产无影响），而测试环境的 H2
 * 表结构由 Hibernate 依本实体生成——不声明这个唯一约束，H2 上就没有唯一索引，
 * ODKU 冲突转更新与「同一 {@code (user_id, start_date)} 至多一段」的幂等断言会静默失效。</p>
 */
@Entity
@Table(name = "streak_segments",
        uniqueConstraints = @UniqueConstraint(name = "uk_streak_segments_user_start",
                columnNames = {"user_id", "start_date"}),
        indexes = @Index(name = "idx_streak_segments_user_days", columnList = "user_id, days"))
public class StreakSegment {

    /** 自增主键；段是派生数据，{@code id} 不承载业务语义。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 用户 id。裸 id，无外键，无关联映射（原因见类级 Javadoc）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 该连续区间的起始日（前一日不在记账日历中）。 */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** 该连续区间的结束日（次日不在记账日历中）。 */
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 段天数，等于结束日与起始日之差加 1，恒 {@code >= 1}。 */
    @Column(name = "days", nullable = false)
    private int days;

    /** 该段首次落表时间；ODKU 冲突转更新时本列不动。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 该段最后一次延长时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public StreakSegment() {
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

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
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
