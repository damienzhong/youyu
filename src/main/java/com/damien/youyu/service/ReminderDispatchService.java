package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.ReminderSendLog;
import com.damien.youyu.domain.ReminderSendResult;
import com.damien.youyu.repository.ReminderQuotaRepository;
import com.damien.youyu.repository.ReminderSendLogRepository;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;

/**
 * 单条提醒的<strong>一次发送尝试</strong>（链路 B 的核心，高风险）：选文案 → 查额度/openid →
 * 发微信 → 写发送记录 → 扣额度，全过程就地捕获、绝不向记账/登录/注销/结算等主路径传播异常
 * （需求 6.7、11.6）。定时扫描与逐条派发由 {@code ReminderScheduler} 承担，本服务只处理「这一条」。
 *
 * <h2>发送顺序（design.md §4）</h2>
 * <ol>
 *   <li><b>幂等预检</b>（需求 6.5）：{@code (reminder_id, trigger_date)} 已有发送记录 → 直接 return，
 *       不重复发。这是唯一键 {@code uk_reminder_send_logs_reminder_date} 之外的先行友好短路。</li>
 *   <li><b>选文案</b>（需求 4.2～4.8）：触发当刻只读 {@code user_growth.last_record_date}，经
 *       {@link StreakJudgment#todayDone} 判「今日已记账」，再由 {@link ReminderMessageResolver#pick}
 *       映射到两条文案之一。读失败或档案/日历为空 → {@code done=false}、选「今天还没记账哦~」，
 *       <b>不写 {@code user_growth}</b>。</li>
 *   <li><b>超追补窗口</b>（需求 3.4）：{@code remindTime} 早于 {@code now-10min} → 写
 *       {@code SKIPPED_STALE}、不发、不扣额度。</li>
 *   <li><b>额度 / openid</b>（需求 6.2、6.3）：剩余额度为 0 或 {@code wx_openid} 空 → 写
 *       {@code SKIPPED_NO_QUOTA}、不发、不扣额度、不报错。</li>
 *   <li><b>发微信</b>（需求 6.1、6.4、5.5、5.6）：{@code errcode=0} → 写 {@code SENT} +
 *       {@link ReminderQuotaRepository#decrementFloorZero}（-1 且不小于 0）；非零/异常 → 写
 *       {@code FAILED}（记 errcode）、额度不动；{@code 43101}（用户拒收/无额度）→ 额度
 *       {@link ReminderQuotaRepository#zero} 归零对齐。</li>
 * </ol>
 *
 * <h2>幂等与并发（需求 6.5、6.6）</h2>
 * <p>发送记录写入撞唯一键（并发触发时另一线程已写）→ 捕 {@link DataIntegrityViolationException}
 * 后<b>静默放弃本次</b>：不重复发微信、不报错。发送前的幂等预检 + 唯一键是双保险。</p>
 *
 * <h2>纯增量只读（需求 6.8、11.1、11.2）</h2>
 * <p>只读 {@code user_growth.last_record_date} 与 {@code users.wx_openid}（均为投影查询），
 * <b>绝不 save/写这两张表</b>；只写本 spec 新增的 {@code reminder_send_logs} 与 {@code reminder_quota}。</p>
 *
 * <p>Feature: custom-reminder。覆盖需求 3.4、4.5～4.8、5.5、5.6、6.1～6.6、6.8。</p>
 */
