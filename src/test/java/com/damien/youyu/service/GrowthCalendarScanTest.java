package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link GrowthCalendarService#scan(List)} 的示例/边界单元测试（关联需求 4.9、4.10、4.12、4.13）。
 *
 * <p>{@code scan} 是纯函数：不读时钟、不查库、不碰任何可变共享状态，因此本测试不需要 Spring 上下文、
 * 不需要 mock，直接以日期列表驱动。</p>
 *
 * <p>每个用例都经 {@link #scanAndAssertInvariants(List)} 走一遍三条由构造过程保证的不变式
 * （需求 4.9、4.10）：{@code maxStreak >= currentSegment}、
 * {@code totalDays == 去重后日期个数}、{@code lastDate == 去重后最大日期}（空集时为 {@code null}），
 * 再由各用例断言自己关心的具体取值。这样新增用例时不变式自动被覆盖，不会漏断。</p>
 */
class GrowthCalendarScanTest {

    // ---- 空集与单点（需求 4.10） ----

    @Test
    void emptyCalendarYieldsAllZerosAndNullLastDate() {
        assertThat(scanAndAssertInvariants(List.of())).isEqualTo(new CalendarScan(0, 0, 0, null));
        // null 与空集等价对待：物化列写回路径不必先判空。
        assertThat(GrowthCalendarService.scan(null)).isEqualTo(new CalendarScan(0, 0, 0, null));
    }

    @Test
    void singleDateYieldsSegmentAndStreakOfOne() {
        LocalDate only = LocalDate.of(2024, 5, 20);

        CalendarScan scan = scanAndAssertInvariants(List.of(only));

        assertThat(scan).isEqualTo(new CalendarScan(1, 1, 1, only));
    }

    // ---- 全连续 / 全孤立（需求 4.12） ----

    @Test
    void fullyConsecutiveCalendarYieldsSegmentEqualToTotalDays() {
        List<LocalDate> dates = consecutive(LocalDate.of(2024, 3, 1), 30);

        CalendarScan scan = scanAndAssertInvariants(dates);

        assertThat(scan).isEqualTo(new CalendarScan(30, 30, 30, LocalDate.of(2024, 3, 30)));
    }

    @Test
    void fullyIsolatedCalendarYieldsSegmentAndStreakOfOne() {
        // 每隔一日记一次：任意相邻两日相差 2 天，故没有任何两日属于同一区间（需求 4.12 后半句）。
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            dates.add(LocalDate.of(2024, 6, 1).plusDays(i * 2L));
        }

        CalendarScan scan = scanAndAssertInvariants(dates);

        assertThat(scan).isEqualTo(new CalendarScan(8, 1, 1, LocalDate.of(2024, 6, 15)));
    }

    // ---- 多段：最长段不是最后一段（需求 4.9） ----

    @Test
    void multipleSegmentsTakeLongestAsMaxStreakAndLastAsCurrentSegment() {
        List<LocalDate> dates = new ArrayList<>();
        dates.addAll(consecutive(LocalDate.of(2024, 1, 1), 3));   // 3 天
        dates.addAll(consecutive(LocalDate.of(2024, 1, 10), 5));  // 5 天（最长）
        dates.addAll(consecutive(LocalDate.of(2024, 2, 1), 2));   // 2 天（最后一段）

        CalendarScan scan = scanAndAssertInvariants(dates);

        assertThat(scan.totalDays()).isEqualTo(10);
        assertThat(scan.currentSegment()).isEqualTo(2);
        assertThat(scan.maxStreak()).isEqualTo(5);
        assertThat(scan.lastDate()).isEqualTo(LocalDate.of(2024, 2, 2));
    }

    // ---- 跨月 / 跨年 / 闰日（需求 4.12） ----

    @Test
    void consecutiveAcrossMonthBoundary() {
        // 1 月 31 天、4 月 30 天：月长不同不影响判定，一律按 epochDay 相差 1 天算连续。
        assertThat(scanAndAssertInvariants(List.of(
                LocalDate.of(2024, 1, 30), LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 1)))
                .maxStreak()).isEqualTo(3);
        assertThat(scanAndAssertInvariants(List.of(
                LocalDate.of(2024, 4, 30), LocalDate.of(2024, 5, 1)))
                .maxStreak()).isEqualTo(2);
    }

    @Test
    void consecutiveAcrossYearBoundary() {
        CalendarScan scan = scanAndAssertInvariants(List.of(
                LocalDate.of(2023, 12, 30), LocalDate.of(2023, 12, 31),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2)));

        assertThat(scan).isEqualTo(new CalendarScan(4, 4, 4, LocalDate.of(2024, 1, 2)));
    }

    @Test
    void leapDayIsAnOrdinaryConsecutiveDay() {
        // 2024 是闰年：02-28 → 02-29 → 03-01 是连续 3 天。
        assertThat(scanAndAssertInvariants(List.of(
                LocalDate.of(2024, 2, 28), LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1)))
                .maxStreak()).isEqualTo(3);

        // 同一年缺了 02-29 就不连续（相差 2 天），这是闰年与非闰年的分水岭。
        assertThat(scanAndAssertInvariants(List.of(
                LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1)))
                .maxStreak()).isEqualTo(1);

        // 2023 非闰年：02-28 的次日就是 03-01，同样只按 epochDay 判定，不需要任何按年分支。
        assertThat(scanAndAssertInvariants(List.of(
                LocalDate.of(2023, 2, 28), LocalDate.of(2023, 3, 1)))
                .maxStreak()).isEqualTo(2);
    }

    // ---- 输入乱序 / 含重复（纯函数性质：同一集合恒得同一输出） ----

    @Test
    void shuffledInputYieldsSameResultAsAscendingInput() {
        List<LocalDate> ascending = new ArrayList<>();
        ascending.addAll(consecutive(LocalDate.of(2024, 7, 1), 4));
        ascending.addAll(consecutive(LocalDate.of(2024, 7, 10), 2));
        CalendarScan expected = scanAndAssertInvariants(ascending);

        List<LocalDate> shuffled = new ArrayList<>(ascending);
        Collections.shuffle(shuffled, new Random(20240701L));
        List<LocalDate> beforeCall = List.copyOf(shuffled);

        assertThat(scanAndAssertInvariants(shuffled)).isEqualTo(expected);
        // 归一化不得改动调用方传入的列表。
        assertThat(shuffled).isEqualTo(beforeCall);
        // 完全倒序也是一种排列。
        List<LocalDate> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);
        assertThat(scanAndAssertInvariants(descending)).isEqualTo(expected);
    }

    @Test
    void duplicatedDatesAreCountedOnce() {
        List<LocalDate> withDuplicates = Arrays.asList(
                LocalDate.of(2024, 8, 1), LocalDate.of(2024, 8, 1),
                LocalDate.of(2024, 8, 2), LocalDate.of(2024, 8, 2), LocalDate.of(2024, 8, 2),
                LocalDate.of(2024, 8, 4));

        CalendarScan scan = scanAndAssertInvariants(withDuplicates);

        // 去重后为 08-01、08-02、08-04：3 天、最长连续 2、末段 1。
        assertThat(scan).isEqualTo(new CalendarScan(3, 1, 2, LocalDate.of(2024, 8, 4)));
    }

    @Test
    void nullElementIsRejectedInsteadOfSilentlySkipped() {
        // 畸形输入说明写入或解析路径有缺陷，静默跳过会让累计天数悄悄少算。
        assertThatThrownBy(() -> GrowthCalendarService.scan(
                Arrays.asList(LocalDate.of(2024, 9, 1), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 辅助 ----

    /**
     * 扫描并断言三条对任意输入都必须成立的不变式（需求 4.9、4.10、4.13）。
     *
     * <p>顺带断言同一输入两次调用结果相等——这是「增量维护结果 == 全量重算结果」（需求 4.13）
     * 之所以构造性成立的前提：两条路径调的是同一个纯函数。</p>
     */
    private static CalendarScan scanAndAssertInvariants(List<LocalDate> dates) {
        CalendarScan scan = GrowthCalendarService.scan(dates);
        Set<LocalDate> distinct = new HashSet<>(dates);

        assertThat(scan.maxStreak())
                .as("历史最长连续天数恒 >= 连续段长度")
                .isGreaterThanOrEqualTo(scan.currentSegment());
        assertThat(scan.totalDays())
                .as("累计记账天数等于去重后的日期个数")
                .isEqualTo(distinct.size());
        if (distinct.isEmpty()) {
            assertThat(scan.currentSegment()).isZero();
            assertThat(scan.maxStreak()).isZero();
            assertThat(scan.lastDate()).isNull();
        } else {
            assertThat(scan.lastDate()).isEqualTo(Collections.max(distinct));
            assertThat(scan.currentSegment()).isPositive();
        }
        assertThat(GrowthCalendarService.scan(dates))
                .as("纯函数：同一输入恒得同一输出")
                .isEqualTo(scan);
        return scan;
    }

    /** 自 {@code start} 起连续 {@code days} 个自然日，升序。 */
    private static List<LocalDate> consecutive(LocalDate start, int days) {
        List<LocalDate> dates = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            dates.add(start.plusDays(i));
        }
        return dates;
    }
}
