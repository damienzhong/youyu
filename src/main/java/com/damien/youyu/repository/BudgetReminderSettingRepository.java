package com.damien.youyu.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.BudgetReminderSetting;

/**
 * 预算提醒偏好与独立订阅额度仓库（{@code budget_reminder_settings}，每用户至多一行，主键即 {@code user_id}）。
 *
 * <p>额度的每次增减都是<b>原子的条件更新</b>，不走 {@code save()} 的「先查后写」——需求 6.5 要求
 * 并发的上报授权与发送扣减不产生丢失更新（终值等于所有增减操作的净和）。累加走 {@link #addCapped}
 * 的 UPSERT（封顶 50），扣减走 {@link #decrementFloorZero}（不小于 0），归零走 {@link #zeroOut}，
 * 三者都在数据库层完成读改写，天然防丢更新——与 {@code ReminderQuotaRepository} 同一范式，但作用于
 * <b>独立</b>的预算提醒额度，不与记账提醒的 {@code reminder_quota} 混用（需求 6.6、9.6）。</p>
 */
@Repository
public interface BudgetReminderSettingRepository extends JpaRepository<BudgetReminderSetting, Long> {

    /** 读取某用户的设置行（getStatus / 收件人筛选复用）；无记录返回空（服务层折算缺省 {@code {true,0}}）。 */
    Optional<BudgetReminderSetting> findByUserId(Long userId);

    /** 只读投影：读取剩余订阅次数；无记录返回空（服务层折算为 0）。 */
    @Query("select s.remaining from BudgetReminderSetting s where s.userId = :userId")
    Optional<Integer> findRemaining(@Param("userId") Long userId);

    /**
     * 更新偏好（需求 1.4）：存在则置 {@code enabled} 与 {@code updated_at}；不存在则以缺省额度 0 建档。
     *
     * <p>{@code enabled} 传 {@code 1/0}（TINYINT），在 MySQL 与 H2 {@code MODE=MySQL} 下均受支持。
     * 用 UPSERT 而非 {@code save()}：{@code @Id} 由应用赋值且不带 {@code @GeneratedValue}，
     * {@code save()} 会先探测 {@code SELECT} 且在并发建档时撞主键（与 {@code ReminderQuota} 同一取舍）。</p>
     */
    @Modifying
    @Query(nativeQuery = true, value =
            "INSERT INTO budget_reminder_settings(user_id, enabled, remaining, created_at, updated_at) "
            + "VALUES(:userId, :enabled, 0, :now, :now) "
            + "ON DUPLICATE KEY UPDATE enabled = :enabled, updated_at = :now")
    void upsertEnabled(@Param("userId") Long userId, @Param("enabled") int enabled,
            @Param("now") LocalDateTime now);

    /**
     * 原子上限累加（需求 6.2、6.3、6.5）：不存在则插入 {@code min(delta,50)}，存在则
     * {@code remaining = min(remaining + delta, 50)}，均在同一条 SQL 内完成，防并发丢更新。
     * 新建时 {@code enabled} 缺省为开启（{@code 1}）。
     */
    @Modifying
    @Query(nativeQuery = true, value =
            "INSERT INTO budget_reminder_settings(user_id, enabled, remaining, created_at, updated_at) "
            + "VALUES(:userId, 1, LEAST(:delta, 50), :now, :now) "
            + "ON DUPLICATE KEY UPDATE remaining = LEAST(remaining + :delta, 50), updated_at = :now")
    void addCapped(@Param("userId") Long userId, @Param("delta") int delta, @Param("now") LocalDateTime now);

    /**
     * 成功发送后扣减（需求 4.2）：{@code remaining} 减 1，且以 {@code remaining > 0} 条件保证不为负。
     *
     * @return 实际影响行数（0 表示无行或已为 0，未扣减）
     */
    @Modifying
    @Query("update BudgetReminderSetting s set s.remaining = s.remaining - 1, s.updatedAt = :now "
            + "where s.userId = :userId and s.remaining > 0")
    int decrementFloorZero(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 归零（需求 4.6）：微信返回额度不足 / 用户拒收（如 {@code 43101}）时本地计数归零对齐。
     *
     * @return 实际影响行数（0 表示无行）
     */
    @Modifying
    @Query("update BudgetReminderSetting s set s.remaining = 0, s.updatedAt = :now where s.userId = :userId")
    int zeroOut(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 注销级联硬删（需求 8.8）：删除该用户的设置行。
     *
     * <p>无行时影响行数 0 即视为成功，删除前不做存在性预查询。由 {@code AccountDeletionService} 在同一
     * 注销事务内、删 {@code users} 行之前调用。</p>
     *
     * @return 实际影响行数
     */
    @Modifying
    @Query("delete from BudgetReminderSetting s where s.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
