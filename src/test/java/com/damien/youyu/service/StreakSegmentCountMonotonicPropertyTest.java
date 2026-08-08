package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 7：段总数单调不减</b>的属性测试（任务 8.2）。
 *
 * <p><i>对任意</i>操作序列（在一个 2–5 人的用户池上交错追加记账日），同一用户在某时刻观察到的段总数
 * <b>大于或等于</b>其在任一更早时刻观察到的段总数（需求 5.8）。注销与需求 4.15 的数据修复删除是仅有的
 * 两个例外，本属性<b>不生成</b>这两种操作。</p>
 *
 * <h2>单调性来自「记账日历只追加」这一外部事实，本类把这条事实建进生成器</h2>
 *
 * <p>段总数的单调性不是 {@code maintain} 的语法保证——若能在两个已存在段之间「架桥」补一个日期，就会把
 * 两段合并、段总数<b>下降</b>。生产之所以不会发生，是因为 {@code GrowthCalendarService.backfillDates}
 * 的追补起点恒为「{@code last_record_date} 的次日」，新日期只会落在<b>尾段之后</b>，永不回填空隙。本类的
 * 生成器如实复刻这条外部事实：每个用户的下一个记账日恒为 {@code 当前最大日期 + gap}（{@code gap ∈ [0, 30]}，
 * {@code gap == 0} 表示同日多笔、日历不新增日期）。于是每次维护要么延长尾段（段总数不变）、要么另起新段
 * （段总数 +1）、要么日历没变（段总数不变），绝不会合并——单调性因此成立。属性测试锁住「生成器的追加
 * 语义」与「维护的段总数走向」这对耦合：一旦维护里冒出一条会减少段行的路径（例如误加合并 / 误删），
 * 本测试立刻变红。</p>
 *
 * <h2>驱动方式与清理</h2>
 *
 * <p>直接调包内可见的 {@code maintain}，对真实 H2 读写并真实提交，不用测试级事务；清理由
 * {@link #resetState()} 每次迭代前清表、{@link #SEQ} 给用户池分配全局唯一 {@code userId}。jqwik 属性方法
 * 的依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成。使用独立命名的内存库。</p>
 *
 * <p>Feature: streak-system, Property 7: 段总数单调不减</p>
 *
 * <p>Validates: Requirements 5.8, 3.4</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-monotonic-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class StreakSegmentCountMonotonicPropertyTest {

    private static final AtomicLong SEQ = new AtomicLong(871_000_000L);
    private static final LocalDate BASE = LocalDate.of(2019, 1, 1);
    private static final LocalDateTime NOW = LocalDateTime.of(2025, 6, 15, 8, 0);

    @Autowired
    private StreakSegmentMaintainer maintainer;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(StreakSegmentCountMonotonicPropertyTest.class).prepareTestInstance(this);
        jdbcTemplate.update("DELETE FROM streak_segments");
    }

    /**
     * 操作序列：1–40 个操作，每个操作是 {@code (用户选择子 [0,4], gap [0,30])}。
     *
     * <p>用户选择子经 {@code % 用户数} 映射到用户池；{@code gap} 决定「下一个记账日 = 当前最大日期 + gap」，
     * {@code gap == 0} 表示同日多笔（日历不新增日期）。</p>
     */
    @Provide
    Arbitrary<List<Tuple2<Integer, Integer>>> operationSequences() {
        Arbitrary<Tuple2<Integer, Integer>> op = Combinators.combine(
                Arbitraries.integers().between(0, 4),
                Arbitraries.integers().between(0, 30)).as(Tuple::of);
        return op.list().ofMinSize(1).ofMaxSize(40);
    }

    /**
     * Feature: streak-system, Property 7: 段总数单调不减
     *
     * <p>追加型操作序列后，每个用户观察到的段总数逐步单调不减（需求 5.8）。</p>
     *
     * <p>Validates: Requirements 5.8, 3.4</p>
     */
    @Property(tries = 20)
    void segmentCountIsMonotonicNonDecreasing(
            @ForAll @IntRange(min = 2, max = 5) int userCount,
            @ForAll("operationSequences") List<Tuple2<Integer, Integer>> ops) {
        assertMonotonic(userCount, ops);
    }

    /** 顶角：单用户、每步 gap 交替 0/1/2，覆盖「同日多笔 / 延长尾段 / 另起新段」三种走向。 */
    @Example
    void alternatingGapsCorner() {
        assertMonotonic(1, List.of(
                Tuple.of(0, 5), Tuple.of(0, 0), Tuple.of(0, 1), Tuple.of(0, 2),
                Tuple.of(0, 0), Tuple.of(0, 1), Tuple.of(0, 10)));
    }

    private void assertMonotonic(int userCount, List<Tuple2<Integer, Integer>> ops) {
        long[] userIds = new long[userCount];
        List<List<LocalDate>> calendars = new ArrayList<>(userCount);
        LocalDate[] maxDate = new LocalDate[userCount];
        long[] lastCount = new long[userCount];
        for (int u = 0; u < userCount; u++) {
            userIds[u] = SEQ.getAndIncrement();
            calendars.add(new ArrayList<>());
            maxDate[u] = null;
            lastCount[u] = 0L;
        }

        for (Tuple2<Integer, Integer> op : ops) {
            int u = op.get1() % userCount;
            int gap = op.get2();

            // 下一个记账日 = 当前最大日期 + gap（首个记账日取 BASE）；日历只追加，永不回填空隙。
            LocalDate next = (maxDate[u] == null) ? BASE : maxDate[u].plusDays(gap);
            maxDate[u] = next;
            calendars.get(u).add(next);

            maintainer.maintain(userIds[u], new ArrayList<>(calendars.get(u)), NOW);

            long count = segmentCountOf(userIds[u]);
            assertThat(count)
                    .as("用户 %d 的段总数必须单调不减：上一观察值=%d，本次=%d（需求 5.8）",
                            u, lastCount[u], count)
                    .isGreaterThanOrEqualTo(lastCount[u]);
            assertThat(count).as("段总数恒为非负整数（需求 5.8）").isNotNegative();
            lastCount[u] = count;
        }
    }

    private long segmentCountOf(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streak_segments WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }
}
