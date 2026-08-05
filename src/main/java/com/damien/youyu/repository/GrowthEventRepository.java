package com.damien.youyu.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.GrowthEvent;

/**
 * 成长事件仓库（{@code growth_events}，只追加表）。
 *
 * <p>本仓库<b>只读</b>（除注销级联的硬删外）：事件写入全部走 {@code GrowthSettlementService} 里
 * {@code JdbcTemplate} 的 {@code INSERT ... ON DUPLICATE KEY UPDATE id = id} 批量语句，
 * 由唯一索引 {@code uk_growth_events_user_key} 承担幂等，不经 JPA 的 {@code save()}。
 * 因此这里刻意<b>不提供任何单行写入方法</b>，以免有人绕开批量语句写出「先查再写」的竞态路径。</p>
 *
 * <p>JPQL 里的 {@code event_type} 字面量（{@code 'DAILY_RECORD'} / {@code 'BADGE'}）必须与
 * {@link com.damien.youyu.domain.GrowthEventType} 的常量保持一致：JPQL 无法引用 Java 常量，
 * 故只能写字面量；改动类型取值时这两处要一起改。</p>
 */
@Repository
public interface GrowthEventRepository extends JpaRepository<GrowthEvent, Long> {

    /**
     * 该用户的全部事件键，一次读完（需求 1.5、3.8、8.2）。
     *
     * <p>结算路径上这一个查询同时服务三件事：事件组装前的幂等过滤、徽章是否已写入的判定、
     * 以及预算达成月份的跳过判定。<b>不要为这三处各写一个带过滤条件的查询</b>——事件行数以
     * 「累计记账天数 + 少量里程碑」为量级，一次全量读回比三次带 {@code event_type} /
     * {@code LIKE} 过滤的查询更省往返，也让「组装出的键是否已存在」这件事只有一处判定依据。</p>
     *
     * @return 事件键集合，顺序不作保证（调用方应放进 {@code Set} 使用）
     */
    @Query("SELECT e.eventKey FROM GrowthEvent e WHERE e.userId = :userId")
    List<String> findEventKeysByUserId(@Param("userId") Long userId);

    /**
     * 经验值合计：{@code SUM(exp_amount)} 的<b>数据库聚合</b>（需求 1.2）。
     *
     * <p><b>不得改为内存累加</b>（既不能改成「旧 {@code exp} + 本次新增经验」，也不能改成把事件读进
     * 内存后 {@code stream().sum()}）。需求 1.2 要求「档案的 {@code exp} 恒等于该用户全部成长事件
     * {@code exp_amount} 之和」，只有每次都从事件表重新聚合才能让这条等式<b>构造性成立</b>：
     * 内存累加一旦漏加、重复加，或在并发结算下基于过期的旧值累加，误差就永久留在物化列里，
     * 而且此后每次结算都以错误值为基准，没有任何自愈路径。聚合的代价是一次索引扫描，
     * 换来的是「物化列可由事实源完整重算」这个不变式（Property 2、Property 5 直接锁这条）。</p>
     *
     * @return 经验合计；该用户无任何事件时返回 0（由 {@code COALESCE} 保证，不返回 {@code null}）
     */
    @Query("SELECT COALESCE(SUM(e.expAmount), 0) FROM GrowthEvent e WHERE e.userId = :userId")
    long sumExpByUserId(@Param("userId") Long userId);

    /**
     * 完整记账日历的事件键（{@code 'DAILY_RECORD:yyyy-MM-dd'}），<b>按日期升序</b>（需求 4.13、10.3）。
     *
     * <p>返回<b>事件键字符串</b>而不是 {@code LocalDate}：{@code YYYY-MM-DD} 定长零填充，
     * 其字典序与日期序完全一致，因此 {@code ORDER BY e.eventKey ASC} 就是日期升序，
     * 无需在 SQL 里做 {@code SUBSTRING} + 类型转换（H2 与 MySQL 在这一点上行为一致，
     * 而日期函数的行为差异会让核心不变式在两种库上失去同一份验证依据）。
     * 服务层再 {@code LocalDate.parse(key.substring("DAILY_RECORD:".length()))} 转成日期，
     * 交给 {@code GrowthCalendarService.scan} 这个纯函数扫描。</p>
     *
     * <p><b>解析失败必须抛异常，不得静默跳过。</b>库里出现畸形键说明写入路径有缺陷（键的拼装只有
     * 结算路径一处），此时跳过这一行只会让累计天数、连续天数悄悄少算——档案看起来正常、数值却是错的，
     * 且每次结算都重复少算，永远不会自愈。让它抛出来会使这次结算整体回滚（{@code REQUIRES_NEW}
     * 事务）并在日志里留下证据，记账本身不受影响，问题因而可见可查。</p>
     *
     * @return 该用户全部 {@code DAILY_RECORD} 事件键，按键升序（即日期升序）；无记账日时为空列表
     */
    @Query("SELECT e.eventKey FROM GrowthEvent e "
            + "WHERE e.userId = :userId AND e.eventType = 'DAILY_RECORD' ORDER BY e.eventKey ASC")
    List<String> findDailyRecordKeys(@Param("userId") Long userId);

