package com.damien.youyu.service.recurring;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.repository.ReminderQuotaRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;

/**
 * 周期记账「存在待确认项」提醒的衔接组件（tasks 8.1；需求 7.1–7.6）。当某规则存在到期且未处理的
 * {@code PENDING} 待确认项、且规则所有者持有有效的微信订阅授权额度时，<b>复用既有 custom-reminder 的
 * 微信一次性订阅消息投递链路</b>向所有者发送一条「存在待确认周期记账」提醒（需求 7.1）。
 *
 * <h2>复用既有投递链路，不新建第二套通道（需求 7.1、与 custom-reminder 一致）</h2>
 * <p>发送直接复用 {@link ReminderDispatchService} 所用的同一批下游组件，<b>不新建任何 HTTP 通道、凭证缓存
 * 或额度体系</b>：</p>
 * <ul>
 *   <li>{@link WeChatClient#sendSubscribeMessage} —— 全项目唯一的一次性订阅消息发送入口（内部含 3 秒超时、
 *       40001 凭证失效重试、任何网络/解析故障归一为 {@link WeChatClient#ERRCODE_LOCAL_FAILURE} 且<b>绝不抛异常</b>）。</li>
 *   <li>{@link WeChatAccessTokenProvider#getToken()} —— 全项目唯一的接口凭证网关。</li>
 *   <li>{@link ReminderQuotaRepository} —— 与 custom-reminder <b>共享的同一份订阅额度池</b>：读剩余额度、
 *       成功后 {@link ReminderQuotaRepository#decrementFloorZero} 扣减、{@code 43101}（用户拒收/无额度）
 *       {@link ReminderQuotaRepository#zero} 归零对齐（与 {@link ReminderDispatchService#sendAndRecord} 同款处置）。</li>
 *   <li>{@link UserRepository#findWxOpenid} —— 收件 {@code openid} 投影查询。</li>
 * </ul>
 *
 * <h2>触发点与事务边界（需求 7.6）</h2>
 * <p>由待确认项查询路径（{@code GET /api/recurring/pending-items} → {@code RecurringPendingItemController.list}）
 * 在 {@link RecurringPendingItemService#queryPendingItems} 返回<b>之后</b>、于<b>请求线程内</b>同步调用
 * {@link #notifyIfPending}。该路径既非 {@code @Transactional}、其上游控制器方法亦非 {@code @Transactional}，
 * 故调用发生在<b>所有主路径事务边界之外</b>——与项目既有 {@code GrowthSettlementTrigger} 的「afterCommit /
 * 请求线程内同步、失败不回灌主路径」同源思路（需求 7.6）。选择查询路径而非后台定时任务，是与本特性
 * 「懒生成为事实源、不强依赖调度可靠性」的设计一致，且<b>无需任何迁移</b>。</p>
 *
 * <h2>失败隔离：绝不阻断主路径（需求 7.2、7.6）</h2>
 * <p>{@link #notifyIfPending} 的<b>整个方法体包在 try/catch(Throwable)</b> 内：投递失败、微信接口异常、
 * 超时（{@link WeChatClient} 已就地归一为哨兵码，不抛异常）、额度耗尽或任何未预期异常都<b>就地捕获、
 * 仅记 {@code [RECURRING_REMIND_FAILED]} 告警日志</b>，绝不向待确认项生成 / 查询 / 确认 / 跳过 / 登录等
 * 主路径抛出异常或返回错误。本组件<b>不读写</b>任何待确认项 / 交易 / 账户余额，故发送成败绝不改变任何
 * 待确认项状态、流水或余额（需求 7.3）。</p>
 *
 * <h2>无有效额度不发送（需求 7.4）</h2>
 * <p>剩余额度为 0 或 {@code openid} 缺失时<b>不发送</b>且<b>不占用当日去重名额</b>（同日稍后授予额度后仍可发一次），
 * 而懒生成与待确认项呈现照常进行（本组件仅在查询完成后被动触发，不参与生成/查询逻辑）。</p>
 *
 * <h2>同 (owner, ledger, 自然日) 至多一条（需求 7.5）</h2>
 * <p>以 {@code (userId, ledgerId)} 为键在<b>当日窗口</b>内做发送前预检 + 原子占位，思路与
 * {@link ReminderDispatchService} 的 {@code (reminder_id, trigger_date)} 幂等预检一致，只是键换为
 * {@code (owner, ledger, 自然日)}。为保持<b>迁移无关、实现从简</b>（本特性 {@code V38} 只建两张业务表、
 * 不为提醒建表），去重采用<b>进程内当日窗口</b>：{@link #claimed} 只保存「当前自然日」已占位的
 * {@code user:ledger} 键，跨自然日（{@code Asia/Shanghai}）时整窗清空。真正发起微信调用前用
 * {@link Set#add} 原子占位，占位成功者才发送，故并发/重复触发下对同一 {@code (owner, ledger, 日)}
 * <b>至多发起一次发送</b>（需求 7.5）。</p>
 *
 * <p><b>已知取舍（与 {@link WeChatAccessTokenProvider} 的进程内缓存同源）：</b>去重为按实例、进程内、
 * 重启即重置。多实例部署下不同实例各自持有当日窗口，理论上同日可能各发一条；这与项目既有微信链路的
 * 单实例前提一致，且重复提醒属低危。若日后需要严格跨实例去重，可平滑升级为一张
 * {@code (user_id, ledger_id, natural_date)} 唯一键的去重表（与 {@code reminder_send_logs} 同款），
 * 不影响本组件对外契约。</p>
 *
 * <p>Feature: recurring-transactions。覆盖需求 7.1、7.2、7.3、7.4、7.5、7.6。</p>
 */
