package com.damien.youyu.service;

import java.util.List;

/**
 * 待播报成就响应：顶层字段集<strong>恰好为</strong>「待播报成就项列表、待播报总条数」2 项（需求 5.4）。
 *
 * <p>{@code items} 按成就事件 id <strong>升序</strong>返回至多 10 项（先解锁的先播报）；
 * {@code total} 取<strong>截断前</strong>的全部待播报项个数而非本次返回的项数，
 * 剩余项在游标推进后的后续请求中继续按 id 升序返回（需求 5.5）。无待播报成就时
 * {@code items} 为空列表、{@code total} 为 0，且不返回错误（需求 5.16）。</p>
 *
 * <p>本响应对应的查询<strong>只读</strong>：不触发结算、不推进游标，连续两次请求返回相同的项、
 * 相同顺序与相同的 {@code total}（需求 5.14、5.17）。</p>
 *
 * <p>响应<strong>不含</strong> {@code email} / {@code wx_openid} / {@code wx_unionid} /
 * {@code invite_code} / {@code plan} / {@code role} 六个字段的键与取值，也<strong>不含</strong>
 * 任何金额字段（需求 6.12）。</p>
 *
 * @param items 待播报成就项，条数 ≤ 10，按成就事件 id 升序
 * @param total 待播报总条数（截断前），落在 {@code [0, 16]} 闭区间内
 */
public record PendingAchievementResponse(List<PendingAchievementItem> items, long total) {
}
