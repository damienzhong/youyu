package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GrowthBadgeCatalog} 的示例/边界单元测试（关联需求 8.1、8.7、8.8、8.11、8.12）。
 *
 * <p>纯组件、无外部依赖，故不起 Spring 上下文。三组用例分别锁住：</p>
 *
 * <ul>
 *   <li><b>清单本身</b>：9 枚徽章的编码 / 中文名 / 门槛 / 统计口径 / 展示顺序逐条断言，
 *       且两次调用顺序恒相同（需求 8.1、8.8）。这里刻意把取值抄一遍，
 *       因为本清单是单一事实源，任何一处漂移都应当在这里失败。</li>
 *   <li><b>{@code BADGE:} 独占命名空间</b>：四个与经验事件同名的编码拼出的事件键恒带前缀；
 *       {@code BUDGET_MET} 徽章的点亮条件只看 {@code event_type = 'BUDGET_MET'} 的行，
 *       不看 {@code BADGE:BUDGET_MET} 行（需求 8.11）。</li>
 *   <li><b>当前值的 min 与 clamp</b>：已点亮恒等于门槛、未点亮取较小者、结果恒落在
 *       {@code [0, target]}（需求 8.12）。</li>
 * </ul>
 */
class GrowthBadgeCatalogTest {

    private GrowthBadgeCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new GrowthBadgeCatalog();
    }

    // ---- 清单：编码 / 名称 / 门槛 / 口径 / 顺序（需求 8.1、8.7、8.8）----

    @Test
    void badgesAreNineDefinitionsInFixedOrder() {
        List<BadgeDef> badges = catalog.badges();

        assertThat(badges).hasSize(9);
        assertBadge(badges.get(0), "FIRST_RECORD", "开张", 1, BadgeMetric.RECORD_COUNT);
        assertBadge(badges.get(1), "RECORD_10", "小有账目", 10, BadgeMetric.RECORD_COUNT);
        assertBadge(badges.get(2), "RECORD_100", "百笔有余", 100, BadgeMetric.RECORD_COUNT);
        assertBadge(badges.get(3), "RECORD_1000", "千笔如一", 1000, BadgeMetric.RECORD_COUNT);
        assertBadge(badges.get(4), "STREAK_7", "七日不辍", 7, BadgeMetric.MAX_STREAK);
        assertBadge(badges.get(5), "STREAK_30", "卅日成习", 30, BadgeMetric.MAX_STREAK);
        assertBadge(badges.get(6), "DAYS_100", "百日记账", 100, BadgeMetric.TOTAL_DAYS);
        assertBadge(badges.get(7), "BUDGET_MET", "预算达标", 1, BadgeMetric.BUDGET_MET_EVENT);
        assertBadge(badges.get(8), "INVITE_1", "同行有余", 1, BadgeMetric.FIRST_INVITE_EVENT);
    }

    /** 展示顺序恒定：两次调用得到逐元素相同的序列，且列表不可修改（需求 8.8）。 */
    @Test
    void badgeOrderIsStableAcrossCallsAndListIsImmutable() {
        List<String> first = catalog.badges().stream().map(BadgeDef::code).toList();
        List<String> second = catalog.badges().stream().map(BadgeDef::code).toList();

        assertThat(second).containsExactlyElementsOf(first);
        assertThat(new GrowthBadgeCatalog().badges().stream().map(BadgeDef::code).toList())
                .as("无状态单例，另一个实例的顺序也相同")
                .containsExactlyElementsOf(first);
        assertThatThrownBy(() -> catalog.badges().add(new BadgeDef("X", "X", 1, BadgeMetric.RECORD_COUNT)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void badgeCodesAreUnique() {
        assertThat(catalog.badges().stream().map(BadgeDef::code).distinct().count()).isEqualTo(9);
        assertThat(catalog.badges().stream().map(BadgeDef::name).distinct().count()).isEqualTo(9);
    }

    // ---- BADGE: 独占命名空间（需求 8.11）----

    /** 四个与经验事件同名的编码，事件键恒带 {@code BADGE:} 前缀。 */
    @Test
    void eventKeyOfSameNamedCodesAlwaysCarriesBadgePrefix() {
        for (String code : List.of("FIRST_RECORD", "STREAK_7", "STREAK_30", "BUDGET_MET")) {
            assertThat(GrowthBadgeCatalog.eventKeyOf(code))
                    .as("%s 的徽章键", code)
                    .isEqualTo("BADGE:" + code)
                    .isNotEqualTo(code);
        }
    }

    @Test
    void everyBadgeEventKeyCarriesBadgePrefix() {
        for (BadgeDef badge : catalog.badges()) {
            assertThat(GrowthBadgeCatalog.eventKeyOf(badge.code()))
                    .startsWith(GrowthBadgeCatalog.BADGE_KEY_PREFIX)
                    .isEqualTo(GrowthBadgeCatalog.BADGE_KEY_PREFIX + badge.code());
        }
    }

    /** 空编码会拼出无法归属到任何徽章的裸前缀键，必须在写入前失败。 */
    @Test
    void eventKeyOfRejectsBlankCode() {
        assertThatThrownBy(() -> GrowthBadgeCatalog.eventKeyOf(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrowthBadgeCatalog.eventKeyOf("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code BUDGET_MET} 徽章的条件只看 {@code event_type = 'BUDGET_MET'} 的经验事件行。
     *
     * <p>{@code unlocked = true} 表示库里已存在 {@code BADGE:BUDGET_MET} 行；
     * 若把该行当成点亮条件，判定就会自我循环。这里断言：已点亮但经验事件不存在时，
     * 条件<b>仍未</b>成立，即 {@code qualified} 不含该编码——{@code BADGE:} 行没有参与判定。</p>
     */
    @Test
    void budgetMetBadgeConditionReadsOnlyBudgetMetEventRow() {
        BadgeDef budgetMet = badgeOf("BUDGET_MET");
        assertThat(budgetMet.metric()).isEqualTo(BadgeMetric.BUDGET_MET_EVENT);

        // 记账笔数拉满，但无 BUDGET_MET 经验事件行：条件不成立。
        GrowthFacts withoutEvent = new GrowthFacts(5000L, 60, 200, false, true);
        assertThat(catalog.qualified(withoutEvent)).doesNotContain("BUDGET_MET");
        assertThat(catalog.currentOf(budgetMet, withoutEvent, false)).isZero();

        // 已存在 BADGE:BUDGET_MET 行（unlocked=true）不改变条件判定结果。
        assertThat(catalog.qualified(withoutEvent))
                .as("BADGE: 行不参与条件判定")
                .doesNotContain("BUDGET_MET");
        assertThat(catalog.currentOf(budgetMet, withoutEvent, true))
                .as("已点亮的当前值仍等于门槛")
                .isEqualTo(1);

        GrowthFacts withEvent = new GrowthFacts(0L, 0, 0, true, false);
        assertThat(catalog.qualified(withEvent)).contains("BUDGET_MET");
        assertThat(catalog.qualified(withEvent))
                .as("INVITE_1 只看 FIRST_INVITE 行，与预算事件无关")
                .doesNotContain("INVITE_1");
    }

    /** 门槛取等号即点亮（判定一律「大于或等于」）。 */
    @Test
    void qualifiedIncludesBadgesAtExactlyTheTarget() {
        GrowthFacts facts = new GrowthFacts(10L, 7, 100, false, false);

        assertThat(catalog.qualified(facts))
                .containsExactly("FIRST_RECORD", "RECORD_10", "STREAK_7", "DAYS_100");
    }

    @Test
    void qualifiedTreatsNullFactsAsEmpty() {
        assertThat(catalog.qualified(null)).isEmpty();
        assertThat(catalog.qualified(GrowthFacts.EMPTY)).isEmpty();
    }

    // ---- 当前值的 min 与 clamp（需求 8.12）----

    /** 已点亮恒等于门槛，即使统计量已回落到 0（删除交易不应让进度回退）。 */
    @Test
    void currentOfUnlockedBadgeAlwaysEqualsTarget() {
        for (BadgeDef badge : catalog.badges()) {
            assertThat(catalog.currentOf(badge, GrowthFacts.EMPTY, true))
                    .as("%s 已点亮", badge.code())
                    .isEqualTo(badge.target());
            assertThat(catalog.currentOf(badge, null, true))
                    .as("%s 已点亮且事实为 null", badge.code())
                    .isEqualTo(badge.target());
        }
    }

    /** 未点亮取 min(统计量, 门槛)。 */
    @Test
    void currentOfLockedBadgeTakesMinOfMetricAndTarget() {
        GrowthFacts below = new GrowthFacts(37L, 3, 12, false, false);
        assertThat(catalog.currentOf(badgeOf("RECORD_10"), below, false))
                .as("统计量 37 超过门槛 10，取门槛")
                .isEqualTo(10);
        assertThat(catalog.currentOf(badgeOf("RECORD_100"), below, false))
                .as("统计量 37 未及门槛 100，取统计量")
                .isEqualTo(37);
        assertThat(catalog.currentOf(badgeOf("STREAK_7"), below, false)).isEqualTo(3);
        assertThat(catalog.currentOf(badgeOf("DAYS_100"), below, false)).isEqualTo(12);
        assertThat(catalog.currentOf(badgeOf("BUDGET_MET"), below, false))
                .as("存在型口径映射为 0 / 1")
                .isZero();
        assertThat(catalog.currentOf(badgeOf("INVITE_1"), new GrowthFacts(0L, 0, 0, false, true), false))
                .isEqualTo(1);
    }

    /** 结果恒落在 {@code [0, target]}：脏数据导致的负统计量钳到 0，超大统计量钳到门槛。 */
    @Test
    void currentOfAlwaysFallsInsideZeroToTarget() {
        List<GrowthFacts> samples = List.of(
                GrowthFacts.EMPTY,
                new GrowthFacts(1L, 1, 1, false, false),
                new GrowthFacts(999L, 29, 99, true, true),
                new GrowthFacts(Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, true, true),
                new GrowthFacts(-5L, -1, -100, false, false),
                new GrowthFacts(Long.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, false, false));

        for (GrowthFacts facts : samples) {
            for (BadgeDef badge : catalog.badges()) {
                for (boolean unlocked : new boolean[] {true, false}) {
                    assertThat(catalog.currentOf(badge, facts, unlocked))
                            .as("%s / unlocked=%s / %s", badge.code(), unlocked, facts)
                            .isBetween(0, badge.target());
                }
            }
        }
    }

    @Test
    void negativeMetricIsClampedToZeroInsteadOfLeakingToClients() {
        GrowthFacts dirty = new GrowthFacts(-7L, -3, -1, false, false);

        assertThat(catalog.currentOf(badgeOf("RECORD_100"), dirty, false)).isZero();
        assertThat(catalog.currentOf(badgeOf("STREAK_30"), dirty, false)).isZero();
        assertThat(catalog.currentOf(badgeOf("DAYS_100"), dirty, false)).isZero();
        assertThat(catalog.qualified(dirty)).isEmpty();
    }

    // ---- 辅助 ----

    private BadgeDef badgeOf(String code) {
        return catalog.badges().stream()
                .filter(b -> b.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("清单中不存在编码 " + code));
    }

    private static void assertBadge(BadgeDef actual, String code, String name, int target, BadgeMetric metric) {
        assertThat(actual.code()).isEqualTo(code);
        assertThat(actual.name()).as("%s 的展示名称", code).isEqualTo(name);
        assertThat(actual.target()).as("%s 的门槛", code).isEqualTo(target);
        assertThat(actual.metric()).as("%s 的统计口径", code).isEqualTo(metric);
    }
}
