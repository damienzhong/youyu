package com.damien.youyu.service.recurring;

import com.damien.youyu.domain.Transaction;

/**
 * 单期次自动入账（{@link RecurringAutoPoster#autoPost}）的处理结果。仅表达两种<b>正常返回</b>结局：
 *
 * <ul>
 *   <li>{@link Outcome#AUTO_POSTED} 已自动入账：已走既有交易创建链路生成一条真实流水、更新账户余额，
 *       并把该期次记为 {@code CONFIRMED}（携 {@link #transaction()}）。调用方据此在入账事务提交后触发告知
 *       （recurring-auto-post 需求 5）。</li>
 *   <li>{@link Outcome#DEGRADED_TO_PENDING} 降级为待确认：因模板目标失效（分类 / 账户在当前账本已不存在 /
 *       不可用）或金额校验不过，未入账，改为保留一条 {@code PENDING} 待确认项交用户处理，不生成流水、
 *       不改余额（需求 3.1、3.2、3.3）。调用方不触发告知。</li>
 * </ul>
 *
 * <p><b>「已处理 / 幂等撞键」不在本枚举内</b>：{@link RecurringAutoPoster#autoPost} 以
 * {@code REQUIRES_NEW} 事务内 {@code saveAndFlush} 占位抢唯一键 {@code uk_recurring_pending_rule_date}，
 * 撞键时抛 {@link org.springframework.dao.DataIntegrityViolationException} 并<b>回滚该新事务</b>——
 * 该异常无法在同一事务内被捕获后正常提交（flush 失败已将事务标记为 rollback-only），故它<b>向外传播</b>，
 * 由调用方（懒入账 / 定时任务）就地捕获并视为「该期次已被另一路径处理」静默结束（需求 2.4、3.4、4.3）。</p>
 *
 * <p>Feature: recurring-auto-post。</p>
 */
public final class AutoPostResult {

    /** 自动入账的正常返回结局。 */
    public enum Outcome {
        /** 已自动入账（生成流水 + 更新余额 + 记 CONFIRMED）。 */
        AUTO_POSTED,
        /** 目标失效 / 金额非法，降级为一条 PENDING 待确认项。 */
        DEGRADED_TO_PENDING
    }

    private final Outcome outcome;
    private final Transaction transaction;

    private AutoPostResult(Outcome outcome, Transaction transaction) {
        this.outcome = outcome;
        this.transaction = transaction;
    }

    /** 构造「已自动入账」结果，携带新生成的流水（供调用方发告知）。 */
    public static AutoPostResult autoPosted(Transaction transaction) {
        return new AutoPostResult(Outcome.AUTO_POSTED, transaction);
    }

    /** 构造「降级为待确认」结果（无流水）。 */
    public static AutoPostResult degradedToPending() {
        return new AutoPostResult(Outcome.DEGRADED_TO_PENDING, null);
    }

    public Outcome outcome() {
        return outcome;
    }

    /** 是否已自动入账（调用方据此决定是否发告知）。 */
    public boolean autoPosted() {
        return outcome == Outcome.AUTO_POSTED;
    }

    /** 已自动入账时的新流水；{@link Outcome#DEGRADED_TO_PENDING} 时为 {@code null}。 */
    public Transaction transaction() {
        return transaction;
    }
}