@Service
public class ReminderDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatchService.class);

    /** 追补窗口（需求 3.3、3.4）：触发时刻已过但仍允许补发的最长时长，10 分钟。 */
    static final int CATCH_UP_WINDOW_MINUTES = 10;

    /**
     * 微信「用户拒收 / 订阅额度不足」错误码（需求 5.6）：微信侧已无额度，本地计数须归零对齐。
     * 微信文档中 {@code 43101} 表示用户未订阅或已取消订阅该模板消息。
     */
    static final int ERRCODE_USER_REFUSED = 43101;

    /** {@code message_variant} 落库取值（需求 9.7）：与 {@link ReminderMessageResolver} 的两条文案一一对应。 */
    static final String VARIANT_DONE = "DONE";
    static final String VARIANT_NOT_YET = "NOT_YET";

    /** 时区一律 {@code Asia/Shanghai}（经注入的 {@link Clock} 保证），此处只负责格式化。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 微信订阅消息 {@code thing} 型字段字符上限（超出则截断）。 */
    private static final int THING_MAX_CHARS = 20;

    /** 记账提醒模板「提醒内容」字段（thing3）固定短语。 */
    static final String REMINDER_THING_CONTENT = "记得记账哦";

    private final ReminderSendLogRepository sendLogRepository;
    private final ReminderQuotaRepository quotaRepository;
    private final UserGrowthRepository userGrowthRepository;
    private final UserRepository userRepository;
    private final WeChatAccessTokenProvider accessTokenProvider;
    private final WeChatClient weChatClient;
    private final SubscribeTemplateProvider templateProvider;
    private final Clock clock;

    public ReminderDispatchService(ReminderSendLogRepository sendLogRepository,
                                   ReminderQuotaRepository quotaRepository,
                                   UserGrowthRepository userGrowthRepository,
                                   UserRepository userRepository,
                                   WeChatAccessTokenProvider accessTokenProvider,
                                   WeChatClient weChatClient,
                                   SubscribeTemplateProvider templateProvider,
                                   Clock clock) {
        this.sendLogRepository = sendLogRepository;
        this.quotaRepository = quotaRepository;
        this.userGrowthRepository = userGrowthRepository;
        this.userRepository = userRepository;
        this.accessTokenProvider = accessTokenProvider;
        this.weChatClient = weChatClient;
        this.templateProvider = templateProvider;
        this.clock = clock;
    }

    /**
     * 对单条提醒执行一次发送尝试（顺序见类级 Javadoc）。
     *
     * @param reminder 触发的提醒配置（已由调度器按启用状态、频率与窗口筛出）
     * @param today    触发日（{@code Asia/Shanghai} 自然日）
     * @param now      当前触发时刻（分钟粒度，{@code Asia/Shanghai}）
     */
    @Transactional
    public void dispatch(CustomReminder reminder, LocalDate today, LocalTime now) {
        Long userId = reminder.getUserId();
        Long reminderId = reminder.getId();

        // ① 幂等预检（需求 6.5）：该提醒该触发日已有记录 → 不重复发。
        if (sendLogRepository.existsByReminderIdAndTriggerDate(reminderId, today)) {
            return;
        }

        // ② 选文案（需求 4.2～4.8）：触发当刻只读 last_record_date，读失败/空 → done=false。
        boolean done = resolveTodayDone(userId, today);
        String message = ReminderMessageResolver.pick(done);
        String variant = done ? VARIANT_DONE : VARIANT_NOT_YET;

        // ③ 超追补窗口（需求 3.4）：早于 now-10min → SKIPPED_STALE，不发、不扣额度。
        if (reminder.getRemindTime().isBefore(now.minusMinutes(CATCH_UP_WINDOW_MINUTES))) {
            writeLog(reminderId, userId, today, ReminderSendResult.SKIPPED_STALE, variant, null);
            return;
        }

        // ④ 额度 / openid（需求 6.2、6.3）：额度为 0 或 openid 空 → SKIPPED_NO_QUOTA，不发、不扣额度、不报错。
        int remaining = quotaRepository.findRemaining(userId).orElse(0);
        String openid = userRepository.findWxOpenid(userId).orElse(null);
        if (remaining <= 0 || openid == null || openid.isBlank()) {
            writeLog(reminderId, userId, today, ReminderSendResult.SKIPPED_NO_QUOTA, variant, null);
            return;
        }

        // ⑤ 发微信（需求 6.1、6.4、5.5、5.6）。
        sendAndRecord(reminderId, userId, today, variant, openid, message);
    }

    /**
     * 触发当刻判定「今日已记账」（需求 4.5～4.8）：只读 {@code user_growth.last_record_date}，
     * 经 {@link StreakJudgment#todayDone} 判定。读取失败或返回不可解析值时兜底为 {@code false}
     * （需求 4.8），绝不写 {@code user_growth}。
     */
    private boolean resolveTodayDone(Long userId, LocalDate today) {
        try {
            Optional<LocalDate> lastRecord = userGrowthRepository.findLastRecordDate(userId);
            return StreakJudgment.todayDone(lastRecord.orElse(null), today);
        } catch (RuntimeException ex) {
            // 需求 4.8：读失败兜底为「今日未记账」，只记告警日志、不写 user_growth、不影响主路径。
            log.warn("读取 user_growth.last_record_date 失败，兜底为今日未记账, userId={}", userId, ex);
            return false;
        }
    }

    /**
     * 调微信 {@code subscribeMessage.send} 并按结果落记录、扣/归零额度（需求 6.1、6.4、5.5、5.6）。
     *
     * <p>{@code errcode=0} → {@code SENT} + 额度 -1；非零 → {@code FAILED}（记 errcode）、额度不动，
     * 其中 {@code 43101} 额外归零对齐；任何异常 → {@code FAILED}（errcode 空）、额度不动、不外抛。</p>
     */
    private void sendAndRecord(Long reminderId, Long userId, LocalDate today,
                               String variant, String openid, String message) {
        // 模板 id 从数据库配置读取（替代旧的环境变量来源）；未配置 / 未启用 → 视为发送失败，
        // 写 FAILED（errcode 空）、不扣额度、不外抛，与微信调用异常分支一致（需求 6.4）。
        Optional<String> templateId = templateProvider.templateId(SubscribeTemplateProvider.BIZ_REMINDER);
        if (templateId.isEmpty()) {
            writeLog(reminderId, userId, today, ReminderSendResult.FAILED, variant, null);
            log.warn("记账提醒模板未配置，发送安全降级为失败, reminderId={}, userId={}", reminderId, userId);
            return;
        }
        try {
            String token = accessTokenProvider.getToken();
            // 按模板字段填值：time1=当前时刻(Asia/Shanghai)、thing3=固定提醒内容、thing4=已记账/未记账文案。
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("time1", LocalDateTime.now(clock).format(TIME_FORMAT));
            fields.put("thing3", truncateThing(REMINDER_THING_CONTENT));
            fields.put("thing4", truncateThing(message));
            int errcode = weChatClient.sendSubscribeMessage(token, openid, templateId.get(), fields);
            if (errcode == 0) {
                // 成功：先写 SENT（唯一键冲突则本次作废、不扣额度），再扣额度（需求 6.1、5.5）。
                boolean written = writeLog(reminderId, userId, today, ReminderSendResult.SENT, variant, 0);
                if (written) {
                    quotaRepository.decrementFloorZero(userId, LocalDateTime.now(clock));
                }
            } else {
                // 失败：写 FAILED 记 errcode，额度不动（需求 6.4）；43101 → 额度归零对齐（需求 5.6）。
                writeLog(reminderId, userId, today, ReminderSendResult.FAILED, variant, errcode);
                log.warn("提醒发送失败, reminderId={}, userId={}, errcode={}", reminderId, userId, errcode);
                if (errcode == ERRCODE_USER_REFUSED) {
                    quotaRepository.zero(userId, LocalDateTime.now(clock));
                }
            }
        } catch (RuntimeException ex) {
            // 需求 6.4：微信调用抛异常 → FAILED（errcode 空）、不扣额度、不外抛，记不含金额/邮箱/令牌的告警日志。
            writeLog(reminderId, userId, today, ReminderSendResult.FAILED, variant, null);
            log.warn("提醒发送异常, reminderId={}, userId={}", reminderId, userId, ex);
        }
    }

    /** 微信 {@code thing} 型字段上限 20 字符：超长截断，保证落入模板字段限制内。 */
    private static String truncateThing(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > THING_MAX_CHARS ? text.substring(0, THING_MAX_CHARS) : text;
    }

    /**
     * 写一条发送记录（需求 9.7）。撞唯一键 {@code (reminder_id, trigger_date)}（并发触发时另一线程已写）
     * → 捕 {@link DataIntegrityViolationException} 后静默放弃本次、不报错（需求 6.6）。
     *
     * @return {@code true} 成功写入；{@code false} 唯一键冲突、本次作废（调用方据此决定是否扣额度）
     */
    private boolean writeLog(Long reminderId, Long userId, LocalDate today,
                             ReminderSendResult result, String variant, Integer errcode) {
        ReminderSendLog logRow = new ReminderSendLog();
        logRow.setReminderId(reminderId);
        logRow.setUserId(userId);
        logRow.setTriggerDate(today);
        logRow.setResult(result);
        logRow.setMessageVariant(variant);
        logRow.setWxErrcode(errcode);
        logRow.setCreatedAt(LocalDateTime.now(clock));
        try {
            sendLogRepository.saveAndFlush(logRow);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 并发触发：另一线程已为同 (reminder_id, trigger_date) 写入 → 静默放弃本次（需求 6.6）。
            log.debug("发送记录唯一键冲突，静默放弃本次, reminderId={}, triggerDate={}", reminderId, today);
            return false;
        }
    }
}
