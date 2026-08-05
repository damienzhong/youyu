package com.damien.youyu.service;

/**
 * 提醒列表项 / 单条提醒响应：字段集<strong>恰好为 4 项</strong>——提醒 id、频率、提醒时间与启用状态
 * （需求 1.2、7.1、7.3）。
 *
 * <p>创建、更新与列表三处的单条提醒表示统一收敛到本记录，字段集因此逐处相等：不含 {@code user_id}
 * / {@code created_at} / {@code updated_at} 等内部列，也不含任何金额、账本名、邮箱与邀请码
 * （需求 8.4、10.11）。</p>
 *
 * @param reminderId 提醒的自增主键（正整数）
 * @param frequency  频率枚举名：{@code DAILY} / {@code WEEKDAY} / {@code WEEKEND}
 * @param remindTime 提醒时间，{@code HH:mm}（24 小时制，分钟粒度，{@code Asia/Shanghai} 口径）
 * @param enabled    是否启用
 */
public record ReminderItem(Long reminderId, String frequency, String remindTime, boolean enabled) {
}
