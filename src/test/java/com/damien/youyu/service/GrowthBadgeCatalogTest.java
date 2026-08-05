package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GrowthBadgeCatalog} 的示例/边界单元测试（关联需求 1.1、1.2、1.4、1.7、1.8、2.10、6.4）。
 *
 * <p>纯组件、无外部依赖，故不起 Spring 上下文。三组用例分别锁住：</p>
 *
 * <ul>
 *   <li><b>清单本身</b>：16 枚成就的编码 / 中文名 / 描述 / 分类 / 门槛 / 统计口径 / 展示顺序逐条断言，
 *       且两次调用顺序恒相同（需求 1.1、1.7）。这里刻意把取值抄一遍，
 *       因为本清单是单一事实源，任何一处漂移都应当在这里失败；
 *       既有 9 枚的编码 / 名称 / 门槛与 {@code V32} 时期逐项相同（需求 1.4）。</li>
 *   <li><b>{@code BADGE:} 独占命名空间</b>：四个与经验事件同名的编码拼出的事件键恒带前缀；
 *       {@code BUDGET_MET} 成就的解锁条件只看 {@code event_type = 'BUDGET_MET'} 的行，
 *       不看 {@code BADGE:BUDGET_MET} 行（需求 2.10）。</li>
 *   <li><b>分类排布</b>：同分类连续出现、分类首现顺序为
 *       {@code START/STREAK/VOLUME/SOCIAL/THEME}（需求 1.8）。</li>
 *   <li><b>当前值的 min 与 clamp</b>：已解锁恒等于门槛、未解锁取较小者、结果恒落在
 *       {@code [0, target]}，负统计量钳到 0、{@code Long.MAX_VALUE} 不溢出（需求 6.4）。</li>
 * </ul>
 *
 * <p>清单常量的结构约束（项数 / 唯一性 / 长度 / 门槛区间 / 分类连续）由
 * {@link AchievementCatalogSelfCheckTest} 从缺陷清单的一侧覆盖，两个测试类合起来才是需求 1.13 的完整回归。</p>
 */
class GrowthBadgeCatalogTest {

