package com.damien.youyu.service;

import java.util.List;

/**
 * 成就清单响应：顶层字段集<strong>恰好为</strong>「成就视图列表、已解锁成就数、成就总数」3 项（需求 6.1）。
 *
 * <p>这 3 个键在每次成功响应中恒存在，{@code achievements} 恒含 16 项、{@code total} 恒为 16，
 * 顶层不返回第 4 个字段（需求 6.1）。零数据新用户同样返回 16 项、全部未解锁、当前值全 0、
 * {@code unlockedCount} 为 0（需求 6.18）。</p>
 *
 * <p>结算失败或被节流时字段集与结算成功时<strong>完全相同</strong>：返回已持久化的解锁状态 +
 * 实时聚合的当前值，不对外暴露错误码（需求 6.7）。</p>
 *
 * <p>本响应<strong>不含</strong> {@code email} / {@code wx_openid} / {@code wx_unionid} /
 * {@code invite_code} / {@code plan} / {@code role} 六个字段的键与取值，也<strong>不含</strong>
 * 任何金额字段（需求 6.12）。</p>
 *
 * @param achievements  成就视图列表，恒 16 项、按 {@link GrowthBadgeCatalog} 的清单顺序（需求 1.7）
 * @param unlockedCount 已解锁成就数，等于列表中已解锁项的个数，落在 {@code [0, 16]} 闭区间内（需求 6.5）
 * @param total         成就总数，恒为 16
 */
public record AchievementListResponse(List<AchievementView> achievements,
                                      int unlockedCount, int total) {
}
