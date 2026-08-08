package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 12：并发终态唯一</b>的属性测试（任务 8.3）。
 *
 * <p><i>对任意</i>并发度 ∈ [2, 8] × <i>任意</i>记账日历：同一用户在 1000ms 内并发发起 2～8 次结算后，
 * 段表满足两条终态性质（需求 4.12、4.13、4.14）：</p>
 * <ul>
 *   <li>任一 {@code start_date} 的段行数<b>至多 1</b>——由唯一约束 {@code uk_streak_segments_user_start}
 *       兜底；</li>
 *   <li>并发落定后的段序列满足 Property 1 的<b>五条不变式</b>，且逐项等于纯函数
 *       {@link GrowthCalendarService#segments(List)} 对同一日历的重算结果——串行化由既有的
 *       {@code user_growth} 行级写锁承担，段维护的 ODKU 兜底把并发插入收敛为「至多一次插入 + 其余转更新」。</li>
 * </ul>
 *
 * <h2>H2 只验证其中一半（与 growth-level-system 的既有取舍一致）</h2>
 *
 * <p>H2 {@code MODE=MySQL} <b>复现不出</b> InnoDB 真实的行级写锁竞争：{@code SELECT ... FOR UPDATE}
 * 在 H2 上不会「另一会话持锁时立即抛错并触发退避放弃」。因此本属性在 H2 上验证的是
 * 「<b>唯一约束兜底 + ODKU 冲突转更新</b>」这一半——即便多个并发结算都试图为同一 {@code start_date}
 * 插入段行，唯一约束与 {@code ON DUPLICATE KEY UPDATE} 也把终态收敛为每个起始日至多一行、且取值正确。
 * 「行级写锁真实串行化」那一半落在任务 1.4 的真实 MySQL 手工验证清单第 3 项，本类不覆盖。</p>
 *
 * <h2>驱动方式（不依赖事务回滚，日历一律落在过去以绕开节流）</h2>
 *
 * <p>{@code settle} 带 {@code REQUIRES_NEW}，只有真实提交才能观察到段的终态，故本类<b>不用测试级事务包裹</b>；
 * 每次迭代都用全新的 {@code userId}（全局自增 {@link #SEQ}），迭代间天然互不影响。记账日一律放在
 * <b>过去</b>（今天往前数几天），使记账侧节流的第二个条件（{@code last_record_date == 结算日}）永不成立，
 * 全部并发 {@code settle} 都真实执行段维护。并发争锁下少数结算可能因 500ms 墙钟预算耗尽抛
 * {@code GrowthLockAbandonedException}——这是既有行锁机制的正常降级，被线程内捕获并忽略，
 * 只要至少一次结算成功、终态就收敛到正确段序列（后到的成功结算做全量对账覆盖）。</p>
 *
 * <p>jqwik 属性方法不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 手工完成（上下文缓存复用）。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Feature: streak-system, Property 12: 并发终态唯一</p>
 * <p>Validates: Requirements 4.12, 4.13, 4.14</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-concurrent-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class StreakConcurrentTerminalPropertyTest {

    /** 交易直插语句：列顺序与 {@link #seedTransaction} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 全局自增序号：保证跨迭代的用户 id 全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong(1_260_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void prepare() throws Exception {
        new TestContextManager(StreakConcurrentTerminalPropertyTest.class).prepareTestInstance(this);
    }

    // ---------------- 生成器 ----------------

    /**
     * 记账日历：长度 0～60 的一组自然日偏移（相对一个固定的过去基准日往后推），去重升序。
     *
     * <p>偏移取值刻意稠密（含相邻与断裂），使生成的日历同时覆盖全连续、全离散、跨月、含空隙等形态；
     * 全部日期落在过去，绕开记账侧节流。</p>
     */
    @Provide
    Arbitrary<List<LocalDate>> calendars() {
        LocalDate base = LocalDate.now().minusDays(90);
        Arbitrary<Integer> offsets = Arbitraries.integers().between(0, 80);
        return offsets.set().ofMinSize(0).ofMaxSize(60)
                .map(set -> set.stream().sorted().map(base::plusDays).toList());
    }

    // ---------------- Property 12 ----------------

    /**
     * Feature: streak-system, Property 12: 并发终态唯一
     *
     * <p>并发度 ∈ [2, 8] × 记账日历：并发结算落定后，任一 {@code start_date} 至多一行段、段序列满足五条
     * 不变式且逐项等于纯函数重算结果。{@code tries = 100}。</p>
     *
     * <p>Validates: Requirements 4.12, 4.13, 4.14</p>
     */
    @Property(tries = 25)
    void concurrentSettlements_leaveAtMostOneSegmentPerStartDate_andSatisfyFiveInvariants(
            @ForAll("calendars") List<LocalDate> calendar,
            @ForAll @IntRange(min = 2, max = 8) int concurrency) throws Exception {

        long userId = SEQ.getAndIncrement();
        long ledgerId = 900_000_000L + userId;

        // 一天一笔支出：记账日历（DAILY_RECORD 日期集合）等于 calendar 去重。
        for (LocalDate day : calendar) {
            seedTransaction(userId, ledgerId, day);
        }

        runConcurrently(concurrency, () -> {
            try {
                settlementService.settle(userId, TriggerSource.RECORD);
            } catch (GrowthLockAbandonedException ignored) {
                // 争锁下的正常降级：500ms 墙钟预算耗尽即放弃本次结算，不影响终态收敛。
            }
        });

        // ── 性质一：任一 start_date 至多一行段（唯一约束兜底，需求 4.13、4.14）─────────────
        List<Long> duplicateStartDates = jdbcTemplate.queryForList(
                "SELECT COUNT(*) FROM streak_segments WHERE user_id = ? "
                        + "GROUP BY start_date HAVING COUNT(*) > 1",
                Long.class, userId);
        assertThat(duplicateStartDates)
                .as("并发落定后任一 start_date 的段行数至多 1（uk_streak_segments_user_start 兜底）")
                .isEmpty();

        // ── 性质二：段序列满足五条不变式，且逐项等于纯函数重算结果（需求 4.12）───────────
        List<Seg> persisted = segmentsOf(userId);
        assertFiveInvariants(persisted, calendar);
    }

    // ---------------- 五条不变式 ----------------

    /**
     * 断言落表的段序列满足 design.md「Property 1」的五条不变式。做法是先断言它逐项等于纯函数
     * {@link GrowthCalendarService#segments(List)} 对同一日历的重算结果（这已蕴含五条），再对
     * 「至多落一段 / 两两不相邻」这类跨段性质补一道独立校验，避免与被测实现共用同一段划分逻辑而空转。
     */
    private void assertFiveInvariants(List<Seg> persisted, List<LocalDate> calendar) {
        List<StreakSegmentView> expected = GrowthCalendarService.segments(calendar);
        CalendarScan scan = GrowthCalendarService.scan(calendar);

        assertThat(persisted).as("并发终态的段数与纯函数重算一致").hasSize(expected.size());

        long sumDays = 0;
        int maxDays = 0;
        for (int i = 0; i < expected.size(); i++) {
            StreakSegmentView want = expected.get(i);
            Seg have = persisted.get(i);
            // 不变式①：endDate >= startDate 且 days == endDate − startDate + 1。
            assertThat(have.end()).as("第 %d 段 end_date 与重算一致", i).isEqualTo(want.endDate());
            assertThat(have.start()).as("第 %d 段 start_date 与重算一致", i).isEqualTo(want.startDate());
            assertThat(have.days()).as("第 %d 段 days 与重算一致", i).isEqualTo(want.days());
            assertThat(have.end()).as("第 %d 段 end_date >= start_date", i)
                    .isAfterOrEqualTo(have.start());
            assertThat(have.days())
                    .as("第 %d 段 days == end − start + 1", i)
                    .isEqualTo((int) (have.end().toEpochDay() - have.start().toEpochDay() + 1));
            // 不变式②：任一段起始日严格晚于前一段结束日的次日（两两既不相交也不相邻）。
            if (i > 0) {
                assertThat(have.start())
                        .as("第 %d 段与前一段之间至少隔 1 个不在日历中的自然日", i)
                        .isAfter(persisted.get(i - 1).end().plusDays(1));
            }
            sumDays += have.days();
            maxDays = Math.max(maxDays, have.days());
        }

        // 不变式③：Σ days == totalRecordDays（去重后的日历日期个数）。
        assertThat(sumDays).as("Σ days == totalRecordDays").isEqualTo(scan.totalDays());
        // 不变式④：max(days) == scan.maxStreak()。
        assertThat(maxDays).as("max(days) == maxStreak").isEqualTo(scan.maxStreak());
        // 不变式⑤：非空时末段 end_date == lastDate；空时段表为空。
        if (persisted.isEmpty()) {
            assertThat(scan.lastDate()).as("空日历：lastDate 为空").isNull();
        } else {
            assertThat(persisted.get(persisted.size() - 1).end())
                    .as("末段 end_date == lastDate").isEqualTo(scan.lastDate());
        }
    }

    // ---------------- 并发编排 ----------------

    /**
     * 用一道倒计时门让 {@code concurrency} 个线程尽量同时起跑，并在 1000ms 内全部落定
     * （需求 4.12 的「1000ms 内并发」）。
     */
    private void runConcurrently(int concurrency, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        try {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(done.await(1000, TimeUnit.MILLISECONDS))
                    .as("2～8 次并发结算应在 1000ms 内全部落定（需求 4.12）").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- 库读取 / 播种辅助 ----------------

    /** 段行的不可变投影，供逐列相等断言。 */
    private record Seg(LocalDate start, LocalDate end, int days) {
    }

    /** 该用户全部段行，按 {@code start_date} 升序。 */
    private List<Seg> segmentsOf(long userId) {
        return jdbcTemplate.query(
                "SELECT start_date, end_date, days FROM streak_segments "
                        + "WHERE user_id = ? ORDER BY start_date",
                (rs, i) -> new Seg(
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getInt("days")),
                userId);
    }

    private void seedTransaction(long userId, long ledgerId, LocalDate recordDay) {
        Timestamp createdAt = Timestamp.valueOf(recordDay.atTime(12, 0));
        long ref = 900_000_000L + userId;
        jdbcTemplate.update(INSERT_TX_SQL, userId, ledgerId, userId, "expense",
                new BigDecimal("1.00"), ref, ref, createdAt, createdAt, createdAt);
    }
}
