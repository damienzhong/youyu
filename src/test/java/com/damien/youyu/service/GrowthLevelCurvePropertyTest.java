package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * {@link GrowthLevelCurve} 的属性测试（<b>Property 1：等级曲线的单调性与换算边界</b>）。
 *
 * <h2>测试层级选择</h2>
 * <p>{@link GrowthLevelCurve} 是无状态、无依赖的单例，等级换算是纯逻辑，不碰数据库、不读时钟，
 * 故直接 {@code new} 构造被测对象、走纯 jqwik，不引入 Spring 上下文。</p>
 *
 * <h2>期望值的独立计算</h2>
 * <p>测试<b>不复用</b>被测的二分实现：{@code threshold} 一侧由公式
 * {@code 2(L-1)^2 + 8(L-1)} 在测试内独立重算——这正是需求 2.11「曲线是公式、不是手写常量表」的
 * 回归锁（若有人把 {@link GrowthLevelCurve} 改成手写阈值表并抄错一格，本断言即失败）；
 * {@code levelOf} 一侧由「自 Lv1 起线性扫描、取满足 {@code threshold(L) <= exp} 的最大 L」独立算出，
 * 与被测的 {@code Arrays.binarySearch + 负返回值处理} 是两条不同实现，互为参照。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li>经验值 {@code exp}：边界集（0、每个阈值及其 ±1、满级门槛 20393/20394/20395、
 *       {@code Long.MAX_VALUE}、负值）∪ 全域随机 {@code long}，使二分的插入点分支与整数比较边界都被压到。</li>
 *   <li>等级 {@code level}：整个定义域 [1, 100]。</li>
 *   <li>{@code expLow <= expHigh} 一对经验值：验证「经验增则等级不减」。</li>
 * </ul>
 *
 * <p>Feature: growth-level-system, Property 1: 等级曲线的单调性与换算边界</p>
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.11</p>
 */
class GrowthLevelCurvePropertyTest {

    private final GrowthLevelCurve curve = new GrowthLevelCurve();

    /** 满级门槛 threshold(100)；exp 达到它即恒为满级（需求 2.6）。 */
    private static final long MAX_THRESHOLD = 20394L;

    // ---------------- 期望值：独立于被测实现 ----------------

    /** 公式重算，作为 threshold 的独立参照（需求 2.1、2.11）。 */
    private static long formulaThreshold(int level) {
        long n = level - 1L;
        return 2L * n * n + 8L * n;
    }

    /** 线性扫描求「满足 threshold(L) <= exp 的最大 L」，上限 100；与被测的二分互为参照（需求 2.3、2.6）。 */
    private static int expectedLevelOf(long exp) {
        int result = 1;
        for (int level = 1; level <= GrowthLevelCurve.MAX_LEVEL; level++) {
            if (formulaThreshold(level) <= exp) {
                result = level;
            } else {
                break; // threshold 严格递增，一旦越过即无需再看更高等级
            }
        }
        return result;
    }

    // ---------------- 生成器 ----------------

    /** 经验值输入空间：边界集（阈值及其 ±1、满级前后、极值、负值）∪ 全域随机 long。 */
    @Provide
    Arbitrary<Long> exps() {
        List<Long> boundaries = new ArrayList<>();
        boundaries.add(Long.MIN_VALUE);
        boundaries.add(-1L);
        boundaries.add(0L);
        for (int level = 1; level <= GrowthLevelCurve.MAX_LEVEL; level++) {
            long t = formulaThreshold(level);
            boundaries.add(t - 1L);
            boundaries.add(t);
            boundaries.add(t + 1L);
        }
        boundaries.add(Long.MAX_VALUE);
        return Arbitraries.oneOf(
                Arbitraries.of(boundaries),
                Arbitraries.longs());
    }

    // ---------------- Property 1 ----------------

