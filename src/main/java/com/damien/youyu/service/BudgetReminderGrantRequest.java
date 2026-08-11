package com.damien.youyu.service;

/**
 * 上报预算提醒订阅授权的请求体（subscribe-message-reminders 需求 6.1）。
 *
 * <p>{@code grantedCount} 以原文（{@code String}）接收，交 {@code BudgetReminderService} 解析并校验
 * {@code [1,5]}，以精确返回 {@code BUDGET_REMINDER_GRANT_INVALID}，而非让框架类型转换抢先抛出
 * {@code REQUEST_BODY_INVALID}（照抄 {@code ReminderGrantRequest} 取舍）。</p>
 *
 * @param grantedCount 本次经 {@code wx.requestSubscribeMessage} 对预算提醒模板点击「允许」的次数原文
 */
public record BudgetReminderGrantRequest(String grantedCount) {
}
