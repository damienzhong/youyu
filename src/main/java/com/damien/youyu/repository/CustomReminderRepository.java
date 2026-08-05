package com.damien.youyu.repository;

import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.ReminderFrequency;

/**
 * 自定义提醒配置仓库（{@code custom_reminders}，一行 = 用户创建的一条提醒）。
 *
 * <p>提醒的写入走继承来的 {@code save()}：{@link CustomReminder} 的 {@code @Id} 是自增代理键
 * （带 {@code @GeneratedValue}），新建即 {@code INSERT}、更新即脏检查 {@code UPDATE}，
 * 无「先查后写」的建档竞态。同一用户同一频率同一时间的去重由唯一约束
 * {@code uk_custom_reminders_user_freq_time} 在库侧承担（并发下后写者撞唯一键抛
 * {@code DataIntegrityViolationException}，服务层映射为 {@code REMINDER_DUPLICATE}），
 * 应用层的 {@link #existsByUserIdAndFrequencyAndRemindTime} 只作先行友好校验。</p>
 *
 * <p>调度器扫描走 {@link #findDue}，命中迁移脚本 {@code V35__custom_reminder.sql} 已建的复合索引
 * {@code idx_custom_reminders_enabled_time (enabled, remind_time)}；星期几→频率集合的映射在 Java 侧
 * 算好后作为 {@code IN} 参数传入，故本仓库不新增任何索引。</p>
 */
@Repository
public interface CustomReminderRepository extends JpaRepository<CustomReminder, Long> {

    /**
     * 去重先行校验（需求 1.5）：当前用户是否已存在与提交的频率与时间两项均相同的提醒
     * （无论其启用状态为真或假）。库侧兜底由唯一约束 {@code uk_custom_reminders_user_freq_time} 承担。
     */
    boolean existsByUserIdAndFrequencyAndRemindTime(Long userId, ReminderFrequency frequency, LocalTime remindTime);

    /** 上限校验（需求 1.6）：当前用户启用与停用的提醒总数（达到 10 条时拒绝创建第 11 条）。 */
    int countByUserId(Long userId);

    /** 列表查询（需求 7.1、7.2）：仅本人提醒，按 {@code created_at} 升序；无提醒时为空列表。 */
    List<CustomReminder> findByUserIdOrderByCreatedAtAsc(Long userId);

    /**
     * 归属校验（需求 7.3、7.5、8.8）：按提醒 id 与用户 id 同时匹配读取。
     *
     * <p>更新 / 删除时先经此方法定位：为空即「不存在或不属于本人」，服务层对两种情形返回完全相同的
     * {@code NOT_FOUND}，不泄漏他人提醒是否存在。</p>
     */
    Optional<CustomReminder> findByIdAndUserId(Long id, Long userId);

    /**
     * 调度扫描（需求 3.2、3.3、3.7）：启用中、频率命中当日、且触发时刻落在
     * {@code [start, end]} 闭区间（即 {@code [now-追补窗口, now]}）内的提醒。
     *
     * <p>{@code freqs} 由调度器按当日 {@code DayOfWeek} 算出的命中频率集合（{@code DAILY} 恒含，
     * 工作日含 {@code WEEKDAY}，周末含 {@code WEEKEND}）。停用提醒因 {@code enabled = true} 过滤不入选。</p>
     */
    @Query("select r from CustomReminder r where r.enabled = true and r.frequency in :freqs "
            + "and r.remindTime between :start and :end")
    List<CustomReminder> findDue(@Param("freqs") Collection<ReminderFrequency> freqs,
            @Param("start") LocalTime start, @Param("end") LocalTime end);

    /**
     * 注销级联硬删（需求 9.11）：删除该用户的全部提醒行。
     *
     * <p>无行时影响行数 0 即视为成功，删除前不做存在性预查询。由 {@code AccountDeletionService} 在同一
     * 注销事务内、删 {@code users} 行之前调用。</p>
     *
     * @return 实际影响行数
     */
    @Modifying
    @Query("delete from CustomReminder r where r.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
