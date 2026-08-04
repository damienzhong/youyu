package com.damien.youyu.service;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 结算触发器：把「新增有效记账交易」这一业务事件与「成长结算」这一副作用连接起来（需求 9.1）。
 *
 * <p>目标：<b>业务事务成功提交之后</b>，在<b>调用线程内同步</b>、以 {@link GrowthSettlementService#settle}
 * 的<b>独立事务</b>（{@code REQUIRES_NEW}）完成结算，且结算的任何故障都不影响已提交的记账与记账接口的
 * 响应（需求 9.3、9.7、9.9）。做法是向当前事务注册一个 {@link TransactionSynchronization}，在其
 * {@code afterCommit} 阶段调结算；无事务上下文时走就地结算的兜底路径。</p>
 *
 * <h2>四条禁令（都「长得不像约束」，改动前务必读完）</h2>
 *
 * <ol>
 *   <li><b>异常绝不允许穿出 {@code afterCommit} 回调。</b>Spring 的
 *       {@code AbstractPlatformTransactionManager.triggerAfterCommit} 是在 {@code processCommit}
 *       <b>内部</b>调用同步回调的；回调抛出的异常不会被事务管理器吞掉，而是穿出 {@code commit()}，
 *       最终以异常形式出现在业务方法（{@code TransactionService.create} 等）的调用点上——于是记账接口
 *       会因为成长体系的故障返回 500，<b>尽管交易早已提交</b>。因此 {@link #settleQuietly} 必须把每个
 *       {@code userId} 的结算用 {@code try-catch} 包起来。</li>
 *   <li><b>同一事务只注册一次回调。</b>否则若 {@code create} 将来被循环调用（例如模板批量记账），就会
 *       注册 N 个回调、结算 N 次（违背需求 9.4）。做法是用
 *       {@link TransactionSynchronizationManager#bindResource} 绑一个 {@code Set<Long>} 当「回调已注册」
 *       的标记：有资源即说明已注册，后续调用只往集合里 {@code add(userId)}，{@code afterCommit} 把整个
 *       集合合并为<b>一轮</b>结算。</li>
 *   <li><b>回调内只携带不可变的 {@code Long userId}，绝不携带实体对象。</b>{@code afterCommit} 触发时
 *       持久化上下文（{@code EntityManager}）已 flush 并即将关闭，共享的 {@code EntityManagerHolder}
 *       也已不可再用。把实体传进回调、在回调里 lazy load 关联、或复用原 {@code EntityManager} 都会得到
 *       {@code LazyInitializationException} 或对一个已关闭 Session 的调用。结算内部的读写一律由
 *       {@code REQUIRES_NEW} 开出的<b>新</b> {@code EntityManager} 完成。</li>
 *   <li><b>异常必须在 {@code settle} 的事务边界<b>之外</b>吞掉。</b>{@code settle} 标注
 *       {@code REQUIRES_NEW}，Spring 只在异常<b>穿出</b>被通知方法时回滚该事务。若在 {@code settle}
 *       方法体内 {@code catch} 掉异常并正常返回，Spring 会照常提交一个<b>已被标记 rollback-only</b> 的
 *       事务、或留下部分写入（破坏需求 9.7）。因此吞异常这件事只能发生在这里（边界外）。</li>
 * </ol>
 *
 * <p>本组件不驱动任何 {@code @Async} / 定时任务 / 线程池：结算发生在<b>请求线程</b>内（需求 9.9）。
 * 时间测量一律用注入的 {@link Clock}（{@code TimeConfig} 提供），不用 {@code System.currentTimeMillis()}。</p>
 */
@Component
public class GrowthSettlementTrigger {

    private static final Logger log = LoggerFactory.getLogger(GrowthSettlementTrigger.class);

    /** 单次结算的耗时预算：超过则记一条 {@code [GROWTH_SETTLE_SLOW]} WARN（不影响结果，仅告警）。 */
    private static final long SLOW_SETTLE_MILLIS = 1000L;

    /** 绑定到当前事务的待结算 {@code userId} 集合的资源键，兼作「回调已注册」的标记。 */
    private static final String PENDING_KEY = GrowthSettlementTrigger.class.getName() + ".PENDING";

    private final GrowthSettlementService settlementService;
    private final Clock clock;

    public GrowthSettlementTrigger(GrowthSettlementService settlementService, Clock clock) {
        this.settlementService = settlementService;
        this.clock = clock;
    }

    /**
     * 请求一次结算。在业务事务内调用时注册 {@code afterCommit} 回调并按 {@code userId} 去重合并；
     * 无事务上下文时就地结算（兜底）。本方法<b>不抛出任何异常</b>，也<b>不改变</b>调用方的事务状态。
     *
     * @param userId 结算用户 id（等于 {@code users.id}）；为 {@code null} 时直接返回，不做任何事
     */
    public void requestSettlement(Long userId) {
        if (userId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 兜底：没有事务上下文（被非事务方法直接调用、或将来有人在 @Transactional 之外用了它）。
            // 此时「提交后」无从判定，直接就地结算：settle 自己带 REQUIRES_NEW，会开一个新事务。
            settleQuietly(Set.of(userId), "NO_TX_CONTEXT");
            return;
        }
        @SuppressWarnings("unchecked")
        Set<Long> pending = (Set<Long>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (pending == null) {
            // 禁令 ②：同一事务内只注册一次回调。用绑定资源做标记，后续调用只往集合里加 userId。
            // 用 LinkedHashSet 而非 HashSet，使多用户结算顺序稳定可测。
            pending = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING_KEY, pending);
            final Set<Long> captured = pending;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 禁令 ①：绝不让异常穿出本回调，否则会穿出业务方法的 commit()，令记账接口返回 500。
                    settleQuietly(captured, "AFTER_COMMIT");
                }

                @Override
                public void afterCompletion(int status) {
                    // 无论提交还是回滚都必须解绑：Spring 只清理它自己管理的同步回调列表，
                    // bindResource 的资源要自己解绑，否则线程池复用线程时会把上一个事务的集合带进下一个事务。
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                }
            });
        }
        // 禁令 ③：只携带不可变的 Long userId，绝不携带实体对象（afterCommit 阶段持久化上下文已关闭）。
        pending.add(userId);
    }

    /**
     * 逐 {@code userId} 结算，把每次结算的异常就地吞掉只记 WARN（禁令 ①、④）。
     *
     * <p>{@code catch (Exception e)} 覆盖运行时异常、受检异常、行锁超时、连接获取失败等一切
     * {@code Exception}，结算失败会在下一次结算自愈（幂等可重入）。<b>刻意不捕获 {@code Error}</b>：
     * {@code OutOfMemoryError} / {@code StackOverflowError} 是 JVM 级故障，吞掉只会掩盖问题——这是对
     * 需求 9.5「任何异常」的一处<b>刻意收窄</b>，故此处只捕获 {@code Exception} 而非 {@code Throwable}。</p>
     */
    private void settleQuietly(Set<Long> userIds, String source) {
        for (Long userId : userIds) {
            long startedAt = clock.millis();
            try {
                settlementService.settle(userId, TriggerSource.RECORD);   // @Transactional(REQUIRES_NEW)
            } catch (Exception e) {
                // 需求 9.5：含运行时/受检异常、行锁超时、连接获取失败，一律只记日志、不再抛出。
                log.warn("[GROWTH_SETTLE_FAILED] source={} userId={} 结算失败，将在下次结算自愈",
                        source, userId, e);
            } finally {
                long cost = clock.millis() - startedAt;
                if (cost > SLOW_SETTLE_MILLIS) {
                    log.warn("[GROWTH_SETTLE_SLOW] userId={} cost={}ms 超出 {}ms 预算",
                            userId, cost, SLOW_SETTLE_MILLIS);
                }
            }
        }
    }
}
