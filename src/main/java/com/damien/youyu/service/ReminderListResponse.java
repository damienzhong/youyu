package com.damien.youyu.service;

import java.util.List;

/**
 * 提醒设置查询响应：顶层<strong>恰好 2 项</strong>——本人提醒列表与剩余订阅次数
 * （需求 5.7、7.1、7.2）。
 *
 * <p>{@code reminders} 仅含 {@code user_id} 等于当前会话用户的提醒，按 {@code created_at} 升序；
 * 无任何提醒时为空列表（元素数量为 0），而非 {@code null}（需求 7.2）。{@code remainingQuota} 为该用户
 * 当前剩余订阅次数，取值恒 ≥ 0；该用户尚无订阅授权记录时为 0（需求 5.7、7.1、7.2）。</p>
 *
 * @param reminders      本人提醒列表，按 {@code created_at} 升序；无提醒时为空列表
 * @param remainingQuota 剩余订阅次数，≥ 0；无授权记录时为 0
 */
public record ReminderListResponse(List<ReminderItem> reminders, int remainingQuota) {
}
