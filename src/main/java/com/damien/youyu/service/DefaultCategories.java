package com.damien.youyu.service;

/**
 * 新账本 / 新手引导预置的默认分类树（两级：父分类 + 子分类）。
 *
 * <p>单一事实来源：{@link LedgerService}（创建账本时预置）与 {@link CategoryService}（给空账本补齐）
 * 均从此处取用，避免两处清单漂移。父分类的 {@link Group#children()} 为其子分类名称（可为空数组表示无子分类）。
 * 名称在同一 kind、同一父级范围内互不重复，符合唯一约束 (kind, parent_id, name)。图标在落库时由
 * {@link CategoryIcons#guess} 按名称推断。</p>
 */
public final class DefaultCategories {

    private DefaultCategories() {
    }

    /** 一个父分类及其子分类名称。 */
    public record Group(String name, String... children) {
    }

    /** 默认支出分类树。 */
    public static final Group[] EXPENSE = {
            new Group("餐饮", "早餐", "午餐", "晚餐", "外卖", "零食", "饮料", "咖啡奶茶", "聚餐"),
            new Group("交通", "公交", "地铁", "打车", "加油", "停车", "火车", "飞机", "共享单车"),
            new Group("购物", "服饰", "鞋包", "数码", "家电", "日用品", "美妆", "母婴"),
            new Group("居住", "房租", "房贷", "物业", "水费", "电费", "燃气", "家居"),
            new Group("娱乐", "电影", "游戏", "运动健身", "演出", "唱歌", "订阅会员"),
            new Group("医疗", "药品", "门诊", "住院", "体检", "保健"),
            new Group("教育", "书籍", "培训", "学费", "文具", "考试"),
            new Group("通讯", "话费", "流量", "宽带"),
            new Group("人情", "红包", "礼物", "请客", "孝敬"),
            new Group("旅行", "机票", "酒店", "门票", "特产"),
            new Group("宠物", "宠物粮", "宠物医疗", "宠物用品"),
            new Group("其他")
    };

    /** 默认收入分类树。 */
    public static final Group[] INCOME = {
            new Group("工资", "月薪", "奖金", "加班费", "补贴"),
            new Group("兼职", "外快", "劳务"),
            new Group("理财", "利息", "基金", "股票", "分红"),
            new Group("红包", "礼金"),
            new Group("报销"),
            new Group("退款"),
            new Group("其他")
    };

    /** 分类树落库后的总条数（父 + 子），供测试与容量评估使用。 */
    public static int totalCount(Group[] groups) {
        int total = 0;
        for (Group g : groups) {
            total += 1 + g.children().length;
        }
        return total;
    }
}