@Component
public class RecurringReminderNotifier {

    private static final Logger log = LoggerFactory.getLogger(RecurringReminderNotifier.class);

    /**
     * 「存在待确认周期记账」提醒文案（需求 7.1）。逐字符固定，落在订阅消息模板字段长度限制内，
     * 与 {@link ReminderMessageResolver} 的两条文案同一简短风格。
     */
    static final String MSG_PENDING = "有待确认的周期记账啦~";

    /**
     * 微信「用户拒收 / 订阅额度不足」错误码：微信侧已无额度，本地计数须归零对齐（需求 7.2）。
     * 与 {@link ReminderDispatchService} 的同名常量取值一致（微信文档 {@code 43101}）。
     */
    static final int ERRCODE_USER_REFUSED = 43101;

    private final ReminderQuotaRepository quotaRepository;
    private final UserRepository userRepository;
    private final WeChatAccessTokenProvider accessTokenProvider;
    private final WeChatClient weChatClient;
    private final Clock clock;

    /**
     * 额度写入（{@link ReminderQuotaRepository#decrementFloorZero} / {@link ReminderQuotaRepository#zero}
     * 均为 {@code @Modifying} 写）的<b>独立事务边界</b>：本组件在请求线程、<b>所有主路径事务之外</b>被触发
     * （见类级 Javadoc「触发点与事务边界」），若直接调用 {@code @Modifying} 写会因无活动事务抛
     * {@code TransactionRequiredException}，其结果被外层 {@code try/catch} 吞成 {@code [RECURRING_REMIND_FAILED]}
     * ——额度实际从未扣减（破坏需求 7.1 的额度记账）。故每次额度增减都下沉到本模板以
     * {@link org.springframework.transaction.annotation.Propagation#REQUIRES_NEW} 语义（{@code PROPAGATION_REQUIRES_NEW}）
     * 各开一个<b>独立事务</b>就地提交，既真正落库，又<b>绝不</b>在查询主路径上重新引入共享事务
     * （与 {@code RecurringPendingItemGenerator} / {@code GrowthSettlementService} 的 REQUIRES_NEW 独立事务同源思路）。
     */
    private final TransactionTemplate quotaTx;

    /** 当日去重窗口对应的自然日（{@code Asia/Shanghai}）；跨日时整窗清空。 */
    private volatile LocalDate windowDay;

    /** 当日已占位的 {@code user:ledger} 键集合（进程内、当日窗口，见类级 Javadoc）。 */
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();

