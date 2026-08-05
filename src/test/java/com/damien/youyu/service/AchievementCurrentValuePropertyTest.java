package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * <b>Property 3：当前值恒落在 {@code [0, target]}，已解锁恒等于 {@code target}</b>的属性测试（任务 8.2）。
 *
 * <p><i>对任意</i>成就（16 枚<b>全枚举</b>）× 其统计口径取值（含极端负值、{@code -1}、{@code 0}、
 * {@code 1}、{@code 门槛−1}、{@code 门槛}、{@code 门槛+1}、该分量类型的最大值）× 任意解锁状态
 * （{@code true} / {@code false}），断言 {@link GrowthBadgeCatalog#currentOf} 的四条不变式：</p>
 *
 * <ul>
 *   <li><b>区间</b>：{@code 0 ≤ current ≤ target} 恒成立（需求 3.13、6.4）；</li>
 *   <li><b>已解锁恒等于门槛</b>：{@code unlocked ⇒ current == target}，与统计口径的当前取值无关
 *       ——删除交易、把「旅行」分类改名或删除使实时聚合口径回落时，已解锁成就的进度<b>不回退</b>
 *       （需求 3.12、6.4）；</li>
 *   <li><b>未解锁取较小者</b>：{@code !unlocked ⇒ current == max(0, min(统计量, target))}（需求 6.4）；</li>
 *   <li><b>永不为负、永不溢出</b>：脏数据造成的负统计量钳到 0 而不是把负数发给前端；
 *       {@code Long.MAX_VALUE} 这类取值经 {@code (int)} 收窄时不得回绕成负数或小于门槛的值
 *       （需求 3.13）。</li>
 * </ul>
 *
 * <h2>为什么是全枚举而不是随机抽样</h2>
 *
 * <p>输入空间恰好 16 × 8 × 2 = 256 个组合，小到可以穷举，因此本类用
 * {@link GenerationMode#EXHAUSTIVE} 把它<b>整个</b>跑一遍：随机抽样在 256 个组合上只是「大概率覆盖」，
 * 而这条属性的价值正在于「一个组合都不漏」——{@code STREAK_365} 的 {@code 门槛+1} 与
 * {@code TRAVEL_MASTER} 的极端负值都是只出现一次的组合。</p>
 *
 * <h2>三个刻意的建模细节</h2>
 *
 * <ol>
 *   <li><b>两个天数口径是 {@code int} 分量</b>：{@link GrowthFacts#maxStreakDays()} 与
 *       {@link GrowthFacts#totalRecordDays()} 声明为 {@code int}（记账天数不可能超出 {@code int}），
 *       故这两个口径的「最大值 / 极端负值」档取 {@link Integer#MAX_VALUE} /
 *       {@link Integer#MIN_VALUE}，其余六个口径取 {@link Long#MAX_VALUE} / {@link Long#MIN_VALUE}。
 *       断言用的期望值一律按<b>实际写进 record 的那个取值</b>算，因此这不是放宽而是精确建模。</li>
 *   <li><b>存在型口径只有 0 / 1</b>：{@link BadgeMetric#FIRST_INVITE_EVENT} 由布尔分量承载，
 *       取值档按 {@code ≥ 1 → true} 映射回 1、其余映射回 0（需求 3.8），期望值随之按映射后的取值算。</li>
 *   <li><b>只给被测成就的那一个分量赋值</b>，其余七个分量恒为 0：{@code currentOf} 只读被测成就自己的
 *       口径，把其它分量一起拉高会让「取错口径」这类缺陷被掩盖（比如误用
 *       {@code recordCount} 算 {@code DAYS_100} 的进度）。</li>
 * </ol>
 *
 * <p>纯组件、无外部依赖，故不起 Spring 上下文；{@link GrowthBadgeCatalog} 直接 {@code new}
 * （清单是静态常量，{@code @PostConstruct} 的自校验由 {@code AchievementCatalogSelfCheckTest} 覆盖）。</p>
 *
 * <p>Feature: achievement-system, Property 3: 当前值恒落在 [0, target]，已解锁恒等于 target</p>
 *
 * <p>Validates: Requirements 3.13, 6.4</p>
 */
class AchievementCurrentValuePropertyTest {

    /** 被测组件：无依赖的纯常量清单 + 三条钳制规则。 */
    private final GrowthBadgeCatalog catalog = new GrowthBadgeCatalog();

    /**
     * 统计口径取值档（需求 3.13 的输入空间）。
     *
     * <p>{@code TARGET_MINUS_ONE} 在门槛为 1 时退化成 0、{@code ONE} 在门槛为 1 时与 {@code TARGET}
     * 重合——这两处重合是<b>刻意保留</b>的：全枚举不做去重，重复组合只是多跑一次同样的断言，
     * 而一旦有人把门槛改动，这些档位自动落到新的边界上。</p>
     */
    enum ValueKind {
        /** 该分量类型的最小值：{@code (int)} 收窄前先被 {@code max(0, ...)} 钳住，最容易暴露溢出。 */
        NEGATIVE_EXTREME,
        /** 紧贴 0 下沿的负值：脏数据里最常见的那一种。 */
        NEGATIVE_ONE,
        ZERO,
        ONE,
        TARGET_MINUS_ONE,
        TARGET,
        TARGET_PLUS_ONE,
        /** 该分量类型的最大值：{@code min(值, target)} 之后必须恰好等于门槛，不得回绕。 */
        MAX
    }

    @Provide
    Arbitrary<Integer> badgeIndexes() {
        return Arbitraries.integers().between(0, GrowthBadgeCatalog.EXPECTED_SIZE - 1);
    }

    // ---------------- Property 3 ----------------

    /**
     * Feature: achievement-system, Property 3: 当前值恒落在 [0, target]，已解锁恒等于 target
     *
     * <p>16 枚成就 × 8 档口径取值 × 2 种解锁状态全枚举，逐组合断言区间、已解锁恒等门槛、
     * 未解锁取 {@code min}、永不为负、永不溢出。</p>
     *
     * <p>Validates: Requirements 3.13, 6.4</p>
     */
    @Property(tries = 1000, generation = GenerationMode.EXHAUSTIVE)
    void property3_currentValueIsClampedIntoTargetRange(@ForAll("badgeIndexes") int badgeIndex,
                                                        @ForAll ValueKind kind,
                                                        @ForAll boolean unlocked) {
        BadgeDef badge = catalog.badges().get(badgeIndex);
        int target = badge.target();

        // 写进 record 的原始取值，与 currentOf 实际读到的「有效取值」（int 收窄 / 布尔映射之后的值）。
        long raw = rawValue(kind, target, badge.metric());
        long effective = effectiveValue(raw, badge.metric());
        GrowthFacts facts = factsWith(badge.metric(), raw);

        int current = catalog.currentOf(badge, facts, unlocked);

        assertThat(current)
                .as("成就 %s（口径 %s，取值档 %s）的当前值恒落在 [0, %d]（需求 3.13、6.4）",
                        badge.code(), badge.metric(), kind, target)
                .isBetween(0, target);
        assertThat(current)
                .as("成就 %s 的当前值永不为负：脏数据造成的负统计量钳到 0（需求 3.13）", badge.code())
                .isNotNegative();

        if (unlocked) {
            assertThat(current)
                    .as("已解锁的成就 %s 当前值恒等于门槛 %d，与口径当前取值 %d 无关（需求 3.12、6.4）",
                            badge.code(), target, effective)
                    .isEqualTo(target);
        } else {
            assertThat(current)
                    .as("未解锁的成就 %s 当前值等于 max(0, min(%d, %d))（需求 6.4）",
                            badge.code(), effective, target)
                    .isEqualTo((int) Math.max(0L, Math.min(effective, target)));
        }

        if (kind == ValueKind.MAX) {
            assertThat(current)
                    .as("成就 %s 的口径取到分量最大值时当前值恰好等于门槛，不得因 int 收窄而回绕（需求 3.13）",
                            badge.code())
                    .isEqualTo(target);
        }
    }

    // ---------------- 定点用例：八个口径同时取极值 ----------------

    /**
     * 八个分量<b>同时</b>取各自类型的最大值时，16 枚成就的当前值一律等于门槛（未解锁与已解锁都成立）。
     *
     * <p>属性方法每次只拉高一个分量，这条用例补上「全都拉满」的组合，把
     * {@code (int) Math.max(0L, Math.min(value, target))} 里任何一处漏掉 {@code min} 的写法钉死
     * ——漏掉 {@code min} 时 {@code Long.MAX_VALUE} 收窄成 {@code -1}，断言立刻变红。</p>
     *
     * <p>Validates: Requirements 3.13, 6.4</p>
     */
    @Example
    void allMetricsAtMaximum_yieldCurrentEqualToTargetForEveryBadge() {
        GrowthFacts saturated = new GrowthFacts(Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Long.MAX_VALUE, true, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

        List<BadgeDef> badges = catalog.badges();
        assertThat(badges).hasSize(GrowthBadgeCatalog.EXPECTED_SIZE);
        for (BadgeDef badge : badges) {
            assertThat(catalog.currentOf(badge, saturated, false))
                    .as("成就 %s 的口径拉满时未解锁当前值等于门槛（需求 6.4）", badge.code())
                    .isEqualTo(badge.target());
            assertThat(catalog.currentOf(badge, saturated, true))
                    .as("成就 %s 的口径拉满时已解锁当前值等于门槛（需求 6.4）", badge.code())
                    .isEqualTo(badge.target());
        }
    }

    /**
     * 八个分量<b>同时</b>取各自类型的最小值时，未解锁的当前值一律为 0、已解锁的一律等于门槛。
     *
     * <p>并补上 {@code facts == null} 这条降级入参（{@code currentOf} 按
     * {@link GrowthFacts#EMPTY} 处理）：查询路径在聚合全部失败时会走到这里，它同样必须落在
     * {@code [0, target]} 内（需求 3.14）。</p>
     *
     * <p>Validates: Requirements 3.13, 6.4</p>
     */
    @Example
    void allMetricsAtMinimum_yieldZeroWhenLockedAndTargetWhenUnlocked() {
        GrowthFacts negative = new GrowthFacts(Long.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Long.MIN_VALUE, false, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE);

        for (BadgeDef badge : catalog.badges()) {
            assertThat(catalog.currentOf(badge, negative, false))
                    .as("成就 %s 的负统计量钳到 0，不把负数发给前端（需求 3.13）", badge.code())
                    .isZero();
            assertThat(catalog.currentOf(badge, negative, true))
                    .as("成就 %s 已解锁时当前值仍恒等于门槛（需求 6.4）", badge.code())
                    .isEqualTo(badge.target());
            assertThat(catalog.currentOf(badge, null, false))
                    .as("成就 %s 在 facts 为空的降级路径上当前值为 0（需求 3.14）", badge.code())
                    .isZero();
            assertThat(catalog.currentOf(badge, null, true))
                    .as("成就 %s 在 facts 为空的降级路径上已解锁仍等于门槛（需求 6.4）", badge.code())
                    .isEqualTo(badge.target());
        }
    }

    // ---------------- 取值档 → 事实 ----------------

    /** 某档在给定门槛与口径下的原始取值；两个 {@code int} 分量的极值按 {@code int} 取。 */
    private static long rawValue(ValueKind kind, int target, BadgeMetric metric) {
        boolean intComponent = metric == BadgeMetric.MAX_STREAK || metric == BadgeMetric.TOTAL_DAYS;
        return switch (kind) {
            case NEGATIVE_EXTREME -> intComponent ? Integer.MIN_VALUE : Long.MIN_VALUE;
            case NEGATIVE_ONE -> -1L;
            case ZERO -> 0L;
            case ONE -> 1L;
            case TARGET_MINUS_ONE -> target - 1L;
            case TARGET -> target;
            case TARGET_PLUS_ONE -> target + 1L;
            case MAX -> intComponent ? Integer.MAX_VALUE : Long.MAX_VALUE;
        };
    }

    /**
     * {@code currentOf} 实际读到的取值：存在型口径按 {@code ≥ 1 → 1，其余 → 0} 映射（需求 3.8），
     * 其余口径原样。
     */
    private static long effectiveValue(long raw, BadgeMetric metric) {
        return (metric == BadgeMetric.FIRST_INVITE_EVENT) ? (raw >= 1L ? 1L : 0L) : raw;
    }

    /** 只给 {@code metric} 对应的分量赋值，其余七个分量恒为 0（见类级 Javadoc 第 3 点）。 */
    private static GrowthFacts factsWith(BadgeMetric metric, long value) {
        return switch (metric) {
            case RECORD_COUNT -> new GrowthFacts(value, 0, 0, 0L, false, 0L, 0L, 0L);
            case MAX_STREAK -> new GrowthFacts(0L, (int) value, 0, 0L, false, 0L, 0L, 0L);
            case TOTAL_DAYS -> new GrowthFacts(0L, 0, (int) value, 0L, false, 0L, 0L, 0L);
            case BUDGET_MET_COUNT -> new GrowthFacts(0L, 0, 0, value, false, 0L, 0L, 0L);
            case FIRST_INVITE_EVENT -> new GrowthFacts(0L, 0, 0, 0L, value >= 1L, 0L, 0L, 0L);
            case SAVING_MONTH_COUNT -> new GrowthFacts(0L, 0, 0, 0L, false, value, 0L, 0L);
            case COLLAB_MEMBER_COUNT -> new GrowthFacts(0L, 0, 0, 0L, false, 0L, value, 0L);
            case TRAVEL_RECORD_COUNT -> new GrowthFacts(0L, 0, 0, 0L, false, 0L, 0L, value);
        };
    }
}