    private GrowthBadgeCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new GrowthBadgeCatalog();
    }

    // ---- 清单：编码 / 名称 / 描述 / 分类 / 门槛 / 口径 / 顺序（需求 1.1、1.2、1.8）----

    @Test
    void badgesAreSixteenDefinitionsInFixedOrder() {
        List<BadgeDef> badges = catalog.badges();

        assertThat(badges).hasSize(16);
        assertBadge(badges.get(0), "FIRST_RECORD", "开张", "记下第 1 笔账，从今天开始",
                AchievementCategory.START, 1, BadgeMetric.RECORD_COUNT);
        assertBadge(badges.get(1), "STREAK_7", "七日不辍", "连续记账满 7 天",
                AchievementCategory.STREAK, 7, BadgeMetric.MAX_STREAK);
        assertBadge(badges.get(2), "STREAK_30", "卅日成习", "连续记账满 30 天，习惯已成",
                AchievementCategory.STREAK, 30, BadgeMetric.MAX_STREAK);
        assertBadge(badges.get(3), "STREAK_100", "百日不辍", "连续记账满 100 天",
                AchievementCategory.STREAK, 100, BadgeMetric.MAX_STREAK);
        assertBadge(badges.get(4), "STREAK_365", "岁岁有余", "连续记账满 365 天，整整一年",
                AchievementCategory.STREAK, 365, BadgeMetric.MAX_STREAK);
        assertBadge(badges.get(5), "RECORD_10", "小有账目", "累计记账满 10 笔",
                AchievementCategory.VOLUME, 10, BadgeMetric.RECORD_COUNT);
        assertBadge(badges.get(6), "RECORD_100", "百笔有余", "累计记账满 100 笔",
                AchievementCategory.VOLUME, 100, BadgeMetric.RECORD_COUNT);
        assertBadge(badges.get(7), "RECORD_500", "五百笔在册", "累计记账满 500 笔",
                AchievementCategory.VOLUME, 500, BadgeMetric.RECORD_COUNT);
        assertBadge(badges.get(8), "RECORD_1000", "千笔如一", "累计记账满 1000 笔",
                AchievementCategory.VOLUME, 1000, BadgeMetric.RECORD_COUNT);
        assertBadge(badges.get(9), "DAYS_100", "百日记账", "累计记账天数满 100 天",
                AchievementCategory.VOLUME, 100, BadgeMetric.TOTAL_DAYS);
        assertBadge(badges.get(10), "INVITE_1", "同行有余", "成功邀请第 1 位好友加入",
                AchievementCategory.SOCIAL, 1, BadgeMetric.FIRST_INVITE_EVENT);
        assertBadge(badges.get(11), "COLLAB_1", "共账之始", "第 1 位成员加入你的账本",
                AchievementCategory.SOCIAL, 1, BadgeMetric.COLLAB_MEMBER_COUNT);
        assertBadge(badges.get(12), "BUDGET_MET", "预算达标", "首次在一个月内守住预算",
                AchievementCategory.THEME, 1, BadgeMetric.BUDGET_MET_COUNT);
        assertBadge(badges.get(13), "BUDGET_MASTER", "预算达人", "累计 3 个月达成预算",
                AchievementCategory.THEME, 3, BadgeMetric.BUDGET_MET_COUNT);
        assertBadge(badges.get(14), "SAVING_MASTER", "储蓄达人", "累计 3 个月存下两成收入",
                AchievementCategory.THEME, 3, BadgeMetric.SAVING_MONTH_COUNT);
        assertBadge(badges.get(15), "TRAVEL_MASTER", "旅行达人", "旅行支出累计满 10 笔",
                AchievementCategory.THEME, 10, BadgeMetric.TRAVEL_RECORD_COUNT);
    }

    /** 展示顺序恒定：两次调用得到逐元素相同的序列，且列表不可修改（需求 1.7）。 */
    @Test
    void badgeOrderIsStableAcrossCallsAndListIsImmutable() {
        List<String> first = catalog.badges().stream().map(BadgeDef::code).toList();
        List<String> second = catalog.badges().stream().map(BadgeDef::code).toList();

        assertThat(second).containsExactlyElementsOf(first);
        assertThat(new GrowthBadgeCatalog().badges().stream().map(BadgeDef::code).toList())
                .as("无状态单例，另一个实例的顺序也相同")
                .containsExactlyElementsOf(first);
        assertThatThrownBy(() -> catalog.badges().add(new BadgeDef(
                "X", "X", "XXXXXX", AchievementCategory.START, 1, BadgeMetric.RECORD_COUNT)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void badgeCodesAreUnique() {
        assertThat(catalog.badges().stream().map(BadgeDef::code).distinct().count()).isEqualTo(16);
        assertThat(catalog.badges().stream().map(BadgeDef::name).distinct().count()).isEqualTo(16);
        assertThat(catalog.badges().stream().map(BadgeDef::description).distinct().count()).isEqualTo(16);
    }

    /**
     * 同一分类的成就连续出现（需求 1.8）。
     *
     * <p>断言方式与「按分类分组后组内计数相等」不同：这里逐项扫描，一旦某个分类在被别的分类
     * 中断之后又出现即失败。分组计数会漏掉 {@code START, STREAK, START} 这种交错排布，
     * 而交错会让成就页的分组渲染（按首现顺序分组）把同一分类拆成两组。</p>
     */
    @Test
    void badgesOfTheSameCategoryAppearContiguously() {
        List<BadgeDef> badges = catalog.badges();
        Set<AchievementCategory> closed = new LinkedHashSet<>();
        AchievementCategory previous = null;

        for (int i = 0; i < badges.size(); i++) {
            AchievementCategory current = badges.get(i).category();
            if (current != previous) {
                assertThat(closed)
                        .as("第 %d 项（%s）的分类 %s 被别的分类中断后又出现", i + 1, badges.get(i).code(), current)
                        .doesNotContain(current);
                if (previous != null) {
                    closed.add(previous);
                }
                previous = current;
            }
        }

        assertThat(closed).as("共 5 个分类，最后一个分类不进 closed").hasSize(4);
    }

    /** 分类首现顺序恒为 START → STREAK → VOLUME → SOCIAL → THEME（需求 1.8）。 */
    @Test
    void categoryFirstAppearanceOrderIsStartStreakVolumeSocialTheme() {
        List<AchievementCategory> firstAppearance = new ArrayList<>();
        for (BadgeDef badge : catalog.badges()) {
            if (!firstAppearance.contains(badge.category())) {
                firstAppearance.add(badge.category());
            }
        }

        assertThat(firstAppearance).containsExactly(
                AchievementCategory.START,
                AchievementCategory.STREAK,
                AchievementCategory.VOLUME,
                AchievementCategory.SOCIAL,
                AchievementCategory.THEME);
        assertThat(firstAppearance)
                .as("首现顺序即枚举声明顺序")
                .containsExactly(AchievementCategory.values());
    }

    /**
     * 既有 9 枚成就的编码 / 展示名称 / 门槛数值与 {@code V32}（growth-level-system）时期逐项相同
     * （需求 1.4、1.6）。
     *
     * <p>取值刻意在这里再抄一遍 {@code V32} 时期的字面量，而不是从当前清单派生：
     * 已解锁用户的 {@code BADGE:<编码>} 行早就落库了，改编码等于让那些行变成需求 1.12 的
     * 「未知 BADGE 行」而被忽略——成就墙上会凭空少一枚，且没有任何报错。
     * 改名称或门槛虽不丢数据，但会让用户看到与当初解锁时不同的说法。</p>
     */
    @Test
    void legacyNineBadgesKeepTheirV32CodeNameAndTarget() {
        record Legacy(String code, String name, int target) {
        }
        List<Legacy> v32 = List.of(
                new Legacy("FIRST_RECORD", "开张", 1),
                new Legacy("RECORD_10", "小有账目", 10),
                new Legacy("RECORD_100", "百笔有余", 100),
                new Legacy("RECORD_1000", "千笔如一", 1000),
                new Legacy("STREAK_7", "七日不辍", 7),
                new Legacy("STREAK_30", "卅日成习", 30),
                new Legacy("DAYS_100", "百日记账", 100),
                new Legacy("BUDGET_MET", "预算达标", 1),
                new Legacy("INVITE_1", "同行有余", 1));

        for (Legacy legacy : v32) {
            BadgeDef actual = badgeOf(legacy.code());
            assertThat(actual.name()).as("%s 的展示名称与 V32 相同", legacy.code()).isEqualTo(legacy.name());
            assertThat(actual.target()).as("%s 的门槛与 V32 相同", legacy.code()).isEqualTo(legacy.target());
        }
        assertThat(catalog.badges().stream().map(BadgeDef::code))
                .as("16 枚编码是 V32 的 9 枚的超集，一个不少、一个不改名")
                .containsAll(v32.stream().map(Legacy::code).toList());
    }

    // ---- BADGE: 独占命名空间（需求 2.10）----

    /** 四个与经验事件同名的编码，事件键恒带 {@code BADGE:} 前缀。 */
    @Test
    void eventKeyOfSameNamedCodesAlwaysCarriesBadgePrefix() {
        for (String code : List.of("FIRST_RECORD", "STREAK_7", "STREAK_30", "BUDGET_MET")) {
            assertThat(GrowthBadgeCatalog.eventKeyOf(code))
                    .as("%s 的成就键", code)
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

    /** 空编码会拼出无法归属到任何成就的裸前缀键，必须在写入前失败。 */
    @Test
    void eventKeyOfRejectsBlankCode() {
        assertThatThrownBy(() -> GrowthBadgeCatalog.eventKeyOf(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrowthBadgeCatalog.eventKeyOf("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code BUDGET_MET} 成就的条件只看 {@code event_type = 'BUDGET_MET'} 的经验事件行。
     *
     * <p>{@code unlocked = true} 表示库里已存在 {@code BADGE:BUDGET_MET} 行；
     * 若把该行当成解锁条件，判定就会自我循环。这里断言：已解锁但经验事件不存在时，
     * 条件<b>仍未</b>成立，即 {@code qualified} 不含该编码——{@code BADGE:} 行没有参与判定。</p>
     */
    @Test
    void budgetMetBadgeConditionReadsOnlyBudgetMetEventRow() {
        BadgeDef budgetMet = badgeOf("BUDGET_MET");
        assertThat(budgetMet.metric()).isEqualTo(BadgeMetric.BUDGET_MET_COUNT);

        // 记账笔数拉满，但预算达成月数为 0：条件不成立。
        GrowthFacts withoutEvent = new GrowthFacts(5000L, 60, 200, 0L, true, 0L, 0L, 0L);
        assertThat(catalog.qualified(withoutEvent)).doesNotContain("BUDGET_MET", "BUDGET_MASTER");
        assertThat(catalog.currentOf(budgetMet, withoutEvent, false)).isZero();

        // 已存在 BADGE:BUDGET_MET 行（unlocked=true）不改变条件判定结果。
        assertThat(catalog.qualified(withoutEvent))
                .as("BADGE: 行不参与条件判定")
                .doesNotContain("BUDGET_MET");
        assertThat(catalog.currentOf(budgetMet, withoutEvent, true))
                .as("已解锁的当前值仍等于门槛")
                .isEqualTo(1);

        GrowthFacts withEvent = new GrowthFacts(0L, 0, 0, 1L, false, 0L, 0L, 0L);
        assertThat(catalog.qualified(withEvent)).contains("BUDGET_MET");
        assertThat(catalog.qualified(withEvent))
                .as("门槛 3 的 BUDGET_MASTER 只达成 1 个月时不解锁")
                .doesNotContain("BUDGET_MASTER");
        assertThat(catalog.qualified(withEvent))
                .as("INVITE_1 只看 FIRST_INVITE 行，与预算事件无关")
                .doesNotContain("INVITE_1");
    }

    /** 门槛取等号即解锁（判定一律「大于或等于」）。 */
    @Test
    void qualifiedIncludesBadgesAtExactlyTheTarget() {
        GrowthFacts facts = new GrowthFacts(10L, 7, 100, 0L, false, 3L, 1L, 10L);

        assertThat(catalog.qualified(facts))
                .containsExactly("FIRST_RECORD", "STREAK_7", "RECORD_10", "DAYS_100",
                        "COLLAB_1", "SAVING_MASTER", "TRAVEL_MASTER");
    }

    @Test
    void qualifiedTreatsNullFactsAsEmpty() {
        assertThat(catalog.qualified(null)).isEmpty();
        assertThat(catalog.qualified(GrowthFacts.EMPTY)).isEmpty();
    }

    // ---- 当前值的 min 与 clamp（需求 6.4）----

    /** 已解锁恒等于门槛，即使统计量已回落到 0（删除交易、改名「旅行」分类不应让进度回退）。 */
    @Test
    void currentOfUnlockedBadgeAlwaysEqualsTarget() {
        for (BadgeDef badge : catalog.badges()) {
            assertThat(catalog.currentOf(badge, GrowthFacts.EMPTY, true))
                    .as("%s 已解锁", badge.code())
                    .isEqualTo(badge.target());
            assertThat(catalog.currentOf(badge, null, true))
                    .as("%s 已解锁且事实为 null", badge.code())
                    .isEqualTo(badge.target());
        }
    }

    /** 未解锁取 min(统计量, 门槛)。 */
    @Test
    void currentOfLockedBadgeTakesMinOfMetricAndTarget() {
        GrowthFacts below = new GrowthFacts(37L, 3, 12, 0L, false, 2L, 0L, 4L);
        assertThat(catalog.currentOf(badgeOf("RECORD_10"), below, false))
                .as("统计量 37 超过门槛 10，取门槛")
                .isEqualTo(10);
        assertThat(catalog.currentOf(badgeOf("RECORD_100"), below, false))
                .as("统计量 37 未及门槛 100，取统计量")
                .isEqualTo(37);
        assertThat(catalog.currentOf(badgeOf("STREAK_7"), below, false)).isEqualTo(3);
        assertThat(catalog.currentOf(badgeOf("STREAK_365"), below, false)).isEqualTo(3);
        assertThat(catalog.currentOf(badgeOf("DAYS_100"), below, false)).isEqualTo(12);
        assertThat(catalog.currentOf(badgeOf("SAVING_MASTER"), below, false)).isEqualTo(2);
        assertThat(catalog.currentOf(badgeOf("TRAVEL_MASTER"), below, false)).isEqualTo(4);
        assertThat(catalog.currentOf(badgeOf("COLLAB_1"), below, false)).isZero();
        assertThat(catalog.currentOf(badgeOf("BUDGET_MET"), below, false))
                .as("预算达成月数为 0")
                .isZero();
        assertThat(catalog.currentOf(badgeOf("INVITE_1"),
                new GrowthFacts(0L, 0, 0, 0L, true, 0L, 0L, 0L), false))
                .as("存在型口径映射为 0 / 1")
                .isEqualTo(1);
    }

    /** 结果恒落在 {@code [0, target]}：脏数据导致的负统计量钳到 0，超大统计量钳到门槛。 */
    @Test
    void currentOfAlwaysFallsInsideZeroToTarget() {
        List<GrowthFacts> samples = List.of(
                GrowthFacts.EMPTY,
                new GrowthFacts(1L, 1, 1, 0L, false, 1L, 1L, 1L),
                new GrowthFacts(999L, 29, 99, 2L, true, 2L, 0L, 9L),
                new GrowthFacts(Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Long.MAX_VALUE, true, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                new GrowthFacts(-5L, -1, -100, -1L, false, -3L, -7L, -9L),
                new GrowthFacts(Long.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                        Long.MIN_VALUE, false, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE));

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

    /**
     * 统计量取 {@code Long.MAX_VALUE} 时结果恰等于门槛，且不因 {@code long → int} 而溢出成负数。
     *
     * <p>钳制必须先在 {@code long} 域内取 {@code min}、再转 {@code int}：
     * 若先把 {@code Long.MAX_VALUE} 强转成 {@code int} 再取 {@code min}，会得到 {@code -1}，
     * 前端就会渲染出「-1 / 1000」。这条用例专门锁住那个顺序。</p>
     */
    @Test
    void currentOfDoesNotOverflowOnLongMaxValueMetrics() {
        GrowthFacts huge = new GrowthFacts(Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Long.MAX_VALUE, true, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

        for (BadgeDef badge : catalog.badges()) {
            assertThat(catalog.currentOf(badge, huge, false))
                    .as("%s 在 Long.MAX_VALUE 下恰等于门槛且非负", badge.code())
                    .isEqualTo(badge.target())
                    .isNotNegative();
        }
        assertThat(catalog.qualified(huge))
                .as("统计量拉满时 16 枚全部达成条件")
                .hasSize(16);
    }

    @Test
    void negativeMetricIsClampedToZeroInsteadOfLeakingToClients() {
        GrowthFacts dirty = new GrowthFacts(-7L, -3, -1, -2L, false, -4L, -5L, -6L);

        assertThat(catalog.currentOf(badgeOf("RECORD_100"), dirty, false)).isZero();
        assertThat(catalog.currentOf(badgeOf("STREAK_30"), dirty, false)).isZero();
        assertThat(catalog.currentOf(badgeOf("DAYS_100"), dirty, false)).isZero();
        assertThat(catalog.currentOf(badgeOf("TRAVEL_MASTER"), dirty, false)).isZero();
        assertThat(catalog.qualified(dirty)).isEmpty();
    }

    // ---- 辅助 ----

    private BadgeDef badgeOf(String code) {
        return catalog.badges().stream()
                .filter(b -> b.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("清单中不存在编码 " + code));
    }

    private static void assertBadge(BadgeDef actual, String code, String name, String description,
                                    AchievementCategory category, int target, BadgeMetric metric) {
        assertThat(actual.code()).isEqualTo(code);
        assertThat(actual.name()).as("%s 的展示名称", code).isEqualTo(name);
        assertThat(actual.description()).as("%s 的描述", code).isEqualTo(description);
        assertThat(actual.category()).as("%s 的分类", code).isEqualTo(category);
        assertThat(actual.target()).as("%s 的门槛", code).isEqualTo(target);
        assertThat(actual.metric()).as("%s 的统计口径", code).isEqualTo(metric);
    }
}
