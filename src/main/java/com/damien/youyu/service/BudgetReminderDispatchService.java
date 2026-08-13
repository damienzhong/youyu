package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.BudgetReminderSendLog;
import com.damien.youyu.repository.BudgetReminderSendLogRepository;
import com.damien.youyu.repository.BudgetReminderSettingRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;

/**
 * 单收件人单范围单级别的<strong>一次预算提醒发送尝试</strong>（链路 B 的核心，高风险）：幂等预检 →
 * 选文案 → 查额度/openid → 发微信 → 写发送记录 → 扣/归零额度，全过程就地捕获、绝不向记账/登录/注销/
 * 结算等主路径传播异常（需求 4.5、9.4）。评估、收件人筛选与逐项去重派发由
 * {@code BudgetReminderEvaluationService} 承担，本服务只处理「这一条」。
 *
 * <h2>发送顺序（design.md §5）</h2>
 * <ol>
 *   <li><b>幂等预检</b>（需求 3.2）：{@code (userId, ledgerId, budgetMonth, scopeRef, level)} 已有记录
 *       → 直接 return，不重复发。这是唯一键 {@code uk_budget_reminder_send_logs_scope} 之外的先行友好短路。</li>
 *   <li><b>选文案</b>（需求 5）：{@link BudgetReminderMessageResolver#pick} 由级别与范围映射到一条文案。</li>
 *   <li><b>额度 / openid</b>（需求 4.3、4.4）：剩余额度为 0 → 写 {@code SKIPPED_NO_QUOTA}；{@code openid}
 *       空 → 写 {@code SKIPPED_NO_OPENID}；两者均不发、不扣额度、不报错。</li>
 *   <li><b>发微信</b>（需求 4.2、4.5、4.6、4.8）：{@code errcode=0} → 写 {@code SENT} +
 *       {@link BudgetReminderSettingRepository#decrementFloorZero}（-1 且不小于 0）；非零/异常 → 写
 *       {@code FAILED}（记 errcode）、额度不动；{@code 43101}（拒收/无额度）→ 额度归零对齐。</li>
 * </ol>
 *
 * <h2>幂等与并发（需求 3.4）</h2>
 * <p>发送记录写入撞唯一键（并发触发时另一线程已写）→ 捕 {@link DataIntegrityViolationException}
 * 后<b>静默放弃本次</b>：不重复发微信、不报错。发送前的幂等预检 + 唯一键是双保险——照抄
 * {@code ReminderDispatchService} 的范式，作用于<b>独立</b>的预算提醒额度与发送记录。</p>
 *
 * <p>Feature: subscribe-message-reminders。覆盖需求 3.2、3.4、4.2~4.6、4.8、5。</p>
 */
@Service
public class BudgetReminderDispatchService {

    private static final Logger log = LoggerFactory.getLogger(BudgetReminderDispatchService.class);

    /**
     * 微信「用户拒收 / 订阅额度不足」错误码（需求 4.6）：微信侧已无额度，本地计数须归零对齐。
     * 微信文档中 {@code 43101} 表示用户未订阅或已取消订阅该模板消息。
     */
    static final int ERRCODE_USER_REFUSED = 43101;

    /** 发送结果落库取值（需求 8.3）。 */
    static final String RESULT_SENT = "SENT";
    static final String RESULT_SKIPPED_NO_QUOTA = "SKIPPED_NO_QUOTA";
    static final String RESULT_SKIPPED_NO_OPENID = "SKIPPED_NO_OPENID";
    static final String RESULT_FAILED = "FAILED";

    /** 时区一律 {@code Asia/Shanghai}（经注入的 {@link Clock} 保证），此处只负责格式化。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 微信订阅消息 {@code thing} 型字段字符上限（超出则截断）。 */
    private static final int THING_MAX_CHARS = 20;

    private final BudgetReminderSendLogRepository sendLogRepository;
    private final BudgetReminderSettingRepository settingRepository;
    private final WeChatAccessTokenProvider accessTokenProvider;
    private final WeChatClient weChatClient;
    private final SubscribeTemplateProvider templateProvider;
    private final Clock clock;

