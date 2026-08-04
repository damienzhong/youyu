package com.damien.youyu.service;

import java.time.LocalDateTime;

/**
 * 经验明细列表项：字段集<strong>恰好等于</strong>需求 10.3 的 5 项。
 *
 * <p>刻意不含任何用于指定目标用户的字段，也不含 {@code email} / {@code wx_openid} /
 * {@code wx_unionid} / {@code invite_code} / {@code plan} / {@code role}（需求 10.13）。
 * 明细仅返回 {@code user_id} 等于当前会话用户的成长事件、按 {@code id} 倒序（需求 10.3）。</p>
 *
 * @param eventType 事件类型，取 {@code growth_events.event_type} 原值
 *                  （{@code FIRST_RECORD} / {@code DAILY_RECORD} / {@code STREAK} /
 *                  {@code BUDGET_MET} / {@code FIRST_INVITE} / {@code BADGE}）
 * @param id        成长事件主键
 * @param eventKey  事件键，取 {@code growth_events.event_key} 原值
 * @param expAmount 该事件的经验值（{@code BADGE} 事件恒为 0，需求 8.3）
 * @param createdAt 事件发生时刻
 */
public record GrowthEventItem(Long id, String eventType, String eventKey,
                              int expAmount, LocalDateTime createdAt) {
}
