package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.damien.youyu.domain.StreakSegment;
import com.damien.youyu.repository.StreakSegmentRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link StreakSegmentMaintainer#maintain} 的示例/边界单元测试（关联需求 4.4、4.6、4.7、4.8、
 * 4.10、4.11、4.15、4.16、7.7）。
 *
 * <p>不起 Spring 上下文：仓储与 {@link JdbcTemplate} 用 Mockito 桩、墙钟用可推进的
 * {@link MutableClock}。段的应有序列由真实的 {@link GrowthCalendarService#segments} 纯函数从
 * 构造的记账日历算出（该纯函数已由 {@code GrowthCalendarServiceSegmentsTest} 单独覆盖），
 * 已持久化的段用桩 {@link StreakSegmentRepository#findByUserIdOrderByStartDateAsc} 返回，因此
 * 本测试锁住的是 diff、写入行数与耗时/异常这几段维护逻辑本身：</p>
 *
 * <ul>
 *   <li>首次建立：已持久化为空，diff 等于全部应有段，一次 {@code batchUpdate} 写入全部行；</li>
 *   <li>尾段延长：仅当前段的 {@code end_date}/{@code days} 变，diff 恰 1 行（一次 ODKU 表现为 UPDATE）；</li>
 *   <li>另起新段：新增一段孤立日，diff 恰 1 行（一次 ODKU 表现为 INSERT）、旧段不进 diff；</li>
 *   <li>无变化：diff 为空 → {@code batchUpdate} 与 {@code deleteByIdIn} 均不被调用（0 条 SQL）；</li>
 *   <li>孤儿段删除：已持久化中起始日不在重算结果里的段被 {@code deleteByIdIn} 删除（数据修复路径）；</li>
 *   <li>写入行数越界：写入行数超过 {@code max(1000, 日历天数) + 1} 抛 {@link IllegalStateException}，
 *       且在抛出前不执行任何删除或写入 SQL；</li>
 *   <li>耗时 > 300ms：记一条 {@code [STREAK_MAINTAIN_SLOW]} WARN，不抛异常；</li>
 *   <li>异常不被吞掉：底层写入失败时异常穿出 {@code maintain} 而非被 {@code catch}（保证
 *       {@code REQUIRES_NEW} 事务能回滚，需求 4.16）。</li>
 * </ul>
 */
class StreakSegmentMaintainerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long USER = 42L;
    private static final LocalDateTime NOW = LocalDateTime.of(2025, 1, 10, 12, 0, 0);
    private static final String UPSERT_SQL =
            "INSERT INTO streak_segments (user_id, start_date, end_date, days, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE end_date = VALUES(end_date), days = VALUES(days), "
                    + "updated_at = VALUES(updated_at)";

    private StreakSegmentRepository repository;
    private JdbcTemplate jdbcTemplate;
    private MutableClock clock;
    private StreakSegmentMaintainer maintainer;

    private Logger maintainerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        repository = mock(StreakSegmentRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        clock = new MutableClock(Instant.parse("2025-01-10T04:00:00Z"), ZONE);
        maintainer = new StreakSegmentMaintainer(repository, jdbcTemplate, clock);

        maintainerLogger = (Logger) LoggerFactory.getLogger(StreakSegmentMaintainer.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        maintainerLogger.addAppender(logAppender);
        maintainerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        maintainerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // ---- 首次建立：diff = 全部段 ----

    @Test
    void firstBuildWritesAllSegments() {
        // 日历有两段：[01-01..01-02] 与 [01-05]。
        List<LocalDate> calendar = dates("2025-01-01", "2025-01-02", "2025-01-05");
        when(repository.findByUserIdOrderByStartDateAsc(USER)).thenReturn(List.of());

        maintainer.maintain(USER, calendar, NOW);

        List<Object[]> batch = captureBatch();
        assertThat(batch).hasSize(2);
        // 第一行：段 [01-01..01-02] days=2
        assertThat(batch.get(0)).containsExactly(
                USER, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-02"), 2, NOW, NOW);
        // 第二行：段 [01-05..01-05] days=1
        assertThat(batch.get(1)).containsExactly(
                USER, LocalDate.parse("2025-01-05"), LocalDate.parse("2025-01-05"), 1, NOW, NOW);
        verify(repository, never()).deleteByIdIn(anyList());
    }

    // ---- 尾段延长：1 行 UPDATE ----

    @Test
    void tailExtensionWritesSingleRow() {
        // 已持久化 [01-01..01-02]，日历延长到 01-03。
        when(repository.findByUserIdOrderByStartDateAsc(USER))
                .thenReturn(List.of(segment(1L, "2025-01-01", "2025-01-02", 2)));
        List<LocalDate> calendar = dates("2025-01-01", "2025-01-02", "2025-01-03");

        maintainer.maintain(USER, calendar, NOW);

        List<Object[]> batch = captureBatch();
        assertThat(batch).hasSize(1);
        assertThat(batch.get(0)).containsExactly(
                USER, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-03"), 3, NOW, NOW);
        verify(repository, never()).deleteByIdIn(anyList());
    }

    // ---- 另起新段：1 行 INSERT ----

    @Test
    void newSegmentWritesSingleRow() {
        // 已持久化 [01-01..01-02] 不变，日历新增孤立日 01-05。
        when(repository.findByUserIdOrderByStartDateAsc(USER))
                .thenReturn(List.of(segment(1L, "2025-01-01", "2025-01-02", 2)));
        List<LocalDate> calendar = dates("2025-01-01", "2025-01-02", "2025-01-05");

        maintainer.maintain(USER, calendar, NOW);

        List<Object[]> batch = captureBatch();
        assertThat(batch).hasSize(1);
        assertThat(batch.get(0)).containsExactly(
                USER, LocalDate.parse("2025-01-05"), LocalDate.parse("2025-01-05"), 1, NOW, NOW);
        verify(repository, never()).deleteByIdIn(anyList());
    }

    // ---- 无变化：0 条 SQL ----

    @Test
    void noChangeWritesNothing() {
        when(repository.findByUserIdOrderByStartDateAsc(USER))
                .thenReturn(List.of(segment(1L, "2025-01-01", "2025-01-02", 2)));
        List<LocalDate> calendar = dates("2025-01-01", "2025-01-02");

        maintainer.maintain(USER, calendar, NOW);

        verify(jdbcTemplate, never()).batchUpdate(eq(UPSERT_SQL), anyList());
        verify(repository, never()).deleteByIdIn(anyList());
    }

    // ---- 孤儿段删除 ----

    @Test
    void orphanSegmentDeleted() {
        // 已持久化 [01-01..01-02]（在重算结果中）与 [02-01..02-01]（起始日不在重算结果中 → 孤儿）。
        when(repository.findByUserIdOrderByStartDateAsc(USER))
                .thenReturn(List.of(
                        segment(1L, "2025-01-01", "2025-01-02", 2),
                        segment(2L, "2025-02-01", "2025-02-01", 1)));
        List<LocalDate> calendar = dates("2025-01-01", "2025-01-02");

        maintainer.maintain(USER, calendar, NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).deleteByIdIn(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(2L);
        // [01-01..01-02] 逐项相同 ⇒ 无 upsert。
        verify(jdbcTemplate, never()).batchUpdate(eq(UPSERT_SQL), anyList());
    }

    // ---- 写入行数越界抛 IllegalStateException ----

    @Test
    void writeCountOverCeilingThrows() {
        // 空日历 ⇒ 应有段为空；已持久化 1002 段全部成为孤儿，写入行数 1002 > max(1000,0)+1 = 1001。
        List<StreakSegment> persisted = new ArrayList<>();
        for (int i = 0; i < 1002; i++) {
            persisted.add(segment((long) (i + 1), LocalDate.of(2025, 1, 1).plusDays(i * 2L)));
        }
        when(repository.findByUserIdOrderByStartDateAsc(USER)).thenReturn(persisted);

        assertThatThrownBy(() -> maintainer.maintain(USER, List.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超过上界");

        // 抛出发生在有界性断言处，任何删除/写入 SQL 都不应执行。
        verify(repository, never()).deleteByIdIn(anyList());
        verify(jdbcTemplate, never()).batchUpdate(eq(UPSERT_SQL), anyList());
    }

    // ---- 耗时 > 300ms 记 WARN ----

    @Test
    void slowMaintainLogsWarn() {
        // 用读取已持久化段这一步把墙钟推进 400ms（> 300ms 预算）来模拟慢维护。
        when(repository.findByUserIdOrderByStartDateAsc(USER)).thenAnswer(invocation -> {
            clock.advance(Duration.ofMillis(400));
            return List.of();
        });

        maintainer.maintain(USER, List.of(), NOW);

        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("[STREAK_MAINTAIN_SLOW]");
                });
    }

    // ---- 异常不被吞掉：底层写入失败时异常穿出 ----

    @Test
    void writeFailurePropagates() {
        when(repository.findByUserIdOrderByStartDateAsc(USER)).thenReturn(List.of());
        List<LocalDate> calendar = dates("2025-01-01");
        RuntimeException boom = new RuntimeException("批量写入失败");
        when(jdbcTemplate.batchUpdate(eq(UPSERT_SQL), anyList())).thenThrow(boom);

        assertThatThrownBy(() -> maintainer.maintain(USER, calendar, NOW))
                .isSameAs(boom);
    }

    // ---- helpers ----

    private List<Object[]> captureBatch() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(eq(UPSERT_SQL), captor.capture());
        return captor.getValue();
    }

    private static List<LocalDate> dates(String... isoDates) {
        List<LocalDate> out = new ArrayList<>();
        for (String iso : isoDates) {
            out.add(LocalDate.parse(iso));
        }
        return out;
    }

    private static StreakSegment segment(Long id, String start, String end, int days) {
        StreakSegment s = new StreakSegment();
        s.setId(id);
        s.setUserId(USER);
        s.setStartDate(LocalDate.parse(start));
        s.setEndDate(LocalDate.parse(end));
        s.setDays(days);
        s.setCreatedAt(NOW);
        s.setUpdatedAt(NOW);
        return s;
    }

    private static StreakSegment segment(Long id, LocalDate day) {
        StreakSegment s = new StreakSegment();
        s.setId(id);
        s.setUserId(USER);
        s.setStartDate(day);
        s.setEndDate(day);
        s.setDays(1);
        s.setCreatedAt(NOW);
        s.setUpdatedAt(NOW);
        return s;
    }

    // ---- 可推进的时钟 ----

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
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
