package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * <b>Property 10：里程碑的单调性与边界</b>的属性测试（任务 8.5）。
 *
 * <p>对<i>任意</i>当前连续天数 {@code s ∈ [0, 500]} 与<i>任意</i>门槛集合（正常清单、单元素、空集、
 * 乱序、含重复），断言 {@link StreakMilestones#nextAfter(int)} 满足需求 3.6、3.7、3.8：</p>
 *
 * <ul>
 *   <li>{@code nextAfter(s)} 为空 ⟺ {@code s ≥ 里程碑集合最大值}（空集时恒为空）；</li>
 *   <li>非空时 {@code nextAfter(s) > s}，且 {@code nextAfter(s) − s ∈ [1, 里程碑最大值]}；</li>
 *   <li>非空时 {@code nextAfter(s)} 恰为升序去重集合中<b>首个大于 s</b> 的门槛；</li>
 *   <li>{@code s} 递增时 {@code nextAfter(s)} 单调不减（把「已全部达成」的空值视为 {@code +∞}）。</li>
 * </ul>
 *
 * <h2>门槛集合的构造</h2>
 *
 * <p>门槛集合直接由 {@link StreakMilestones} 从 {@link GrowthBadgeCatalog} 中 {@code MAX_STREAK} 口径
 * 门槛派生。为覆盖单元素 / 空集 / 乱序 / 含重复等 {@link GrowthBadgeCatalog#badges()} 本身不会出现的
 * 门槛形态，本测试用一份<b>桩清单</b>（覆写 {@code badges()} 返回任意 {@code MAX_STREAK} 门槛）驱动
 * {@link StreakMilestones#derive()}——{@code derive()} 只读 {@code metric} 与 {@code target} 两项，
 * 升序去重后收口，桩清单的 code / name / description 只作占位，不参与派生。</p>
 *
 * <h2>源码扫描子句（复用任务 3.3）</h2>
 *
 * <p>Property 10 的「源码扫描」子句——里程碑集合恒等于 {@code MAX_STREAK} 口径门槛的升序去重结果、
 * 且服务端源码不出现 7 / 30 / 100 / 365 四个裸字面量——由 {@link StreakMilestoneSourceScanTest}
 * 实现，本类<b>直接复用</b>其两个断言方法（{@link #sourceScanNoHardcodedMilestoneLiterals()} 与
 * {@link #milestoneSetEqualsCatalogMaxStreakThresholds()}），使 Property 10 的两半（值域性质 +
 * 单一事实源）在同一属性测试类下收口。</p>
 *
 * <p>Feature: streak-system, Property 10: 里程碑的单调性与边界</p>
 * <p>Validates: Requirements 3.5, 3.6, 3.7, 3.8, 3.9, 3.11, 10.10</p>
 */
class StreakMilestonePropertyTest {

    /**
     * 用一份桩清单构造 {@link StreakMilestones}：清单里每个 {@code MAX_STREAK} 门槛取 {@code thresholds}
     * 中的一个取值（保留原始顺序与重复，派生时才升序去重），并混入一枚非 {@code MAX_STREAK} 门槛，
     * 断言口径过滤把它排除在里程碑集合之外。
     */
    private static StreakMilestones milestonesFor(List<Integer> thresholds) {
        List<BadgeDef> badges = new ArrayList<>();
        // 混入一枚 RECORD_COUNT 口径门槛：里程碑派生按 MAX_STREAK 口径过滤，它不应进入里程碑集合。
        badges.add(new BadgeDef("STUB_NON_STREAK", "占位甲", "非连续口径占位门槛",
                AchievementCategory.VOLUME, 250, BadgeMetric.RECORD_COUNT));
        int i = 0;
        for (Integer t : thresholds) {
            badges.add(new BadgeDef("STUB_STREAK_" + (i++), "占位乙" + i, "连续口径占位门槛" + i,
                    AchievementCategory.STREAK, t, BadgeMetric.MAX_STREAK));
        }
        GrowthBadgeCatalog catalog = new GrowthBadgeCatalog() {
            @Override
            public List<BadgeDef> badges() {
                return badges;
            }
        };
        StreakMilestones milestones = new StreakMilestones(catalog);
        milestones.derive();   // @PostConstruct 在无 Spring 上下文时手动触发
        return milestones;
    }

    /** 升序去重后的门槛集合，即里程碑集合的期望取值（唯一事实源在 MAX_STREAK 口径门槛）。 */
    private static List<Integer> sortedDistinct(List<Integer> thresholds) {
        return thresholds.stream().distinct().sorted().toList();
    }

    // ---------------- 生成器 ----------------

    @Provide
    Arbitrary<List<Integer>> thresholdSets() {
        // 随机门槛集合：size 0 覆盖空集、size 1 覆盖单元素，天然含乱序与重复。
        Arbitrary<List<Integer>> random = Arbitraries.integers().between(1, 500).list().ofMaxSize(8);
        // 刻意构造的高风险形态：真实清单、乱序、含重复、单元素、空集。
        Arbitrary<List<Integer>> shaped = Arbitraries.of(
                List.of(7, 30, 100, 365),            // 正常清单
                List.of(365, 7, 100, 30),            // 乱序
                List.of(7, 7, 30, 30, 100, 365),     // 含重复
                List.of(1),                          // 单元素（最小门槛）
                List.of(500),                        // 单元素（大门槛）
                List.<Integer>of());                 // 空集
        return Arbitraries.oneOf(random, shaped);
    }

    @Provide
    Arbitrary<Integer> streakDays() {
        return Arbitraries.integers().between(0, 500);
    }

    // ---------------- Property 10：值域性质 ----------------

    /**
     * Feature: streak-system, Property 10: 里程碑的单调性与边界
     *
     * <p>{@code nextAfter} 为空 ⟺ {@code s ≥ 最大门槛}；非空时严格大于 {@code s}、差落在
     * {@code [1, 最大门槛]}、且恰为首个大于 {@code s} 的门槛（需求 3.6、3.7、3.8）。</p>
     *
     * <p>Validates: Requirements 3.6, 3.7, 3.8, 3.9, 3.11</p>
     */
    @Property(tries = 25)
    void nextAfterIsBoundedAndFirstGreater(@ForAll("thresholdSets") List<Integer> raw,
                                           @ForAll("streakDays") int s) {
        List<Integer> expected = sortedDistinct(raw);
        StreakMilestones milestones = milestonesFor(raw);
        Integer next = milestones.nextAfter(s);

        if (expected.isEmpty()) {
            // 空集：nextAfter 恒为空（页面据此展示「已全部达成」，需求 3.11）。
            assertThat(next).as("空门槛集合下 nextAfter(%d) 应为空", s).isNull();
            return;
        }

        int max = expected.get(expected.size() - 1);
        if (s >= max) {
            assertThat(next).as("s=%d ≥ 最大门槛 %d 时 nextAfter 应为空", s, max).isNull();
        } else {
            assertThat(next).as("s=%d < 最大门槛 %d 时 nextAfter 应非空", s, max).isNotNull();
            assertThat(next).as("nextAfter(%d) 应严格大于 s", s).isGreaterThan(s);
            assertThat(next - s).as("nextAfter(%d) − s 应落在 [1, %d]", s, max).isBetween(1, max);

            // 恰为升序去重集合中首个大于 s 的门槛。
            Integer firstGreater = expected.stream().filter(t -> t > s).findFirst().orElseThrow();
            assertThat(next).as("nextAfter(%d) 应为首个大于 s 的门槛", s).isEqualTo(firstGreater);
        }
    }

    /**
     * Feature: streak-system, Property 10: 里程碑的单调性与边界
     *
     * <p>{@code s} 递增时 {@code nextAfter(s)} 单调不减：把「已全部达成」的空值视为 {@code +∞}，
     * 则 {@code s1 ≤ s2 ⟹ nextAfter(s1) ≤ nextAfter(s2)}（需求 3.6、3.7）。</p>
     *
     * <p>Validates: Requirements 3.6, 3.7</p>
     */
    @Property(tries = 25)
    void nextAfterIsMonotoneNonDecreasing(@ForAll("thresholdSets") List<Integer> raw,
                                          @ForAll("streakDays") int a,
                                          @ForAll("streakDays") int b) {
        int s1 = Math.min(a, b);
        int s2 = Math.max(a, b);
        StreakMilestones milestones = milestonesFor(raw);

        Integer n1 = milestones.nextAfter(s1);
        Integer n2 = milestones.nextAfter(s2);

        if (n1 == null) {
            // s1 已达最大门槛（或空集）⇒ s2 ≥ s1 也必然为空。
            assertThat(n2).as("nextAfter(%d)=null 时 nextAfter(%d) 也应为空（单调不减）", s1, s2).isNull();
        } else if (n2 != null) {
            assertThat(n1).as("s1=%d ≤ s2=%d 时 nextAfter 应单调不减", s1, s2).isLessThanOrEqualTo(n2);
        }
        // n1 != null && n2 == null 属合法（s2 越过最大门槛，视为 +∞，仍单调不减）。
    }

    // ---------------- 源码扫描子句（复用任务 3.3 的 StreakMilestoneSourceScanTest）----------------

    /**
     * 里程碑数值不写死的源码扫描（Property 10 的「源码扫描」子句，需求 3.5、10.10）：直接复用
     * {@link StreakMilestoneSourceScanTest} 的断言，避免在两处各写一份扫描逻辑。
     *
     * <p>Validates: Requirements 3.5, 10.10</p>
     */
    @Test
    void sourceScanNoHardcodedMilestoneLiterals() {
        new StreakMilestoneSourceScanTest().streakSourceFilesDoNotHardcodeMilestoneValues();
    }

    /**
     * 里程碑集合恒等于 {@code MAX_STREAK} 口径门槛的升序去重结果（Property 10 的「集合恒等」子句，
     * 需求 3.5、10.10）：复用 {@link StreakMilestoneSourceScanTest} 的断言。
     *
     * <p>Validates: Requirements 3.5, 10.10</p>
     */
    @Test
    void milestoneSetEqualsCatalogMaxStreakThresholds() {
        new StreakMilestoneSourceScanTest().streakMilestonesEqualCatalogMaxStreakThresholds();
    }
}
