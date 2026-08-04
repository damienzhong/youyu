package com.damien.youyu.service;

/**
 * 一枚徽章的完整定义：编码、展示名称、门槛数值与统计口径（需求 8.1、8.7、8.10）。
 *
 * <p>这四项连同在 {@link GrowthBadgeCatalog#badges()} 中的下标（即展示顺序，需求 8.8）
 * 构成徽章清单的<b>全部</b>事实。迁移脚本、数据库与 miniapp 一律不重复定义其中任何一项
 * （需求 8.10）：展示名称随成长概览响应下发，前端只负责渲染服务端给的字符串。</p>
 *
 * @param code   徽章编码，区分大小写的固定字符串；也是事件键的后半段
 *               （{@link GrowthBadgeCatalog#eventKeyOf(String)}）
 * @param name   中文展示名称，随响应下发（需求 8.5、8.10）
 * @param target 门槛数值，同时就是概览响应里的目标值（需求 8.7）；存在型口径恒为 1
 * @param metric 门槛所对应的统计口径（需求 8.7）
 */
public record BadgeDef(String code, String name, int target, BadgeMetric metric) {
}
