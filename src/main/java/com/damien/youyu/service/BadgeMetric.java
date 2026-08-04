package com.damien.youyu.service;

/**
 * 徽章点亮条件所依据的统计口径（需求 8.1、8.7）。
 *
 * <p>每枚徽章的门槛数值（{@link BadgeDef#target()}）都必须配一个口径，否则「当前值」无从计算。
 * 前三个是<b>数量型</b>口径，门槛可以是任意正整数；后两个是<b>存在型</b>口径，门槛恒为 1，
 * 当前值只有 0 与 1 两种取值（需求 8.7）。</p>
 *
 * <p>本枚举<b>不落库</b>：库里只存 {@code BADGE:<编码>} 这一个事实，口径纯属服务端派生逻辑
 * （需求 8.9、8.10）。因此调整某枚徽章的口径不需要迁移脚本，也不会影响已点亮的徽章。</p>
 */
public enum BadgeMetric {

    /** 累计有效记账笔数（需求 7.1）。 */
    RECORD_COUNT,

    /** 历史最长连续记账天数（需求 4.8）。 */
    MAX_STREAK,

    /** 累计记账天数（需求 4.7）。 */
    TOTAL_DAYS,

    /**
     * 存在型：该用户是否存在 {@code event_type = 'BUDGET_MET'} 的成长事件（需求 8.1、8.11）。
     *
     * <p><b>只看经验事件类型，不看 {@code BADGE:BUDGET_MET} 行。</b>后者是本徽章自己的点亮标记，
     * 拿它当点亮条件会让判定自我循环（已点亮 ⇒ 条件成立 ⇒ 应点亮），条件是否真的成立就再也测不出来。</p>
     */
    BUDGET_MET_EVENT,

    /**
     * 存在型：该用户是否存在 {@code event_key = 'FIRST_INVITE'} 的成长事件（需求 8.1、6.1）。
     *
     * <p>同样只看经验事件键，不看 {@code BADGE:INVITE_1} 行。</p>
     */
    FIRST_INVITE_EVENT
}
