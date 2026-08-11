package com.damien.youyu.service;

/**
 * 预算提醒状态响应体（subscribe-message-reminders 需求 1.1）。
 *
 * <p>恰含两项：{@code enabled}（预算提醒偏好，无记录缺省为真）与 {@code remainingQuota}
 * （预算提醒剩余订阅次数，独立于记账提醒，无记录为 0）。查询状态、更新偏好、上报授权三处均以此返回。</p>
 *
 * @param enabled        预算提醒偏好
 * @param remainingQuota 预算提醒剩余订阅次数（{@code [0,50]}）
 */
public record BudgetReminderStatus(boolean enabled, int remainingQuota) {
}
