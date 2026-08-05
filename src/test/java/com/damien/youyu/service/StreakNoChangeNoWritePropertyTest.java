package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 5：无变化即无写入</b>的属性测试（任务 8.2）。
 *
 * <p><i>对任意</i>记账日历：首次段维护把它落表之后，只要日历未新增任何日期，再维护 2–5 次都执行
 * <b>0 条</b>插入 / 更新 / 删除语句，且段行数与全部列取值（含 {@code created_at}、{@code updated_at}）
 * 与首次维护后<b>逐项相同</b>（需求 4.8、4.10、4.11 后半句、5.2）。</p>
 *
 * <h2>为什么 0 写入是构造性的、却仍要属性测试锁住</h2>
 *
 * <p>{@link StreakSegmentMaintainer#maintain} 的 diff 比较的是「应有值」与「已持久化值」的<b>逐项相等</b>
 * （{@code end_date} 与 {@code days}），而非「先查是否存在再决定写不写」的时序判断。同一日历经纯函数
 * {@code segments} 恒得同一段序列，故第二次 diff 必为空 → upsert 批为空 → {@code batchUpdate} 不被调用、
 * 孤儿集为空 → 删除不被调用。这是<b>值幂等</b>。属性测试锁住它：一旦有人把 diff 改成「每次都写一遍」
 * 或把 {@code updated_at} 无条件刷新，本测试立刻变红。</p>
 *
 * <h2>怎么数「写入语句条数」：段的写入分两条物理路径，各自计数</h2>
 *
 * <p>段维护的写入只有两条路径——插入 / 更新走 {@code JdbcTemplate} 的一条 ODKU 批量语句
 * （{@code INSERT ... ON DUPLICATE KEY UPDATE}），删除走 JPA {@code @Modifying}。{@code JdbcTemplate}
 * 的原生 SQL <b>不经</b> Hibernate，因此 Hibernate 的 {@code StatementInspector} / {@code Statistics}
 * 看不到 ODKU。本类因此用一个 {@code @Primary} 的<b>计数型 {@link JdbcTemplate}</b>
 * （{@link CountingJdbcTemplate}）拦截 {@code batchUpdate(String, List)}：SQL 命中 {@code streak_segments}
 * 即计一次——这精确等于 ODKU 语句条数。删除路径则由「段行数 + 全部列取值逐项不变」这条<b>效果级</b>
 * 断言兜住（删除会减少行数、插入 / 更新会改动 {@code updated_at}）。再叠加一层保险：第二次起一律传入
 * <b>推进后的 {@code now}</b>，若真发生任何 ODKU 写入，{@code updated_at} 会跳到新值而被逐列比对当场抓出。</p>
 *
 * <h2>驱动方式与清理</h2>
 *
 * <p>直接调包内可见的 {@code maintain}，对真实 H2 读写并真实提交，故不用测试级事务；清理由
 * {@link #resetState()} 每次迭代前清表、{@link #SEQ} 分配全局唯一 {@code userId}。jqwik 属性方法的依赖
 * 注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成。使用独立命名的内存库。</p>
 *
 * <p>Feature: streak-system, Property 5: 无变化即无写入</p>
 *
 * <p>Validates: Requirements 4.8, 4.10, 4.11, 5.2</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-nochange-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(StreakNoChangeNoWritePropertyTest.CountingJdbcConfig.class)
class StreakNoChangeNoWritePropertyTest {

    private static final AtomicLong SEQ = new AtomicLong(851_000_000L);
    private static final LocalDate BASE = LocalDate.of(2021, 3, 1);
    /** 首次维护的时间戳。 */
    private static final LocalDateTime NOW_FIRST = LocalDateTime.of(2025, 6, 15, 8, 0);
    /** 重复维护的时间戳（刻意推进一天）：若误发 ODKU，{@code updated_at} 会跳到此值而被逐列比对抓出。 */
    private static final LocalDateTime NOW_REPEAT = NOW_FIRST.plusDays(1);

    @Autowired
    private StreakSegmentMaintainer maintainer;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(StreakNoChangeNoWritePropertyTest.class).prepareTestInstance(this);
        jdbcTemplate.update("DELETE FROM streak_segments");
        CountingJdbcTemplate.reset();
    }

    /** 日历：0–40 个偏移（{@code [0, 400]}），覆盖空 / 单点 / 连续 / 离散 / 重复 / 乱序。 */
    @Provide
    Arbitrary<List<Integer>> calendars() {
        return Arbitraries.integers().between(0, 400).list().ofMaxSize(40);
    }

    /**
     * Feature: streak-system, Property 5: 无变化即无写入
     *
     * <p>日历未新增日期时，重复维护 0 条写入语句、段行全部列逐项不变（需求 4.8、4.10、4.11、5.2）。</p>
     *
     * <p>Validates: Requirements 4.8, 4.10, 4.11, 5.2</p>
     */
    @Property(tries = 40)
    void repeatedMaintenanceWithUnchangedCalendarWritesNothing(
            @ForAll("calendars") List<Integer> offsets,
            @ForAll @IntRange(min = 2, max = 5) int repeats) {
        assertNoChangeNoWrite(offsets, repeats);
    }

    /** 空日历顶角：段行数为 0，重复维护仍 0 写入（否则「无写入」在空表上恒真、失去意义的边界也要覆盖）。 */
    @Example
    void emptyCalendarCorner() {
        assertNoChangeNoWrite(List.of(), 3);
    }

    private void assertNoChangeNoWrite(List<Integer> offsets, int repeats) {
        long userId = SEQ.getAndIncrement();
        List<LocalDate> calendar = new ArrayList<>(offsets.size());
        for (int offset : offsets) {
            calendar.add(BASE.plusDays(offset));
        }

        // 首次维护：把日历落表。
        maintainer.maintain(userId, new ArrayList<>(calendar), NOW_FIRST);
        List<SegFull> afterFirst = fullSegmentsOf(userId);

        // 从这里开始计数：日历未变的重复维护应 0 条写入语句。
        CountingJdbcTemplate.reset();
        for (int i = 0; i < repeats; i++) {
            maintainer.maintain(userId, new ArrayList<>(calendar), NOW_REPEAT);
        }

        assertThat(CountingJdbcTemplate.segmentWrites())
                .as("日历未新增日期时重复维护 %d 次执行的段写入语句（ODKU）应为 0 条（需求 4.8、5.2）", repeats)
                .isZero();
        assertThat(fullSegmentsOf(userId))
                .as("段行数与全部列取值（含 created_at / updated_at）与首次维护后逐项相同（需求 4.11、5.2）")
                .isEqualTo(afterFirst);
    }

    /** 段行的完整投影（含时间戳），供「全部列逐项不变」断言。 */
    private record SegFull(LocalDate start, LocalDate end, int days,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    private List<SegFull> fullSegmentsOf(long userId) {
        return jdbcTemplate.query(
                "SELECT start_date, end_date, days, created_at, updated_at "
                        + "FROM streak_segments WHERE user_id = ? ORDER BY start_date",
                (rs, i) -> new SegFull(
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getInt("days"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                userId);
    }

    /** 计数型 {@code JdbcTemplate} 覆盖 Spring Boot 自动装配的那一个（{@code @Primary}），注入进段维护。 */
    @TestConfiguration
    static class CountingJdbcConfig {
        @Bean
        @Primary
        JdbcTemplate countingJdbcTemplate(DataSource dataSource) {
            return new CountingJdbcTemplate(dataSource);
        }
    }

    /**
     * 只在 {@code batchUpdate(String, List)} 命中 {@code streak_segments} 时计数、随后透明委托 super。
     *
     * <p>段维护的插入 / 更新只走这一条 ODKU 批量语句，故命中次数精确等于 ODKU 语句条数。计数器为静态，
     * 供属性方法读取；每次迭代前 {@link #reset()}。</p>
     */
    static final class CountingJdbcTemplate extends JdbcTemplate {

        private static final AtomicInteger SEGMENT_WRITES = new AtomicInteger();

        CountingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
            if (sql != null && sql.toLowerCase(Locale.ROOT).contains("streak_segments")) {
                SEGMENT_WRITES.incrementAndGet();
            }
            return super.batchUpdate(sql, batchArgs);
        }

        static void reset() {
            SEGMENT_WRITES.set(0);
        }

        static int segmentWrites() {
            return SEGMENT_WRITES.get();
        }
    }
}
