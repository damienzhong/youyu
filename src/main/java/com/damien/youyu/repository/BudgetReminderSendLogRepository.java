package com.damien.youyu.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.BudgetReminderSendLog;

/**
 * 预算提醒发送记录仓库（{@code budget_reminder_send_logs}，一行 = 一次发送尝试的落表结果）。
 *
 * <p>发送记录的写入走继承来的 {@code save()}：{@link BudgetReminderSendLog} 的 {@code @Id} 是自增代理键。
 * 幂等由唯一约束 {@code uk_budget_reminder_send_logs_scope (user_id, ledger_id, budget_month,
 * scope_ref, level)} 构造性保证（需求 3.1）——每月每范围每级别至多一条记录，并发触发时后写者撞唯一键抛
 * {@code DataIntegrityViolationException}，由发送编排静默放弃本次（需求 3.4）。下面两个存在性查询只作
 * 发送前的幂等先行预检与「超支已推则不补预警」判定。</p>
 */
@Repository
public interface BudgetReminderSendLogRepository extends JpaRepository<BudgetReminderSendLog, Long> {

    /**
     * 幂等预检（需求 3.2）：该收件人该账本该自然月该范围该级别是否已存在任一发送记录。
     */
    boolean existsByUserIdAndLedgerIdAndBudgetMonthAndScopeRefAndLevel(
            Long userId, Long ledgerId, String budgetMonth, long scopeRef, String level);

    /**
     * 「超支已推则不补预警」判定（需求 3.3）：该收件人该账本该自然月该范围的 {@code OVER} 级别是否已存在
     * 发送记录。存在则不再为该范围该月的 {@code WARN} 级别生成任何发送尝试。
     */
    default boolean existsOverLog(Long userId, Long ledgerId, String budgetMonth, long scopeRef) {
        return existsByUserIdAndLedgerIdAndBudgetMonthAndScopeRefAndLevel(
                userId, ledgerId, budgetMonth, scopeRef, "OVER");
    }

    /**
     * 注销级联硬删（需求 8.8）：删除该用户的全部发送记录行。
     *
     * <p>无行时影响行数 0 即视为成功，删除前不做存在性预查询。由 {@code AccountDeletionService} 在同一
     * 注销事务内、删 {@code users} 行之前调用。</p>
     *
     * @return 实际影响行数
     */
    @Modifying
    @Query("delete from BudgetReminderSendLog l where l.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
