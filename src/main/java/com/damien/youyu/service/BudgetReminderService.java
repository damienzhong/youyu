package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.BudgetReminderSetting;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.BudgetReminderSettingRepository;

/**
 * 预算提醒的<strong>同步操作服务</strong>（链路 A）：预算提醒偏好的查询 / 更新与订阅授权上报，与账本无关，
 * 数据归属只认令牌用户 id。事件驱动的评估与发送（链路 B）由 {@code BudgetReminderEvaluationService} /
 * {@code BudgetReminderDispatchService} 承担，不在本服务内。
 *
 * <h2>缺省语义（需求 1.2）</h2>
 * <p>{@link #getStatus} 读 {@code budget_reminder_settings}；无记录返回缺省 {@code {enabled=true,
 * remainingQuota=0}}，且<b>不建行</b>——预算提醒缺省开启，初始无额度。</p>
 *
 * <h2>偏好更新（需求 1.4、1.5）</h2>
 * <p>{@link #updatePreference} 以原文接收 {@code enabled}：为 {@code null} 或不可解析为布尔 → 抛
 * {@code BUDGET_REMINDER_PREF_INVALID} 且偏好不变；合法则 UPSERT 偏好、置 {@code updated_at} 为服务端
 * 当前时刻，返回最新 {@code {enabled, remainingQuota}}。</p>
 *
 * <h2>额度原子累加（需求 6.2~6.5）</h2>
 * <p>{@link #grantQuota} 解析并校验 {@code grantedCount ∈ [1,5]}，否则 {@code BUDGET_REMINDER_GRANT_INVALID}
 * 且额度不变；合法走 {@link BudgetReminderSettingRepository#addCapped} 的 UPSERT 原子上限累加（封顶 50，
 * 防并发丢更新），与发送侧的 {@code decrementFloorZero} 一并保证并发的上报与扣减不丢更新。</p>
 *
 * <p>Feature: subscribe-message-reminders。覆盖需求 1.2、1.4、1.5、6.2、6.3、6.4。</p>
 */
@Service
public class BudgetReminderService {

    /** 上报订阅授权次数的合法闭区间 {@code [1,5]}（需求 6.1、6.4）。 */
    private static final int GRANT_MIN = 1;
    private static final int GRANT_MAX = 5;

    private final BudgetReminderSettingRepository settingRepository;
    private final Clock clock;

    public BudgetReminderService(BudgetReminderSettingRepository settingRepository, Clock clock) {
        this.settingRepository = settingRepository;
        this.clock = clock;
    }

    /**
     * 查询本人预算提醒状态（需求 1.1、1.2）：无记录返回缺省 {@code {enabled=true, remainingQuota=0}}，不建行。
     *
     * @param userId 令牌所标识的用户 id
     * @return 预算提醒状态
     */
    @Transactional(readOnly = true)
    public BudgetReminderStatus getStatus(Long userId) {
        return settingRepository.findByUserId(userId)
                .map(s -> new BudgetReminderStatus(s.isEnabled(), s.getRemaining()))
                .orElseGet(() -> new BudgetReminderStatus(true, 0));
    }

    /**
     * 更新本人预算提醒偏好（需求 1.4、1.5）：{@code enabled} 为 {@code null} 或不可解析为布尔 → 抛
     * {@code BUDGET_REMINDER_PREF_INVALID} 且偏好不变；合法则 UPSERT 偏好并置 {@code updated_at}，
     * 返回最新状态。
     *
     * @param userId     令牌所标识的用户 id
     * @param enabledRaw 偏好原文（须为布尔值 {@code true} / {@code false}）
     * @return 更新后的预算提醒状态
     */
    @Transactional
    public BudgetReminderStatus updatePreference(Long userId, Boolean enabledRaw) {
        if (enabledRaw == null) {
            throw ApiException.budgetReminderPrefInvalid();
        }
        settingRepository.upsertEnabled(userId, enabledRaw ? 1 : 0, LocalDateTime.now(clock));
        return getStatus(userId);
    }

    /**
     * 上报本人预算提醒订阅授权（需求 6.1~6.5）：解析 {@code grantedCount} 且 {@code ∈ [1,5]}，否则
     * {@code BUDGET_REMINDER_GRANT_INVALID}；经 {@link BudgetReminderSettingRepository#addCapped}
     * 原子上限累加（封顶 50，防并发丢更新），返回增加后的剩余订阅次数。
     *
     * @param userId          令牌所标识的用户 id
     * @param grantedCountRaw 本次授权次数原文（须为 {@code 1}~{@code 5} 的整数）
     * @return 增加后的剩余订阅次数，{@code ∈ [1,50]}
     */
    @Transactional
    public int grantQuota(Long userId, String grantedCountRaw) {
        int grantedCount = parseGrantedCount(grantedCountRaw);   // BUDGET_REMINDER_GRANT_INVALID
        settingRepository.addCapped(userId, grantedCount, LocalDateTime.now(clock));
        return settingRepository.findRemaining(userId).orElse(0);
    }

    /**
     * 解析上报授权次数（需求 6.4）：缺失 / 空白 / 无法解析为整数 / 不在 {@code [1,5]} 一律
     * {@code BUDGET_REMINDER_GRANT_INVALID}。
     */
    private int parseGrantedCount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.budgetReminderGrantInvalid();
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.budgetReminderGrantInvalid();
        }
        if (value < GRANT_MIN || value > GRANT_MAX) {
            throw ApiException.budgetReminderGrantInvalid();
        }
        return value;
    }
}
