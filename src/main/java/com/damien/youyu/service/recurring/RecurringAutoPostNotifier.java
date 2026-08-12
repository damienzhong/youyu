package com.damien.youyu.service.recurring;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.domain.Transaction;
import com.damien.youyu.repository.ReminderQuotaRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;

/**
 * 自动入账「已自动记一笔」告知的衔接组件（recurring-auto-post tasks 7.1；需求 5.1–5.5）。某期次自动入账
 * 成功后（已生成流水并更新余额），若规则所有者持有有效的微信订阅授权额度，则<b>复用既有 custom-reminder /
 * recurring-transactions 的微信一次性订阅消息投递链路</b>向所有者发送一条「已自动记一笔」告知（需求 5.1）。
 *
 * <h2>复用既有投递链路，不新建通道（需求 5.1）</h2>
 * <p>与 {@link RecurringReminderNotifier} 同款，直接复用同一批下游组件，不新建任何 HTTP 通道 / 凭证缓存 /
 * 额度体系：{@link WeChatClient#sendSubscribeMessage}（内含 3 秒超时、40001 重试、故障归一为哨兵码且绝不抛异常）、
 * {@link WeChatAccessTokenProvider#getToken()}、与 custom-reminder <b>共享的同一份订阅额度池</b>
 * {@link ReminderQuotaRepository}、{@link UserRepository#findWxOpenid}。</p>
 *
 * <h2>触发时机：入账事务提交之后、主路径事务边界之外（需求 5.3）</h2>
 * <p>{@link RecurringAutoPoster#autoPost} 是 {@code REQUIRES_NEW} 独立事务，返回即已提交；调用方
 * （懒入账分流 {@link RecurringPendingItemService} / 定时任务 {@link RecurringAutoPostScheduler}）在拿到
 * {@link AutoPostResult#autoPosted()} 后调用本组件——此时入账已落库、且调用发生在无外层事务的请求线程 /
 * 调度线程内，故告知恒在入账事务提交之后、所有主路径事务边界之外执行（与 {@code GrowthSettlementTrigger}
 * 同源思路）。告知的成功 / 失败绝不影响已持久化的入账结果。</p>
 *
 * <h2>失败隔离：绝不影响入账（需求 5.2、5.5）</h2>
 * <p>{@link #notifyAutoPosted} 整个方法体包在 {@code try/catch(Throwable)} 内：投递失败、微信异常、超时
 * （{@link WeChatClient} 已就地归一）、额度耗尽或任何未预期异常都就地捕获、仅记
 * {@code [RECURRING_AUTOPOST_NOTIFY_FAILED]} 告警日志，<b>绝不回滚已完成的自动入账、不改变任何流水 /
 * 账户余额 / 期次状态 / 幂等键</b>，也绝不向调用方主路径抛出（需求 5.2、5.5）。</p>
 *
 * <h2>无有效额度不发送（需求 5.4）</h2>
 * <p>剩余额度为 0 或 {@code openid} 缺失时不发送，而自动入账已正常完成、不受影响（需求 5.4）。</p>
 *
 * <p>Feature: recurring-auto-post。覆盖需求 5.1、5.2、5.3、5.4、5.5。</p>
 */
@Component
public class RecurringAutoPostNotifier {

    private static final Logger log = LoggerFactory.getLogger(RecurringAutoPostNotifier.class);

    /** 微信「用户拒收 / 订阅额度不足」错误码：微信侧已无额度，本地计数须归零对齐（与既有链路一致）。 */
    static final int ERRCODE_USER_REFUSED = 43101;

    private final ReminderQuotaRepository quotaRepository;
    private final UserRepository userRepository;
    private final WeChatAccessTokenProvider accessTokenProvider;
    private final WeChatClient weChatClient;
    private final Clock clock;

    /**
     * 额度写（{@code @Modifying}）的独立事务边界：本组件在主路径事务之外被触发，直接调 {@code @Modifying}
     * 写会因无活动事务抛 {@code TransactionRequiredException}。故每次额度增减经本模板以
     * {@link org.springframework.transaction.annotation.Propagation#REQUIRES_NEW} 各开独立事务就地提交
     * （与 {@link RecurringReminderNotifier#sendReminder} 同源思路）。
     */
    private final TransactionTemplate quotaTx;

