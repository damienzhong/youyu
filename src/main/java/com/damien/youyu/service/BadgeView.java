package com.damien.youyu.service;

import java.time.LocalDateTime;

/**
 * 徽章视图：单枚徽章随成长概览下发的六个字段（需求 8.5）。
 *
 * <p>字段<strong>是且仅是</strong>这六个：编码、展示名称、是否已点亮、解锁时刻、目标值、当前值。
 * 展示名称与门槛数值随响应下发，迁移脚本、数据库与 miniapp 一律不重复定义（需求 8.10）。</p>
 *
 * <p>achievement-system 需求 12.1、12.2 起，概览的徽章列表由 16 枚成就派生（不再是 9 枚），
 * 但<strong>本视图的字段集仍恰好是这六项</strong>：描述、成就分类、统计口径与成就事件 id 四项
 * 一律<strong>不</strong>加进来——它们只随成就清单接口的
 * {@link AchievementView}（9 项）下发。两者是<b>同一份快照</b>的两种投影，因此六个同名字段
 * 在第 N 项上逐项相等（需求 12.3），详见 {@code GrowthQueryService.assembleBadges}。</p>
 *
 * <p>取值口径（由 {@link GrowthBadgeCatalog} 与查询层共同保证）：</p>
 * <ul>
 *   <li>{@code unlocked}：以「该用户存在对应 {@code BADGE} 事件」为唯一判定依据，一经点亮永不熄灭（需求 8.4）。</li>
 *   <li>{@code unlockedAt}：已点亮取对应 {@code BADGE} 事件的 {@code created_at}；未点亮为 {@code null}（需求 8.6）。</li>
 *   <li>{@code target}：等于该徽章点亮条件的门槛数值（{@code BUDGET_MET} 与 {@code INVITE_1} 为 1，需求 8.7）。</li>
 *   <li>{@code current}：已点亮恒等于 {@code target}；未点亮取「统计量当前取值」与 {@code target} 的较小者，
 *       恒落在 {@code [0, target]} 闭区间内（需求 8.12）。</li>
 * </ul>
 *
 * @param code       徽章编码，与 {@link GrowthBadgeCatalog} 常量一字不差
 * @param name       徽章展示名称（中文）
 * @param unlocked   是否已点亮
 * @param unlockedAt 解锁时刻，未点亮为 {@code null}
 * @param target     目标值（门槛数值），≥ 1
 * @param current    当前值，落在 {@code [0, target]} 闭区间内
 */
public record BadgeView(String code, String name, boolean unlocked,
                        LocalDateTime unlockedAt, int target, int current) {
}
