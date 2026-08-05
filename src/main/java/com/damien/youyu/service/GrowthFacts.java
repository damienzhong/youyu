package com.damien.youyu.service;

/**
 * 判定成就（徽章）点亮条件所需的全部统计事实（growth-level-system 需求 8.1）。
 *
 * <p>纯值载体：{@link GrowthBadgeCatalog#qualified(GrowthFacts)} 只读这八个分量，不查库、不读时钟，
 * 因此成就判定是纯函数，结算侧（读事实源后判定应写入哪些 {@code BADGE} 行）与查询侧
 * （判定「条件已成立但事件尚未写入」，growth-level-system 需求 8.13）传入同一份事实必得同一结论。</p>
 *
 * <p><b>本 record 就是 achievement-system 需求 3.16 要求的「每个统计口径在单次结算 / 单次请求内
 * 只求值一次」的载体。</b>八个分量在一处（结算第 ③ 步 / {@code AchievementSnapshotService.snapshot}）
 * 一次求全，随后本次结算或本次请求内全部依赖这些口径的成就共用同一份取值。
 * 因此<b>任何新增口径都应当加成本 record 的一个分量，而不是在调用点各自查一次</b>——
 * 在调用点临时查询会让同一次请求里两枚成就读到不同时刻的取值，需求 3.16 与需求 12.3
 * （概览徽章列表与成就清单逐项相等）都会随之退化成「靠测试凑巧对上」。</p>
 *
 * <p>三个计数型分量刻意<b>只描述经验事件的条数</b>、存在型分量刻意<b>只描述经验事件是否存在</b>，
 * 都不描述对应成就是否已点亮（growth-level-system 需求 8.11 的双向隔离）：调用方必须以
 * {@code event_type = 'BUDGET_MET'} / {@code 'SAVING_MONTH'} 的行、{@code event_key = 'FIRST_INVITE'}
 * 的行来填，不得用 {@code BADGE:BUDGET_MET} / {@code BADGE:SAVING_MASTER} / {@code BADGE:INVITE_1}
 * 行来填（achievement-system 需求 3.6、3.7、3.8）。</p>
 *
 * <p>全部数量型分量以 64 位整型承载、取值落在 {@code [0, Long.MAX_VALUE]}；查询结果为空或该用户
 * 尚无 {@code user_growth} 行时一律按 0 计（achievement-system 需求 3.13）。实时聚合口径
 * （{@link #travelRecordCount()}）的取值可能随分类改名 / 删除而下降，进度回落由
 * {@link GrowthBadgeCatalog} 的钳制规则承担，已写入的 {@code BADGE} 行一字不动
 * （achievement-system 需求 3.12）。</p>
 *
 * @param recordCount        累计有效记账笔数（growth-level-system 需求 7.1）；
 *                           对应 {@link BadgeMetric#RECORD_COUNT}
 * @param maxStreakDays      历史最长连续记账天数（growth-level-system 需求 4.8）；
 *                           对应 {@link BadgeMetric#MAX_STREAK}
 * @param totalRecordDays    累计记账天数（growth-level-system 需求 4.7）；
 *                           对应 {@link BadgeMetric#TOTAL_DAYS}
 * @param budgetMetCount     {@code event_type = 'BUDGET_MET'} 的成长事件条数
 *                           （achievement-system 需求 3.6）；对应 {@link BadgeMetric#BUDGET_MET_COUNT}
 * @param firstInviteEvent   是否存在 {@code event_key = 'FIRST_INVITE'} 的成长事件
 *                           （achievement-system 需求 3.8）；对应 {@link BadgeMetric#FIRST_INVITE_EVENT}
 * @param savingMonthCount   {@code event_type = 'SAVING_MONTH'} 的成长事件条数
 *                           （achievement-system 需求 3.7）；对应 {@link BadgeMetric#SAVING_MONTH_COUNT}
 * @param collabMemberCount  本人拥有账本下 {@code role = 'EDITOR'} 且非本人的成员行行数
 *                           （achievement-system 需求 3.3、3.4）；
 *                           对应 {@link BadgeMetric#COLLAB_MEMBER_COUNT}
 * @param travelRecordCount  旅行分类树下的有效支出笔数（achievement-system 需求 3.9、3.10）；
 *                           对应 {@link BadgeMetric#TRAVEL_RECORD_COUNT}
 */
public record GrowthFacts(
        long recordCount,
        int maxStreakDays,
        int totalRecordDays,
        long budgetMetCount,
        boolean firstInviteEvent,
        long savingMonthCount,
        long collabMemberCount,
        long travelRecordCount) {

    /** 全零事实：无档案且结算失败时的降级取值（growth-level-system 需求 9.11，16 枚均未点亮）。 */
    public static final GrowthFacts EMPTY = new GrowthFacts(0L, 0, 0, 0L, false, 0L, 0L, 0L);
}