    public BudgetReminderDispatchService(BudgetReminderSendLogRepository sendLogRepository,
                                         BudgetReminderSettingRepository settingRepository,
                                         WeChatAccessTokenProvider accessTokenProvider,
                                         WeChatClient weChatClient,
                                         SubscribeTemplateProvider templateProvider,
                                         Clock clock) {
        this.sendLogRepository = sendLogRepository;
        this.settingRepository = settingRepository;
        this.accessTokenProvider = accessTokenProvider;
        this.weChatClient = weChatClient;
        this.templateProvider = templateProvider;
        this.clock = clock;
    }

    /**
     * 对单收件人单范围单级别执行一次发送尝试（顺序见类级 Javadoc）。
     *
     * @param userId             收件人用户 id
     * @param ledgerId           账本 id
     * @param month              预算自然月（{@code Asia/Shanghai}）
     * @param scopeRef           预算范围：{@code 0} 表示月度总预算，大于 {@code 0} 表示分类 id
     * @param level              级别：{@code WARN} / {@code OVER}
     * @param categoryNameOrNull 分类当前名称（范围为总预算或名称不可得时可为 {@code null}）
     * @param overAmount         超预算金额（{@code BigDecimal}，{@code OVER} 为已支出-预算、{@code WARN} 为 0），
     *                           用于填充预算超支通知模板的 {@code amount2} 字段
     * @param openid             收件人 {@code wx_openid}（可空）
     * @param remaining          收件人当前预算提醒剩余订阅次数（由评估侧读入，避免重复查询）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(Long userId, Long ledgerId, YearMonth month, long scopeRef,
                         String level, String categoryNameOrNull, BigDecimal overAmount,
                         String openid, int remaining) {
        String monthKey = month.toString();

        // ① 幂等预检（需求 3.2）：同键已有记录 → 不重复发。
        if (sendLogRepository.existsByUserIdAndLedgerIdAndBudgetMonthAndScopeRefAndLevel(
                userId, ledgerId, monthKey, scopeRef, level)) {
            return;
        }

        // ② 选文案（需求 5）。
        String message = BudgetReminderMessageResolver.pick(level, scopeRef, categoryNameOrNull);

        // ③ 额度 / openid（需求 4.3、4.4）：不发、不扣额度、不报错。
        if (remaining <= 0) {
            writeLog(userId, ledgerId, monthKey, scopeRef, level, RESULT_SKIPPED_NO_QUOTA, null);
            return;
        }
        if (openid == null || openid.isBlank()) {
            writeLog(userId, ledgerId, monthKey, scopeRef, level, RESULT_SKIPPED_NO_OPENID, null);
            return;
        }

        // ④ 发微信（需求 4.2、4.5、4.6、4.8）。
        sendAndRecord(userId, ledgerId, monthKey, scopeRef, level, overAmount, openid, message);
    }

    /**
     * 调微信 {@code sendBudgetSubscribeMessage} 并按结果落记录、扣/归零额度（需求 4.2、4.5、4.6、4.8）。
     *
     * <p>{@code errcode=0} → {@code SENT} + 额度 -1；非零 → {@code FAILED}（记 errcode）、额度不动，
     * 其中 {@code 43101} 额外归零对齐；任何异常 → {@code FAILED}（errcode 空）、额度不动、不外抛。</p>
     */
    private void sendAndRecord(Long userId, Long ledgerId, String monthKey, long scopeRef,
                               String level, BigDecimal overAmount, String openid, String message) {
        // 模板 id 从数据库配置读取（替代旧的环境变量来源）；未配置 / 未启用 → 视为发送失败，
        // 写 FAILED（errcode 空）、不扣额度、不外抛，与微信调用异常分支一致（需求 4.5）。
        java.util.Optional<String> templateId = templateProvider.templateId(SubscribeTemplateProvider.BIZ_BUDGET);
        if (templateId.isEmpty()) {
            writeLog(userId, ledgerId, monthKey, scopeRef, level, RESULT_FAILED, null);
            log.warn("预算提醒模板未配置，发送安全降级为失败, userId={}, ledgerId={}, scopeRef={}, level={}",
                    userId, ledgerId, scopeRef, level);
            return;
        }
        try {
            String token = accessTokenProvider.getToken();
            // 按模板字段填值：amount2=超预算金额(保留2位纯数字)、time3=当前时刻(Asia/Shanghai)、thing4=文案。
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("amount2", formatAmount(overAmount));
            fields.put("time3", LocalDateTime.now(clock).format(TIME_FORMAT));
            fields.put("thing4", truncateThing(message));
            int errcode = weChatClient.sendSubscribeMessage(token, openid, templateId.get(), fields);
            if (errcode == 0) {
                // 成功：先写 SENT（唯一键冲突则本次作废、不扣额度），再扣额度（需求 4.2）。
                boolean written = writeLog(userId, ledgerId, monthKey, scopeRef, level, RESULT_SENT, 0);
                if (written) {
                    settingRepository.decrementFloorZero(userId, LocalDateTime.now(clock));
                }
            } else {
                // 失败：写 FAILED 记 errcode，额度不动（需求 4.5）；43101 → 额度归零对齐（需求 4.6）。
                writeLog(userId, ledgerId, monthKey, scopeRef, level, RESULT_FAILED, errcode);
                log.warn("预算提醒发送失败, userId={}, ledgerId={}, scopeRef={}, level={}, errcode={}",
                        userId, ledgerId, scopeRef, level, errcode);
                if (errcode == ERRCODE_USER_REFUSED) {
                    settingRepository.zeroOut(userId, LocalDateTime.now(clock));
                }
            }
        } catch (RuntimeException ex) {
            // 需求 4.5：微信调用抛异常 → FAILED（errcode 空）、不扣额度、不外抛，记不含金额/邮箱/令牌的告警日志。
            writeLog(userId, ledgerId, monthKey, scopeRef, level, RESULT_FAILED, null);
            log.warn("预算提醒发送异常, userId={}, ledgerId={}, scopeRef={}, level={}",
                    userId, ledgerId, scopeRef, level, ex);
        }
    }

