package com.damien.youyu.service;

/**
 * 一枚成就（徽章）的完整定义：编码、展示名称、中文描述、分类、门槛数值与统计口径
 * （需求 1.1、1.2、1.3、1.8）。
 *
 * <p>这六项连同在 {@link GrowthBadgeCatalog#badges()} 中的下标（即展示顺序，需求 1.7）
 * 构成成就清单的<b>全部</b>事实。迁移脚本、数据库与 miniapp 一律不重复定义其中任何一项
 * （需求 1.3）：展示名称、描述与门槛数值随成就查询接口下发，前端只负责渲染服务端给的字符串。</p>
 *
 * <p><b>{@code description} 与 {@code category} 是本 spec 新增的两个分量。</b>
 * 描述是「要做到什么」的唯一权威文案（需求 1.2：6–30 个 Unicode 码点、两两不同、
 * 门槛 &gt; 1 时含该门槛数值的十进制写法、不出现编码 / 分类 / 口径三类原始字面量）；
 * 分类决定成就页的分组与组内排布（需求 1.8：同分类连续出现，分类首现顺序即
 * {@link AchievementCategory} 的声明顺序）。两项都随响应下发，其中分类下发的是
 * {@link AchievementCategory#label()} 的中文名而非枚举 code。</p>
 *
 * @param code        成就编码，区分大小写的固定字符串；也是事件键的后半段
 *                    （{@link GrowthBadgeCatalog#eventKeyOf(String)}）
 * @param name        中文展示名称，随响应下发；长度落在 2–10 个 Unicode 码点（需求 1.1、1.3）
 * @param description 中文描述，随响应下发；长度落在 6–30 个 Unicode 码点（需求 1.2）
 * @param category    成就分类，决定分组与展示顺序（需求 1.8）
 * @param target      门槛数值，同时就是响应里的目标值（需求 1.1）；落在 [1, 1000]，存在型口径恒为 1
 * @param metric      门槛所对应的统计口径（需求 1.1、需求 3）
 */
public record BadgeDef(String code, String name, String description,
                       AchievementCategory category, int target, BadgeMetric metric) {
}
