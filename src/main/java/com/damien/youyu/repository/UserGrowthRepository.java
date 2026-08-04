package com.damien.youyu.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.UserGrowth;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

/**
 * 用户成长档案仓库（{@code user_growth}，每用户至多一行，主键即 {@code user_id}）。
 *
 * <p>本仓库<b>不承担建档</b>：{@link UserGrowth} 的 {@code @Id} 由应用赋值且刻意不带
 * {@code @GeneratedValue}，{@code save()} 一个新实例会走 merge 语义（先发一次 SELECT 判定
 * insert / update）。建档由 {@code GrowthSettlementService} 走 {@code JdbcTemplate} 的
 * {@code INSERT ... ON DUPLICATE KEY UPDATE user_id = user_id} 完成，既省掉探测查询，
 * 也顺手解决并发建档的竞态。本仓库只负责「加锁读」「物化列写回（由 JPA 脏检查发出 UPDATE）」
 * 与「注销时的硬删」。</p>
 */
@Repository
public interface UserGrowthRepository extends JpaRepository<UserGrowth, Long> {

    /**
     * 加行级写锁读取成长档案（需求 1.9：先加锁再更新）。
     *
     * <p>{@code jakarta.persistence.lock.timeout = 0} 使 Hibernate 的 {@code MySQLDialect} 把本查询
     * 渲染为 {@code SELECT ... FOR UPDATE NOWAIT}，即取不到锁<b>立即失败</b>（抛
     * {@code PessimisticLockingFailureException}）而不是阻塞等待。已在 MySQL
     * {@code 8.0.46-0ubuntu0.22.04.3} 上实测确认：持锁会话未提交时，第二个会话以
     * {@code ERROR 3572 ... NOWAIT is set} 立即返回；对照组普通 {@code FOR UPDATE} 配
     * {@code innodb_lock_wait_timeout = 3} 则确实等满 3 秒后以 {@code ERROR 1205} 失败
     * （见 design.md 「迁移脚本」小节实测结论 ④）。</p>
     *
     * <p><b>需求 9.16 的「500ms 内未取得写锁则放弃」不在这里、也无法在这里表达</b>，它由服务层
     * （{@code GrowthSettlementService.lockProfileWithBudget}）以<b>应用层墙钟预算 + 有限次退避重试</b>
     * 实现。原因是数据库侧给不出 500ms 这个粒度：{@code innodb_lock_wait_timeout} 的最小粒度是
     * <b>1 秒</b>——同一实例上 {@code SET SESSION innodb_lock_wait_timeout = 0} 被钳到 1（读回为 1），
     * {@code = 0.5} 直接报 {@code ERROR 1232 Incorrect argument type}；而 {@code SELECT ... FOR UPDATE}
     * 在 MySQL 8 上只有 {@code NOWAIT}（0 等待）与 {@code SKIP LOCKED} 两种非阻塞修饰，没有
     * 「等 N 毫秒」的语法。所以分工是固定的：{@code NOWAIT} 让每次尝试立即返回，
     * 服务层用墙钟决定还要不要再试。</p>
     *
     * <p>因此<b>不要</b>把这个 hint 删掉或改成非 0 值：删掉会让本查询退化为阻塞等待（最短 1 秒），
     * 一个长事务就能把连接占住，服务层的 500ms 预算随之失效。</p>
     *
     * @param userId 用户 id（即主键）
     * @return 档案行；不存在时为空（调用方应先以 ODKU 建档，故正常路径上不应为空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT g FROM UserGrowth g WHERE g.userId = :userId")
    Optional<UserGrowth> findForUpdateById(@Param("userId") Long userId);

    /**
     * 注销级联：硬删该用户的成长档案行（需求 12.11）。
     *
     * <p>无行时影响行数 0 即视为成功，<b>删除前不做任何存在性预查询</b>，也不写任何软删除或归档副本。
     * 由 {@code AccountDeletionService} 在删 {@code users} 行之前调用，且固定在
     * {@code GrowthEventRepository.deleteByUserId} 之后执行（顺序只为使删除步骤可逐语句断言）。</p>
     *
     * @return 实际影响行数，0 或 1
     */
    @Modifying
    @Query("DELETE FROM UserGrowth g WHERE g.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