    /** 超预算金额格式化为保留 2 位小数的纯数字字符串（如 {@code "10.00"}）；空值兜底为 {@code "0.00"}。 */
    private static String formatAmount(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 微信 {@code thing} 型字段上限 20 字符：超长截断，保证落入模板字段限制内。 */
    private static String truncateThing(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > THING_MAX_CHARS ? text.substring(0, THING_MAX_CHARS) : text;
    }

    /**
     * 写一条发送记录（需求 8.3）。撞唯一键 {@code uk_budget_reminder_send_logs_scope}（并发触发时另一线程已写）
     * → 捕 {@link DataIntegrityViolationException} 后静默放弃本次、不报错（需求 3.4）。
     *
     * @return {@code true} 成功写入；{@code false} 唯一键冲突、本次作废（调用方据此决定是否扣额度）
     */
    private boolean writeLog(Long userId, Long ledgerId, String monthKey, long scopeRef,
                             String level, String result, Integer errcode) {
        BudgetReminderSendLog row = new BudgetReminderSendLog();
        row.setUserId(userId);
        row.setLedgerId(ledgerId);
        row.setBudgetMonth(monthKey);
        row.setScopeRef(scopeRef);
        row.setLevel(level);
        row.setResult(result);
        row.setWxErrcode(errcode);
        row.setCreatedAt(LocalDateTime.now(clock));
        try {
            sendLogRepository.saveAndFlush(row);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 并发触发：另一线程已为同键写入 → 静默放弃本次（需求 3.4）。
            log.debug("预算提醒发送记录唯一键冲突，静默放弃本次, userId={}, ledgerId={}, scopeRef={}, level={}",
                    userId, ledgerId, scopeRef, level);
            return false;
        }
    }
}
