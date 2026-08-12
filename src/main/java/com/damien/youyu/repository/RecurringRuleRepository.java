package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.PostMode;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;

/**
 * 周期规则仓库（{@code recurring_rules}，一行 = 用户建立的一条固定记账规则）。
 *
 * <p>规则的写入走继承来的 {@code save()}：{@link RecurringRule} 的 {@code @Id} 是自增代理键，
 * 新建即 {@code INSERT}、更新即脏检查 {@code UPDATE}。归属校验一律经 {@link #findByIdAndUserIdAndLedgerId}
 * 定位：为空即「不存在或不属于本人 / 本账本」，服务层对两种情形返回完全相同的 {@code NOT_FOUND}，
 * 不泄漏他人规则是否存在（需求 6.7、8.4、8.5）。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@Repository
public interface RecurringRuleRepository extends JpaRepository<RecurringRule, Long> {

    /**
     * 懒生成扫描（需求 3.7、6.1）：当前账本下指定状态的规则。
     * 传入 {@code RuleStatus.ACTIVE} 即得当前账本全部启用规则；命中索引
     * {@code idx_recurring_rules_ledger_status (ledger_id, status)}。
     */
    List<RecurringRule> findByLedgerIdAndStatus(Long ledgerId, RuleStatus status);

    /**
     * 列表查询（需求 6.3、8.4）：当前账本当前用户的全部规则（含 {@code ACTIVE}/{@code PAUSED}），
     * 按 {@code created_at} 升序。
     */
    List<RecurringRule> findByUserIdAndLedgerIdOrderByCreatedAtAsc(Long userId, Long ledgerId);

    /**
     * 归属校验（需求 6.7、8.4、8.5）：按规则 id + 用户 id + 账本 id 三者同时匹配读取。
     *
     * <p>详情 / 编辑 / 暂停 / 恢复 / 删除时先经此方法定位：为空即返回 {@code NOT_FOUND}，
     * 不泄漏他人或他账本规则的存在性。</p>
     */
    Optional<RecurringRule> findByIdAndUserIdAndLedgerId(Long id, Long userId, Long ledgerId);

    /**
     * 每日定时自动入账任务扫描（recurring-auto-post 需求 4.2）：<b>跨全部账本</b>按状态 + 入账方式查询规则。
     * 传入 {@code (RuleStatus.ACTIVE, PostMode.AUTO)} 即得全部启用且自动入账的规则，供
     * {@link com.damien.youyu.service.recurring.RecurringAutoPostScheduler} 对其到期未处理期次自动入账。
     * 命中索引 {@code idx_recurring_rules_ledger_status} 的 status 前缀不完整，规则总量有限，全表按
     * status/post_mode 过滤可接受。
     */
    List<RecurringRule> findByStatusAndPostMode(RuleStatus status, PostMode postMode);
}
