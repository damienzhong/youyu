package com.damien.youyu.service;

/**
 * 成就分类（需求 1.1、1.8）。
 *
 * <p>枚举取值的声明顺序<b>就是</b>需求 1.8 要求的分类首次出现顺序
 * {@code START/STREAK/VOLUME/SOCIAL/THEME}：成就清单常量按分类连续排列，
 * 首现顺序与本枚举的 {@link #ordinal()} 一致，启动自校验按这个顺序逐项断言。
 * 因此调整此处的声明顺序等于调整成就页的分组顺序，改动时必须同步核对清单排布。</p>
 *
 * <p><b>接口下发的是 {@link #label()} 的中文名，而不是枚举 code。</b>
 * 需求 1.3 禁止在 miniapp 中重复定义成就清单里的任何一项（含分类），
 * 需求 9.3 又要求成就页按分类分组、每组展示该分类的中文展示名——
 * 若只下发 {@code START} 这类 code，miniapp 就必须自己维护一份 code → 中文名的映射表，
 * 两条需求会直接冲突。把中文名随响应下发（需求 6.2 的「分类」字段）是唯一能同时成立的做法：
 * 服务端仍是唯一事实源，客户端拿到的就是可直接渲染的文案。</p>
 *
 * <p>枚举 code 保持服务端内部使用：清单常量、统计口径判定与自校验都用 code，
 * 它不落库（分类是纯派生的展示维度），因此重命名 code 无需迁移脚本。</p>
 */
public enum AchievementCategory {

    /** 起步：第一次做到某件事的成就。 */
    START("起步"),

    /** 坚持：连续记账天数类的成就。 */
    STREAK("坚持"),

    /** 积累：累计笔数与累计天数类的成就。 */
    VOLUME("积累"),

    /** 协作：邀请好友与账本协作类的成就。 */
    SOCIAL("协作"),

    /** 主题：预算、储蓄、旅行等特定主题的成就。 */
    THEME("主题");

    private final String label;

    AchievementCategory(String label) {
        this.label = label;
    }

    /**
     * 该分类的中文展示名，随成就查询接口下发（需求 6.2、9.3）。
     *
     * @return 中文展示名，恒非空
     */
    public String label() {
        return label;
    }
}
