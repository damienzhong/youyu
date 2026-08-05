package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * {@link GrowthCalendarService#segments(List)} 的段序列五条不变式回归锁（<b>Property 1：段序列五条不变式</b>）。
 *
 * <h2>测试层级选择</h2>
 * <p>段序列的五条不变式（需求 4.2）全部由 {@code segments} 这个无依赖静态纯函数构造性成立：
 * 不变式①由 {@link StreakSegmentView#of} 在构造时算出 {@code days} 保证；②③⑤由 {@code segments}
 * 的收口逻辑保证；④由 {@code segments} 与 {@code scan} 共用同一相邻判定规则保证（Property 2 的等价性传导）。
 * 故本测试直接以记账日历驱动纯函数、走纯 jqwik，不引入 Spring 上下文、不落库、不 mock——落表只是把
 * 内存里的段逐行 ODKU 写下去，写入值即 {@code segments} 的输出，段边界的正确性完全由本纯函数决定。</p>
 *
 * <h2>期望值的独立计算</h2>
 * <p>不变式③④⑤的期望值不复用 {@code segments} 自身，而是另写一份仅靠 {@link Set#contains} 判定日期归属的
 * 朴素参考实现（{@link #distinctCount}、{@link #naiveMaxStreak}、{@link #naiveMax}）——两条实现算法不同，
 * 互为参照：任一侧把「相差 1 天」写成「相差 ≤1 天」、漏掉跨月/跨年/闰日的边界或段收口抄错一格，
 * 等价性断言即失败。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li><b>分段构造</b>：起点 × [(段长, 空洞)…]，空洞 ≥1 保证段间断开，空洞 =1 恰好造出「相差 2 天不连续」的边界；</li>
 *   <li><b>纯随机集合</b>：全域随机日期，压出稀疏 / 偶然相邻 / 重复的混合形态；</li>
 *   <li><b>两个极端</b>：全连续（单段）与全孤立（段长恒 1）；</li>
 *   <li>每个日历都以升序去重 / 随机打乱 / 注入重复项三种形态各跑一遍，验证输入顺序与重复项不影响段序列。</li>
 * </ul>
 * <p>规模封顶 400（需求：任意日历长度 0–400），日期跨 2000–2035 含闰年闰日。空集 / 单点分支另由 {@link Example} 覆盖。</p>
 *
 * <p>Feature: streak-system, Property 1: 段序列五条不变式</p>
 *
 * <p>Validates: Requirements 4.2, 4.1, 8.14</p>
 */
class StreakSegmentInvariantsPropertyTest {

    /** 分段构造起点的下界（含），含 2024 闰年，覆盖闰日 2024-02-29。 */
    private static final long EPOCH_MIN = LocalDate.of(2000, 1, 1).toEpochDay();
    /** 分段构造起点的上界（含）。 */
    private static final long EPOCH_MAX = LocalDate.of(2035, 12, 31).toEpochDay();
    /** 日历规模上限（任意日历长度 0–400）。 */
    private static final int MAX_SIZE = 400;

    // ---------------- 生成器 ----------------

    /** 分段构造集合：起点 × [(段长, 空洞)…]，规模封顶 MAX_SIZE。 */
    @Provide
    Arbitrary<List<LocalDate>> segmentedSets() {
        Arbitrary<Long> start = Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX);
        Arbitrary<int[]> segment = Combinators.combine(
                        Arbitraries.integers().between(1, 60),
                        Arbitraries.integers().between(1, 30))
                .as((len, gap) -> new int[] {len, gap});
        Arbitrary<List<int[]>> segments = segment.list().ofMaxSize(20);
        return Combinators.combine(start, segments).as(StreakSegmentInvariantsPropertyTest::buildSegmented);
    }

    /** 纯随机日期集合，全域取值。 */
    @Provide
    Arbitrary<List<LocalDate>> randomSets() {
        return Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX)
                .map(LocalDate::ofEpochDay)
                .list().ofMaxSize(MAX_SIZE);
    }

    /** 两个极端：全连续（单段）与全孤立（段长恒 1、空洞恒 2）。 */
    @Provide
    Arbitrary<List<LocalDate>> extremeSets() {
        Arbitrary<Long> start = Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX);
        Arbitrary<Integer> count = Arbitraries.integers().between(0, MAX_SIZE);
        Arbitrary<Boolean> consecutive = Arbitraries.of(true, false);
        return Combinators.combine(start, count, consecutive).as((s, n, cons) -> {
            List<LocalDate> dates = new ArrayList<>(n);
            long step = cons ? 1L : 2L;
            for (int i = 0; i < n; i++) {
                dates.add(LocalDate.ofEpochDay(s + i * step));
            }
            return dates;
        });
    }

    /** 三类生成器合流。 */
    @Provide
    Arbitrary<List<LocalDate>> calendars() {
        return Arbitraries.oneOf(segmentedSets(), randomSets(), extremeSets());
    }

    private static List<LocalDate> buildSegmented(long start, List<int[]> segments) {
        List<LocalDate> dates = new ArrayList<>();
        long cursor = start;
        for (int[] seg : segments) {
            int len = seg[0];
            int gap = seg[1];
            for (int i = 0; i < len && dates.size() < MAX_SIZE; i++) {
                dates.add(LocalDate.ofEpochDay(cursor + i));
            }
            if (dates.size() >= MAX_SIZE) {
                break;
            }
            cursor += (long) len + gap;
        }
        return dates;
    }

    // ---------------- Property 1 ----------------

    /**
     * Feature: streak-system, Property 1: 段序列五条不变式
     *
     * <p>对任意日历（含空 / 单点 / 全连续 / 全离散 / 重复 / 乱序 / 跨月跨年闰日），
     * {@code segments(calendar)} 同时满足需求 4.2 的五条不变式，且升序去重 / 打乱 / 注入重复三种输入形态
     * 得出同一段序列（段边界与输入顺序、重复项无关）。</p>
     *
     * <p>Validates: Requirements 4.1, 4.2, 4.3, 8.14</p>
     */
    @Property(tries = 1000)
    void property1_segmentSeriesInvariants(
            @ForAll("calendars") List<LocalDate> generated,
            @ForAll long shuffleSeed) {

        List<StreakSegmentView> segments = GrowthCalendarService.segments(generated);
        assertInvariants(generated, segments);

        // 需求 4.13 的纯函数性质：升序去重 / 随机打乱 / 注入重复项三种形态得出同一段序列。
        Set<LocalDate> distinct = new TreeSet<>(generated);
        List<LocalDate> ascending = new ArrayList<>(distinct);

        List<LocalDate> shuffled = new ArrayList<>(distinct);
        Collections.shuffle(shuffled, new Random(shuffleSeed));
        List<LocalDate> withDuplicates = withInjectedDuplicates(shuffled, new Random(shuffleSeed * 31L + 7L));

        assertThat(GrowthCalendarService.segments(ascending))
                .as("升序去重输入的段序列应与原始输入一致（纯函数）")
                .isEqualTo(segments);
        assertThat(GrowthCalendarService.segments(shuffled))
                .as("随机打乱输入不应改变段序列（纯函数）")
                .isEqualTo(segments);
        assertThat(GrowthCalendarService.segments(withDuplicates))
                .as("注入重复项不应改变段序列（纯函数）")
                .isEqualTo(segments);
    }

    /** 空日历：段序列为空、累计天数 0、最近记账日为空值（不变式⑤的空分支，需求 4.2）。 */
    @Example
    void property1_emptyCalendarYieldsEmptySeries() {
        List<StreakSegmentView> segments = GrowthCalendarService.segments(List.of());
        assertThat(segments).as("空日历段序列为空").isEmpty();
        assertThat(GrowthCalendarService.scan(List.of()).lastDate()).as("空日历最近记账日为空值").isNull();
    }

    /** 单点日历：恰一段、起止同日、天数 1（不变式①⑤，需求 4.2、8.14）。 */
    @Example
    void property1_singleDayCalendarYieldsSingleUnitSegment() {
        LocalDate day = LocalDate.of(2024, 2, 29); // 闰日
        List<StreakSegmentView> segments = GrowthCalendarService.segments(List.of(day));
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).startDate()).isEqualTo(day);
        assertThat(segments.get(0).endDate()).isEqualTo(day);
        assertThat(segments.get(0).days()).isEqualTo(1);
    }

    // ---------------- 断言 ----------------

    /** 需求 4.2 的五条不变式，期望值全部由独立朴素参考实现算出。 */
    private static void assertInvariants(List<LocalDate> calendar, List<StreakSegmentView> segments) {
        CalendarScan scan = GrowthCalendarService.scan(calendar);

        // 不变式③：Σ days == 累计记账天数（去重后日历日期个数）。
        long sumDays = segments.stream().mapToLong(StreakSegmentView::days).sum();
        assertThat(sumDays)
                .as("不变式③：Σ days 应等于累计记账天数（去重后日期个数）")
                .isEqualTo(distinctCount(calendar));

        if (segments.isEmpty()) {
            // 不变式⑤（空分支）：序列为空时累计天数为 0 且最近记账日为空值。
            assertThat(distinctCount(calendar)).as("空段序列 ⇒ 累计记账天数为 0").isZero();
            assertThat(scan.lastDate()).as("空段序列 ⇒ 最近记账日为空值").isNull();
            assertThat(scan.maxStreak()).as("空段序列 ⇒ 最长连续天数为 0").isZero();
            return;
        }

        StreakSegmentView prev = null;
        int maxDays = 0;
        for (StreakSegmentView seg : segments) {
            // 不变式①：endDate >= startDate 且 days == 结束日 − 起始日 + 1。
            assertThat(seg.endDate())
                    .as("不变式①：结束日应不早于起始日")
                    .isAfterOrEqualTo(seg.startDate());
            long expectedDays = seg.endDate().toEpochDay() - seg.startDate().toEpochDay() + 1L;
            assertThat((long) seg.days())
                    .as("不变式①：days 应等于结束日 − 起始日 + 1")
                    .isEqualTo(expectedDays);
            assertThat(seg.days())
                    .as("不变式① / 需求 8.14：段天数落在 [1, Integer.MAX_VALUE]")
                    .isGreaterThanOrEqualTo(1);

            // 不变式②：按起始日升序时，任一段起始日严格晚于前一段结束日的次日（既不相交也不相邻）。
            if (prev != null) {
                assertThat(seg.startDate().toEpochDay())
                        .as("不变式②：相邻两段之间至少间隔 1 个不在日历中的自然日（既不相交也不相邻）")
                        .isGreaterThan(prev.endDate().toEpochDay() + 1L);
            }
            maxDays = Math.max(maxDays, seg.days());
            prev = seg;
        }

        // 不变式④：max days == scan(calendar).maxStreak()，且等于独立朴素实现。
        assertThat(maxDays)
                .as("不变式④：最大段天数应等于 scan 的最长连续天数")
                .isEqualTo(scan.maxStreak());
        assertThat(maxDays)
                .as("不变式④：最大段天数应等于朴素参考实现的最长连续天数")
                .isEqualTo(naiveMaxStreak(calendar));

        // 不变式⑤：非空时最后一段结束日 == 最近记账日（= 去重日历最大值）。
        assertThat(segments.get(segments.size() - 1).endDate())
                .as("不变式⑤：末段结束日应等于最近记账日")
                .isEqualTo(scan.lastDate());
        assertThat(segments.get(segments.size() - 1).endDate())
                .as("不变式⑤：末段结束日应等于朴素参考实现的日历最大值")
                .isEqualTo(naiveMax(calendar));
    }

    // ---------------- 朴素参考实现：独立于被测 ----------------

    private static long distinctCount(List<LocalDate> calendar) {
        return new java.util.HashSet<>(calendar).size();
    }

    private static LocalDate naiveMax(List<LocalDate> calendar) {
        return Collections.max(calendar);
    }

    /** 仅靠 Set#contains 找段首再向后逐日数长度取最大，与被测的 epochDay 相减实现算法不同。 */
    private static int naiveMaxStreak(List<LocalDate> calendar) {
        Set<LocalDate> set = new java.util.HashSet<>(calendar);
        int max = 0;
        for (LocalDate day : set) {
            if (!set.contains(day.minusDays(1))) {
                int len = 0;
                for (LocalDate cur = day; set.contains(cur); cur = cur.plusDays(1)) {
                    len++;
                }
                max = Math.max(max, len);
            }
        }
        return max;
    }

    // ---------------- 辅助 ----------------

    /** 对每个日期按随机重数（1–3 次）复制，模拟同一日多次记账后未去重的输入。 */
    private static List<LocalDate> withInjectedDuplicates(List<LocalDate> dates, Random random) {
        List<LocalDate> result = new ArrayList<>(dates.size() * 2);
        for (LocalDate date : dates) {
            int repeats = 1 + random.nextInt(3);
            for (int i = 0; i < repeats; i++) {
                result.add(date);
            }
        }
        Collections.shuffle(result, random);
        return result;
    }
}
