package com.damien.youyu.service;

/**
 * 判定徽章点亮条件所需的全部统计事实（需求 8.1）。
 *
 * <p>纯值载体：{@link GrowthBadgeCatalog#qualified(GrowthFacts)} 只读这五项，不查库、不读时钟，
 * 因此徽章判定是纯函数，结算侧（读事实源后判定应写入哪些 {@code BADGE} 行）与查询侧
 * （判定「条件已成立但事件尚未写入」，需求 8.13）传入同一份事实必得同一结论。</p>
 *
 * <p>两个布尔项刻意<b>只描述经验事件是否存在</b>，不描述对应徽章是否已点亮（需求 8.11 的双向隔离）：
 * 调用方必须以 {@code event_type = 'BUDGET_MET'} 的行、{@code event_key = 'FIRST_INVITE'} 的行来填，
 * 不得用 {@code BADGE:BUDGET_MET} / {@code BADGE:INVITE_1} 行来填。</p>
 *
 * @param recordCount        累计有效记账笔数（需求 7.1）；对应 {@link BadgeMetric#RECORD_COUNT}
 * @param maxStreakDays      历史最长连续记账天数（需求 4.8）；对应 {@link BadgeMetric#MAX_STREAK}
 * @param totalRecordDays    累计记账天数（需求 4.7）；对应 {@link BadgeMetric#TOTAL_DAYS}
 * @param budgetMetEvent     是否存在 {@code event_type = 'BUDGET_MET'} 的成长事件；
 *                           对应 {@link BadgeMetric#BUDGET_MET_EVENT}
 * @param firstInviteEvent   是否存在 {@code event_key = 'FIRST_INVITE'} 的成长事件；
 *                           对应 {@link BadgeMetric#FIRST_INVITE_EVENT}
 */
public record GrowthFacts(
        long recordCount,
        int maxStreakDays,
        int totalRecordDays,
        boolean budgetMetEvent,
        boolean firstInviteEvent) {

    /** 全零事实：无档案且结算失败时的降级取值（需求 9.11，9 枚均未点亮）。 */
    public static final GrowthFacts EMPTY = new GrowthFacts(0L, 0, 0, false, false);
}
