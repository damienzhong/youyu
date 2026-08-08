package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.service.AchievementListResponse;
import com.damien.youyu.service.AchievementQueryService;
import com.damien.youyu.service.AchievementView;
import com.damien.youyu.service.BadgeView;
import com.damien.youyu.service.GrowthOverviewResponse;
import com.damien.youyu.service.GrowthQueryService;
import com.damien.youyu.service.GrowthSettlementService;
import com.damien.youyu.service.SettleOutcome;
import com.damien.youyu.service.TriggerSource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 6：概览徽章列表与成就清单逐项相等</b>的属性测试（任务 8.3）。
 *
 * <p><i>对任意</i>用户状态 ∈ {零数据, 随机解锁子集, 全部解锁, 结算被节流后的间隙态,
 * 结算失败后的间隙态} × 两种调用顺序（先概览后清单 / 先清单后概览，两次之间无新解锁），断言：</p>
 *
 * <ul>
 *   <li>成长概览响应的徽章列表<b>第 N 项</b>与成就清单响应的成就视图<b>第 N 项</b>（N ∈ [1, 16]）在
 *       <b>成就编码、展示名称、是否已解锁、解锁时刻、门槛数值、当前值</b>六项上逐项相等，
 *       且该相等对<b>已解锁项与未解锁项同时成立</b>（需求 12.3）；</li>
 *   <li>概览顶层仍恰好 15 项、徽章项仍恰好 6 项（需求 12.1，用记录组件反射机械断言）；</li>
 *   <li>两个列表恒 16 项、顺序恒为需求 1.1 表格的序号顺序（需求 12.2）。</li>
 * </ul>
 *
 * <h2>「两次之间无新解锁」怎么保证，而不是靠运气</h2>
 *
 * <p>需求 12.3 的前提是两次请求之间该用户没有新解锁的成就，而<b>两条路径都是写入型 GET</b>
 * （各自内含一次 {@code OVERVIEW} 结算）。若第一次调用的结算刚好补写了一枚 {@code BADGE}，第二次调用
 * 自然会多看见一枚，属性就会在一个与被测无关的原因上变红。因此每个状态在比对之前一律先做一次
 * <b>预热调用</b>（{@link #warmUp}）：预热的结算把该状态下全部已达门槛的成就一次落库，此后事实源不再
 * 改变，两次比对调用的结算便无事可做。间隙态刻意反其道而行——预热之后<b>再</b>改事实源（补到 100 笔），
 * 而结算又被节流 / 被注入异常挡住，于是「条件已成立但 {@code BADGE} 行尚未写入」这个最容易让两条路径
 * 错开的状态被稳定地造出来。</p>
 *
 * <h2>为什么间隙态是这条属性的关键</h2>
 *
 * <p>已解锁项的六项相等，只要两条路径都从 {@code growth_events} 读同一行就成立，几乎测不出分歧；
 * 真正区分「共用同一份快照」与「各自组装 facts 碰巧对上」的是间隙态下<b>未解锁项的当前值</b>：
 * {@code RECORD_100} 此刻的事实是 100 笔、门槛也是 100，但 {@code BADGE} 行不存在，两条路径必须
 * 同时给出「未解锁 + 当前值 100 + 空解锁时刻」。任何一条路径自行查库算 facts，就会在这一项上错开。
 * 因此两个间隙态下额外断言这一项（{@link #assertGapStateOnRecordHundred}）。</p>
 *
 * <h2>驱动方式与清理</h2>
 *
 * <p>两条读取路径都在结算的<b>事务边界之外</b>（各自 {@code catch} 掉结算异常），而结算本身带
 * {@code @Transactional(REQUIRES_NEW)} 需要真实提交才能被观察到，故本类<b>不用测试级事务包裹</b>；
 * 清理不靠回滚，由 {@link #resetState()} 每次迭代前显式清表，并用全局自增序号 {@link #SEQ} 保证
 * {@code userId} / {@code ledgerId} / 分类 id 全局唯一（双重隔离——节流器是进程内单例、没有清理入口，
 * 每次迭代换新 {@code userId} 才能让「首次请求必放行」成立）。时钟用 {@code @Primary} 的可推进
 * {@link MutableClock}（固定 {@code Asia/Shanghai} 的 {@code 2025-06-15 08:00}）：非间隙态在每次调用前
 * 推进 61 秒以越过 60 秒 / 10 秒两个节流窗口，节流间隙态则<b>刻意不推进</b>，使 10 秒窗口必然命中。
 * jqwik 属性方法不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 手工完成（上下文缓存复用）。</p>
 *
 * <p>请求走<b>服务层</b>（{@link GrowthQueryService#getOverview} 与
 * {@link AchievementQueryService#getAchievements}）而不是 HTTP：属性方法要跑几十次迭代，
 * 每次都真实建号 + 发码登录会把耗时抬到分钟级，而 Property 6 的不变式落在这两个响应对象上，
 * 与过滤链、序列化无关。HTTP 层的字段集与 JSON 形状由 {@code AchievementOverviewParityIntegrationTest}
 * 与 {@code AchievementApiContractIntegrationTest}（任务 7.4、6.2）覆盖。</p>
 *
 * <p>Feature: achievement-system, Property 6: 概览徽章列表与成就清单逐项相等</p>
 *
 * <p>Validates: Requirements 12.1, 12.2, 12.3</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-parity-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import({AchievementOverviewParityPropertyTest.ClockConfig.class,
        AchievementOverviewParityPropertyTest.ProbeConfig.class})
class AchievementOverviewParityPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 2025-06-15 08:00（Asia/Shanghai）：判定日恒为 2025-06-15，全程不越自然日。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 越过记账侧 60 秒与概览侧 10 秒两个节流窗口的推进量。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);

    /** 全部交易的 {@code created_at} / {@code occurred_at} 一律取昨天（日历只多出这一天）。 */
    private static final LocalDate YESTERDAY = LocalDate.of(2025, 6, 14);

    /** 16 枚成就的编码与展示顺序（需求 1.1 表格的独立副本，不从被测清单取）。 */
    private static final List<String> CATALOG_CODES = List.of(
            "FIRST_RECORD",
            "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365",
            "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000", "DAYS_100",
            "INVITE_1", "COLLAB_1",
            "BUDGET_MET", "BUDGET_MASTER", "SAVING_MASTER", "TRAVEL_MASTER");

    private static final int TOTAL_ACHIEVEMENTS = 16;

    /** 成长概览顶层字段数（growth-level-system 需求 10.1；本 spec 需求 12.1 要求一字不改）。 */
    private static final int OVERVIEW_TOP_FIELDS = 15;

    /** 徽章列表项字段数（需求 12.1：不得新增描述 / 分类 / 口径 / 事件 id 四项中的任何一项）。 */
    private static final int BADGE_FIELDS = 6;

    /** 成就视图字段数（需求 6.2：恰好 9 项）。 */
    private static final int ACHIEVEMENT_VIEW_FIELDS = 9;

    /** 间隙态用来暴露分歧的那一枚成就：门槛 100，事实恰好补到 100 笔而 {@code BADGE} 行未写入。 */
    private static final String GAP_CODE = "RECORD_100";
    private static final int GAP_TARGET = 100;

    /**
     * 在本类播种的事实源下恒不可能解锁的成就：门槛 365 个连续记账日，而全部交易都落在同一天
     * （{@code max_streak} 恒为 1）。用于让「部分解锁」状态构造性地留下至少一枚未解锁项。
     */
    private static final String ALWAYS_LOCKED_CODE = "STREAK_365";

    /**
     * 穷举 10 个「状态 × 调用顺序」组合时使用的固定预置解锁子集（三个不同分类的成就）。
     *
     * <p>刻意含 {@code STREAK_7} 与 {@code DAYS_100} 这类「事实源撑不到门槛却已解锁」的项：
     * 需求 2.3 说成就不可撤销，因此它们必须仍显示为已解锁且当前值等于门槛——这是「已解锁恒等于
     * {@code target}」这条钳制在两条路径上都成立的直接考验。</p>
     */
    private static final Set<String> FIXED_SUBSET = Set.of("STREAK_7", "DAYS_100", "TRAVEL_MASTER");

    /** 交易直插语句：列顺序与 {@link #txRow} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 预置 {@code BADGE} 行的解锁时刻基准：逐枚差 1 分钟，使「解锁时刻」在两条路径上可逐枚区分。 */
    private static final LocalDateTime BADGE_AT_BASE = LocalDateTime.of(2024, 1, 2, 3, 4, 5);

    /** 全局自增序号：保证跨迭代 userId / ledgerId / 分类 id 全局唯一（清理不靠回滚，节流器不可清理）。 */
    private static final AtomicLong SEQ = new AtomicLong(640_000_000L);

    @Autowired
    private GrowthQueryService growthQueryService;
    @Autowired
    private AchievementQueryService achievementQueryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ProbeSettlementService probe;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(AchievementOverviewParityPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        probe.reset();
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删事实源与三张成长 / 成就表（各表间无外键）。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM ledger_members");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM categories");
    }

    // ---------------- 生成器 ----------------

    /** 五种用户状态（design.md「Property 6」的生成器维度一）。 */
    enum State {
        /** 零数据新用户：16 项全未解锁，相等性只在未解锁项上被考验。 */
        ZERO_DATA,
        /** 随机解锁子集：已解锁与未解锁两类项同时出现。 */
        PARTIAL_UNLOCK,
        /** 全部解锁：16 项均已解锁，当前值恒等于门槛、解锁时刻逐枚不同。 */
        FULL_UNLOCK,
        /** 结算被 10 秒窗口节流的间隙态：条件已成立而 {@code BADGE} 行尚未写入。 */
        THROTTLED_GAP,
        /** 结算失败的间隙态：两条路径各自吞掉异常后照常返回。 */
        FAILED_GAP
    }

    /**
     * 预置 {@code BADGE} 行的随机子集（1–15 枚）。
     *
     * <p>取值空间刻意<b>排除 {@link #ALWAYS_LOCKED_CODE}</b>：{@code PARTIAL_UNLOCK} 状态只播 10 笔
     * 落在同一天的记账，{@code max_streak} 恒为 1，故 {@code STREAK_365} 无论如何都解锁不了。
     * 加上这一条约束后「部分解锁」状态下已解锁数恒 ≤15，「已解锁与未解锁两类项同时存在」
     * 便是构造性成立的，而不是靠随机子集碰巧没取满——预热的结算会把
     * {@code FIRST_RECORD} / {@code RECORD_10} 一并点亮，若子集恰好是「16 枚里少了这两枚之一」，
     * 不加约束时会意外变成全解锁。</p>
     */
    @Provide
    Arbitrary<Set<String>> badgeSubsets() {
        String[] unlockable = CATALOG_CODES.stream()
                .filter(code -> !ALWAYS_LOCKED_CODE.equals(code))
                .toArray(String[]::new);
        return Arbitraries.of(unlockable).set().ofMinSize(1).ofMaxSize(unlockable.length);
    }

    // ---------------- Property 6 ----------------

    /**
     * Feature: achievement-system, Property 6: 概览徽章列表与成就清单逐项相等
     *
     * <p>按状态播种 → 预热一次（把该状态下能解锁的一次落库）→ 按给定顺序调用两条读取路径 →
     * 逐项比对六个字段。间隙态额外断言 {@code RECORD_100} 在两条路径上同为「未解锁 + 当前值 100 +
     * 空解锁时刻」，并用 {@link ProbeSettlementService} 证明这两次调用的结算<b>确实</b>被节流跳过 /
     * 确实各失败了一次，而不是碰巧没写东西。</p>
     *
     * <p>Validates: Requirements 12.1, 12.2, 12.3</p>
     */
    @Property(tries = 15)
    void property6_overviewBadgesEqualAchievementViewsItemByItem(
            @ForAll State state,
            @ForAll boolean overviewFirst,
            @ForAll("badgeSubsets") Set<String> preUnlocked) {
        runScenario(state, overviewFirst, preUnlocked);
    }

    /**
     * 五种状态 × 两种调用顺序<b>逐一走一遍</b>（10 个组合），使「五 × 二」这句话是机械保证的。
     *
     * <p>属性方法负责随机化（尤其是预置解锁子集），但随机取样并不保证 30 次迭代把 10 个组合都覆盖到
     * ——靠概率兜底的覆盖率是会随机漏掉分支的。故本用例用固定子集把 10 个组合穷举一遍，
     * 两者分工：本用例保覆盖，属性方法保广度。</p>
     *
     * <p>Validates: Requirements 12.1, 12.2, 12.3</p>
     */
    @Example
    void allFiveStatesTimesBothCallOrders_areCoveredExhaustively() {
        for (State state : State.values()) {
            for (boolean overviewFirst : List.of(true, false)) {
                runScenario(state, overviewFirst, FIXED_SUBSET);
            }
        }
    }

    /** 跑一个「状态 × 调用顺序 × 预置解锁子集」场景并复核全部不变式。 */
    private void runScenario(State state, boolean overviewFirst, Set<String> preUnlocked) {
        long userId = SEQ.getAndIncrement();
        long ledgerId = insertLedger(userId);
        boolean gapState = (state == State.THROTTLED_GAP || state == State.FAILED_GAP);

        switch (state) {
            case ZERO_DATA -> { /* 不播种任何事实源，也不预置任何 BADGE 行 */ }
            case PARTIAL_UNLOCK -> {
                seedExpenses(userId, ledgerId, 10);
                seedBadgeRows(userId, inCatalogOrder(preUnlocked));
            }
            case FULL_UNLOCK -> {
                seedExpenses(userId, ledgerId, 3);
                // 是否解锁的唯一依据就是这 16 行（需求 1.10），逐枚给不同的 created_at。
                seedBadgeRows(userId, CATALOG_CODES);
            }
            case THROTTLED_GAP, FAILED_GAP -> seedExpenses(userId, ledgerId, 10);
            default -> throw new IllegalStateException("未覆盖的状态: " + state);
        }

        // 预热：把该状态下已达门槛的成就一次落库，使两次比对调用之间不可能再有新解锁（需求 12.3 的前提）。
        warmUp(userId);

        if (gapState) {
            // 预热之后再改事实源：RECORD_100 的条件成立（10 + 90 = 100 笔），但 BADGE 行尚未写入。
            seedExpenses(userId, ledgerId, 90);
        }
        probe.reset();
        if (state == State.FAILED_GAP) {
            probe.throwOnSettle(new IllegalStateException("注入：结算失败"));
        }

        // ── 两种调用顺序：两次之间没有任何事实源变更，也没有任何新解锁 ─────────────────────────
        GrowthOverviewResponse overview;
        AchievementListResponse list;
        if (overviewFirst) {
            overview = callOverview(userId, state);
            list = callList(userId, state);
        } else {
            list = callList(userId, state);
            overview = callOverview(userId, state);
        }

        String label = state + " / " + (overviewFirst ? "先概览后清单" : "先清单后概览");
        int unlockedCount = assertParity(label, overview, list);

        // 每个状态确实覆盖了它该覆盖的解锁 / 未解锁组合，否则属性沦为「只测了一种形状」。
        switch (state) {
            case ZERO_DATA -> assertThat(unlockedCount).as(label + " / 零数据用户 16 项全未解锁").isZero();
            case PARTIAL_UNLOCK -> {
                assertThat(unlockedCount)
                        .as(label + " / 部分解锁：已解锁与未解锁两类项同时存在")
                        .isBetween(1, TOTAL_ACHIEVEMENTS - 1);
                assertThat(badgeRowCount(userId, ALWAYS_LOCKED_CODE))
                        .as(label + " / " + ALWAYS_LOCKED_CODE + " 在本类的事实源下恒不可解锁").isZero();
            }
            case FULL_UNLOCK -> assertThat(unlockedCount)
                    .as(label + " / 全解锁").isEqualTo(TOTAL_ACHIEVEMENTS);
            case THROTTLED_GAP -> {
                assertThat(probe.outcomes())
                        .as(label + " / 两次调用的结算均被 10 秒窗口跳过（需求 12.9）")
                        .containsExactly(SettleOutcome.SKIPPED_THROTTLED, SettleOutcome.SKIPPED_THROTTLED);
                assertGapStateOnRecordHundred(label, userId, overview, list);
            }
            case FAILED_GAP -> {
                assertThat(probe.settleCalls())
                        .as(label + " / 两条读取路径各尝试了一次结算并各自失败").isEqualTo(2);
                assertGapStateOnRecordHundred(label, userId, overview, list);
            }
            default -> throw new IllegalStateException("未覆盖的状态: " + state);
        }
    }

    // ---------------- 断言 ----------------

    /**
     * 概览徽章列表第 N 项与成就清单第 N 项在六项上逐项相等（需求 12.3），并断言两个响应的形状不变
     * （需求 12.1、12.2）。
     *
     * @return 已解锁项个数，供调用方断言该状态确实覆盖了预期的解锁 / 未解锁组合
     */
    private int assertParity(String label, GrowthOverviewResponse overview, AchievementListResponse list) {
        // 字段集用记录组件反射机械断言：新增 / 删除 / 改名任何一项都会让这里变红（需求 12.1、6.2）。
        assertThat(GrowthOverviewResponse.class.getRecordComponents())
                .as("成长概览顶层恰好 %d 项（需求 12.1）", OVERVIEW_TOP_FIELDS).hasSize(OVERVIEW_TOP_FIELDS);
        assertThat(recordComponentNames(BadgeView.class))
                .as("徽章项恰好 6 项且一项不改名（需求 12.1）")
                .containsExactly("code", "name", "unlocked", "unlockedAt", "target", "current");
        assertThat(recordComponentNames(BadgeView.class)).hasSize(BADGE_FIELDS);
        assertThat(AchievementView.class.getRecordComponents())
                .as("成就视图恰好 9 项（需求 6.2）").hasSize(ACHIEVEMENT_VIEW_FIELDS);

        List<BadgeView> badges = overview.badges();
        List<AchievementView> views = list.achievements();
        assertThat(badges).as(label + " / 徽章列表恒 16 项（需求 12.2）").hasSize(TOTAL_ACHIEVEMENTS);
        assertThat(views).as(label + " / 成就视图恒 16 项（需求 6.1）").hasSize(TOTAL_ACHIEVEMENTS);
        assertThat(badges.stream().map(BadgeView::code).toList())
                .as(label + " / 徽章顺序即清单序号 1..16（需求 12.2）").isEqualTo(CATALOG_CODES);
        assertThat(views.stream().map(AchievementView::code).toList())
                .as(label + " / 成就视图顺序即清单序号 1..16（需求 1.7）").isEqualTo(CATALOG_CODES);

        int unlockedCount = 0;
        for (int i = 0; i < TOTAL_ACHIEVEMENTS; i++) {
            BadgeView badge = badges.get(i);
            AchievementView view = views.get(i);
            String at = label + " / 第 " + (i + 1) + " 项（" + badge.code() + "）";

            // 六项逐项相等（需求 12.3），对已解锁项与未解锁项同时成立。
            assertThat(badge.code()).as(at + " 的成就编码").isEqualTo(view.code());
            assertThat(badge.name()).as(at + " 的展示名称").isEqualTo(view.name());
            assertThat(badge.unlocked()).as(at + " 的是否已解锁").isEqualTo(view.unlocked());
            assertThat(badge.unlockedAt()).as(at + " 的解锁时刻").isEqualTo(view.unlockedAt());
            assertThat(badge.target()).as(at + " 的门槛数值").isEqualTo(view.target());
            assertThat(badge.current()).as(at + " 的当前值").isEqualTo(view.current());

            // 当前值的两条硬约束（需求 3.13、2.13）：落在 [0, target]，已解锁恒等于 target。
            assertThat(badge.current()).as(at + " 的当前值落在 [0, " + badge.target() + "]")
                    .isBetween(0, badge.target());
            if (badge.unlocked()) {
                unlockedCount++;
                assertThat(badge.unlockedAt()).as(at + " 已解锁 → 解锁时刻非空").isNotNull();
                assertThat(badge.current()).as(at + " 已解锁 → 当前值等于门槛").isEqualTo(badge.target());
            } else {
                assertThat(badge.unlockedAt()).as(at + " 未解锁 → 解锁时刻为空").isNull();
            }
        }

        assertThat(list.unlockedCount())
                .as(label + " / 已解锁成就数等于列表中已解锁项个数（需求 6.5）").isEqualTo(unlockedCount);
        assertThat(list.total()).as(label + " / 成就总数恒 16（需求 6.1）").isEqualTo(TOTAL_ACHIEVEMENTS);
        return unlockedCount;
    }

    /**
     * 间隙态的可观察证据：{@code RECORD_100} 的条件已成立（累计 100 笔）但 {@code BADGE} 行尚未写入，
     * 两条路径必须同时给出「未解锁 + 当前值等于门槛 + 空解锁时刻」。
     *
     * <p>这是唯一能区分「两条路径共用同一份快照」与「各自组装 facts 碰巧对上」的一项：只要有一条路径
     * 自行查库算 facts，它与另一条在这里必然错开。</p>
     */
    private void assertGapStateOnRecordHundred(String label, long userId, GrowthOverviewResponse overview,
                                               AchievementListResponse list) {
        BadgeView badge = overview.badges().stream()
                .filter(item -> GAP_CODE.equals(item.code())).findFirst()
                .orElseThrow(() -> new AssertionError("徽章列表缺少 " + GAP_CODE));
        AchievementView view = list.achievements().stream()
                .filter(item -> GAP_CODE.equals(item.code())).findFirst()
                .orElseThrow(() -> new AssertionError("成就清单缺少 " + GAP_CODE));

        assertThat(badge.unlocked()).as(label + " / 间隙态：" + GAP_CODE + " 的 BADGE 行尚未写入").isFalse();
        assertThat(view.unlocked()).isFalse();
        assertThat(badge.current()).as(label + " / 间隙态：" + GAP_CODE + " 的当前值等于门槛")
                .isEqualTo(GAP_TARGET);
        assertThat(view.current()).isEqualTo(GAP_TARGET);
        assertThat(badge.unlockedAt()).as(label + " / 间隙态：" + GAP_CODE + " 无解锁时刻").isNull();
        assertThat(view.unlockedAt()).isNull();
        // 读库确认：断言的是「条件成立而事件未写入」，不是「事实源没到位」。
        assertThat(badgeRowCount(userId, GAP_CODE))
                .as(label + " / 间隙态：库里确实没有 " + GAP_CODE + " 的 BADGE 行").isZero();
        assertThat(validRecordCount(userId))
                .as(label + " / 间隙态：事实源确实已到 100 笔").isEqualTo(GAP_TARGET);
    }

    private static List<String> recordComponentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    // ---------------- 两条读取路径 ----------------

    /** 预热：推进时钟越过节流窗口后请求一次概览，让结算真实执行并把可解锁的成就一次落库。 */
    private void warmUp(long userId) {
        CLOCK.advance(BEYOND_THROTTLE);
        growthQueryService.getOverview(userId);
    }

    /**
     * 调用成长概览。非间隙态先推进时钟使结算真实执行；节流间隙态<b>刻意不推进</b>，
     * 让 10 秒窗口必然命中（{@code FAILED_GAP} 推不推进都一样，异常在结算入口就抛出）。
     */
    private GrowthOverviewResponse callOverview(long userId, State state) {
        advanceUnlessThrottledGap(state);
        return growthQueryService.getOverview(userId);
    }

    private AchievementListResponse callList(long userId, State state) {
        advanceUnlessThrottledGap(state);
        return achievementQueryService.getAchievements(userId);
    }

    private void advanceUnlessThrottledGap(State state) {
        if (state != State.THROTTLED_GAP) {
            CLOCK.advance(BEYOND_THROTTLE);
        }
    }

    // ---------------- 事实源播种 ----------------

    /** {@code count} 笔落在昨天的 {@code 1.00} 有效支出（{@code created_at} 决定记账日历）。 */
    private void seedExpenses(long userId, long ledgerId, int count) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(txRow(userId, ledgerId));
        }
        jdbcTemplate.batchUpdate(INSERT_TX_SQL, batch);
    }

    /** 预置 {@code BADGE} 行：逐枚不同的 {@code created_at}，{@code exp_amount} 恒 0（需求 1.11）。 */
    private void seedBadgeRows(long userId, List<String> codes) {
        for (int i = 0; i < codes.size(); i++) {
            jdbcTemplate.update(
                    "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                            + "VALUES (?, ?, ?, 0, ?)",
                    userId, GrowthEventType.BADGE, "BADGE:" + codes.get(i),
                    Timestamp.valueOf(BADGE_AT_BASE.plusMinutes(i)));
        }
    }

    /** 把随机子集按清单序号排序，使预置顺序与展示顺序一致（顺序本身不影响属性，但便于定位）。 */
    private static List<String> inCatalogOrder(Set<String> codes) {
        return CATALOG_CODES.stream().filter(codes::contains).toList();
    }

    private static Object[] txRow(long userId, long ledgerId) {
        Timestamp at = Timestamp.valueOf(YESTERDAY.atTime(12, 0));
        return new Object[] {userId, ledgerId, userId, "expense", new BigDecimal("1.00"),
                placeholderRef(userId), placeholderRef(userId), at, at, at};
    }

    /**
     * 「绝不可能是真实主键」且按用户隔离的 {@code account_id} / {@code category_id} 占位取值。
     *
     * <p>多次迭代共用同一个内存库，与真实分类主键撞号会让「旅行」判定误命中。</p>
     */
    private static long placeholderRef(long userId) {
        return 900_000_000L + userId;
    }

    private long insertLedger(long userId) {
        long ledgerId = SEQ.getAndIncrement();
        Timestamp now = Timestamp.valueOf(BADGE_AT_BASE);
        jdbcTemplate.update(
                "INSERT INTO ledgers (id, user_id, name, type, sort_order, is_default, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PERSONAL', 0, FALSE, ?, ?)",
                ledgerId, userId, "parity-" + userId, now, now);
        return ledgerId;
    }

    // ---------------- 库读取辅助 ----------------

    private long badgeRowCount(long userId, String code) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_key = ?",
                Long.class, userId, "BADGE:" + code);
        return count == null ? 0L : count;
    }

    /** 该用户的有效记账笔数（{@code created_by} 归属、未软删、账本非空）。 */
    private long validRecordCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions "
                        + "WHERE created_by = ? AND deleted_at IS NULL AND ledger_id IS NOT NULL",
                Long.class, userId);
        return count == null ? 0L : count;
    }

    // ---------------- 测试基础设施 ----------------

    /** {@code @Primary} 可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使两个节流窗口可确定性驱动。 */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    /**
     * 计数并可注入故障的 {@link GrowthSettlementService}：默认<b>委托</b>给真实（被 Spring 事务代理
     * 包裹的）bean，{@code REQUIRES_NEW} 因而照常生效。它<b>不是</b> Mockito 替身
     * ——对带 {@code @Transactional} 的类做 spy 会绕过事务代理、令 {@code REQUIRES_NEW} 失效。
     * 构造时给父类传 13 个 {@code null}：本类只覆盖 {@code settle} 并转发给 {@code delegate}，
     * 父类字段永不被触及。
     */
    static class ProbeSettlementService extends GrowthSettlementService {

        private final GrowthSettlementService delegate;
        private final AtomicInteger settleCalls = new AtomicInteger();
        private final List<SettleOutcome> outcomes = new CopyOnWriteArrayList<>();
        private volatile RuntimeException toThrow;

        ProbeSettlementService(GrowthSettlementService delegate) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            this.delegate = delegate;
        }

        @Override
        public SettleOutcome settle(Long userId, TriggerSource source) {
            settleCalls.incrementAndGet();
            RuntimeException injected = this.toThrow;
            if (injected != null) {
                throw injected;
            }
            SettleOutcome outcome = delegate.settle(userId, source);   // 经事务代理 → REQUIRES_NEW 生效
            outcomes.add(outcome);
            return outcome;
        }

        void reset() {
            settleCalls.set(0);
            outcomes.clear();
            toThrow = null;
        }

        void throwOnSettle(RuntimeException e) {
            this.toThrow = e;
        }

        int settleCalls() {
            return settleCalls.get();
        }

        List<SettleOutcome> outcomes() {
            return List.copyOf(outcomes);
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        @Primary
        ProbeSettlementService probeSettlementService(
                @Qualifier("growthSettlementService") GrowthSettlementService real) {
            return new ProbeSettlementService(real);
        }
    }

    /** 可推进、可归位的时钟（供每次迭代前 reset）。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        void reset(Instant to) {
            this.instant = to;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
