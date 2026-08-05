package com.damien.youyu.service;

/**
 * 上报订阅授权请求（需求 5.1）：唯一入参是本次经 {@code wx.requestSubscribeMessage} 对提醒模板
 * 点击「允许」的次数 {@code grantedCount}（合法取值为 {@code 1}～{@code 5} 的整数）。
 *
 * <p><b>{@code grantedCount} 刻意声明为 {@link String} 而非 {@link Integer}</b>，与
 * {@code AchievementAckRequest} 把 {@code lastEventId} 声明为 {@code String} 同一理由：
 * 缺失 / 空白 / 不可解析 / 越界一律须由服务层收敛为 {@code REMINDER_GRANT_INVALID}（需求 5.4），
 * 不能让 Jackson 在进入方法体之前把 {@code "abc"} 抛成 {@code REQUEST_BODY_INVALID}（另一错误码、
 * 另一套字段集），也不能绕过控制器第一步的「令牌用户仍存在」校验。Jackson 会把 JSON 数字
 * {@code 3} 也收成字符串 {@code "3"}，因此客户端传数字或字符串都能工作。</p>
 *
 * @param grantedCount 本次授权次数的<strong>原文</strong>（合法为 {@code 1}～{@code 5} 的整数），
 *                     缺失 / 不可解析 / 越界由 {@link ReminderService#grantQuota} 拒绝
 */
public record ReminderGrantRequest(String grantedCount) {
}
