package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.ReminderFrequency;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CustomReminderRepository;
import com.damien.youyu.repository.ReminderQuotaRepository;

/**
 * 自定义提醒的<strong>同步操作服务</strong>（链路 A）：提醒的增删改查与订阅授权上报，与账本无关，
 * 数据归属只认令牌用户 id。定时触发与发送（链路 B）由 {@code ReminderScheduler} /
 * {@code ReminderDispatchService} 承担，不在本服务内。
 *
 * <h2>创建校验优先级（需求 1.9）</h2>
 * <p>{@link #create} 严格按 {@code FREQUENCY > TIME > DUPLICATE > LIMIT} 短路：先
 * {@link #parseFrequency}（非法即 {@code REMINDER_FREQUENCY_INVALID}），再 {@link #parseHhmm}
 * （非法即 {@code REMINDER_TIME_INVALID}），再
 * {@link CustomReminderRepository#existsByUserIdAndFrequencyAndRemindTime}（命中即
 * {@code REMINDER_DUPLICATE}），最后 {@link CustomReminderRepository#countByUserId}
 * （达上限即 {@code REMINDER_LIMIT_EXCEEDED}）。同时命中多条时只返回优先级最高者，因短路而构造性成立。</p>
 *
 * <p>唯一约束 {@code uk_custom_reminders_user_freq_time} 在库侧兜底并发下的重复插入：写入撞唯一键抛
 * {@link DataIntegrityViolationException} 时映射为 {@code REMINDER_DUPLICATE}，应用层 {@code exists}
 * 只是先行友好校验（需求 1.5、7.8）。</p>
 *
 * <h2>更新 / 删除的归属统一 {@code NOT_FOUND}（需求 7.5、8.8）</h2>
 * <p>{@link #update} 与 {@link #delete} 一律先 {@link CustomReminderRepository#findByIdAndUserId}
 * 定位：为空即「不存在或不属于本人」，两种情形返回<strong>完全相同</strong>的 {@code NOT_FOUND}，
 * 不泄漏他人提醒是否存在。更新只写提交（非空）字段、未提交字段保持原值；校验失败在改动前抛出，
 * 整行保持不变（需求 7.3、7.4）；改动后与本人另一条撞频率与时间 → {@code REMINDER_DUPLICATE}（需求 7.8）。</p>
 *
 * <h2>额度原子增减（需求 5.8）</h2>
 * <p>{@link #grantQuota} 走 {@link ReminderQuotaRepository#addCapped} 的 UPSERT 原子上限累加，
 * 不做「先查后写」，与发送侧的 {@code decrementFloorZero} 一并保证并发的上报与扣减不丢更新。</p>
 *
 * <p>Feature: custom-reminder。覆盖需求 1.1～1.7、1.9；5.1～5.4、5.7、5.8；7.1～7.8。</p>
 */
@Service
public class ReminderService {

    /** 每用户提醒数量上限（需求 1.6）：已有 10 条时拒绝创建第 11 条。 */
    static final int MAX_REMINDERS_PER_USER = 10;

    /** 订阅额度累积上限（需求 5.3）。实际上限施加在 {@link ReminderQuotaRepository#addCapped} 的 SQL 内。 */
    static final int QUOTA_CAP = 50;

    /** 上报订阅授权次数的合法闭区间 {@code [1,5]}（需求 5.1、5.4）。 */
    private static final int GRANT_MIN = 1;
    private static final int GRANT_MAX = 5;

    /**
     * 提醒时间格式（需求 1.4）：零填充两位小时（{@code 00-23}）与两位分钟（{@code 00-59}），不含秒。
     * {@code 8:00}、{@code 08:60}、{@code 24:00}、{@code 08:00:00} 均不匹配。
     */
    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** {@code HH:mm} 解析 / 格式化器（分钟粒度）。 */
    private static final DateTimeFormatter HHMM_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final CustomReminderRepository customReminderRepository;
    private final ReminderQuotaRepository quotaRepository;
    private final Clock clock;

    public ReminderService(CustomReminderRepository customReminderRepository,
                           ReminderQuotaRepository quotaRepository,
                           Clock clock) {
        this.customReminderRepository = customReminderRepository;
        this.quotaRepository = quotaRepository;
        this.clock = clock;
    }

