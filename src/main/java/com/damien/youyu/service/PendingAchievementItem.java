package com.damien.youyu.service;

import java.time.LocalDateTime;

/**
 * 待播报成就项：字段集<strong>恰好为</strong>「成就编码、展示名称、描述、分类、解锁时刻、
 * 成就事件 id」6 项（需求 5.4）。
 *
 * <p>六个字段与 {@link AchievementView} 的同名字段<strong>逐项相等</strong>：同一枚成就在
 * 待播报接口与成就清单接口下发的 {@code code} / {@code name} / {@code description} /
 * {@code category} / {@code unlockedAt} / {@code eventId} 取值一致，两处都取自同一份清单常量
 * （{@link GrowthBadgeCatalog}）与同一行 {@code BADGE} 成长事件，因此不存在两份可以漂移的文案。</p>
 *
 * <p>待播报项一律是<strong>已解锁</strong>的成就（定义即「{@code event_type} 为 {@code BADGE}
 * 且 {@code id} 大于播报游标的成长事件所对应的成就」，需求 5.2），故本项没有
 * {@code unlocked} / {@code target} / {@code current} 三个字段——它们对播报没有意义。
 * {@code unlockedAt} 与 {@code eventId} 仍声明为包装类型，与 {@link AchievementView} 保持同一
 * 时间表示形式（{@link LocalDateTime}，需求 6.3），不新增第二种。</p>
 *
 * <p>{@code category} 承载的是 {@link AchievementCategory#label()} 的中文展示名而非枚举 code，
 * 理由见 {@link AchievementView} 的类级 Javadoc。</p>
 *
 * <p>本项<strong>不含</strong> {@code email} / {@code wx_openid} / {@code wx_unionid} /
 * {@code invite_code} / {@code plan} / {@code role} 六个字段的键与取值，也<strong>不含</strong>
 * 任何金额字段（需求 6.12）。</p>
 *
 * @param code        成就编码，与 {@link GrowthBadgeCatalog} 常量一字不差
 * @param name        展示名称（中文）
 * @param description 中文描述
 * @param category    分类的<strong>中文展示名</strong>，取 {@link AchievementCategory#label()}
 * @param unlockedAt  解锁时刻，取对应 {@code BADGE} 事件的 {@code created_at}
 * @param eventId     成就事件 id，取对应 {@code BADGE} 事件的 {@code id}，也是游标推进的取值来源
 */
public record PendingAchievementItem(String code, String name, String description,
                                     String category, LocalDateTime unlockedAt, Long eventId) {
}
