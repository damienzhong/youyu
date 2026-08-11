package com.damien.youyu.service;

/**
 * 更新预算提醒偏好的请求体（subscribe-message-reminders 需求 1.3）。
 *
 * <p>{@code enabled} 以原文（{@code String}）接收，交 {@code BudgetReminderService} 解析，
 * 以精确返回本域错误码 {@code BUDGET_REMINDER_PREF_INVALID}，而非让框架类型转换抢先抛出
 * {@code REQUEST_BODY_INVALID}（另一错误码且会绕过鉴权校验）。Jackson 会把 JSON 布尔强制转为字符串，
 * 故 miniapp 传布尔或字符串均可。</p>
 *
 * @param enabled 预算提醒偏好原文（{@code "true"} / {@code "false"}）
 */
public record BudgetReminderPreferenceRequest(String enabled) {
}
