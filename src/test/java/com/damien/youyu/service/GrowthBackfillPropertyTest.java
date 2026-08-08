package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.UserGrowthRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 7：追补的有界性与收敛性</b>的属性测试（任务 9.7）。
 *
 * <p>对<i>任意</i>历史记账日集合（可含空洞、跨度可超过单次追补窗口）与<i>任意</i>次连续结算，锁住
 * {@link GrowthCalendarService#backfillDates} 与 {@link GrowthSettlementService#settle} 协作产生的这些
 * 硬不变式：</p>
 * <ul>
 *   <li><b>有界性</b>：单次结算的追补查询次数恒 ≤2（需求 4.6）；单次结算写入的 {@code DAILY_RECORD}
 *       条数 ≤1000（需求 3.10、4.6）；单次结算写入的成长事件<b>总条数</b> ≤1016（需求 3.10）。</li>
 *   <li><b>单调推进</b>：每次结算的追补起点（追补窗口首日）<b>严格晚于</b>上一次结算的追补起点
 *       （需求 4.2、4.3）。</li>
 *   <li><b>收敛性</b>：只要仍有未补发的记账日，每次结算至少补发 1 个记账日，因此补齐全部历史记账日
 *       所需的结算次数<b>不超过</b>未补发记账日的个数（需求 4.3、4.14）；补齐后记账日历<b>恰好等于</b>
 *       全部历史记账日集合（需求 4.9）。</li>
 *   <li><b>无空洞不变式</b>：每一次结算提交后，{@code last_record_date} 恒等于记账日历的最大日期；
 *       且不存在「早于 {@code last_record_date}、本是记账日却不在日历中」的日期——即追补从不跳过
 *       起点与已推进边界之间的任何记账日（需求 4.9、4.14）。</li>
 *   <li><b>窗口末日 &lt; 结算日 ⇒ 本次不写 {@code DAILY_RECORD:<结算日>}</b>：存量大户单窗覆盖不到
 *       结算日时，{@code last_record_date} 只推进到窗口末日、不越过尚未补发的日期，记账日历也不会
 *       凭空出现结算日（需求 4.2、4.14）。</li>
 * </ul>
 *
 * <h2>追补查询计数：Hibernate {@link StatementInspector}（而非包裹 {@code DataSource}）</h2>
 * <p>「单次结算追补查询次数 ≤2」这条要用一个<b>计数型装饰器</b>锁死。本类经由
 * {@code hibernate.session_factory.statement_inspector} 注册 {@link BackfillQueryInspector}：它拦截
 * Hibernate 发出的每条 SQL，只对追补查询 A（{@code MIN(created_at) ...}）与查询 B
 * （{@code DISTINCT CAST(created_at AS DATE) ...}）计数。这两个片段是追补查询<b>独有</b>的（累计笔数走
 * {@code COUNT(*)}、累计金额按 {@code type} 分组、预算按 {@code occurred_at} 聚合，均不含这两个片段），
 * 故计数精确。选 {@code StatementInspector} 而不去包裹 {@code DataSource} 的原因：① 它<b>只</b>看
 * Hibernate/JPA 发出的 SQL，测试自己为播种与「独立推算追补起点」而走的 {@link JdbcTemplate} 原生 SQL
 * <b>天然不被计入</b>，无需额外过滤；② 结算内部建档 / 批量写事件走的也是 {@code JdbcTemplate}，同样不计入，
 * 计数器里剩下的正好是「这次结算发了几次追补查询」。每次 {@code settle} 前把计数器归零、结算后读取即可。</p>
 *
 * <h2>播种方式：{@code jdbcTemplate.batchUpdate} 直接预置交易行</h2>
 * <p>历史记账日<b>不</b>走「200×N 次业务记账接口」，而是用 {@code jdbcTemplate.batchUpdate} 一次性直插
 * {@code transactions} 表（{@code created_by}=用户、{@code deleted_at} 为 NULL、{@code type='expense'}、
 * {@code ledger_id} 非空，即「有效记账交易」的四条件），记账日由 {@code created_at} 决定。这样大规模历史
 * （示例测试的 3000 天）也能在毫秒级造好。属性测试内规模上限压到 <b>1200</b> 天跨度（仍足以覆盖
 * 「窗口末日 &lt; 结算日」分支，因为单窗只覆盖 1000 天）；3000 那档由 {@link #bigHistory_convergesAndStaysBounded()}
 * 单独作示例跑一次。</p>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有让它<b>真正提交</b>才能在库里观察到每一步
 * 追补的终态、并让「补齐过程中每一次提交后」的不变式可被跨事务读取。因此本类<b>不用</b>测试级事务包裹，
 * 每次调用各自提交；清理改由 {@link #resetState()} 每次迭代前显式清库，并用全局自增序号 {@link #SEQ}
 * 保证每次迭代 {@code userId}/{@code ledgerId} 全局唯一，双重隔离。注入一个 {@code @Primary} 的可推进
 * {@link MutableClock}（覆盖 {@code TimeConfig} 的系统时钟、固定 {@code Asia/Shanghai}）使结算日可确定性推进。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 静态上下文缓存复用，多次迭代
 * 只加载一次上下文）。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Feature: growth-level-system, Property 7: 追补的有界性与收敛性</p>
 *
 * <p>Validates: Requirements 3.10, 4.2, 4.3, 4.5, 4.6, 4.9, 4.14</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-backfill-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 计数型装饰器：拦截 Hibernate 发出的追补查询（见类级 Javadoc「追补查询计数」）。
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.damien.youyu.service.GrowthBackfillPropertyTest$BackfillQueryInspector"
})
@Import(GrowthBackfillPropertyTest.ClockConfig.class)
class GrowthBackfillPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：初始结算日 = 2025-06-15。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 跨迭代复用同一内存库，用序号保证 userId / ledgerId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(700_000L);

    /** 单次追补窗口跨度上界（自然日）：起点 + 999 天，含两端恰好 1000 天。 */
    private static final int MAX_WINDOW_SPAN_DAYS = 999;
    /** 单次结算写入 {@code DAILY_RECORD} 的上界（需求 3.10、4.6）。 */
    private static final int MAX_DAILY_WRITES = 1000;
    /** 单次结算写入成长事件总条数的上界（需求 3.10）。 */
    private static final int MAX_TOTAL_WRITES = 1016;
    /** 追补查询次数上界（需求 4.6）。 */
    private static final int MAX_BACKFILL_QUERIES = 2;

    private static final String INSERT_RECORD_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 'expense', 12.34, ?, ?, ?, ?, ?)";

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthBackfillPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        BackfillQueryInspector.reset();
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删三张表。两张成长表均无外键，删除顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    // ---------------- 生成器 ----------------

    /**
     * 一次场景：历史记账日相对初始结算日的偏移集合（0–1200 天，可空、可含空洞与重复）
     * 与结算之间的时钟推进秒数（{@code 0} / {@code 61s} / {@code 1 天}）。
     *
     * <p>偏移上界 1200 &gt; 单窗 1000 天，因此最早记账日可落在追补窗口覆盖不到结算日的位置，
     * 稳定覆盖「窗口末日 &lt; 结算日」分支。</p>
     */
    static final class RecordHistory {
        final List<Integer> dayOffsets;
        final long advanceSeconds;

        RecordHistory(List<Integer> dayOffsets, long advanceSeconds) {
            this.dayOffsets = dayOffsets;
            this.advanceSeconds = advanceSeconds;
        }
    }

    @Provide
    Arbitrary<RecordHistory> recordHistories() {
        Arbitrary<List<Integer>> offsets = Arbitraries.integers().between(0, 1200)
                .list().ofMinSize(0).ofMaxSize(60);
        Arbitrary<Long> advance = Arbitraries.of(0L, 61L, 86_400L);
        return Combinators.combine(offsets, advance).as(RecordHistory::new);
    }

    // ---------------- Property 7 ----------------

    /**
     * 对任意历史记账日集合连续结算至收敛：有界性、追补起点严格推进、收敛性、无空洞、
     * 窗口末日 &lt; 结算日不写结算日（需求 3.10、4.2、4.3、4.5、4.6、4.9、4.14）。
     *
     * <p>Validates: Requirements 3.10, 4.2, 4.3, 4.5, 4.6, 4.9, 4.14</p>
     */
    @Property(tries = 25)
    void property7_backfillBoundedAndConverging(@ForAll("recordHistories") RecordHistory history) {
        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();
        LocalDate baseSettleDate = LocalDate.now(CLOCK);

        TreeSet<LocalDate> recordDays = new TreeSet<>();
        for (int offset : history.dayOffsets) {
            recordDays.add(baseSettleDate.minusDays(offset));
        }
        seedRecords(userId, ledgerId, recordDays);

        settleUntilConvergenceAndAssert(userId, recordDays, history.advanceSeconds);
    }

    /**
     * 存量大户示例：预置 3000 个连续历史记账日（单窗只覆盖 1000 天，需多次结算收敛，稳定覆盖
     * 「窗口末日 &lt; 结算日」分支）。规模较大单独作示例跑一次，避免拖慢 {@code @Property} 每轮迭代。
     *
     * <p>Validates: Requirements 3.10, 4.2, 4.3, 4.5, 4.6, 4.9, 4.14</p>
     */
    @Example
    void bigHistory_convergesAndStaysBounded() {
        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();
        LocalDate baseSettleDate = LocalDate.now(CLOCK);

        TreeSet<LocalDate> recordDays = new TreeSet<>();
        for (int d = 1; d <= 3000; d++) {          // 偏移 1..3000：最早记账日在结算日前 3000 天。
            recordDays.add(baseSettleDate.minusDays(d));
        }
        seedRecords(userId, ledgerId, recordDays);

        // 每次结算之间推进 61 秒（越过 60 秒记账节流窗口，仍是同一自然日，结算日不变）。
        settleUntilConvergenceAndAssert(userId, recordDays, 61L);
    }

    // ---------------- 收敛循环与不变式断言 ----------------

    /**
     * 反复结算直至 {@code last_record_date} 追平记账日历最大日期，逐次断言 Property 7 的全部不变式。
     */
    private void settleUntilConvergenceAndAssert(long userId, TreeSet<LocalDate> recordDays, long advanceSeconds) {
        LocalDate maxRecordDay = recordDays.isEmpty() ? null : recordDays.last();
        int settlementCount = 0;
        int iterationCap = recordDays.size() + 5;   // 收敛应发生在 recordDays.size() 次以内，留出安全余量。
        List<LocalDate> backfillStarts = new ArrayList<>();
        LocalDate lastRecordDate = null;
        boolean converged = false;

        for (int iter = 0; iter < iterationCap && !converged; iter++) {
            // 本次结算的追补起点（最早未补发记账日）——用测试自己的 JdbcTemplate 独立推算，
            // 不经 Hibernate，故不被计数器计入；据此断言「起点严格推进」。
            LocalDate backfillStart = computeBackfillStart(userId, lastRecordDate);

            int dailyBefore = countDailyRecords(userId);
            int eventsBefore = countEvents(userId);
            BackfillQueryInspector.reset();

            SettleOutcome outcome = settlementService.settle(userId, TriggerSource.RECORD);
            settlementCount++;

            // 有界性①：追补查询次数 ≤2（需求 4.6）。
            assertThat(BackfillQueryInspector.count())
                    .as("单次结算追补查询次数 ≤ %d", MAX_BACKFILL_QUERIES)
                    .isLessThanOrEqualTo(MAX_BACKFILL_QUERIES);

            if (outcome == SettleOutcome.SETTLED) {
                int dailyWrites = countDailyRecords(userId) - dailyBefore;
                int totalWrites = countEvents(userId) - eventsBefore;
                // 有界性②③：单次 DAILY_RECORD ≤1000、总事件 ≤1016（需求 3.10、4.6）。
                assertThat(dailyWrites).as("单次结算 DAILY_RECORD 写入 ≤ %d", MAX_DAILY_WRITES)
                        .isLessThanOrEqualTo(MAX_DAILY_WRITES);
                assertThat(totalWrites).as("单次结算成长事件总写入 ≤ %d", MAX_TOTAL_WRITES)
                        .isLessThanOrEqualTo(MAX_TOTAL_WRITES);
                if (backfillStart != null) {
                    backfillStarts.add(backfillStart);
                }
            }

            UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();
            lastRecordDate = profile.getLastRecordDate();
            List<LocalDate> calendar = calendarDates(userId);
            LocalDate settleDate = LocalDate.now(CLOCK);

            assertNoHoleInvariants(calendar, lastRecordDate, recordDays, settleDate);

            converged = Objects.equals(lastRecordDate, maxRecordDay);
            if (!converged && advanceSeconds > 0) {
                CLOCK.advance(Duration.ofSeconds(advanceSeconds));
            }
        }

        // 收敛性：一定在有限次内追平，且结算次数不超过记账日个数（空集视作 1 次上界）。
        assertThat(converged).as("追补应在有限次结算内收敛").isTrue();
        assertThat(settlementCount)
                .as("补齐所需结算次数 ≤ 未补发记账日个数")
                .isLessThanOrEqualTo(Math.max(recordDays.size(), 1));

        // 单调推进：追补起点严格递增（需求 4.2、4.3）。
        for (int i = 1; i < backfillStarts.size(); i++) {
            assertThat(backfillStarts.get(i))
                    .as("第 %d 次追补起点应严格晚于上一次", i + 1)
                    .isAfter(backfillStarts.get(i - 1));
        }

        // 收敛终态：记账日历恰好等于全部历史记账日集合（需求 4.9）。
        assertThat(new TreeSet<>(calendarDates(userId)))
                .as("补齐后记账日历应等于全部历史记账日")
                .isEqualTo(recordDays);
    }

    /**
     * 无空洞不变式 + 窗口末日 &lt; 结算日不写结算日（需求 4.9、4.14）。
     */
    private void assertNoHoleInvariants(List<LocalDate> calendar, LocalDate lastRecordDate,
                                        TreeSet<LocalDate> recordDays, LocalDate settleDate) {
        if (lastRecordDate == null) {
            assertThat(calendar).as("last_record_date 为空时日历必为空").isEmpty();
            return;
        }
        // last_record_date 恒等于日历最大日期。
        assertThat(Collections.max(calendar))
                .as("last_record_date 应等于记账日历最大日期")
                .isEqualTo(lastRecordDate);
        // 日历 ⊆ 全部记账日（不凭空造出记账日）。
        assertThat(recordDays).as("记账日历应是历史记账日的子集").containsAll(calendar);
        // 无空洞：所有不晚于 last_record_date 的历史记账日都已补发（追补从不跳过边界内的记账日）。
        for (LocalDate day : recordDays) {
            if (!day.isAfter(lastRecordDate)) {
                assertThat(calendar)
                        .as("记账日 %s ≤ last_record_date %s 却缺失（空洞）", day, lastRecordDate)
                        .contains(day);
            }
        }
        // 窗口末日 < 结算日 ⇒ 本次不写 DAILY_RECORD:<结算日>：last_record_date 尚未追平结算日时，
        // 日历不得含结算日（需求 4.2、4.14）。
        if (lastRecordDate.isBefore(settleDate)) {
            assertThat(calendar).as("窗口末日 < 结算日时不应写入结算日的 DAILY_RECORD")
                    .doesNotContain(settleDate);
        }
    }

    // ---------------- 事实源播种与查询（均走 JdbcTemplate，不经 Hibernate、不被计数器计入）----------------

    /** 用 {@code batchUpdate} 直插「有效记账交易」，记账日 = {@code created_at} 的日期。 */
    private void seedRecords(long userId, long ledgerId, TreeSet<LocalDate> recordDays) {
        if (recordDays.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(recordDays.size());
        for (LocalDate day : recordDays) {
            LocalDateTime createdAt = day.atTime(9, 0);
            batch.add(new Object[] {userId, ledgerId, userId, ledgerId, ledgerId, createdAt, createdAt, createdAt});
        }
        jdbcTemplate.batchUpdate(INSERT_RECORD_SQL, batch);
    }

    /**
     * 独立推算本次结算的追补起点（最早未补发的有效记账日）。与生产查询 A 同口径，但走
     * {@link JdbcTemplate}（不经 Hibernate，故不计入追补查询计数）。返回 {@code null} 表示已无可追补。
     */
    private LocalDate computeBackfillStart(long userId, LocalDate lastRecordDate) {
        LocalDateTime earliest;
        if (lastRecordDate == null) {
            earliest = jdbcTemplate.queryForObject(
                    "SELECT MIN(created_at) FROM transactions WHERE created_by = ? "
                            + "AND deleted_at IS NULL AND type IN ('expense','income') AND ledger_id IS NOT NULL",
                    LocalDateTime.class, userId);
        } else {
            earliest = jdbcTemplate.queryForObject(
                    "SELECT MIN(created_at) FROM transactions WHERE created_by = ? "
                            + "AND deleted_at IS NULL AND type IN ('expense','income') AND ledger_id IS NOT NULL "
                            + "AND created_at >= ?",
                    LocalDateTime.class, userId, lastRecordDate.plusDays(1).atStartOfDay());
        }
        return earliest == null ? null : earliest.toLocalDate();
    }

    private int countDailyRecords(long userId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_type = 'DAILY_RECORD'",
                Integer.class, userId);
        return c == null ? 0 : c;
    }

    private int countEvents(long userId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ?", Integer.class, userId);
        return c == null ? 0 : c;
    }

    /** 取该用户 {@code DAILY_RECORD} 事件键解析出的记账日历（升序）。 */
    private List<LocalDate> calendarDates(long userId) {
        List<String> keys = jdbcTemplate.queryForList(
                "SELECT event_key FROM growth_events WHERE user_id = ? AND event_type = 'DAILY_RECORD' "
                        + "ORDER BY event_key ASC",
                String.class, userId);
        List<LocalDate> dates = new ArrayList<>(keys.size());
        for (String key : keys) {
            dates.add(LocalDate.parse(key.substring("DAILY_RECORD:".length())));
        }
        return dates;
    }

    // ---------------- 追补查询计数装饰器 ----------------

    /**
     * Hibernate {@link StatementInspector}：只对追补查询 A（{@code MIN(created_at)}）与查询 B
     * （{@code CAST(created_at AS DATE)}）计数，二者是追补查询独有片段（见类级 Javadoc）。
     * 由 Hibernate 依类名反射实例化，故必须是 {@code public static} 且带公有无参构造；计数器为静态，
     * 供测试线程读取。属性测试串行执行，无并发写计数器之虞。
     */
    public static final class BackfillQueryInspector implements StatementInspector {

        private static final AtomicInteger COUNT = new AtomicInteger();

        public BackfillQueryInspector() {
            // Hibernate 反射实例化所需的公有无参构造。
        }

        @Override
        public String inspect(String sql) {
            if (sql != null) {
                String lower = sql.toLowerCase();
                if (lower.contains("min(created_at)") || lower.contains("cast(created_at as date)")) {
                    COUNT.incrementAndGet();
                }
            }
            return sql;
        }

        static void reset() {
            COUNT.set(0);
        }

        static int count() {
            return COUNT.get();
        }
    }

    /** 提供一个 {@code @Primary} 的可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日可确定性推进。 */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    /** 可推进、可归位的时钟（供每次迭代前 reset）。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
        }

        void reset(Instant to) {
            this.instant = to;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
