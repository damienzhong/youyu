package com.damien.youyu.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.ReminderQuota;

/**
 * 每用户订阅剩余额度仓库（{@code reminder_quota}，每用户至多一行，主键即 {@code user_id}）。
 *
 * <p>额度的每次增减都是<b>原子的条件更新</b>，不走 {@code save()} 的「先查后写」——需求 5.8 要求
 * 并发的上报授权与发送扣减不产生丢失更新（终值等于所有增减操作的净和）。因此本仓库刻意<b>不提供任何
 * 单行 {@code save} 式写入</b>，累加走 {@link #addCapped} 的 UPSERT，扣减走 {@link #decrementFloorZero}
 * 的条件更新，归零走 {@link #zero}，三者都在数据库层完成读改写，天然防丢更新。</p>
 *
 * <p>{@link ReminderQuota} 的 {@code @Id} 由应用赋值且不带 {@code @GeneratedValue}，若走 {@code save()}
 * 一个新实例会触发 merge 语义的探测 {@code SELECT} 且在并发建档时撞主键——{@link #addCapped} 的
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} 让后到者退化为无副作用的自更新，一并解决了这个竞态
 * （与 {@code UserGrowth} / {@code AchievementNotice} 同一取舍）。</p>
 */
@Repository
public interface ReminderQuotaRepository extends JpaRepository<ReminderQuota, Long> {

    /**
     * 读取剩余额度（需求 5.7）：无授权记录时返回空（服务层折算为 0）。
     *
     * <p>投影只取 {@code remaining} 标量，不整实体回读。</p>
     */
    @Query("select q.remaining from ReminderQuota q where q.userId = :userId")
    Optional<Integer> findRemaining(@Param("userId") Long userId);

    /**
     * 原子上限累加（需求 5.2、5.3、5.8）：不存在则插入 {@code min(delta,50)}，存在则
     * {@code remaining = min(remaining + delta, 50)}，均在同一条 SQL 内完成，防并发丢更新。
     *
     * <p>{@code LEAST(..., 50)} 施加累积上限 50（需求 5.3），避免额度无限增长。{@code ON DUPLICATE
     * KEY UPDATE} 在 MySQL 与 H2 {@code MODE=MySQL} 下均受支持。</p>
     */
    @Modifying
    @Query(nativeQuery = true, value =
            "INSERT INTO reminder_quota(user_id, remaining, created_at, updated_at) "
            + "VALUES(:userId, LEAST(:delta, 50), :now, :now) "
            + "ON DUPLICATE KEY UPDATE remaining = LEAST(remaining + :delta, 50), updated_at = :now")
    void addCapped(@Param("userId") Long userId, @Param("delta") int delta, @Param("now") LocalDateTime now);

    /**
     * 成功发送后扣减（需求 5.5）：{@code remaining} 减 1，且以 {@code remaining > 0} 条件保证不为负
     * （减 1 前已为 0 时该行不被更新、影响行数 0，取值保持 0）。
     *
     * @return 实际影响行数（0 表示无行或已为 0，未扣减）
     */
    @Modifying
    @Query("update ReminderQuota q set q.remaining = q.remaining - 1, q.updatedAt = :now "
            + "where q.userId = :userId and q.remaining > 0")
    int decrementFloorZero(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 归零（需求 5.6）：微信返回额度不足 / 用户拒收（如 {@code 43101}）时本地计数归零对齐。
     *
     * @return 实际影响行数（0 表示无行）
     */
    @Modifying
    @Query("update ReminderQuota q set q.remaining = 0, q.updatedAt = :now where q.userId = :userId")
    int zero(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 注销级联硬删（需求 9.11）：删除该用户的额度行。
     *
     * <p>无行时影响行数 0 即视为成功，删除前不做存在性预查询。由 {@code AccountDeletionService} 在同一
     * 注销事务内、删 {@code users} 行之前调用。</p>
     *
     * @return 实际影响行数
     */
    @Modifying
    @Query("delete from ReminderQuota q where q.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
