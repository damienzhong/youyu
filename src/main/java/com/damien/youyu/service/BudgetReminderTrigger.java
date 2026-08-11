package com.damien.youyu.service;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 预算提醒触发器：把「一笔交易写成功」这一业务事件与「预算提醒评估」这一副作用连接起来（需求 2.1）。
 *
 * <p>目标与 {@link GrowthSettlementTrigger} 完全一致——<b>业务事务成功提交之后</b>，在<b>调用线程内同步</b>、
 * 以 {@code BudgetReminderEvaluationService.evaluate} 的<b>独立事务</b>（{@code REQUIRES_NEW}）完成评估与
 * 发送，且评估的任何故障都不影响已提交的记账与记账接口的响应（需求 2.8、9.4）。做法是向当前事务注册一个
 * {@link TransactionSynchronization}，在其 {@code afterCommit} 阶段评估；无事务上下文时走就地评估兜底。</p>
 *
 * <h2>四条禁令（照抄 {@link GrowthSettlementTrigger}，改动前务必读完）</h2>
 * <ol>
 *   <li><b>异常绝不允许穿出 {@code afterCommit} 回调</b>：否则会穿出业务方法的 {@code commit()}，令记账接口
 *       因预算提醒故障返回 500（尽管交易早已提交）。故 {@link #evaluateQuietly} 把每次评估 {@code try-catch} 包起来。</li>
 *   <li><b>同一事务只注册一次回调</b>：用 {@link TransactionSynchronizationManager#bindResource} 绑一个
 *       {@code Set<Key>} 当「回调已注册」标记，后续调用只往集合里 {@code add}，{@code afterCommit} 把整个集合
 *       合并为一轮评估（避免模板批量记账时注册 N 个回调）。</li>
 *   <li><b>回调内只携带不可变值</b>（{@code ledgerId} + {@code YearMonth}），绝不携带交易实体
 *       （afterCommit 阶段持久化上下文已关闭）；当前月与账本类型在回调内用新事务重新读取。</li>
 *   <li><b>异常必须在评估的事务边界之外吞掉</b>：{@code evaluate} 标注 {@code REQUIRES_NEW}，只有异常穿出
 *       被通知方法时 Spring 才回滚该事务；吞异常这件事只能发生在这里（边界外）。</li>
 * </ol>
 *
 * <p>本组件不驱动任何 {@code @Async} / 定时任务 / 线程池：评估发生在<b>请求线程</b>内。</p>
 *
 * <p>Feature: subscribe-message-reminders。覆盖需求 2.1、2.8、9.4。</p>
 */
@Component
public class BudgetReminderTrigger {

    private static final Logger log = LoggerFactory.getLogger(BudgetReminderTrigger.class);

    /** 绑定到当前事务的待评估集合的资源键，兼作「回调已注册」的标记。 */
    private static final String PENDING_KEY = BudgetReminderTrigger.class.getName() + ".PENDING";

    private final BudgetReminderEvaluationService evaluationService;

    public BudgetReminderTrigger(BudgetReminderEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /** 待评估项：账本 + 发生月（不可变值，安全携带进 afterCommit 回调）。 */
    private record Pending(Long ledgerId, YearMonth occurredMonth) { }

    /**
     * 请求一次预算提醒评估。在业务事务内调用时注册 {@code afterCommit} 回调并按 {@code (ledgerId, month)}
     * 去重合并；无事务上下文时就地评估（兜底）。本方法<b>不抛出任何异常</b>，也<b>不改变</b>调用方的事务状态。
     *
     * @param ledgerId      触发交易所属账本 id；为 {@code null}（转账 / 余额调整脱离账本）时直接返回，不做任何事
     * @param occurredMonth 触发交易的发生月（{@code Asia/Shanghai}）；为 {@code null} 时直接返回
     */
    public void requestEvaluation(Long ledgerId, YearMonth occurredMonth) {
        if (ledgerId == null || occurredMonth == null) {
            return;
        }
        Pending item = new Pending(ledgerId, occurredMonth);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 兜底：无事务上下文时无从判定「提交后」，直接就地评估（evaluate 自带 REQUIRES_NEW）。
            evaluateQuietly(Set.of(item), "NO_TX_CONTEXT");
            return;
        }
        @SuppressWarnings("unchecked")
        Set<Pending> pending = (Set<Pending>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (pending == null) {
            // 禁令 ②：同一事务内只注册一次回调。用 LinkedHashSet 使评估顺序稳定可测。
            pending = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING_KEY, pending);
            final Set<Pending> captured = pending;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 禁令 ①：绝不让异常穿出本回调，否则会穿出业务方法的 commit() 令记账接口 500。
                    evaluateQuietly(captured, "AFTER_COMMIT");
                }

                @Override
                public void afterCompletion(int status) {
                    // 无论提交还是回滚都必须解绑，否则线程池复用线程会把上一个事务的集合带进下一个事务。
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                }
            });
        }
        // 禁令 ③：只携带不可变值（ledgerId + YearMonth），绝不携带交易实体。
        pending.add(item);
    }

    /**
     * 逐项评估，把每次评估的异常就地吞掉只记 WARN（禁令 ①、④）。
     *
     * <p>{@code catch (Exception e)} 覆盖运行时异常、受检异常、行锁超时、连接获取失败等一切
     * {@code Exception}；刻意不捕获 {@code Error}（JVM 级故障吞掉只会掩盖问题）。</p>
     */
    private void evaluateQuietly(Set<Pending> items, String source) {
        for (Pending item : items) {
            try {
                evaluationService.evaluate(item.ledgerId(), item.occurredMonth());   // REQUIRES_NEW
            } catch (Exception e) {
                // 需求 2.8、9.4：评估故障绝不阻断记账主路径，只记不含金额 / 邮箱 / 令牌的告警日志。
                log.warn("[BUDGET_REMINDER_EVAL_FAILED] source={} ledgerId={} 评估失败，将在下次交易写入时重试",
                        source, item.ledgerId(), e);
            }
        }
    }
}