    public RecurringAutoPostNotifier(ReminderQuotaRepository quotaRepository,
                                     UserRepository userRepository,
                                     WeChatAccessTokenProvider accessTokenProvider,
                                     WeChatClient weChatClient,
                                     PlatformTransactionManager transactionManager,
                                     Clock clock) {
        this.quotaRepository = quotaRepository;
        this.userRepository = userRepository;
        this.accessTokenProvider = accessTokenProvider;
        this.weChatClient = weChatClient;
        this.quotaTx = new TransactionTemplate(transactionManager);
        this.quotaTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * 在自动入账事务提交之后被调用：向规则所有者发送一条「已自动记一笔」告知（需求 5.1）。
     *
     * <p><b>本方法绝不抛出任何异常</b>：整个方法体被 {@code try/catch(Throwable)} 包裹，任何故障只记
     * {@code [RECURRING_AUTOPOST_NOTIFY_FAILED]} 告警日志（需求 5.2、5.5），调用方可无条件调用而不必担心
     * 告知链路影响已完成的入账。</p>
     *
     * @param ownerUserId 规则所有者 id（告知收件人）
     * @param tx          刚自动入账生成的流水（提供金额 / 备注等非敏感摘要，需求 5.1）
     */
    public void notifyAutoPosted(Long ownerUserId, Transaction tx) {
        try {
            if (ownerUserId == null || tx == null) {
                return;
            }
            // 额度 / openid（需求 5.1、5.4）：无有效额度或 openid 缺失则不发送，入账已完成不受影响。
            int remaining = quotaRepository.findRemaining(ownerUserId).orElse(0);
            String openid = userRepository.findWxOpenid(ownerUserId).orElse(null);
            if (remaining <= 0 || openid == null || openid.isBlank()) {
                return;
            }
            sendNotification(ownerUserId, openid, buildMessage(tx));
        } catch (Throwable t) {
            // 需求 5.2、5.5：任何故障就地捕获、仅记告警，绝不回滚入账、不改流水 / 余额 / 状态、不外抛。
            log.warn("[RECURRING_AUTOPOST_NOTIFY_FAILED] ownerUserId={}, txId={}",
                    ownerUserId, tx == null ? null : tx.getId(), t);
        }
    }

    /**
     * 构建「已自动记一笔」告知文案（需求 5.1）：仅含金额、备注等<b>非敏感</b>摘要，不含账户余额 / 令牌 /
     * 邮箱等敏感字段；长度落在订阅消息模板字段限制内。
     */
    private String buildMessage(Transaction tx) {
        BigDecimal amount = tx.getAmount() == null ? BigDecimal.ZERO : tx.getAmount();
        String note = tx.getNote();
        StringBuilder sb = new StringBuilder("已自动记一笔 ¥").append(amount.toPlainString());
        if (note != null && !note.isBlank()) {
            String trimmed = note.strip();
            // 防御性截断，避免超模板字段长度限制。
            if (trimmed.length() > 20) {
                trimmed = trimmed.substring(0, 20);
            }
            sb.append(' ').append(trimmed);
        }
        return sb.toString();
    }

    /**
     * 发起一次微信一次性订阅消息发送并按结果处置额度（复用既有链路）。{@code errcode=0} → 额度 -1；
     * 非零 → 记 {@code [RECURRING_AUTOPOST_NOTIFY_FAILED]}，其中 {@code 43101} 额外归零对齐。就地捕获自身异常，不外抛。
     */
    private void sendNotification(Long ownerUserId, String openid, String message) {
        try {
            String token = accessTokenProvider.getToken();
            int errcode = weChatClient.sendSubscribeMessage(token, openid, message);
            if (errcode == 0) {
                quotaTx.executeWithoutResult(status ->
                        quotaRepository.decrementFloorZero(ownerUserId, LocalDateTime.now(clock)));
            } else {
                log.warn("[RECURRING_AUTOPOST_NOTIFY_FAILED] 微信订阅消息发送失败, ownerUserId={}, errcode={}",
                        ownerUserId, errcode);
                if (errcode == ERRCODE_USER_REFUSED) {
                    quotaTx.executeWithoutResult(status ->
                            quotaRepository.zero(ownerUserId, LocalDateTime.now(clock)));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("[RECURRING_AUTOPOST_NOTIFY_FAILED] 微信订阅消息发送异常, ownerUserId={}",
                    ownerUserId, ex);
        }
    }
}
