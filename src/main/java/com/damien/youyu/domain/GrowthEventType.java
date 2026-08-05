package com.damien.youyu.domain;

/**
 * 成长事件类型的常量事实源，对应 {@code growth_events.event_type} 的七个取值。
 *
 * <p>刻意做成 {@code String} 常量类而不是枚举：{@link GrowthEvent#getEventType()} 声明为
 * {@code String}（写入路径全部走 {@code JdbcTemplate} 批量语句，实体只服务读取），
 * 若这里定义成枚举，调用方会不断在枚举与字符串之间来回转换，反而多一层可能出错的映射。</p>
 *
 * <p>取值集合的正确性由两处共同保证，缺一不可：数据库侧的
 * {@code ck_growth_events_type}（区分大小写，写错取值直接 {@code ERROR 3819} 拒绝）
 * 与应用侧的本类常量（让写入路径只有一处能拼出类型字面量）。
 * <b>本类的常量集合必须与迁移脚本 {@code ck_growth_events_type} 的取值集合逐项一致（当前为七项），
 * 新增、改名或删除任一类型时两处必须一起改</b>，只改一处会在结算时暴露为整批插入失败：
 * 只改本类则库侧 {@code ERROR 3819} 拒绝整条批量语句，只改 CHECK 则写入路径根本拼不出该字面量。</p>
 *
 * <p>徽章行的类型恒为 {@link #BADGE}、{@code exp_amount} 恒为 0、{@code event_key} 恒带
 * {@code BADGE:} 前缀（见 {@code GrowthBadgeCatalog}）；{@code BADGE:} 是徽章的独占命名空间，
 * 与同名经验事件键 {@code FIRST_RECORD} / {@code STREAK_7} / {@code STREAK_30} /
 * {@code BUDGET_MET} 双向隔离。</p>
 */
public final class GrowthEventType {

    /** 首笔记账，全用户至多一条，{@code event_key} 即 {@code "FIRST_RECORD"}。 */
    public static final String FIRST_RECORD = "FIRST_RECORD";

    /** 每日记账，{@code event_key} 为 {@code "DAILY_RECORD:yyyy-MM-dd"}，每个记账日至多一条。 */
    public static final String DAILY_RECORD = "DAILY_RECORD";

    /** 连续记账里程碑，{@code event_key} 为 {@code "STREAK_7"} / {@code "STREAK_30"}。 */
    public static final String STREAK = "STREAK";

    /** 预算达成，{@code event_key} 为 {@code "BUDGET_MET:yyyy-MM"}，每个自然月至多一条。 */
    public static final String BUDGET_MET = "BUDGET_MET";

    /** 首次邀请好友成功，全用户至多一条，{@code event_key} 即 {@code "FIRST_INVITE"}。 */
    public static final String FIRST_INVITE = "FIRST_INVITE";

    /**
     * 储蓄月达成，{@code event_key} 为 {@code "SAVING_MONTH:yyyy-MM"}，每个已结束自然月至多一条，
     * {@code exp_amount} 恒为 0（成就体系的事实事件，不发经验、不影响等级）。
     */
    public static final String SAVING_MONTH = "SAVING_MONTH";

    /** 点亮徽章，{@code event_key} 为 {@code "BADGE:<编码>"}，{@code exp_amount} 恒为 0。 */
    public static final String BADGE = "BADGE";

    private GrowthEventType() {
        // 常量类，不实例化
    }
}
