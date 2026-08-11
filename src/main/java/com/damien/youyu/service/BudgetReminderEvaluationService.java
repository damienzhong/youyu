package com.damien.youyu.service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.BudgetOverviewResponse;
import com.damien.youyu.api.dto.BudgetOverviewResponse.CategoryBudgetItem;
import com.damien.youyu.domain.BudgetReminderSetting;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.repository.BudgetReminderSendLogRepository;
import com.damien.youyu.repository.BudgetReminderSettingRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.UserRepository;

/**
 * 预算提醒的<strong>评估 + 收件人筛选 + 去重派发</strong>（链路 B 的编排，高风险，独立事务隔离）：
 * 交易写成功后由 {@code BudgetReminderTrigger} 在 afterCommit 阶段调用，复用 {@code BudgetService} 的
 * 自然月已支出聚合与 {@code WARN}/{@code OVER} 阈值口径，逐范围求级别、筛收件人、去重后交
 * {@code BudgetReminderDispatchService} 逐条发送。
 *
 * <h2>评估步骤（design.md §4）</h2>
 * <ol>
 *   <li><b>月份闸门</b>（需求 2.3、2.7）：{@code occurredMonth != 当前月}（{@code Asia/Shanghai}）→ 直接返回。</li>
 *   <li><b>账本类型闸门</b>（需求 2.2、5）：账本不存在或为 AA 账本 → 直接返回；仅个人 / 协作账本继续。</li>
 *   <li><b>求各范围级别</b>（需求 2.1、2.4、2.5、2.6）：调 {@code budgetService.overview}，总预算
 *       {@code hasBudget && status ∈ {WARN,OVER}} → {@code scopeRef=0}；每个分类明细 {@code status ∈
 *       {WARN,OVER}} → {@code scopeRef=categoryId}；未设 / <=0 预算不出现在明细中（{@code OVER} 优先
 *       {@code WARN} 天然由 {@code status} 二选一保证）。</li>
 *   <li><b>收件人集合</b>（需求 1.6、4.1）：该账本成员中「偏好为真（无记录视真）且 openid 非空且
 *       remaining>0」的全部用户。</li>
 *   <li><b>逐 (收件人 × 范围) 去重派发</b>（需求 3.1、3.2、3.3）：{@code OVER} 已推则不补 {@code WARN}；
 *       同键已有记录则跳过（幂等由 {@code DispatchService} 与唯一键双保险）。</li>
 * </ol>
 *
 * <p>本服务方法标注 {@code REQUIRES_NEW}：由 afterCommit 回调在交易<b>确已提交</b>后调用，异常在事务边界
 * 之外由触发器就地吞掉、绝不穿回记账主路径（需求 2.8、9.4）。时区一律用注入的 {@link Clock}
 * （{@code Asia/Shanghai}），不依赖 JVM / DB / OS 默认时区（需求 2.7）。只读既有表、只写本 spec 新增两表
 * （需求 9.1）。</p>
 *
 * <p>Feature: subscribe-message-reminders。覆盖需求 1.6、2.1~2.7、3.1~3.3、4.1、5。</p>
 */