    /**
     * 该用户已点亮的徽章行（需求 8.6：{@code created_at} 即徽章的解锁时刻）。
     *
     * <p>走复合索引 {@code idx_growth_events_user_type}。返回整个实体而不是 {@code event_key}，
     * 因为概览响应需要解锁时刻；徽章编码由 {@code event_key} 去掉 {@code BADGE:} 前缀得到。
     * 过滤条件只有 {@code event_type = 'BADGE'}：{@code BADGE:} 是徽章的独占命名空间，
     * 与同名经验事件键（{@code FIRST_RECORD} / {@code STREAK_7} / {@code STREAK_30} /
     * {@code BUDGET_MET}）双向隔离，因此这里不会捞到经验事件行，
     * 反之判定 {@code BUDGET_MET} 徽章条件时也只看 {@code event_type = 'BUDGET_MET'} 的行。</p>
     *
     * @return 徽章事件行，顺序不作保证（展示顺序由 {@code GrowthBadgeCatalog} 决定）
     */
    @Query("SELECT e FROM GrowthEvent e WHERE e.userId = :userId AND e.eventType = 'BADGE'")
    List<GrowthEvent> findBadgeEvents(@Param("userId") Long userId);

    /**
     * 待播报成就：{@code event_type = 'BADGE'} 且 {@code id} 大于播报游标的行，按 {@code id} 升序
     * （需求 5.2、5.4）。
     *
     * <p>调用方传 {@code PageRequest.of(0, 10)} 取 {@code id} 最小的 10 项——先解锁的先播报。
     * 走既有复合索引 {@code idx_growth_events_user_type (user_id, event_type)}，<b>不新增任何索引</b>：
     * {@code id} 是主键，二级索引叶子上天然带着它，因此 {@code id > :cursor} 的过滤与 {@code ORDER BY
     * id ASC} 的排序都能在索引内完成。</p>
     *
     * <p>JPQL 里的 {@code 'BADGE'} 字面量必须与 {@link com.damien.youyu.domain.GrowthEventType#BADGE}
     * 保持一致：JPQL 无法引用 Java 常量，只能写字面量；改动常量取值时这两处必须一起改。</p>
     *
     * <p>纯只读：本查询<b>不推进游标</b>（游标推进只走 {@code AchievementQueryService} 的单条 ODKU），
     * 因此在无新解锁、未确认的间隙里可重复读取并得到相同的项与顺序（需求 5.17）。</p>
     *
     * @param cursor 播报游标 {@code last_notified_event_id}；该用户无游标行时按 0 传入（需求 5.3）
     * @param pageable 只用于传每页条数，其中的 {@code Sort} 会被 JPQL 的 {@code ORDER BY} 覆盖
     * @return 待播报的徽章事件行，按 {@code id} 升序，至多 {@code pageable} 指定的条数
     */
    @Query("SELECT e FROM GrowthEvent e WHERE e.userId = :userId AND e.eventType = 'BADGE' "
            + "AND e.id > :cursor ORDER BY e.id ASC")
    List<GrowthEvent> findPendingBadgeEvents(@Param("userId") Long userId,
                                            @Param("cursor") long cursor, Pageable pageable);

