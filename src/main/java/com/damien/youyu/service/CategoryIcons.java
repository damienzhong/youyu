package com.damien.youyu.service;

import com.damien.youyu.domain.CategoryKind;

/**
 * 分类图标启发式：按分类名称推断内置线性图标集的 key。
 *
 * <p>与前端 {@code miniapp/src/utils/icons.js} 的 key 集合保持一致，用于：新建默认分类时给定图标、
 * 未显式指定图标时的兜底，以及迁移脚本回填的 Java 侧参照。未命中时按种类给默认（支出 receipt / 收入 income）。</p>
 */
public final class CategoryIcons {

    private CategoryIcons() {
    }

    /** 依据名称关键字推断图标 key；未命中按种类兜底。 */
    public static String guess(String name, CategoryKind kind) {
        String s = name == null ? "" : name;
        if (matches(s, "餐饮", "吃", "饭", "外卖", "美食", "聚餐", "零食", "饮", "咖啡", "奶茶")) {
            return "food";
        }
        if (matches(s, "交通", "地铁", "公交", "打车", "出行", "车", "油", "加油", "停车", "高铁")) {
            return "transport";
        }
        if (matches(s, "购物", "买", "衣", "鞋", "服饰", "数码", "电器", "日用")) {
            return "shopping";
        }
        if (matches(s, "娱乐", "游戏", "电影", "玩", "唱", "运动", "健身")) {
            return "entertainment";
        }
        if (matches(s, "居住", "房租", "房贷", "物业", "水电", "燃气", "家居")) {
            return "home";
        }
        if (matches(s, "医疗", "药", "医院", "健康", "体检")) {
            return "medical";
        }
        if (matches(s, "教育", "学习", "书", "培训", "课", "学费")) {
            return "education";
        }
        if (matches(s, "通讯", "话费", "网费", "流量", "手机", "宽带")) {
            return "communication";
        }
        if (matches(s, "旅行", "旅游", "酒店", "机票", "景点")) {
            return "travel";
        }
        if (matches(s, "宠物")) {
            return "pet";
        }
        if (matches(s, "工资", "薪", "奖金", "报销", "劳务", "兼职")) {
            return "salary";
        }
        if (matches(s, "理财", "利息", "收益", "投资", "分红", "基金", "股票")) {
            return "invest";
        }
        if (matches(s, "红包", "礼金", "转赠", "人情")) {
            return "redpacket";
        }
        if (matches(s, "退款", "返现")) {
            return "refund";
        }
        return kind == CategoryKind.INCOME ? "income" : "receipt";
    }

    private static boolean matches(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
