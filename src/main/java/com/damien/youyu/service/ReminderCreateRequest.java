package com.damien.youyu.service;

/**
 * 创建提醒请求（需求 1.1）：{@code frequency} 与 {@code remindTime} 两个必填字段，
 * 可选 {@code enabled}（缺省为真）。
 *
 * <p><b>{@code frequency} 与 {@code remindTime} 刻意声明为 {@link String}</b>，与
 * {@code AchievementAckRequest} 把 {@code lastEventId} 声明为 {@code String} 同一理由：
 * <strong>不能让框架替我们做类型转换或类型绑定</strong>。取值非法（如空、小写 {@code daily}、
 * {@code 8:00}）必须由服务层解析并映射到本域的 {@code REMINDER_FREQUENCY_INVALID} /
 * {@code REMINDER_TIME_INVALID}，而不是被 Jackson 提前变成另一个错误码、另一套字段集，
 * 也不能绕过控制器第一步的「令牌用户仍存在」校验（需求 8.2 要求 {@code UNAUTHENTICATED}
 * 优先于任何字段校验）。故以原文接收，解析与校验全部落在 {@link ReminderService#create}。</p>
 *
 * @param frequency  频率原文（区分大小写，须为 {@code DAILY} / {@code WEEKDAY} / {@code WEEKEND}）
 * @param remindTime 提醒时间原文（须为 {@code HH:mm}，{@code 00:00}～{@code 23:59}）
 * @param enabled    启用状态；{@code null} 时缺省为启用（需求 1.1）
 */
public record ReminderCreateRequest(String frequency, String remindTime, Boolean enabled) {
}
