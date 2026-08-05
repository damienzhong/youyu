package com.damien.youyu.service;

import java.time.LocalDateTime;

/**
 * 成就视图：单枚成就随成就清单接口下发的九个字段（需求 6.2）。
 *
 * <p>字段<strong>是且仅是</strong>这九项：成就编码、展示名称、描述、分类、门槛数值、当前值、
 * 是否已解锁、解锁时刻、成就事件 id。九个键在全部 16 项上<strong>恒存在</strong>，
 * 不因某项取值为空而省略，也不返回第 10 个字段；字段集与列表项数不随该用户的交易笔数、
 * 成长事件条数与会话账本取值变化（需求 6.2）。</p>
 *
 * <p><b>{@code unlockedAt} 与 {@code eventId} 刻意声明为包装类型</b>
 * （{@link LocalDateTime} / {@link Long} 而非原始类型）：未解锁时这两项必须以<strong>空值</strong>
 * 返回，且不得以 {@code 0}、空字符串或当前时刻替代（需求 6.3、2.13）。用原始 {@code long}
 * 会被迫写成 {@code 0}，那就把「没解锁」和「解锁于 id=0 的事件」混成同一种表示了。
 * 键本身仍存在——序列化不启用 {@code NON_NULL} 省略，客户端可以稳定地按键取值。</p>
 *
 * <p><b>{@code category} 承载的是分类的中文展示名</b>（{@link AchievementCategory#label()}，
 * 即「起步」「坚持」「积累」「协作」「主题」），<strong>不是枚举 code</strong>。理由：需求 1.3
 * 禁止在 miniapp 里重复定义成就清单中的任何一项（含分类），需求 9.3 又要求成就页按分类分组、
 * 每组展示该分类的中文名——若只下发 {@code START} 这类 code，miniapp 就必须自己维护一份
 * code → 中文名映射表，两条需求会直接冲突。把中文名随响应下发是唯一能同时成立的做法：
 * 服务端仍是唯一事实源，客户端拿到的就是可直接渲染的文案。枚举 code 保持服务端内部使用。</p>
 *
 * <p>{@code unlockedAt} 的时间表示形式与成长概览接口徽章列表（{@link BadgeView#unlockedAt()}）
 * 一致，均为 {@link LocalDateTime}，不新增第二种时间表示形式（需求 6.3）。</p>
 *
 * <p>本视图<strong>不含</strong> {@code email} / {@code wx_openid} / {@code wx_unionid} /
 * {@code invite_code} / {@code plan} / {@code role} 六个字段的键与取值，也<strong>不含</strong>
 * 任何金额字段与任何用于指定目标用户身份的字段（需求 6.12、6.10）。</p>
 *
 * @param code        成就编码，与 {@link GrowthBadgeCatalog} 常量一字不差、区分大小写
 * @param name        展示名称（中文），长度 2–10 个 Unicode 码点
 * @param description 中文描述，长度 6–30 个 Unicode 码点
 * @param category    分类的<strong>中文展示名</strong>，取 {@link AchievementCategory#label()}
 * @param target      门槛数值，落在 {@code [1, 1000]} 闭区间内
 * @param current     当前值：已解锁恒等于 {@code target}；未解锁取统计口径当前取值与 {@code target}
 *                    的较小者，恒落在 {@code [0, target]} 闭区间内（需求 6.4）
 * @param unlocked    是否已解锁，以「该用户存在对应 {@code BADGE} 事件」为唯一判定依据，一经解锁不撤销
 * @param unlockedAt  解锁时刻，取对应 {@code BADGE} 事件的 {@code created_at}；未解锁为 {@code null}
 * @param eventId     成就事件 id，取对应 {@code BADGE} 事件的 {@code id}；未解锁为 {@code null}
 */
public record AchievementView(String code, String name, String description, String category,
                              int target, int current, boolean unlocked,
                              LocalDateTime unlockedAt, Long eventId) {
}