    /**
     * 创建提醒（需求 1.1～1.7、1.9）：按 {@code FREQUENCY > TIME > DUPLICATE > LIMIT} 短路校验后落一行。
     *
     * @param userId     令牌所标识的用户 id（调用方已确认其在 {@code users} 表中仍存在）
     * @param frequency  频率原文（区分大小写，须为 {@code DAILY} / {@code WEEKDAY} / {@code WEEKEND}）
     * @param remindTime 提醒时间原文（须为 {@code HH:mm}，{@code 00:00}～{@code 23:59}）
     * @param enabled    启用状态；{@code null} 时缺省为启用（需求 1.1）
     * @return 新建提醒的 4 项字段
     */
    @Transactional
    public ReminderItem create(Long userId, String frequency, String remindTime, Boolean enabled) {
        // 校验优先级由高到低短路（需求 1.9）：FREQUENCY > TIME > DUPLICATE > LIMIT。
        ReminderFrequency freq = parseFrequency(frequency);          // REMINDER_FREQUENCY_INVALID
        LocalTime time = parseHhmm(remindTime);                      // REMINDER_TIME_INVALID
        if (customReminderRepository.existsByUserIdAndFrequencyAndRemindTime(userId, freq, time)) {
            throw ApiException.reminderDuplicate();                  // REMINDER_DUPLICATE
        }
        if (customReminderRepository.countByUserId(userId) >= MAX_REMINDERS_PER_USER) {
            throw ApiException.reminderLimitExceeded();              // REMINDER_LIMIT_EXCEEDED
        }

        LocalDateTime now = LocalDateTime.now(clock);
        CustomReminder r = new CustomReminder();
        r.setUserId(userId);
        r.setFrequency(freq);
        r.setRemindTime(time);
        r.setEnabled(enabled == null || enabled);                    // 缺省启用（需求 1.1）
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        try {
            customReminderRepository.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            // 并发下另一线程已插入同 (user_id, frequency, remind_time)：唯一约束兜底（需求 1.5）。
            throw ApiException.reminderDuplicate();
        }
        return toItem(r);
    }

    /**
     * 更新提醒（需求 7.3、7.4、7.5、7.8）：只写提交（非空）字段，未提交字段保持原值；校验失败整行不变；
     * 改动后与本人另一条撞频率与时间 → {@code REMINDER_DUPLICATE}。
     *
     * @param userId     令牌所标识的用户 id
     * @param reminderId 目标提醒 id
     * @param frequency  新频率原文；{@code null} 表示不改（保持原值）
     * @param remindTime 新提醒时间原文；{@code null} 表示不改
     * @param enabled    新启用状态；{@code null} 表示不改
     * @return 更新后提醒的 4 项字段
     */
    @Transactional
    public ReminderItem update(Long userId, Long reminderId, String frequency, String remindTime, Boolean enabled) {
        // 不存在或不属于本人 → 统一 NOT_FOUND（需求 7.5、8.8），不泄漏他人提醒是否存在。
        CustomReminder r = customReminderRepository.findByIdAndUserId(reminderId, userId)
                .orElseThrow(() -> ApiException.notFound("提醒不存在"));

        // 先校验提交字段（需求 7.4）：任一非法在改动前抛出，目标提醒整行保持不变。
        ReminderFrequency newFreq = (frequency == null) ? r.getFrequency() : parseFrequency(frequency);
        LocalTime newTime = (remindTime == null) ? r.getRemindTime() : parseHhmm(remindTime);

        // 频率或时间实际改动，且撞本人另一条 → REMINDER_DUPLICATE（需求 7.8）。改动前的组合等于本行自身，
        // 只在组合确有变化时才做去重预检，避免把本行误判为重复。
        boolean comboChanged = newFreq != r.getFrequency() || !newTime.equals(r.getRemindTime());
        if (comboChanged
                && customReminderRepository.existsByUserIdAndFrequencyAndRemindTime(userId, newFreq, newTime)) {
            throw ApiException.reminderDuplicate();
        }

        r.setFrequency(newFreq);
        r.setRemindTime(newTime);
        if (enabled != null) {
            r.setEnabled(enabled);
        }
        r.setUpdatedAt(LocalDateTime.now(clock));
        try {
            customReminderRepository.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            // 并发下与另一条撞唯一键：唯一约束兜底（需求 7.8）。
            throw ApiException.reminderDuplicate();
        }
        return toItem(r);
    }