    public RecurringReminderNotifier(ReminderQuotaRepository quotaRepository,
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
        // REQUIRES_NEW：为额度写各开独立事务并就地提交，无论调用方是否处于事务上下文都不与之共享/污染。
        this.quotaTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * 在待确认项查询完成后被动触发：当当前账本存在到期且未处理的 {@code PENDING} 待确认项时，向账本所有者
     * 发送一条「存在待确认周期记账」提醒（需求 7.1），同 {@code (owner, ledger, 自然日)} 至多一条（需求 7.5）。
     *
     * <p><b>本方法绝不抛出任何异常</b>：整个方法体被 {@code try/catch(Throwable)} 包裹，任何故障只记
     * {@code [RECURRING_REMIND_FAILED]} 告警日志（需求 7.2、7.6）。调用方（查询路径）可无条件调用而不必
     * 担心提醒链路影响其成功/失败判定与返回结果。</p>
     *
     * @param userId       账本所有者 / 当前用户 id（提醒收件人）
     * @param ledgerId     当前账本 id（去重维度之一）
     * @param hasDuePending 当前账本是否存在到期且未处理的 {@code PENDING} 待确认项
     *                      （由查询路径以「返回列表非空」判定后传入，需求 7.1）
     */
    public void notifyIfPending(Long userId, Long ledgerId, boolean hasDuePending) {
        try {
            // 无到期未处理待确认项 → 无需提醒（需求 7.1）。
            if (!hasDuePending || userId == null || ledgerId == null) {
                return;
            }

            LocalDate today = LocalDate.now(clock);
            rolloverIfNewDay(today);
            String key = userId + ":" + ledgerId;

            // 当日去重先行预检（需求 7.5）：该 (owner, ledger, 自然日) 已占位则不重复发。
            if (claimed.contains(key)) {
                return;
            }

            // 额度 / openid（需求 7.1、7.4）：无有效额度或 openid 缺失则不发送，且不占用当日名额，
            // 使同日稍后授予额度后仍可发一次；懒生成与呈现由查询路径照常完成，与此无关。
            int remaining = quotaRepository.findRemaining(userId).orElse(0);
            String openid = userRepository.findWxOpenid(userId).orElse(null);
            if (remaining <= 0 || openid == null || openid.isBlank()) {
                return;
            }

            // 原子占位（需求 7.5）：真正发起微信调用前先抢占当日名额；抢占失败说明并发触发已有人在发，
            // 直接放弃本次，从而对同一 (owner, ledger, 日) 至多发起一次发送。
            if (!claimed.add(key)) {
                return;
            }

            sendReminder(userId, ledgerId, openid);
        } catch (Throwable t) {
            // 需求 7.2、7.6：任何故障都就地捕获、仅记告警，绝不向查询/生成/确认/跳过/登录等主路径抛出。
            log.warn("[RECURRING_REMIND_FAILED] userId={}, ledgerId={}", userId, ledgerId, t);
        }
    }

    /**
     * 发起一次微信一次性订阅消息发送并按结果处置额度（复用 custom-reminder 链路，见类级 Javadoc）。
     * {@code errcode=0} → 额度 -1（{@link ReminderQuotaRepository#decrementFloorZero}）；非零 → 记
     * {@code [RECURRING_REMIND_FAILED]}，其中 {@code 43101} 额外归零对齐。本方法就地捕获自身异常，不外抛。
     */
    private void sendReminder(Long userId, Long ledgerId, String openid) {
        try {
            String token = accessTokenProvider.getToken();
            int errcode = weChatClient.sendSubscribeMessage(token, openid, MSG_PENDING);
            if (errcode == 0) {
                // 成功：消费一次共享订阅额度（与 ReminderDispatchService 同款，需求 7.1）。
                // 该 @Modifying 写须有事务边界：在请求线程、主路径事务之外，经 quotaTx 开独立事务就地提交，
                // 否则会因无活动事务抛 TransactionRequiredException、被外层吞掉，额度实际从未扣减。
                quotaTx.executeWithoutResult(status ->
                        quotaRepository.decrementFloorZero(userId, LocalDateTime.now(clock)));
            } else {
                // 失败（含本地哨兵码 / 超时 / 微信异常码）：仅记告警，额度不动（需求 7.2）；
                // 43101 用户拒收/无额度 → 本地额度归零对齐（与 ReminderDispatchService 同款）。
                log.warn("[RECURRING_REMIND_FAILED] 微信订阅消息发送失败, userId={}, ledgerId={}, errcode={}",
                        userId, ledgerId, errcode);
                if (errcode == ERRCODE_USER_REFUSED) {
                    // 同上：归零写也须各自的独立事务边界才能真正落库。
                    quotaTx.executeWithoutResult(status ->
                            quotaRepository.zero(userId, LocalDateTime.now(clock)));
                }
            }
        } catch (RuntimeException ex) {
            // 凭证刷新失败、额度写独立事务提交失败等运行时异常：就地捕获、仅记告警、不外抛（需求 7.2、7.6）。
            // 额度写走 quotaTx 独立事务，其提交失败以 TransactionException（RuntimeException）在此归一处置，
            // 绝不冒泡到查询主路径；notifyIfPending 外层 try/catch(Throwable) 为最终兜底。
            log.warn("[RECURRING_REMIND_FAILED] 微信订阅消息发送异常, userId={}, ledgerId={}",
                    userId, ledgerId, ex);
        }
    }

    /**
     * 跨自然日整窗清空（{@code Asia/Shanghai}）：新的一天到来时重置当日去重窗口，使去重键集合只保存
     * 「当前自然日」的占位，兑现「同一自然日至多一条」而不无界增长（需求 7.5）。
     */
    private void rolloverIfNewDay(LocalDate today) {
        if (!today.equals(windowDay)) {
            synchronized (this) {
                if (!today.equals(windowDay)) {
                    claimed.clear();
                    windowDay = today;
                }
            }
        }
    }
}
