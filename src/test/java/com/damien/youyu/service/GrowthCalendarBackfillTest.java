package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link GrowthCalendarService#backfillDates(Long, LocalDate, LocalDate)} 的追补窗口推导单元测试
 * （关联需求 4.3、4.6、4.14）。
 *
 * <p>{@code backfillDates} 只依赖 {@link TransactionRepository} 的两个只读查询（查询 A
 * {@code findEarliestRecordCreatedAt}、查询 B {@code findRecordDatesInWindow}），不读时钟、不需要
 * Spring 上下文，因此本测试以一个<b>计数型 spy 仓储</b>（{@link CountingSpyRepository}，Mockito mock +
 * 每次查询自增的计数器）驱动，逐用例断言：</p>
 * <ul>
 *   <li>查询次数<b>恒 ≤2</b>：起点无效（查询 A 返回 {@code null} 或起点晚于结算日）时只做查询 A（计 1 次），
 *       否则查询 A + 查询 B（计 2 次），绝不出现第三次；</li>
 *   <li>窗口末日取 {@code min(起点 + 999 天, 结算日)}：由传给查询 B 的半开区间上界
 *       {@code windowEndExclusive} 与返回的 {@link BackfillResult#windowEnd()} 共同锁定；</li>
 *   <li>{@code last_record_date} 为 {@code null} 时查询 A <b>不加时间下界</b>（下界实参为 {@code null}）；</li>
 *   <li>返回的 {@code dates} 严格升序且无重复。</li>
 * </ul>
 */
class GrowthCalendarBackfillTest {

    private static final long USER_ID = 42L;

    // ---- last_record_date 为 NULL：查询 A 不加时间下界（需求 4.6 查询 A） ----

    @Test
    void nullLastRecordDateImposesNoLowerBoundOnQueryA() {
        LocalDate windowStart = LocalDate.of(2024, 1, 10);
        LocalDate settleDate = LocalDate.of(2024, 1, 20);
        CountingSpyRepository spy = new CountingSpyRepository()
                .withEarliest(windowStart.atTime(8, 30))
                .withWindowDates(List.of(windowStart, LocalDate.of(2024, 1, 15)));
        GrowthCalendarService service = new GrowthCalendarService(spy.mock());

        BackfillResult result = service.backfillDates(USER_ID, null, settleDate);

        // 查询 A 的时间下界实参必须是 null（不限制在某个已有日历之后）。
        assertThat(spy.capturedLowerBound()).isNull();
        // 起点有效且不晚于结算日 → 查询 A + 查询 B，计数 = 2。
        assertThat(spy.queryCount()).isEqualTo(2);
        assertThat(result.windowStart()).isEqualTo(windowStart);
        assertThat(result.windowEnd()).isEqualTo(settleDate);
        assertAscendingDistinct(result.dates());
    }

    @Test
    void nonNullLastRecordDateSetsLowerBoundToNextDayStart() {
        LocalDate lastRecordDate = LocalDate.of(2024, 3, 5);
        LocalDate windowStart = LocalDate.of(2024, 3, 6);
        LocalDate settleDate = LocalDate.of(2024, 3, 9);
        CountingSpyRepository spy = new CountingSpyRepository()
                .withEarliest(windowStart.atTime(0, 0))
                .withWindowDates(List.of(windowStart));
        GrowthCalendarService service = new GrowthCalendarService(spy.mock());

        service.backfillDates(USER_ID, lastRecordDate, settleDate);

        // 只看比已有日历更晚的交易：下界 = last_record_date 的次日 00:00。
        assertThat(spy.capturedLowerBound()).isEqualTo(lastRecordDate.plusDays(1).atStartOfDay());
        assertThat(spy.queryCount()).isEqualTo(2);
    }

    // ---- 窗口末日取 min(起点 + 999 天, 结算日) 的两个分支（需求 4.6、4.14） ----

    @Test
    void windowEndTakesStartPlus999WhenItPrecedesSettleDate() {
        // 起点 + 999 天 < 结算日：窗口末日取前者。
        LocalDate windowStart = LocalDate.of(2020, 1, 1);
        LocalDate expectedWindowEnd = windowStart.plusDays(999);
        LocalDate settleDate = LocalDate.of(2099, 1, 1);
        assertThat(expectedWindowEnd).isBefore(settleDate);
        CountingSpyRepository spy = new CountingSpyRepository()
                .withEarliest(windowStart.atTime(12, 0))
                .withWindowDates(List.of(windowStart, expectedWindowEnd));
        GrowthCalendarService service = new GrowthCalendarService(spy.mock());

        BackfillResult result = service.backfillDates(USER_ID, null, settleDate);

        assertThat(result.windowEnd()).isEqualTo(expectedWindowEnd);
        // 查询 B 的半开区间上界 = 窗口末日次日 00:00。
        assertThat(spy.capturedWindowEndExclusive())
                .isEqualTo(expectedWindowEnd.plusDays(1).atStartOfDay());
        assertThat(spy.capturedWindowStart()).isEqualTo(windowStart.atStartOfDay());
        assertThat(spy.queryCount()).isEqualTo(2);
    }

    @Test
    void windowEndTakesSettleDateWhenStartPlus999Exceeds() {
        // 起点 + 999 天 > 结算日：窗口末日取后者（结算日）。
        LocalDate windowStart = LocalDate.of(2024, 1, 1);
        LocalDate settleDate = LocalDate.of(2024, 6, 1);
        assertThat(windowStart.plusDays(999)).isAfter(settleDate);
        CountingSpyRepository spy = new CountingSpyRepository()
                .withEarliest(windowStart.atTime(23, 59, 59))
                .withWindowDates(List.of(windowStart, LocalDate.of(2024, 3, 15), settleDate));
        GrowthCalendarService service = new GrowthCalendarService(spy.mock());

        BackfillResult result = service.backfillDates(USER_ID, null, settleDate);

        assertThat(result.windowEnd()).isEqualTo(settleDate);
        assertThat(spy.capturedWindowEndExclusive())
                .isEqualTo(settleDate.plusDays(1).atStartOfDay());
        assertThat(spy.queryCount()).isEqualTo(2);
        assertAscendingDistinct(result.dates());
    }

    // ---- 起点 == 结算日：仍执行查询 B，窗口末日 = 结算日（需求 4.14） ----

    @Test
    void startEqualToSettleDateStillRunsQueryBWithSingleDayWindow() {
        LocalDate day = LocalDate.of(2024, 5, 20);
        CountingSpyRepository spy = new CountingSpyRepository()
                .withEarliest(day.atTime(9, 0))
                .withWindowDates(List.of(day));
        GrowthCalendarService service = new GrowthCalendarService(spy.mock());

        BackfillResult result = service.backfillDates(USER_ID, null, day);

        assertThat(result.windowStart()).isEqualTo(day);
        assertThat(result.windowEnd()).isEqualTo(day);
        assertThat(result.dates()).containsExactly(day);
        // 半开区间恰好覆盖结算日当天 [当天 00:00, 次日 00:00)。
        assertThat(spy.capturedWindowStart()).isEqualTo(day.atStartOfDay());
        assertThat(spy.capturedWindowEndExclusive()).isEqualTo(day.plusDays(1).atStartOfDay());
        assertThat(spy.queryCount()).isEqualTo(2);
    }

    // ---- 起点 > 结算日（时钟回拨）：跳过查询 B、不写任何事件（需求 4.3、4.6） ----

    @Test
    void startAfterSettleDateSkipsQueryBAndReturnsEmpty() {
        LocalDate settleDate = LocalDate.of(2024, 5, 20);
        // 最早记账交易落在结算日之后：只可能是时钟回拨。
        LocalDateTime earliestAfterSettle = LocalDate.of(2024, 5, 21).atStartOfDay();
        CountingSpyRepository spy = new CountingSpyRepository().withEarliest(earliestAfterSettle);
        GrowthCalendarService service = new GrowthCalendarService(spy.mock());

        BackfillResult result = service.backfillDates(USER_ID, null, settleDate);

        // 只做了查询 A；查询 B 未执行。
        assertThat(spy.queryCount()).isEqualTo(1);
        assertThat(spy.windowQueryExecuted()).isFalse();
        assertThat(result.windowStart()).isNull();
        assertThat(result.windowEnd()).isNull();
        assertThat(result.dates()).isEmpty();
    }

    // ---- 查询 A 返回 null：无可追补交易，同样跳过查询 B（需求 4.3、4.6） ----

    @Test
    void noEarliestRecordSkipsQueryBAndReturnsEmpty() {
        CountingSpyRepository spy = new CountingSpyRepository().withEarliest(null);
        GrowthCalendarService service = new GrowthCalendarService(spy.mock());

        BackfillResult result = service.backfillDates(USER_ID, null, LocalDate.of(2024, 5, 20));

        assertThat(spy.queryCount()).isEqualTo(1);
        assertThat(spy.windowQueryExecuted()).isFalse();
        assertThat(result.dates()).isEmpty();
        assertThat(result.windowStart()).isNull();
        assertThat(result.windowEnd()).isNull();
    }

    // ---- 返回日期严格升序且去重（需求 4.6：查询 B 已 DISTINCT + ORDER BY，本方法须原样保序传出） ----

    @Test
    void returnedDatesAreAscendingAndDistinct() {
        LocalDate windowStart = LocalDate.of(2024, 2, 26);
        LocalDate settleDate = LocalDate.of(2024, 3, 5);
        // 覆盖闰日与跨月，均为升序且互异（模拟查询 B 的 DISTINCT + ORDER BY 契约）。
        List<LocalDate> windowDates = List.of(
                LocalDate.of(2024, 2, 26), LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 4));
        CountingSpyRepository spy = new CountingSpyRepository()
                .withEarliest(windowStart.atStartOfDay())
                .withWindowDates(windowDates);
        GrowthCalendarService service = new GrowthCalendarService(spy.mock());

        BackfillResult result = service.backfillDates(USER_ID, null, settleDate);

        assertThat(result.dates()).containsExactlyElementsOf(windowDates);
        assertAscendingDistinct(result.dates());
        assertThat(spy.queryCount()).isEqualTo(2);
    }

    // ---- 查询次数恒 ≤2：横跨全部分支再兜底断言一次 ----

    @Test
    void queryCountNeverExceedsTwoAcrossAllBranches() {
        LocalDate settleDate = LocalDate.of(2024, 5, 20);

        // 分支一：起点有效 → 2 次。
        CountingSpyRepository twoQueries = new CountingSpyRepository()
                .withEarliest(LocalDate.of(2024, 5, 10).atStartOfDay())
                .withWindowDates(List.of(LocalDate.of(2024, 5, 10)));
        new GrowthCalendarService(twoQueries.mock()).backfillDates(USER_ID, null, settleDate);
        assertThat(twoQueries.queryCount()).isLessThanOrEqualTo(2);

        // 分支二：查询 A 返回 null → 1 次。
        CountingSpyRepository noEarliest = new CountingSpyRepository().withEarliest(null);
        new GrowthCalendarService(noEarliest.mock()).backfillDates(USER_ID, null, settleDate);
        assertThat(noEarliest.queryCount()).isLessThanOrEqualTo(2);

        // 分支三：起点晚于结算日 → 1 次。
        CountingSpyRepository rolledBack = new CountingSpyRepository()
                .withEarliest(settleDate.plusDays(1).atStartOfDay());
        new GrowthCalendarService(rolledBack.mock()).backfillDates(USER_ID, null, settleDate);
        assertThat(rolledBack.queryCount()).isLessThanOrEqualTo(2);
    }

    // ---- 辅助 ----

    /** 断言日期列表严格升序（相邻严格递增）且无重复。 */
    private static void assertAscendingDistinct(List<LocalDate> dates) {
        assertThat(dates).isSorted();
        assertThat(dates).doesNotHaveDuplicates();
        for (int i = 1; i < dates.size(); i++) {
            assertThat(dates.get(i)).isAfter(dates.get(i - 1));
        }
    }

    /**
     * 计数型 spy 仓储：以 Mockito mock 承载 {@link TransactionRepository}，只对
     * {@code backfillDates} 会用到的两个查询打桩，并在每次被调用时自增内部计数器。
     * 由此 {@link #queryCount()} 即本次 {@code backfillDates} 实际发出的数据库查询次数，
     * 用于锁死「查询次数恒 ≤2」。同时用 {@link ArgumentCaptor} 捕获查询实参以验证窗口推导。
     */
    private static final class CountingSpyRepository {
        private final TransactionRepository mock = Mockito.mock(TransactionRepository.class);
        private final AtomicInteger queryCount = new AtomicInteger();
        private final AtomicInteger windowQueryCount = new AtomicInteger();
        private final ArgumentCaptor<LocalDateTime> lowerBoundCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        private final ArgumentCaptor<LocalDateTime> windowStartCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        private final ArgumentCaptor<LocalDateTime> windowEndCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        CountingSpyRepository withEarliest(LocalDateTime earliest) {
            // 用 any() 覆盖下界实参可为 null 的情形（last_record_date 为 NULL 时）。
            Mockito.when(mock.findEarliestRecordCreatedAt(eq(USER_ID), any()))
                    .thenAnswer(invocation -> {
                        queryCount.incrementAndGet();
                        return earliest;
                    });
            return this;
        }

        CountingSpyRepository withWindowDates(List<LocalDate> dates) {
            // 仓储现以 LocalDate 逐字回读（getObject(LocalDate.class)，零时区换算，需求 4.16），
            // 桩直接返回 LocalDate，无需再经 java.sql.Date 中转。
            List<LocalDate> rows = List.copyOf(dates);
            Mockito.when(mock.findRecordDatesInWindow(eq(USER_ID), any(), any()))
                    .thenAnswer(invocation -> {
                        queryCount.incrementAndGet();
                        windowQueryCount.incrementAndGet();
                        return rows;
                    });
            return this;
        }

        TransactionRepository mock() {
            return mock;
        }

        int queryCount() {
            return queryCount.get();
        }

        boolean windowQueryExecuted() {
            return windowQueryCount.get() > 0;
        }

        LocalDateTime capturedLowerBound() {
            Mockito.verify(mock).findEarliestRecordCreatedAt(eq(USER_ID), lowerBoundCaptor.capture());
            return lowerBoundCaptor.getValue();
        }

        LocalDateTime capturedWindowStart() {
            Mockito.verify(mock).findRecordDatesInWindow(eq(USER_ID), windowStartCaptor.capture(), any());
            return windowStartCaptor.getValue();
        }

        LocalDateTime capturedWindowEndExclusive() {
            Mockito.verify(mock).findRecordDatesInWindow(eq(USER_ID), any(), windowEndCaptor.capture());
            return windowEndCaptor.getValue();
        }
    }
}
