package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 6：中断不清零</b>的属性测试（任务 8.2）——本 spec 的<b>产品核心</b>。
 *
 * <p><i>对任意</i>「连续 N 天 → 中断 M 天 → 再记 1 天」的序列（{@code N, M ∈ [1, 400]}）：</p>
 * <ul>
 *   <li>那段 N 天的段行的起始日 / 结束日 / 段天数三列取值<b>不变</b>；</li>
 *   <li>新增一条 {@code start_date == end_date == 重新开始那一日} 且 {@code days == 1} 的段行；</li>
 *   <li>段总数 == 前值 + 1；当前连续天数 == 1；{@code user_growth.max_streak_days} <b>不减少</b>；</li>
 *   <li>中断持续期间任意多次（{@code [1, 10]} 次）读取概览，「上次连续天数 / 上次连续结束日 / 历史最长
 *       连续天数 / 最长段起始日 / 最长段结束日 / 段总数」六项取值<b>逐项相同</b>（历史不随中断时长衰减，
 *       需求 5.10）；且中断期间成长事件行数与全部列、{@code user_growth} 的
 *       {@code exp / level / total_record_days / max_streak_days} 四列取值与中断前最后一次成功结算之后
 *       逐项相同（需求 5.3）。</li>
 * </ul>
 *
 * <h2>反向断言不可选：模拟会动旧段的错误 diff 时本属性必须失败</h2>
 *
 * <p>{@link #mutatingOldSegmentBreaksTheProperty()} 用 {@code @Example} 钉死这条：先用段维护建立一段
 * 旧段，再直接 {@code UPDATE} 那一行（模拟一个会改动旧段的错误 diff），断言「旧段三列不变」这条核心检查
 * <b>确实抛出</b> {@code AssertionError}。少了这条反向锁，「断一次不清零」的正向断言可能因写得太松而恒真——
 * 中断后旧段被误改、{@code max_streak_days} 回退、历史区间墙丢一段，正是这个 spec 存在的理由，
 * 故这条反向断言与正向属性同等重要（见 tasks.md「三处刻意不标可选」）。</p>
 *
 * <h2>驱动方式：真实结算全链路 + 真实概览查询（不能只测段维护）</h2>
 *
 * <p>本属性同时断言段行、{@code user_growth} 四列、{@code growth_events} 行、已解锁成就数与概览六项取值，
 * 这些只有走真实的 {@link GrowthSettlementService#settle} + {@link StreakQueryService#getOverview} 全链路
 * 才能一致地观察到。故本类<b>不用测试级 {@code @Transactional} 包裹</b>（结算走自身的 {@code REQUIRES_NEW}，
 * 只有真实提交才能读回终态），清理由 {@link #resetState()} 每次迭代前清表、{@link #SEQ} 分配全局唯一
 * {@code userId}（也避开 {@code OVERVIEW} 进程内节流器的跨迭代串扰）。判定日取真实时钟今日，
 * N 天连续段与 M 天中断一律铺在<b>过去</b>，「再记 1 天」落在今日——于是当前连续天数恰为 1、
 * {@code broken} 在中断期间恒真。jqwik 属性方法的依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 手工完成。使用独立命名的内存库。</p>
 *
 * <p>Feature: streak-system, Property 6: 中断不清零</p>
 *
 * <p>Validates: Requirements 5.1, 5.2, 5.3, 5.6, 5.7, 5.10, 2.8</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-breaknoreset-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class StreakBreakNoResetPropertyTest {

    private static final AtomicLong SEQ = new AtomicLong(861_000_000L);
    private static final LocalDateTime NOW = LocalDateTime.of(2025, 6, 15, 8, 0);

    /** 交易直插语句：列顺序与 {@link #txRow} 的参数顺序一致（与结算集成测试同构）。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private StreakQueryService streakQueryService;
    @Autowired
    private StreakSegmentMaintainer maintainer;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Clock clock;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(StreakBreakNoResetPropertyTest.class).prepareTestInstance(this);
        jdbcTemplate.update("DELETE FROM streak_segments");
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    /**
     * Feature: streak-system, Property 6: 中断不清零
     *
     * <p>连续 N 天 → 中断 M 天 → 再记 1 天后：旧段三列不变、新增 days=1 段、段总数 +1、当前连续天数=1、
     * {@code max_streak_days} 不减；中断期间六项历史取值与四列 / 成长事件逐项不变（需求 5.1、5.3、5.10）。</p>
     *
     * <p>Validates: Requirements 5.1, 5.2, 5.3, 5.6, 5.7, 5.10, 2.8</p>
     */
    @Property(tries = 10)
    void breakDoesNotResetHistory(
            @ForAll @IntRange(min = 1, max = 400) int n,
            @ForAll @IntRange(min = 1, max = 400) int m,
            @ForAll @IntRange(min = 1, max = 10) int readsDuringBreak) {
        assertBreakNoReset(n, m, readsDuringBreak);
    }

    /** 顶角必跑：N / M 取值域四角 + 单次 / 多次读取，锁住取样可能漏掉的极端。 */
    @Example
    void rangeCorners() {
        assertBreakNoReset(1, 1, 1);
        assertBreakNoReset(400, 1, 10);
        assertBreakNoReset(1, 400, 5);
        assertBreakNoReset(400, 400, 3);
    }

    private void assertBreakNoReset(int n, int m, int readsDuringBreak) {
        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();

        LocalDate today = LocalDate.now(clock);
        LocalDate newDay = today;                        // 「再记 1 天」落在今日 ⇒ 当前连续天数恰为 1
        LocalDate runEnd = today.minusDays(m + 1L);      // N 天连续段的结束日（其后紧跟 M 天中断）
        LocalDate runStart = runEnd.minusDays(n - 1L);   // N 天连续段的起始日

        // ── ① 连续 N 天各记一笔 → 结算 ⇒ 一条 (runStart, runEnd, N) 的段 ──────────────────────────
        seedConsecutiveExpenses(userId, ledgerId, runStart, n);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);

        List<Seg> beforeBreak = segmentsOf(userId);
        assertThat(beforeBreak).as("N 天连续应恰为一段").hasSize(1);
        Seg oldSeg = beforeBreak.get(0);
        assertThat(oldSeg.start()).isEqualTo(runStart);
        assertThat(oldSeg.end()).isEqualTo(runEnd);
        assertThat(oldSeg.days()).isEqualTo(n);

        // 中断前最后一次成功结算之后的快照（需求 5.3 的比对基准）。
        GrowthSnapshot growthBefore = growthSnapshotOf(userId);
        int maxStreakBefore = growthBefore.maxStreakDays();
        long unlockedBefore = unlockedBadgeCount(userId);
        assertThat(maxStreakBefore).as("历史最长连续天数应为 N").isEqualTo(n);

        // ── ② 中断持续期间多次读取概览：六项历史取值 + 四列 + 成长事件逐项不变（需求 5.3、5.10）──────
        StreakOverviewResponse firstRead = streakQueryService.getOverview(userId);
        assertThat(firstRead.broken()).as("中断期间连续中断标识应为真").isTrue();
        HistoryView firstHistory = historyOf(firstRead);

        for (int i = 0; i < readsDuringBreak; i++) {
            StreakOverviewResponse read = streakQueryService.getOverview(userId);
            assertThat(read.broken()).as("中断期间连续中断标识应恒为真").isTrue();
            assertThat(historyOf(read))
                    .as("中断第 %d 次读取：六项历史取值必须逐项相同（历史不随中断时长衰减，需求 5.10）", i + 1)
                    .isEqualTo(firstHistory);
            assertThat(growthSnapshotOf(userId))
                    .as("中断期间 user_growth 四列取值不变（需求 5.3）").isEqualTo(growthBefore);
            assertThat(growthEventsOf(userId))
                    .as("中断期间 growth_events 行数与全部列取值不变（需求 5.3）")
                    .isEqualTo(growthBefore.events());
            assertThat(unlockedBadgeCount(userId))
                    .as("中断期间已解锁成就数不减少（需求 5.3）").isGreaterThanOrEqualTo(unlockedBefore);
        }

        // ── ③ 中断 M 天后再记 1 天 → 结算 ⇒ 旧段三列不变、新增 days=1 段、段总数 +1、当前连续天数=1 ────
        seedConsecutiveExpenses(userId, ledgerId, newDay, 1);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);

        List<Seg> afterRestart = segmentsOf(userId);          // 按 start_date 升序
        assertThat(afterRestart).as("段总数 == 中断前 + 1（需求 5.1）").hasSize(beforeBreak.size() + 1);

        Seg persistedOldSeg = afterRestart.get(0);
        assertThreeColumnsEqual(oldSeg, persistedOldSeg);     // 旧段三列不变（需求 5.1）——正向核心断言

        Seg freshSeg = afterRestart.get(afterRestart.size() - 1);
        assertThat(freshSeg.start()).as("新段起始日 == 重新开始那一日").isEqualTo(newDay);
        assertThat(freshSeg.end()).as("新段结束日 == 重新开始那一日").isEqualTo(newDay);
        assertThat(freshSeg.days()).as("新段段天数 == 1（需求 5.1）").isEqualTo(1);

        StreakOverviewResponse afterRead = streakQueryService.getOverview(userId);
        assertThat(afterRead.currentStreakDays()).as("重新开始后当前连续天数 == 1（需求 5.1）").isEqualTo(1);
        assertThat(afterRead.maxStreakDays())
                .as("max_streak_days 不减少（需求 5.1）").isGreaterThanOrEqualTo(maxStreakBefore);
    }

    /**
     * <b>反向断言（不可选）</b>：模拟一个会改动旧段的错误 diff 时，「旧段三列不变」的核心检查必须失败。
     *
     * <p>先用段维护建立一段 (d, d+4, 5) 的旧段并捕获其三列，再直接 {@code UPDATE} 那一行把
     * {@code days}/{@code end_date} 改掉（模拟错误 diff 动了不该动的旧段），断言
     * {@link #assertThreeColumnsEqual} 对「捕获值 vs 被误改后的持久化值」<b>抛出 {@code AssertionError}</b>。
     * 这锁死了正向断言不是恒真的——它确实能在旧段被改动时变红。</p>
     *
     * <p>Validates: Requirements 5.1</p>
     */
    @Example
    void mutatingOldSegmentBreaksTheProperty() {
        long userId = SEQ.getAndIncrement();
        LocalDate d = LocalDate.of(2024, 2, 26);
        List<LocalDate> run = List.of(d, d.plusDays(1), d.plusDays(2), d.plusDays(3), d.plusDays(4));

        maintainer.maintain(userId, run, NOW);
        List<Seg> before = segmentsOf(userId);
        assertThat(before).hasSize(1);
        Seg oldSeg = before.get(0);
        assertThat(oldSeg.days()).isEqualTo(5);

        // 模拟「会动旧段的错误 diff」：直接改动旧段行的业务列。
        jdbcTemplate.update(
                "UPDATE streak_segments SET days = 1, end_date = start_date WHERE user_id = ?", userId);
        Seg mutated = segmentsOf(userId).get(0);

        assertThatThrownBy(() -> assertThreeColumnsEqual(oldSeg, mutated))
                .as("旧段被误改时，「断一次不清零」的核心断言必须失败（锁死反向）")
                .isInstanceOf(AssertionError.class);
    }

    /** 旧段三列（起止日 + 段天数）不变的核心断言，正反两向共用。 */
    private static void assertThreeColumnsEqual(Seg expected, Seg actual) {
        assertThat(actual.start()).as("旧段起始日不变").isEqualTo(expected.start());
        assertThat(actual.end()).as("旧段结束日不变").isEqualTo(expected.end());
        assertThat(actual.days()).as("旧段段天数不变").isEqualTo(expected.days());
    }

    // ---------------------------------- 视图 / 快照 ----------------------------------

    /** 段行的业务投影，按 {@code start_date} 升序。 */
    private record Seg(LocalDate start, LocalDate end, int days) {
    }

    /** 概览的六项历史取值（需求 5.10）。 */
    private record HistoryView(Integer lastStreakDays, LocalDate lastStreakEnd, int maxStreakDays,
                               LocalDate longestSegmentStart, LocalDate longestSegmentEnd, long segmentCount) {
    }

    private static HistoryView historyOf(StreakOverviewResponse r) {
        return new HistoryView(r.lastStreakDays(), r.lastStreakEnd(), r.maxStreakDays(),
                r.longestSegmentStart(), r.longestSegmentEnd(), r.segmentCount());
    }

    /** {@code user_growth} 四列 + 该用户全部成长事件行（需求 5.3 的比对材料）。 */
    private record GrowthSnapshot(long exp, int level, int totalRecordDays, int maxStreakDays,
                                  List<Map<String, Object>> events) {
    }

    private GrowthSnapshot growthSnapshotOf(long userId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT exp, level, total_record_days, max_streak_days FROM user_growth WHERE user_id = ?",
                userId);
        return new GrowthSnapshot(
                ((Number) row.get("exp")).longValue(),
                ((Number) row.get("level")).intValue(),
                ((Number) row.get("total_record_days")).intValue(),
                ((Number) row.get("max_streak_days")).intValue(),
                growthEventsOf(userId));
    }

    private List<Map<String, Object>> growthEventsOf(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT id, event_type, event_key, exp_amount, created_at "
                        + "FROM growth_events WHERE user_id = ? ORDER BY id", userId);
    }

    private long unlockedBadgeCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_type = 'BADGE'",
                Long.class, userId);
        return n == null ? 0L : n;
    }

    private List<Seg> segmentsOf(long userId) {
        return jdbcTemplate.query(
                "SELECT start_date, end_date, days FROM streak_segments WHERE user_id = ? ORDER BY start_date",
                (rs, i) -> new Seg(
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getInt("days")),
                userId);
    }

    // ---------------------------------- 数据播种 ----------------------------------

    /** 从 {@code firstDay} 起连续 {@code count} 个自然日各直插一笔有效支出（记账日 = {@code created_at}）。 */
    private void seedConsecutiveExpenses(long userId, long ledgerId, LocalDate firstDay, int count) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(txRow(userId, ledgerId, firstDay.plusDays(i)));
        }
        jdbcTemplate.batchUpdate(INSERT_TX_SQL, batch);
    }

    private static Object[] txRow(long userId, long ledgerId, LocalDate recordDay) {
        Timestamp createdAt = Timestamp.valueOf(recordDay.atTime(12, 0));
        long ref = 900_000_000L + userId;
        return new Object[] {userId, ledgerId, userId, "expense", new BigDecimal("1.00"),
                ref, ref, createdAt, createdAt, createdAt};
    }
}
