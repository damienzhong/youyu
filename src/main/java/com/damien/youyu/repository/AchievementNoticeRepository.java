package com.damien.youyu.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.AchievementNotice;

/**
 * 成就播报游标仓库（{@code achievement_notices}，每用户至多一行，主键即 {@code user_id}）。
 *
 * <p>本仓库<b>刻意只暴露两件事</b>：继承而来的 {@code findById}（读回当前游标，无行时按 0 处理）
 * 与 {@link #deleteByUserId(Long)}（注销级联硬删，需求 11.1）。</p>
 *
 * <p><b>不提供任何单行写入方法</b>（既没有自定义的 upsert / update，也不应调用继承来的
 * {@code save}、{@code saveAndFlush}、{@code deleteById} 等写入方法）。原因是游标推进必须走
 * {@code AchievementQueryService} 里那条 {@code INSERT ... ON DUPLICATE KEY UPDATE} 配
 * {@code GREATEST} 的单条 SQL（见 design.md「5. 播报游标」）：那一条语句同时满足单调不减、
 * 重复确认幂等与并发终态取最大值三条不变式，且不需要行锁、不需要先读后写。一旦这里放出一个
 * 写入方法，就会有人写成「先 {@code findById} 读当前值、比一比、再 {@code save}」——那是一条
 * 典型的读改写竞态路径：两个并发确认请求各自读到旧值，后提交的那个可能把游标写回小的取值，
 * 单调性与并发终态两条不变式当场失效。另外 {@link AchievementNotice} 的 {@code @Id} 由应用赋值
 * 且刻意不带 {@code @GeneratedValue}，{@code save()} 一个新实例还会退化为 merge 语义（先发一次
 * {@code SELECT} 判定 insert / update），白多一次探测查询。</p>
 */
@Repository
public interface AchievementNoticeRepository extends JpaRepository<AchievementNotice, Long> {

    /**
     * 注销级联：硬删该用户的播报游标行（需求 11.1、11.4）。
     *
     * <p>以 {@code user_id} 等于该用户 id 为<b>唯一过滤条件</b>的 1 条硬删除语句，影响行数为 0 或 1。
     * 无行时影响行数 0 即视为成功，<b>删除前不做任何存在性预查询</b>，也不写任何软删除标记或归档副本。
     * 由 {@code AccountDeletionService} 在同一注销事务内、成长两表（{@code growth_events} 与
     * {@code user_growth}）删除之后、删 {@code users} 行之前调用；表上无外键，故数据库层对该顺序
     * 没有约束，固定在那一步只为使删除步骤可逐语句断言。</p>
     *
     * @param userId 用户 id（即主键）
     * @return 实际影响行数，0 或 1
     */
    @Modifying
    @Query("DELETE FROM AchievementNotice n WHERE n.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
