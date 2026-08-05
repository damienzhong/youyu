package com.damien.youyu.service;

/**
 * 更新提醒请求（需求 7.3、7.4、7.8）：三个字段<strong>均可选</strong>——只提交需要改动的字段，
 * 缺省（{@code null}）者保持原值不变。
 *
 * <p><b>{@code frequency} 与 {@code remindTime} 声明为 {@link String}</b>，与
 * {@link ReminderCreateRequest} 同一理由：取值非法须由服务层解析后映射到本域的
 * {@code REMINDER_FREQUENCY_INVALID} / {@code REMINDER_TIME_INVALID}，不让框架类型转换抢先报错。
 * {@code null} 与非 {@code null} 在此语义不同：{@code null} 表示「不改」，非 {@code null} 表示
 * 「改为该值并校验」——因此这两个字段不能用基本类型，也不能让框架把缺失与非法混为一谈。</p>
 *
 * @param frequency  新频率原文；{@code null} 表示不改（保持原值）
 * @param remindTime 新提醒时间原文；{@code null} 表示不改
 * @param enabled    新启用状态；{@code null} 表示不改
 */
public record ReminderUpdateRequest(String frequency, String remindTime, Boolean enabled) {
}
