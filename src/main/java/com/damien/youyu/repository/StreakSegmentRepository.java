package com.damien.youyu.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.StreakSegment;

/**
 * 历史连续区间仓库（{@code streak_segments}，段是记账日历的派生视图）。
 *
 * <p>本仓库<b>刻意不提供任何单行写入方法</b>（不提供自定义 upsert，也不应调用继承来的
 * {@code save} / {@code saveAndFlush} / {@code deleteById}）。段的插入与更新只能走
 * {@code StreakSegmentMaintainer} 里那条 {@code INSERT ... ON DUPLICATE KEY UPDATE} 批量语句——
 * 放出一个 {@code save} 就会有人写成「先 {@code findByUserIdAndStartDate} 查一下、没有就
 * {@code save}」，那是一条典型的读改写竞态路径：唯一约束 {@code uk_streak_segments_user_start}
 * 会在并发下把它变成异常而不是更新（沿用 {@link GrowthEventRepository} 与
 * {@link AchievementNoticeRepository} 的同一条立场）。因此本仓库只服务读取（对账读全量、
 * 历史分页、概览聚合）与两条硬删（注销级联、数据修复）。</p>
 *
 * <p>{@link #aggregateRaw} / {@link #endpointsRaw} 返回 {@code Object[]}，由服务层包成
 * {@code StreakAggregate} / {@code StreakEndpoints} 两个 record，其中日期列以
 * {@code getObject(LocalDate.class)} 逐字回读、<b>不经 {@code java.sql.Date}</b>
 * （那条路径会经默认时区换算致整日平移，与 {@code TransactionRepository} 读记账日的取舍一致）。</p>
 *
 * <p>两个查询走迁移脚本 {@code V34__streak.sql} 已建的唯一约束
 * {@code uk_streak_segments_user_start (user_id, start_date)} 与复合索引
 * {@code idx_streak_segments_user_days (user_id, days)}，<b>不新增任何索引</b>。</p>
 */
@Repository
public interface StreakSegmentRepository extends JpaRepository<StreakSegment, Long> {

    /** 对账用：该用户全部段，按起始日升序（段维护的 1 条读查询）。 */
    List<StreakSegment> findByUserIdOrderByStartDateAsc(Long userId);

    /** 历史区间分页：按起始日倒序（需求 6.3、6.4、6.5）。走 {@code uk_streak_segments_user_start} 反向扫描。 */
    Page<StreakSegment> findByUserIdOrderByStartDateDesc(Long userId, Pageable pageable);

    /**
     * 概览 Q2：段总数 + 天数合计 + 最大段天数，一条聚合语句（需求 7.10、7.11）。
     *
     * <p>{@code sumDays} 与 {@code maxDays} 除了作响应素材，还是不变式③④的在线校验材料。
     * {@code COALESCE} 使空表返回 0 而不是 {@code null}——空值一旦流到校验处，比较就恒为假、
     * 校验形同虚设（沿用 {@link GrowthEventRepository#sumExpByUserId} 的同一取舍）。</p>
     *
     * <p>返回类型为 {@code List<Object[]>} 而非 {@code Object[]}：JPQL 多列投影经 Hibernate 返回的是
     * 「行的集合」，每行才是 {@code Object[]}。若把方法声明成 {@code Object[]}，Spring Data 会把
     * <b>整个结果集</b>当作那个数组，得到 {@code Object[]{ Object[]{count,sum,max} }}（首元素是嵌套数组），
     * 服务层按标量取用即抛 {@code NumberFormatException}。聚合查询恒返回一行，故服务层取 {@code get(0)}。</p>
     *
     * @return 恰好一行 {@code [COUNT, SUM(days), MAX(days)]}；该用户无任何段时为 {@code [0, 0, 0]}
     */
    @Query("SELECT COUNT(s), COALESCE(SUM(s.days), 0), COALESCE(MAX(s.days), 0) "
            + "FROM StreakSegment s WHERE s.userId = :userId")
    Object[] aggregateRaw(@Param("userId") Long userId);

    /**
     * 概览 Q3：当前段与最长段，一条 {@code UNION ALL} 语句（需求 7.10、7.11）。
     *
     * <p>{@code kind=0} 取 {@code start_date} 最大者（当前段）；{@code kind=1} 取
     * {@code days DESC, start_date DESC} 首行（最长段，并列时取起始日最晚者）。</p>
     *
     * <p>用一条原生 {@code UNION ALL} 而非两个 JPQL 方法：需求 7.10 按「执行的 SQL 语句条数」计上界，
     * 两个方法就是两条语句，会把概览读查询从 3 条抬到 4 条；一条 UNION ALL 只占 1 条。</p>
     *
     * @return 至多两行，每行 {@code [kind, start_date, end_date, days]}；无段时为空列表
     */
    @Query(value = "(SELECT 0 AS kind, start_date, end_date, days FROM streak_segments "
            + "  WHERE user_id = :userId ORDER BY start_date DESC LIMIT 1) "
            + "UNION ALL "
            + "(SELECT 1 AS kind, start_date, end_date, days FROM streak_segments "
            + "  WHERE user_id = :userId ORDER BY days DESC, start_date DESC LIMIT 1)",
            nativeQuery = true)
    List<Object[]> endpointsRaw(@Param("userId") Long userId);

    /**
     * 数据修复路径：删除起始日不在重算结果中的段行（需求 4.15）。正常流程永不触发。
     *
     * @param ids 待删除的段 id 列表
     * @return 实际影响行数
     */
    @Modifying
    @Query("DELETE FROM StreakSegment s WHERE s.id IN :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);

    /**
     * 注销级联硬删（需求 8.8）。
     *
     * <p>无行时影响行数 0 即视为成功，<b>删除前不做存在性预查询</b>，也不写任何软删除标记或归档副本。
     * 由 {@code AccountDeletionService} 在同一注销事务内、成就播报游标删除之后、删 {@code users} 行
     * 之前调用；表上无外键，故数据库层对该顺序没有约束，固定在那一步只为使删除步骤可逐语句断言。</p>
     *
     * @return 实际影响行数
     */
    @Modifying
    @Query("DELETE FROM StreakSegment s WHERE s.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