    /**
     * 待播报总条数：<b>截断前</b>的全部待播报条数（需求 5.5）。
     *
     * <p>与 {@link #findPendingBadgeEvents} 的过滤条件逐字相同，但<b>不受分页截断影响</b>——
     * 待播报多于 10 项时，列表只返回 10 项，而这里给的是全部条数，客户端因此知道还有后续。
     * 不用 {@code findPendingBadgeEvents(...).size()} 代替：那是截断<b>后</b>的项数，
     * 会把「还有 30 项待播报」显示成「还有 10 项」。</p>
     *
     * <p>走既有索引 {@code idx_growth_events_user_type}，<b>不新增任何索引</b>。JPQL 里的
     * {@code 'BADGE'} 字面量必须与 {@link com.damien.youyu.domain.GrowthEventType#BADGE} 保持一致
     * （JPQL 无法引用 Java 常量）。</p>
     *
     * @param cursor 播报游标；无游标行时按 0 传入
     * @return 待播报条数；游标已等于最大 {@code BADGE} 事件 {@code id} 时返回 0
     */
    @Query("SELECT COUNT(e) FROM GrowthEvent e WHERE e.userId = :userId "
            + "AND e.eventType = 'BADGE' AND e.id > :cursor")
    long countPendingBadgeEvents(@Param("userId") Long userId, @Param("cursor") long cursor);

    /**
     * 该用户最大 {@code BADGE} 事件 {@code id}，是 {@code lastEventId} <b>上界校验的唯一依据</b>
     * （需求 5.6、5.13、5.14）。
     *
     * <p>{@code COALESCE(MAX(id), 0)} 使该用户没有任何 {@code BADGE} 行时返回 0 而不是 {@code null}：
     * 上界因此恒为一个可比较的数值，「零成就用户以 {@code lastEventId = 0} 确认」这条（需求 5.13）
     * 无需在服务层再补空值分支。<b>不得返回包装类型或 {@code null}</b>——空值一旦流到校验处，
     * 越界判定就会退化成「与 {@code null} 比较恒为假」，任意大的 {@code lastEventId} 都能通过。</p>
     *
     * <p>走既有索引 {@code idx_growth_events_user_type}，<b>不新增任何索引</b>。JPQL 里的
     * {@code 'BADGE'} 字面量必须与 {@link com.damien.youyu.domain.GrowthEventType#BADGE} 保持一致
     * （JPQL 无法引用 Java 常量）。</p>
     *
     * @return 最大徽章事件 {@code id}；无 {@code BADGE} 行时为 0
     */
    @Query("SELECT COALESCE(MAX(e.id), 0) FROM GrowthEvent e "
            + "WHERE e.userId = :userId AND e.eventType = 'BADGE'")
    long maxBadgeEventId(@Param("userId") Long userId);

    /**
     * 经验明细分页：按 {@code id} <b>倒序</b>翻页（需求 10.3、10.5）。
     *
     * <p>走复合索引 {@code idx_growth_events_user_id (user_id, id)} 的反向扫描——索引列刻意全部升序声明，
     * InnoDB 反向扫描升序索引即可满足 {@code ORDER BY id DESC}，无需 {@code DESC} 索引。
     * 排序键固定在方法名里而不交给 {@code Pageable}：{@code created_at} 在同一次结算内对所有事件
     * 取同一个时刻（单次结算只读一次时钟），按它排序会让同批事件的相对顺序不确定、翻页可能重复或漏行；
     * 自增 {@code id} 严格全序，是唯一稳定的分页游标。</p>
     *
     * @param pageable 只用于传页码与每页条数，其中的 {@code Sort} 会被方法名的排序覆盖
     * @return 当页事件与总条数（{@code Page.getTotalElements()} 不受分页影响，即需求 10.5 的总条数）
     */
    Page<GrowthEvent> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /** 该用户事件总条数，不受分页影响（需求 10.5）。 */
    long countByUserId(Long userId);

    /**
     * 注销级联：硬删该用户的全部成长事件（需求 12.11）。
     *
     * <p>无行时影响行数 0 即视为成功，<b>删除前不做任何存在性预查询</b>，也不写任何软删除或归档副本。
     * 由 {@code AccountDeletionService} 在删 {@code users} 行之前调用，且固定在
     * {@link UserGrowthRepository#deleteByUserId} <b>之前</b>执行（顺序只为使删除步骤可逐语句断言；
     * 两表均无外键，删除顺序在数据库层没有约束）。</p>
     *
     * @return 实际影响行数
     */
    @Modifying
    @Query("DELETE FROM GrowthEvent e WHERE e.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