    /**
     * 删除提醒（需求 7.5、7.6）：删除 {@code custom_reminders} 行，<strong>不</strong>删除其历史发送记录
     * （发送记录是已发生事实）。不存在或不属于本人 → 统一 {@code NOT_FOUND}。
     *
     * @param userId     令牌所标识的用户 id
     * @param reminderId 目标提醒 id
     */
    @Transactional
    public void delete(Long userId, Long reminderId) {
        CustomReminder r = customReminderRepository.findByIdAndUserId(reminderId, userId)
                .orElseThrow(() -> ApiException.notFound("提醒不存在"));
        // 只删提醒配置行；reminder_send_logs 的历史记录不动（需求 7.6）。
        customReminderRepository.delete(r);
    }

    /**
     * 查询本人提醒与剩余订阅次数（需求 5.7、7.1、7.2）：仅本人提醒，按 {@code created_at} 升序，
     * 每项 4 字段；无提醒时为空列表；剩余订阅次数无记录时为 0。
     *
     * @param userId 令牌所标识的用户 id
     * @return 提醒列表 + 剩余订阅次数
     */
    @Transactional(readOnly = true)
    public ReminderListResponse list(Long userId) {
        List<CustomReminder> rows = customReminderRepository.findByUserIdOrderByCreatedAtAsc(userId);
        List<ReminderItem> items = new ArrayList<>(rows.size());
        for (CustomReminder r : rows) {
            items.add(toItem(r));
        }
        int remaining = quotaRepository.findRemaining(userId).orElse(0);
        return new ReminderListResponse(items, remaining);
    }

    /**
     * 上报订阅授权（需求 5.1、5.2、5.3、5.4、5.8）：{@code grantedCount} 解析为整数且 ∈ {@code [1,5]}，
     * 否则 {@code REMINDER_GRANT_INVALID}；经 {@link ReminderQuotaRepository#addCapped} 原子上限累加
     * （上限 50，防并发丢更新），返回增加后的剩余订阅次数。
     *
     * @param userId          令牌所标识的用户 id
     * @param grantedCountRaw 本次授权次数原文（须为 {@code 1}～{@code 5} 的整数）
     * @return 增加后的剩余订阅次数，∈ {@code [1,50]}
     */
    @Transactional
    public int grantQuota(Long userId, String grantedCountRaw) {
        int grantedCount = parseGrantedCount(grantedCountRaw);       // REMINDER_GRANT_INVALID
        // 原子上限累加：不存在则插入 min(delta,50)，存在则 min(remaining+delta,50)，防并发丢更新（需求 5.8）。
        quotaRepository.addCapped(userId, grantedCount, LocalDateTime.now(clock));
        return quotaRepository.findRemaining(userId).orElse(0);
    }

    // ── 解析 / 映射工具 ──────────────────────────────────────────────────────────────────────────

    /**
     * 解析频率（需求 1.3）：区分大小写精确匹配枚举名，缺失 / 为空 / 非枚举一律
     * {@code REMINDER_FREQUENCY_INVALID}。不做 trim——带空白即视为非法。
     */
    private ReminderFrequency parseFrequency(String raw) {
        if (raw != null) {
            for (ReminderFrequency f : ReminderFrequency.values()) {
                if (f.name().equals(raw)) {
                    return f;
                }
            }
        }
        throw ApiException.reminderFrequencyInvalid();
    }

    /**
     * 解析提醒时间（需求 1.4）：须匹配 {@link #HHMM}（零填充 {@code HH:mm}，小时 {@code 00-23}、
     * 分钟 {@code 00-59}），否则 {@code REMINDER_TIME_INVALID}。
     */
    private LocalTime parseHhmm(String raw) {
        if (raw == null || !HHMM.matcher(raw).matches()) {
            throw ApiException.reminderTimeInvalid();
        }
        return LocalTime.parse(raw, HHMM_FMT);
    }

    /**
     * 解析上报授权次数（需求 5.4）：缺失 / 空白 / 无法解析为整数 / 不在 {@code [1,5]} 一律
     * {@code REMINDER_GRANT_INVALID}。
     */
    private int parseGrantedCount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.reminderGrantInvalid();
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.reminderGrantInvalid();
        }
        if (value < GRANT_MIN || value > GRANT_MAX) {
            throw ApiException.reminderGrantInvalid();
        }
        return value;
    }

    /** 实体 → 响应项（需求 1.2、7.1、7.3）：频率取枚举名，时间格式化为 {@code HH:mm}。 */
    private ReminderItem toItem(CustomReminder r) {
        return new ReminderItem(r.getId(), r.getFrequency().name(),
                r.getRemindTime().format(HHMM_FMT), r.isEnabled());
    }
}