    /**
     * Feature: growth-level-system, Property 1: 等级曲线的单调性与换算边界
     *
     * <p>对任意等级 {@code level} 与任意经验值 {@code exp}（及一对有序经验值 {@code expLow <= expHigh}）：</p>
     * <ul>
     *   <li><b>曲线由公式派生</b>（需求 2.1、2.11）：{@code threshold(level)} 恒等于
     *       {@code 2(level-1)^2 + 8(level-1)}，非手写常量表。</li>
     *   <li><b>严格单调递增</b>（需求 2.2）：{@code level < 100} 时
     *       {@code threshold(level) < threshold(level+1)}。</li>
     *   <li><b>换算取最大 L</b>（需求 2.3）：{@code levelOf(exp)} 等于独立线性扫描算出的
     *       「满足 {@code threshold(L) <= exp} 的最大 L」，且落在 [1, 100]。</li>
     *   <li><b>整数比较、边界取等号即升级</b>（需求 2.4、2.5）：{@code levelOf(threshold(level)) == level}，
     *       且 {@code level > 1} 时 {@code levelOf(threshold(level) - 1) == level - 1}
     *       （相邻阈值间无浮点误差错级）。</li>
     *   <li><b>满级钳制</b>（需求 2.6）：{@code exp >= 20394} 恒返回 100，即便 {@code exp} 继续增大。</li>
     *   <li><b>经验增则等级不减</b>（需求 2.7）：{@code expLow <= expHigh} 时
     *       {@code levelOf(expLow) <= levelOf(expHigh)}。</li>
     * </ul>
     *
     * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.11</p>
     */
    @Property(tries = 1000)
    void property1_levelCurveMonotonicityAndConversionBoundaries(
            @ForAll @IntRange(min = 1, max = GrowthLevelCurve.MAX_LEVEL) int level,
            @ForAll("exps") long exp,
            @ForAll("exps") long expA,
            @ForAll("exps") long expB) {

        // 需求 2.1、2.11：曲线由公式派生，不是手写常量表。
        long threshold = curve.threshold(level);
        assertThat(threshold)
                .as("threshold(%d) 应等于 2(L-1)^2+8(L-1)", level)
                .isEqualTo(formulaThreshold(level));

        // 需求 2.2：定义域上严格单调递增。
        if (level < GrowthLevelCurve.MAX_LEVEL) {
            assertThat(curve.threshold(level + 1))
                    .as("threshold(%d) 应严格大于 threshold(%d)", level + 1, level)
                    .isGreaterThan(threshold);
        }

        // 需求 2.3、2.6：换算取满足 threshold(L) <= exp 的最大 L，钳制在 [1, 100]。
        int actual = curve.levelOf(exp);
        assertThat(actual)
                .as("levelOf(%d) 应等于独立扫描算出的最大等级", exp)
                .isEqualTo(expectedLevelOf(exp));
        assertThat(actual).as("等级恒落在 [1, 100]").isBetween(1, GrowthLevelCurve.MAX_LEVEL);

        // 需求 2.4、2.5：整数比较、阈值取等号即升级，阈值减一落在前一等级（无浮点误差错级）。
        assertThat(curve.levelOf(threshold))
                .as("exp 恰等于 threshold(%d) 时应为 Lv%d", level, level)
                .isEqualTo(level);
        if (level > 1) {
            assertThat(curve.levelOf(threshold - 1L))
                    .as("exp 为 threshold(%d)-1 时应仍为 Lv%d", level, level - 1)
                    .isEqualTo(level - 1);
        }

        // 需求 2.6：满级门槛及以上恒为 100，满级后经验继续累计、等级不再上升。
        if (exp >= MAX_THRESHOLD) {
            assertThat(actual).as("exp>=20394 恒满级").isEqualTo(GrowthLevelCurve.MAX_LEVEL);
        }

        // 需求 2.7：经验单调增导致等级单调不减。
        long low = Math.min(expA, expB);
        long high = Math.max(expA, expB);
        assertThat(curve.levelOf(low))
                .as("经验从 %d 增到 %d，等级不应减少", low, high)
                .isLessThanOrEqualTo(curve.levelOf(high));
    }
}
