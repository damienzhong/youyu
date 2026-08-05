package com.damien.youyu.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.ReminderSendLog;

/**
 * 提醒发送记录仓库（{@code reminder_send_logs}，一行 = 一次发送尝试的落表结果）。
 *
 * <p>发送记录的写入走继承来的 {@code save()}：{@link ReminderSendLog} 的 {@code @Id} 是自增代理键。
 * 幂等由唯一约束 {@code uk_reminder_send_logs_reminder_date (reminder_id, trigger_date)} 构造性保证
 * （需求 6.5）——同一提醒同一触发日至多一条记录，并发触发时后写者撞唯一键抛
 * {@code DataIntegrityViolationException}，由发送编排静默放弃本次（需求 6.6）。
 * {@link #existsByReminderIdAndTriggerDate} 只作发送前的幂等先行预检。</p>
 *
 * <p>删除提醒时<b>不删</b>其历史发送记录（发送记录是已发生事实，需求 7.6）；仅注销时才由
 * {@code AccountDeletionService} 按 {@code user_id} 显式删除。</p>
 */
@Repository
public interface ReminderSendLogRepository extends JpaRepository<ReminderSendLog, Long> {

    /**
     * 幂等预检（需求 6.5、6.6）：该提醒在该触发日是否已存在发送记录。
     */
    boolean existsByReminderIdAndTriggerDate(Long reminderId, LocalDate triggerDate);

    /**
     * 注销级联硬删（需求 9.11）：删除该用户的全部发送记录行。
     *
     * <p>无行时影响行数 0 即视为成功，删除前不做存在性预查询。由 {@code AccountDeletionService} 在同一
     * 注销事务内、删 {@code users} 行之前调用。</p>
     *
     * @return 实际影响行数
     */
    @Modifying
    @Query("delete from ReminderSendLog l where l.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
