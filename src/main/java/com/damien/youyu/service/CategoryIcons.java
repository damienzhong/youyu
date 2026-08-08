package com.damien.youyu.service;

import com.damien.youyu.domain.CategoryKind;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 分类图标启发式：按分类名称推断内置线性图标集的 key。
 *
 * <p>与前端 {@code miniapp/src/utils/icons.js} 的 key 集合保持一致，用于：新建默认分类时给定图标、
 * 未显式指定图标时的兜底，以及迁移脚本回填的 Java 侧参照。未命中时按种类给默认（支出 receipt / 收入 income）。</p>
 */
public final class CategoryIcons {

    private CategoryIcons() {
    }

    /**
     * 内置图标 key 白名单，与前端 {@code miniapp/src/utils/icons.js} 的 {@code ICON_KEY_SET} 完全一致
     * （逐 key 相等，见设计 Property 1）。来源：{@code design/category-icon-library.html} 的全部分组 key
     * 加上 icons.js 既有 key，去重后共 171 枚。{@link Set#of} 在类加载时即校验无重复。
     */
    public static final Set<String> KEYS = Set.of(
            "food", "breakfast", "coffee", "milktea", "fruit", "wine", "snack", "dessert", "hotpot", "veg",
            "bbq", "noodle", "transport", "subway", "taxi", "fuel", "parking", "train", "plane", "bike",
            "ship", "charge", "shopping", "clothes", "shoe", "digital", "beauty", "daily", "homeapp", "gift",
            "bag", "baby", "supermarket", "home", "water", "electric", "gas", "property", "furniture", "repair",
            "wifi", "clean", "plant", "entertainment", "movie", "game", "ktv", "travel", "sport", "book",
            "music", "photo", "pet", "show", "medical", "medicine", "checkup", "heart", "tooth", "fitness",
            "education", "stationery", "tuition", "training", "instrument", "read", "redpacket", "treat", "ceremony", "donate",
            "family", "communication", "broadband", "phonebill", "mail", "express", "cloud", "invest", "insurance", "repay",
            "interest", "fee", "tax", "salary", "bonus", "parttime", "refund", "earning", "reimburse", "basketball",
            "soccer", "swim", "dumbbell", "badminton", "hiking", "car", "carwash", "maintain", "toll", "tire",
            "carinsure", "hotel", "ticket", "luggage", "visa", "beach", "map", "haircut", "laundry", "housekeep",
            "moving", "member", "locksmith", "laptop", "mobile", "camera", "headphone", "printer", "software", "formula",
            "diaper", "toy", "kidcloth", "kidedu", "vaccine", "cake", "lantern", "rings", "tree", "firework",
            "anniversary", "makeup", "skincare", "perfume", "nail", "spa", "razor", "receipt", "transfer", "cash",
            "card", "wallet", "coin", "pig", "star", "flag", "more", "calendar", "lock", "utilities",
            "income", "chart", "budget", "list", "diamond", "user", "search", "members", "import", "export",
            "recycle", "tag", "loan", "folder", "info", "chat", "yuan", "badge", "warning", "bell",
            "settings"
    );

    /** 图标背景色格式：{@code #RRGGBB}（6 位十六进制，大小写均可）。 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** 是否为合法的 hex 背景色（{@code ^#[0-9a-fA-F]{6}$}）。 */
    public static boolean isValidColor(String color) {
        return color != null && COLOR_PATTERN.matcher(color).matches();
    }

    /** 净化背景色：合法（{@link #isValidColor}）原样返回，否则返回 {@code null}（视为未提供，由默认色兜底）。 */
    public static String sanitizeColor(String color) {
        return isValidColor(color) ? color : null;
    }

    /** 净化图标 key：在 {@link #KEYS} 白名单内原样返回，否则返回 {@code null}（视为未提供，由名称推断兜底）。 */
    public static String sanitizeIcon(String icon) {
        return icon != null && KEYS.contains(icon) ? icon : null;
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