@Service
public class BudgetReminderEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(BudgetReminderEvaluationService.class);

    private static final String LEVEL_OVER = "OVER";
    private static final String LEVEL_WARN = "WARN";

    private final BudgetService budgetService;
    private final LedgerRepository ledgerRepository;
    private final LedgerMemberRepository memberRepository;
    private final BudgetReminderSettingRepository settingRepository;
    private final BudgetReminderSendLogRepository sendLogRepository;
    private final UserRepository userRepository;
    private final BudgetReminderDispatchService dispatchService;
    private final Clock clock;

    public BudgetReminderEvaluationService(BudgetService budgetService,
                                           LedgerRepository ledgerRepository,
                                           LedgerMemberRepository memberRepository,
                                           BudgetReminderSettingRepository settingRepository,
                                           BudgetReminderSendLogRepository sendLogRepository,
                                           UserRepository userRepository,
                                           BudgetReminderDispatchService dispatchService,
                                           Clock clock) {
        this.budgetService = budgetService;
        this.ledgerRepository = ledgerRepository;
        this.memberRepository = memberRepository;
        this.settingRepository = settingRepository;
        this.sendLogRepository = sendLogRepository;
        this.userRepository = userRepository;
        this.dispatchService = dispatchService;
        this.clock = clock;
    }

    /** 一个达到预警 / 超支级别的预算范围。 */
    private record ScopeLevel(long scopeRef, String level, String categoryNameOrNull) { }

    /** 一名合格收件人及其发送所需的 openid 与剩余额度。 */
    private record Recipient(Long userId, String openid, int remaining) { }

    /**
     * 评估某账本某当前自然月的预算提醒并派发（步骤见类级 Javadoc）。
     *
     * @param ledgerId      触发交易所属账本 id
     * @param occurredMonth 触发交易的发生月（{@code Asia/Shanghai}）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluate(Long ledgerId, YearMonth occurredMonth) {
        if (ledgerId == null || occurredMonth == null) {
            return;
        }
        // ① 月份闸门（需求 2.3、2.7）：只对当前自然月触发。
        YearMonth currentMonth = YearMonth.now(clock);
        if (!occurredMonth.equals(currentMonth)) {
            return;
        }
        // ② 账本类型闸门（需求 2.2、5）：AA 账本不设预算，不评估；账本不存在也不评估。
        Ledger ledger = ledgerRepository.findById(ledgerId).orElse(null);
        if (ledger == null || ledger.isAa()) {
            return;
        }

        // ③ 求各范围级别（需求 2.1、2.4、2.5、2.6）。
        List<ScopeLevel> scopes = resolveScopeLevels(ledgerId, currentMonth);
        if (scopes.isEmpty()) {
            return;
        }

        // ④ 收件人集合（需求 1.6、4.1）。
        List<Recipient> recipients = resolveRecipients(ledgerId);
        if (recipients.isEmpty()) {
            return;
        }

        // ⑤ 逐 (收件人 × 范围) 去重派发（需求 3.1、3.2、3.3）。
        String monthKey = currentMonth.toString();
        for (Recipient r : recipients) {
            for (ScopeLevel s : scopes) {
                // 超支已推则不补预警（需求 3.3）：同范围同月已有 OVER 记录时跳过 WARN。
                if (LEVEL_WARN.equals(s.level())
                        && sendLogRepository.existsOverLog(r.userId(), ledgerId, monthKey, s.scopeRef())) {
                    continue;
                }
                dispatchService.dispatch(r.userId(), ledgerId, currentMonth, s.scopeRef(),
                        s.level(), s.categoryNameOrNull(), r.openid(), r.remaining());
            }
        }
    }

    /** 由 {@code BudgetService.overview} 求出所有达到 WARN/OVER 级别的预算范围（需求 2.1、2.4~2.6）。 */
    private List<ScopeLevel> resolveScopeLevels(Long ledgerId, YearMonth month) {
        BudgetOverviewResponse overview = budgetService.overview(ledgerId, month);
        List<ScopeLevel> scopes = new ArrayList<>();
        // 月度总预算范围（scopeRef=0）：仅当已设总预算且状态为 WARN/OVER。
        if (overview.hasBudget() && isReminderLevel(overview.status())) {
            scopes.add(new ScopeLevel(0L, overview.status(), null));
        }
        // 分类预算范围（scopeRef=categoryId）：overview 只列出已设(>0)的分类预算。
        if (overview.categories() != null) {
            for (CategoryBudgetItem item : overview.categories()) {
                if (isReminderLevel(item.status())) {
                    scopes.add(new ScopeLevel(item.categoryId(), item.status(), item.name()));
                }
            }
        }
        return scopes;
    }

    /** 收件人：该账本成员中偏好为真（无记录视真）且 openid 非空且剩余额度>0 的用户（需求 1.6、4.1）。 */
    private List<Recipient> resolveRecipients(Long ledgerId) {
        List<LedgerMember> members = memberRepository.findByLedgerId(ledgerId);
        List<Recipient> recipients = new ArrayList<>(members.size());
        for (LedgerMember m : members) {
            Long userId = m.getUserId();
            if (userId == null) {
                continue;
            }
            BudgetReminderSetting setting = settingRepository.findByUserId(userId).orElse(null);
            boolean enabled = setting == null || setting.isEnabled();   // 无记录视为开启
            int remaining = setting == null ? 0 : setting.getRemaining();
            if (!enabled || remaining <= 0) {
                continue;
            }
            String openid = userRepository.findWxOpenid(userId).orElse(null);
            if (openid == null || openid.isBlank()) {
                continue;
            }
            recipients.add(new Recipient(userId, openid, remaining));
        }
        return recipients;
    }

    private boolean isReminderLevel(String status) {
        return LEVEL_WARN.equals(status) || LEVEL_OVER.equals(status);
    }

    /** 记一条不含金额 / 邮箱 / 令牌的告警日志（评估异常由触发器在事务边界外吞掉，此处仅供内部调用点使用）。 */
    void logEvaluationFailure(Long ledgerId, RuntimeException ex) {
        log.warn("预算提醒评估失败, ledgerId={}", ledgerId, ex);
    }
}
