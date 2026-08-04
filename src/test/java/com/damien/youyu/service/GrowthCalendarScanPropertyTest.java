package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * {@link GrowthCalendarService#scan(List)} 的属性测试（<b>Property 6：连续段算法与朴素实现等价</b>）。
 *
 * <h2>测试层级选择</h2>
 * <p>{@code scan} 是无依赖的静态纯函数：不读时钟、不查库、不碰任何可变共享状态，故直接以日期列表驱动、
 * 走纯 jqwik，不引入 Spring 上下文、不 mock。</p>
 *
 * <h2>期望值的独立计算</h2>
 * <p>本测试<b>不复用</b>被测的 {@code toEpochDay} 相减 + 单趟扫描实现，而是另写一份<b>朴素参考实现</b>
 * {@link #naiveScan(List)}：以 {@link Set#contains} 判定日期归属——
 * {@code currentSegment} 由「从最大日期向前 {@code while (set.contains(d))} 回溯」求出；
 * {@code maxStreak} 由「对每个日期判 {@code !set.contains(d-1)} 找段首、再向后逐日 {@code contains} 数长度」
 * 求出。两条实现算法不同（一个靠相邻 epochDay 差、一个靠集合成员判定），互为参照：任一侧抄错一格、
 * 把「相差 1 天」写成「相差 ≤1 天」或漏掉跨月/跨年/闰日的某个边界，等价性断言即失败。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li><b>分段构造</b>：起点 ∈ [2000-01-01, 2035-12-31] × 段长 ∈ [1, 400] × 段间空洞 ∈ [1, 60]
 *       × 段数 ∈ [0, 30]，规模上限 2000。空洞 ≥1 保证段间断开，空洞 =1 恰好造出「相差 2 天不连续」的边界。</li>
 *   <li><b>纯随机集合</b>：全域随机日期，压出稀疏 / 偶然相邻 / 重复的混合形态。</li>
 *   <li><b>两个极端</b>：全连续（单段）与全孤立（段长恒 1）。</li>
 * </ul>
 * <p>每个生成的集合都以「升序去重 / 随机打乱 / 随机注入重复项」三种形态各跑一遍，验证输入顺序与重复项
 * 不影响输出（需求 4.13 的纯函数性质），且三者都等于朴素实现在去重集合上的结果。</p>
 *
 * <p>Feature: growth-level-system, Property 6: 连续段算法与朴素实现等价</p>
 *
 * <p>Validates: Requirements 4.9, 4.10, 4.12, 4.13</p>
 */
class GrowthCalendarScanPropertyTest {

    /** 分段构造起点的下界（含）。 */
    private static final long EPOCH_MIN = LocalDate.of(2000, 1, 1).toEpochDay();
    /** 分段构造起点的上界（含）。 */
    private static final long EPOCH_MAX = LocalDate.of(2035, 12, 31).toEpochDay();
    /** 集合规模上限（需求 4.6 追补窗口 1000 的两倍余量）。 */
    private static final int MAX_SIZE = 2000;

    // ---------------- 朴素参考实现：独立于被测 ----------------

    /**
     * 朴素 O(n²) 风格参考实现，仅用 {@link Set#contains} 判定日期归属，不依赖相邻 epochDay 差。
     *
     * <ul>
     *   <li>{@code totalDays} = 去重后集合大小；</li>
     *   <li>{@code lastDate} = 集合最大值（空集为 {@code null}）；</li>
     *   <li>{@code currentSegment} = 从最大日期向前逐日回溯、集合仍包含即 +1；</li>
     *   <li>{@code maxStreak} = 对每个「段首」（其前一日不在集合内）向后逐日数长度取最大。</li>
     * </ul>
     */
    private static CalendarScan naiveScan(List<LocalDate> input) {
        Set<LocalDate> set = new HashSet<>(input);
        if (set.isEmpty()) {
            return new CalendarScan(0, 0, 0, null);
        }
        int totalDays = set.size();
        LocalDate max = Collections.max(set);

        int currentSegment = 0;
        for (LocalDate d = max; set.contains(d); d = d.minusDays(1)) {
            currentSegment++;
        }

        int maxStreak = 0;
        for (LocalDate day : set) {
            if (!set.contains(day.minusDays(1))) { // day 是某连续区间的段首
                int len = 0;
                for (LocalDate cur = day; set.contains(cur); cur = cur.plusDays(1)) {
                    len++;
                }
                maxStreak = Math.max(maxStreak, len);
            }
        }
        return new CalendarScan(totalDays, currentSegment, maxStreak, max);
    }

    // ---------------- 生成器 ----------------

    /** 分段构造集合：起点 × [(段长, 空洞)…]，规模封顶 MAX_SIZE。 */
    @Provide
    Arbitrary<List<LocalDate>> segmentedSets() {
        Arbitrary<Long> start = Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX);
        Arbitrary<int[]> segment = Combinators.combine(
                Arbitraries.integers().between(1, 400),
                Arbitraries.integers().between(1, 60))
                .as((len, gap) -> new int[] {len, gap});
        Arbitrary<List<int[]>> segments = segment.list().ofMaxSize(30);
        return Combinators.combine(start, segments).as(GrowthCalendarScanPropertyTest::buildSegmented);
    }

    /** 纯随机日期集合，全域取值。 */
    @Provide
    Arbitrary<List<LocalDate>> randomSets() {
        return Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX)
                .map(LocalDate::ofEpochDay)
                .list().ofMaxSize(500);
    }

    /** 两个极端：全连续（单段）与全孤立（段长恒 1、空洞恒 2）。 */
    @Provide
    Arbitrary<List<LocalDate>> extremeSets() {
        Arbitrary<Long> start = Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX);
        Arbitrary<Integer> count = Arbitraries.integers().between(0, 500);
        Arbitrary<Boolean> consecutive = Arbitraries.of(true, false);
        return Combinators.combine(start, count, consecutive).as((s, n, cons) -> {
            List<LocalDate> dates = new ArrayList<>(n);
            long step = cons ? 1L : 2L; // 全连续步长 1；全孤立步长 2（任意相邻两日相差 2 天，无连续）
            for (int i = 0; i < n; i++) {
                dates.add(LocalDate.ofEpochDay(s + i * step));
            }
            return dates;
        });
    }

    /** 三类生成器合流。 */
    @Provide
    Arbitrary<List<LocalDate>> dateSets() {
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

    // ---------------- Property 6 ----------------

    /**
     * Feature: growth-level-system, Property 6: 连续段算法与朴素实现等价
     *
     * <p>对任意日期集合（含空集、单点、连续段、多段、跨月跨年、含闰日）：</p>
     * <ul>
     *   <li><b>与朴素实现逐字段相等</b>（需求 4.9、4.10、4.12）：{@code scan} 的四项取值全部等于
     *       {@link #naiveScan(List)} 在去重集合上的结果。</li>
     *   <li><b>核心不变式</b>（需求 4.9）：{@code maxStreak >= currentSegment}；
     *       {@code totalDays} 等于去重后日期个数；空集时四项为 {@code (0, 0, 0, null)}。</li>
     *   <li><b>输入顺序与重复项无关</b>（需求 4.13 的纯函数性质）：升序去重 / 随机打乱 / 注入重复项
     *       三种形态得出同一结果。</li>
     * </ul>
     *
     * <p>Validates: Requirements 4.9, 4.10, 4.12, 4.13</p>
     */
    @Property(tries = 1000)
    void property6_scanEquivalentToNaiveReference(
            @ForAll("dateSets") List<LocalDate> generated,
            @ForAll long shuffleSeed) {

        Set<LocalDate> distinct = new HashSet<>(generated);

        // 朴素参考实现（独立算法）作为期望值。
        CalendarScan expected = naiveScan(generated);

        // 需求 4.9、4.10、4.12：被测与朴素实现逐字段相等。
        CalendarScan ascending = GrowthCalendarService.scan(sortedDistinct(distinct));
        assertThat(ascending)
                .as("scan 应与朴素参考实现逐字段相等（升序去重输入）")
                .isEqualTo(expected);

        // 核心不变式（由构造过程保证，随生成器一并压到）。
        assertThat(ascending.maxStreak())
                .as("历史最长连续天数恒 >= 连续段长度")
                .isGreaterThanOrEqualTo(ascending.currentSegment());
        assertThat(ascending.totalDays())
                .as("累计记账天数等于去重后日期个数")
                .isEqualTo(distinct.size());
        if (distinct.isEmpty()) {
            assertThat(ascending).isEqualTo(new CalendarScan(0, 0, 0, null));
        } else {
            assertThat(ascending.lastDate()).isEqualTo(Collections.max(distinct));
        }

        // 需求 4.13：随机打乱后输出不变。
        List<LocalDate> shuffled = new ArrayList<>(distinct);
        Collections.shuffle(shuffled, new Random(shuffleSeed));
        assertThat(GrowthCalendarService.scan(shuffled))
                .as("随机打乱输入不应改变输出（纯函数）")
                .isEqualTo(expected);

        // 需求 4.13：注入重复项后输出不变。
        List<LocalDate> withDuplicates = withInjectedDuplicates(shuffled, new Random(shuffleSeed * 31L + 7L));
        assertThat(GrowthCalendarService.scan(withDuplicates))
                .as("注入重复项不应改变输出（纯函数）")
                .isEqualTo(expected);
    }

    // ---------------- 辅助 ----------------

    private static List<LocalDate> sortedDistinct(Set<LocalDate> distinct) {
        List<LocalDate> list = new ArrayList<>(distinct);
        Collections.sort(list);
        return list;
    }

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
