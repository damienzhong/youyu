package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.UserGrowthRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 13：徽章的命名空间隔离、当前值区间与顺序</b>的属性测试（任务 9.13）。
 *
 * <p>对<i>任意</i>成长事件集合（刻意包含 {@code event_type='BADGE'} 且
 * {@code event_key='BADGE:FIRST_RECORD'} 与同名裸键经验事件 {@code FIRST_RECORD} <b>并存</b>的构造）
 * 与任意统计量，锁住需求 8 的这一组不变式：</p>
 * <ul>
 *   <li><b>命名空间双向隔离</b>（需求 8.11）：徽章已点亮当且仅当存在 {@code event_type='BADGE'} 且
 *       {@code event_key='BADGE:<编码>'} 的行——裸键经验事件（{@code FIRST_RECORD} / {@code STREAK_7} /
 *       {@code STREAK_30} / {@code BUDGET_MET:yyyy-MM} / {@code FIRST_INVITE}）<b>绝不</b>作为任何徽章
 *       已点亮的依据（正向）；反向地 {@code BADGE:} 行既不计入经验（{@code exp} 恒不因徽章增加）、
 *       不计入累计记账天数（{@code total_record_days} 只数 {@code event_type='DAILY_RECORD'} 的行）、
 *       也不构成 {@code BUDGET_MET} 徽章的点亮条件（该条件只看 {@code event_type='BUDGET_MET'} 的行）
 *       与 {@code FIRST_RECORD} / {@code STREAK} 经验事件的判定依据。</li>
 *   <li><b>顺序与规模</b>（需求 8.1、8.5、8.8）：响应恒返回 16 项，编码 / 名称 / 目标值与目录逐行一致，
 *       两次连续请求顺序相同。</li>
 *   <li><b>当前值区间</b>（需求 8.7、8.12）：已点亮 {@code current == target} 且 {@code unlockedAt}
 *       非空并等于该 {@code BADGE} 行的 {@code created_at}（需求 8.6）；未点亮
 *       {@code current == min(统计量, target)} 且 {@code unlockedAt} 为空；{@code 0 ≤ current ≤ target}
 *       恒成立。</li>
 *   <li><b>条件已成立但事件尚未写入</b>（需求 8.13）：返回未点亮 + {@code current == target} +
 *       {@code unlockedAt} 为空，且不报错。</li>
 *   <li><b>徽章不发放经验</b>（需求 8.2、8.3）：一次结算点亮 1–16 枚徽章使经验增加 0；库里全部
 *       {@code BADGE} 行的 {@code exp_amount} 恒为 0。</li>
 * </ul>
 *
 * <h2>怎么把「结算尚未写入徽章」这个中间态稳定造出来</h2>
 * <p>{@code getOverview} 自身会先触发一次结算，而结算会把条件已成立的徽章<b>补齐</b>——若不做处理，
 * 需求 8.13 的中间态与「任意已写入 {@code BADGE} 行子集」这个前提根本无法被观察到。本测试用两步把它
 * 固定下来：① 先调 {@link GrowthSettlementService#recalculateOnly}（<b>只重算物化列、不组装也不插入
 * 任何事件</b>）把 {@code exp} / 三个天数列按我们播种的事件算出来；② 再调
 * {@link GrowthSettlementThrottle#markSettled} 让紧接着的概览请求落在 10 秒窗口内被<b>节流跳过</b>
 * （需求 10.14）。于是概览读到的就是我们播种的那份事件集合，一行不多、一行不少。</p>
 *
 * <h2>刻意混入的三种 {@code BADGE} 命名空间「诱饵」行</h2>
 * <p>{@code BADGE:DAILY_RECORD:2020-01-01}、{@code BADGE:BUDGET_MET:2025-05}、
 * {@code BADGE:FIRST_INVITE} 三行都是合法的 {@code BADGE} 行（{@code event_type='BADGE'}、
 * {@code exp_amount=0}），但它们的编码都<b>不在</b>目录里，因此必须被彻底忽略。它们把「用
 * {@code contains} 而不是精确键 / 前缀判定」这类退化写法钉死：一旦有人把
 * {@code anyKeyStartsWith(keys, "BUDGET_MET:")} 改成 {@code key.contains("BUDGET_MET")}、或把
 * {@code event_type='DAILY_RECORD'} 的日历过滤改成按键 {@code contains("DAILY_RECORD:")}，
 * 累计记账天数或 {@code BUDGET_MET} / {@code INVITE_1} 徽章的当前值立刻错，本测试必然变红。</p>
 *
 * <h2>驱动方式与清理（对齐 {@code GrowthRecalculationPropertyTest} 的约定）</h2>
 * <p>{@code settle} / {@code recalculateOnly} 带 {@code @Transactional(REQUIRES_NEW)}，必须让它们
 * <b>真正提交</b>才能在库里观察到终态，故本测试<b>不</b>用测试级事务包裹；清理相应地不能靠回滚，
 * 由 {@link #resetState()} 在每次迭代前显式清三张表，并用全局自增序号 {@link #SEQ} 保证
 * {@code userId} / 行 id 全局唯一。事件与交易一律走 {@link JdbcTemplate} 直插：徽章与经验事件的写入
 * 路径在生产代码里只有结算一处（仓储刻意不提供单行写入方法），播种任意事件子集只能走原生 SQL。
 * jqwik 属性方法不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 里手工完成（上下文缓存复用，多次迭代只加载一次）。</p>
 *
 * <p>Feature: growth-level-system, Property 13: 徽章的命名空间隔离、当前值区间与顺序</p>
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.3, 8.5, 8.6, 8.7, 8.8, 8.11, 8.12, 8.13</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-badge-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(GrowthBadgePropertyTest.ClockConfig.class)
class GrowthBadgePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：结算日恒为 2025-06-15。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);
    /** 结算时刻（{@code LocalDateTime.now(CLOCK)}）：新写入事件的 {@code created_at} 恒为该时刻。 */
    private static final LocalDateTime NOW_LDT = LocalDateTime.of(2025, 6, 15, 8, 0, 0);

    /** 播种日历的锚点日：与结算日相隔足够远，使全部播种记账日严格早于结算日。 */
    private static final LocalDate CALENDAR_ANCHOR = LocalDate.of(2025, 3, 1);
    /** 交易行的 {@code created_at}：全部落结算日，故结算至多补 1 个 {@code DAILY_RECORD}。 */
    private static final LocalDateTime TX_CREATED_AT = LocalDateTime.of(2025, 6, 15, 9, 0, 0);
    /** 播种 {@code BADGE} 行的解锁时刻基准（逐枚 +1 分钟，使「解锁时刻取该行 created_at」可逐枚区分）。 */
    private static final LocalDateTime SEEDED_UNLOCK_AT = LocalDateTime.of(2025, 5, 1, 10, 15, 30);

    /** 同一个 H2 库跨迭代复用，用序号保证 userId / 行 id 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(910_000_000L);

    /**
     * 16 枚徽章的期望编码 / 名称 / 目标值与展示顺序（achievement-system 需求 1.1 / 12.2 表格的独立副本，
     * 用于锁住目录不漂移）。
     *
     * <p>achievement-system 把清单从 growth-level-system 时期的 9 枚扩到 16 枚，既有 9 枚的编码 /
     * 名称 / 门槛一字不改，只是改为<b>按分类连续</b>排布（起步 → 坚持 → 积累 → 协作 → 主题），
     * 因此这里既断言规模也断言顺序。</p>
     */
    private static final String[] EXPECTED_CODES = {
            "FIRST_RECORD",
            "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365",
            "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000", "DAYS_100",
            "INVITE_1", "COLLAB_1",
            "BUDGET_MET", "BUDGET_MASTER", "SAVING_MASTER", "TRAVEL_MASTER"};
    private static final String[] EXPECTED_NAMES = {
            "开张",
            "七日不辍", "卅日成习", "百日不辍", "岁岁有余",
            "小有账目", "百笔有余", "五百笔在册", "千笔如一", "百日记账",
            "同行有余", "共账之始",
            "预算达标", "预算达人", "储蓄达人", "旅行达人"};
    private static final int[] EXPECTED_TARGETS = {
            1,
            7, 30, 100, 365,
            10, 100, 500, 1000, 100,
            1, 1,
            1, 3, 3, 10};

    /** 累计笔数取值档（覆盖各门槛的下沿 / 等于 / 上沿）。 */
    private static final int[] RECORD_COUNTS = {0, 1, 9, 10, 99, 100, 999, 1000, 1001};
    /** 连续段长度档（覆盖 7 / 30 门槛的两侧）。 */
    private static final int[] STREAK_LENGTHS = {0, 6, 7, 29, 30, 45};
    /** 累计记账天数档（覆盖 100 门槛的两侧）。 */
    private static final int[] TOTAL_DAYS = {0, 99, 100, 120};

    /** 布尔标志位在生成器 {@code flags} 列表中的下标。 */
    private static final int FLAG_BUDGET_MET_EVENT = 0;
    private static final int FLAG_FIRST_INVITE_EVENT = 1;
    private static final int FLAG_EXP_FIRST_RECORD = 2;
    private static final int FLAG_EXP_STREAK_7 = 3;
    private static final int FLAG_EXP_STREAK_30 = 4;
    private static final int FLAG_DECOY_BADGES = 5;
    private static final int FLAG_COUNT = 6;

    /** 事件键字面量（与生产代码各自独立定义，避免测试与被测共用同一处常量）。 */
    private static final String KEY_FIRST_RECORD = "FIRST_RECORD";
    private static final String KEY_STREAK_7 = "STREAK_7";
    private static final String KEY_STREAK_30 = "STREAK_30";
    private static final String KEY_FIRST_INVITE = "FIRST_INVITE";
    private static final String KEY_BUDGET_MET = "BUDGET_MET:2025-04";
    private static final String KEY_DAILY_PREFIX = "DAILY_RECORD:";

    /** 三行 {@code BADGE} 命名空间诱饵：编码均不在目录内，必须被彻底忽略（见类级 Javadoc）。 */
    private static final List<String> DECOY_BADGE_KEYS = List.of(
            "BADGE:DAILY_RECORD:2020-01-01", "BADGE:BUDGET_MET:2025-05", "BADGE:FIRST_INVITE");

    @Autowired
    private GrowthQueryService queryService;
    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private GrowthSettlementThrottle throttle;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthBadgePropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠事务回滚：每次迭代前硬删三张表。成长两表均无外键，删除顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    // ---------------- 生成器 ----------------

    /**
     * 一次场景：累计笔数档 × 连续段长度档 × 累计天数档 × 已写入的 {@code BADGE} 行子集（2^16 的抽样）
     * × 6 个布尔标志（是否播种 {@code BUDGET_MET} / {@code FIRST_INVITE} 经验事件、
     * 是否播种与徽章<b>同名</b>的裸键经验事件 {@code FIRST_RECORD} / {@code STREAK_7} / {@code STREAK_30}、
     * 是否播种三行 {@code BADGE} 诱饵）。
     */
    record Scenario(int recordKind, int streakKind, int totalDaysKind,
                    List<Boolean> seededBadges, List<Boolean> flags) {

        int recordCount() {
            return RECORD_COUNTS[recordKind];
        }

        int streakLength() {
            return STREAK_LENGTHS[streakKind];
        }

        /** 累计记账天数（不小于连续段长度，否则日历自相矛盾）。 */
        int totalRecordDays() {
            return Math.max(streakLength(), TOTAL_DAYS[totalDaysKind]);
        }

        boolean flag(int index) {
            return flags.get(index);
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(
                Arbitraries.integers().between(0, RECORD_COUNTS.length - 1),
                Arbitraries.integers().between(0, STREAK_LENGTHS.length - 1),
                Arbitraries.integers().between(0, TOTAL_DAYS.length - 1),
                Arbitraries.of(true, false).list().ofSize(EXPECTED_CODES.length),
                Arbitraries.of(true, false).list().ofSize(FLAG_COUNT)
        ).as(Scenario::new);
    }

    // ---------------- Property 13 ----------------

    /**
     * Feature: growth-level-system, Property 13: 徽章的命名空间隔离、当前值区间与顺序
     *
     * <p>播种「交易 + 任意事件集合（含同名裸键经验事件、任意 {@code BADGE} 行子集与三行诱饵）」后：
     * ① 只重算物化列（不写事件）并断言 {@code BADGE} 行既不进经验也不进累计记账天数；
     * ② 让概览请求被 10 秒窗口节流跳过，于是概览读到的正是播种的那份事件集合，逐枚断言 16 项的顺序、
     * 点亮依据、解锁时刻、当前值区间与「条件已成立但尚未写入」的中间态；
     * ③ 再真实结算一次，断言缺失的徽章被补齐、徽章使经验增加 0、{@code BADGE} 行没有反向污染任何
     * 经验事件的判定（{@code BUDGET_MET} / {@code FIRST_INVITE} / {@code FIRST_RECORD} / {@code STREAK}
     * 各类型的行数与参考实现逐项相等）。</p>
     *
     * <p>Validates: Requirements 8.1, 8.2, 8.3, 8.5, 8.6, 8.7, 8.8, 8.11, 8.12, 8.13</p>
     */
    @Property(tries = 15)
    void property13_badgeNamespaceIsolationCurrentRangeAndOrder(@ForAll("scenarios") Scenario scenario) {
        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();

        int recordCount = scenario.recordCount();
        List<LocalDate> calendar = calendarOf(scenario.streakLength(), scenario.totalRecordDays());
        CalendarRef calendarRef = calendarRef(calendar);

        boolean budgetMetEvent = scenario.flag(FLAG_BUDGET_MET_EVENT);
        boolean firstInviteEvent = scenario.flag(FLAG_FIRST_INVITE_EVENT);
        boolean expFirstRecord = scenario.flag(FLAG_EXP_FIRST_RECORD);
        boolean expStreak7 = scenario.flag(FLAG_EXP_STREAK_7);
        boolean expStreak30 = scenario.flag(FLAG_EXP_STREAK_30);

        // ── 播种事实源与事件 ────────────────────────────────────────────────────────
        seedTransactions(userId, ledgerId, recordCount);
        seedDailyRecords(userId, calendar);
        if (expFirstRecord) {
            insertEvent(userId, GrowthEventType.FIRST_RECORD, KEY_FIRST_RECORD, 10, NOW_LDT);
        }
        if (expStreak7) {
            insertEvent(userId, GrowthEventType.STREAK, KEY_STREAK_7, 30, NOW_LDT);
        }
        if (expStreak30) {
            insertEvent(userId, GrowthEventType.STREAK, KEY_STREAK_30, 100, NOW_LDT);
        }
        if (budgetMetEvent) {
            insertEvent(userId, GrowthEventType.BUDGET_MET, KEY_BUDGET_MET, 50, NOW_LDT);
        }
        if (firstInviteEvent) {
            insertEvent(userId, GrowthEventType.FIRST_INVITE, KEY_FIRST_INVITE, 80, NOW_LDT);
        }
        Set<String> seededBadgeCodes = seedBadges(userId, scenario.seededBadges());
        if (scenario.flag(FLAG_DECOY_BADGES)) {
            seedDecoyBadges(userId);
        }

        // ── ① 只重算物化列（不组装、不插入任何事件）：BADGE 行不进经验、不进累计记账天数 ──────
        settlementService.recalculateOnly(userId);
        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();

        long expectedExpBefore = 5L * calendarRef.totalDays()
                + (expFirstRecord ? 10L : 0L) + (expStreak7 ? 30L : 0L) + (expStreak30 ? 100L : 0L)
                + (budgetMetEvent ? 50L : 0L) + (firstInviteEvent ? 80L : 0L);
        assertThat(profile.getExp())
                .as("经验只由非 BADGE 事件累计：全部 BADGE 行 exp_amount 为 0，诱饵行同样不贡献经验（需求 8.3、8.11）")
                .isEqualTo(expectedExpBefore);
        assertThat(profile.getTotalRecordDays())
                .as("累计记账天数只数 event_type='DAILY_RECORD' 的行，BADGE:DAILY_RECORD:... 诱饵不计入（需求 8.11）")
                .isEqualTo(calendarRef.totalDays());
        assertThat(profile.getMaxStreakDays())
                .as("历史最长连续天数只由 DAILY_RECORD 日历推导")
                .isEqualTo(calendarRef.maxStreak());
        assertAllBadgeRowsCarryZeroExp(userId);

        // ── ② 让概览请求被 10 秒窗口节流跳过，于是概览读到的正是播种的那份事件集合（需求 10.14）──
        throttle.markSettled(userId);
        GrowthOverviewResponse overview = queryService.getOverview(userId);
        GrowthOverviewResponse overviewAgain = queryService.getOverview(userId);

        assertCatalogOrder(overview, overviewAgain);

        // 参考统计量：笔数取交易事实源，两个天数取 DAILY_RECORD 日历，两个存在型口径只看经验事件行。
        Stats stats = new Stats(recordCount, calendarRef.maxStreak(), calendarRef.totalDays(),
                budgetMetEvent, firstInviteEvent);
        Map<String, LocalDateTime> badgeRowCreatedAt = badgeRowCreatedAt(userId);
        assertBadgeViews(overview, stats, seededBadgeCodes, badgeRowCreatedAt);

        // 需求 8.13：条件已成立但 BADGE 行尚未写入 ⇒ 未点亮 + current == target + 空解锁时刻 + 不报错。
        Set<String> qualifiedBefore = qualifiedCodes(stats);
        for (BadgeView badge : overview.badges()) {
            if (qualifiedBefore.contains(badge.code()) && !seededBadgeCodes.contains(badge.code())) {
                assertThat(badge.unlocked())
                        .as("徽章 %s 条件已成立但事件尚未写入，应返回未点亮（需求 8.13）", badge.code())
                        .isFalse();
                assertThat(badge.current())
                        .as("徽章 %s 条件已成立但事件尚未写入，当前值应等于目标值（需求 8.13）", badge.code())
                        .isEqualTo(badge.target());
                assertThat(badge.unlockedAt())
                        .as("徽章 %s 未点亮，解锁时刻应为空（需求 8.6、8.13）", badge.code())
                        .isNull();
            }
        }

        // ── ③ 真实结算一次：补齐缺失徽章、徽章使经验增加 0、BADGE 行不反向污染经验事件判定 ───────
        long expBeforeSettle = profile.getExp();
        settlementService.settle(userId, TriggerSource.RECORD);
        UserGrowth settled = userGrowthRepository.findById(userId).orElseThrow();

        // 结算会把结算日补进日历（交易的 created_at 全落结算日），据此推导结算后的参考统计量。
        int totalDaysAfter = calendarRef.totalDays() + (recordCount >= 1 ? 1 : 0);
        int maxStreakAfter = Math.max(calendarRef.maxStreak(), recordCount >= 1 ? 1 : 0);
        boolean hasFirstRecordExp = expFirstRecord || recordCount >= 1;
        boolean hasStreak7Exp = expStreak7 || maxStreakAfter >= 7;
        boolean hasStreak30Exp = expStreak30 || maxStreakAfter >= 30;

        // 反向隔离的可观察断言（需求 8.11）：BADGE:FIRST_RECORD / BADGE:BUDGET_MET / BADGE:FIRST_INVITE
        // 与三行诱饵都不得被当成对应的经验事件——各类型行数只由真实事实源决定。
        assertThat(countByKey(userId, KEY_FIRST_RECORD))
                .as("FIRST_RECORD 经验事件只由累计笔数决定，BADGE:FIRST_RECORD 行不参与该判定（需求 8.11）")
                .isEqualTo(hasFirstRecordExp ? 1L : 0L);
        assertThat(countByKey(userId, KEY_STREAK_7))
                .as("STREAK_7 经验事件只由日历最长连续天数决定（需求 8.11）")
                .isEqualTo(hasStreak7Exp ? 1L : 0L);
        assertThat(countByKey(userId, KEY_STREAK_30))
                .as("STREAK_30 经验事件只由日历最长连续天数决定（需求 8.11）")
                .isEqualTo(hasStreak30Exp ? 1L : 0L);
        assertThat(countByType(userId, GrowthEventType.BUDGET_MET))
                .as("未播种任何预算事实，BADGE:BUDGET_MET 与诱饵行不得凭空造出 BUDGET_MET 经验事件（需求 8.11）")
                .isEqualTo(budgetMetEvent ? 1L : 0L);
        assertThat(countByType(userId, GrowthEventType.FIRST_INVITE))
                .as("未播种任何邀请关系，BADGE:FIRST_INVITE 诱饵不得凭空造出 FIRST_INVITE 经验事件（需求 8.11）")
                .isEqualTo(firstInviteEvent ? 1L : 0L);
        assertThat(settled.getTotalRecordDays())
                .as("累计记账天数只数 DAILY_RECORD 行（需求 8.11）")
                .isEqualTo(totalDaysAfter);

        // 徽章使经验增加 0（需求 8.2、8.3）：结算后经验等于「非 BADGE 事件之和」，与新点亮的徽章枚数无关。
        long expectedExpAfter = 5L * totalDaysAfter
                + (hasFirstRecordExp ? 10L : 0L) + (hasStreak7Exp ? 30L : 0L) + (hasStreak30Exp ? 100L : 0L)
                + (budgetMetEvent ? 50L : 0L) + (firstInviteEvent ? 80L : 0L);
        assertThat(settled.getExp())
                .as("结算后经验等于全部非 BADGE 事件之和：本次点亮的徽章使经验增加 0（需求 8.3）")
                .isEqualTo(expectedExpAfter);
        assertThat(settled.getExp() - expBeforeSettle)
                .as("经验增量只来自本次补写的经验事件，与徽章无关（需求 8.3）")
                .isEqualTo(expectedExpAfter - expBeforeSettle);
        assertAllBadgeRowsCarryZeroExp(userId);

        // 结算后：已写入的 BADGE 行 == 播种子集 ∪ 结算日事实下条件已成立的编码（需求 8.2、8.4）。
        Stats statsAfter = new Stats(recordCount, maxStreakAfter, totalDaysAfter,
                budgetMetEvent, firstInviteEvent);
        Set<String> expectedUnlocked = new LinkedHashSet<>(seededBadgeCodes);
        expectedUnlocked.addAll(qualifiedCodes(statsAfter));

        GrowthOverviewResponse afterOverview = queryService.getOverview(userId);
        assertCatalogOrder(afterOverview, queryService.getOverview(userId));
        assertBadgeViews(afterOverview, statsAfter, expectedUnlocked, badgeRowCreatedAt(userId));

        // 播种时已存在的徽章保持原解锁时刻（结算不覆写既有行）；本次新点亮的取结算时刻（需求 8.6）。
        Map<String, LocalDateTime> createdAtAfter = badgeRowCreatedAt(userId);
        for (String code : seededBadgeCodes) {
            assertThat(createdAtAfter.get(code))
                    .as("已点亮徽章 %s 的解锁时刻不因再次结算而改变（需求 8.4、8.6）", code)
                    .isEqualTo(SEEDED_UNLOCK_AT.plusMinutes(indexOfCode(code)));
        }
        for (String code : expectedUnlocked) {
            if (!seededBadgeCodes.contains(code)) {
                assertThat(createdAtAfter.get(code))
                        .as("本次结算新点亮的徽章 %s，解锁时刻取该 BADGE 行的 created_at（需求 8.6）", code)
                        .isEqualTo(NOW_LDT);
            }
        }
    }

    /**
     * 同名键并存的定点用例（需求 8.11 双向隔离）：同时播种四个<b>裸键</b>经验事件
     * （{@code FIRST_RECORD} / {@code STREAK_7} / {@code STREAK_30} / {@code BUDGET_MET:2025-04}）
     * 与<b>唯一一行</b> {@code BADGE:FIRST_RECORD}，且累计笔数为 0、日历为空。
     *
     * <p>正向：只有 {@code FIRST_RECORD} 徽章点亮（其依据只能是那行 {@code BADGE:FIRST_RECORD}，
     * 因为累计笔数是 0）；裸键 {@code STREAK_7} / {@code STREAK_30} / {@code BUDGET_MET:2025-04}
     * 一律<b>不</b>使同名徽章点亮，其中两枚连续徽章的当前值为 0（裸键不是连续天数）、
     * {@code BUDGET_MET} 徽章条件成立但事件未写入故当前值等于目标值 1、解锁时刻为空（需求 8.13）。</p>
     *
     * <p>反向：{@code BADGE:FIRST_RECORD} 与三行诱饵既不贡献经验（经验恒为 10+30+100+50=190）、
     * 也不进累计记账天数（恒 0）；随后的真实结算只补写 {@code BADGE:BUDGET_MET} 一行，
     * 经验仍为 190，且 {@code BADGE:FIRST_RECORD} 的 {@code created_at} 一字不改。</p>
     *
     * <p>Validates: Requirements 8.1, 8.2, 8.3, 8.5, 8.6, 8.7, 8.8, 8.11, 8.12, 8.13</p>
     */
    @Example
    void sameNamedExpEventsAndBadgeRowsCoexistWithBidirectionalIsolation() throws Exception {
        resetState();
        long userId = SEQ.getAndIncrement();

        insertEvent(userId, GrowthEventType.FIRST_RECORD, KEY_FIRST_RECORD, 10, NOW_LDT);
        insertEvent(userId, GrowthEventType.STREAK, KEY_STREAK_7, 30, NOW_LDT);
        insertEvent(userId, GrowthEventType.STREAK, KEY_STREAK_30, 100, NOW_LDT);
        insertEvent(userId, GrowthEventType.BUDGET_MET, KEY_BUDGET_MET, 50, NOW_LDT);
        insertEvent(userId, GrowthEventType.BADGE, GrowthBadgeCatalog.eventKeyOf("FIRST_RECORD"), 0,
                SEEDED_UNLOCK_AT);
        seedDecoyBadges(userId);

        settlementService.recalculateOnly(userId);
        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(profile.getExp())
                .as("BADGE 行与诱饵行都不贡献经验（需求 8.3、8.11）").isEqualTo(190L);
        assertThat(profile.getTotalRecordDays())
                .as("BADGE:DAILY_RECORD:... 诱饵不进累计记账天数（需求 8.11）").isZero();

        throttle.markSettled(userId);
        GrowthOverviewResponse overview = queryService.getOverview(userId);
        Map<String, BadgeView> byCode = byCode(overview);

        // 正向：唯一点亮的是有 BADGE 行的 FIRST_RECORD，尽管累计笔数为 0。
        assertThat(byCode.get("FIRST_RECORD").unlocked())
                .as("BADGE:FIRST_RECORD 行是点亮的唯一依据（需求 8.4、8.11）").isTrue();
        assertThat(byCode.get("FIRST_RECORD").current()).isEqualTo(1);
        assertThat(byCode.get("FIRST_RECORD").unlockedAt()).isEqualTo(SEEDED_UNLOCK_AT);

        // 正向：裸键经验事件不作为徽章点亮依据，也不充当其统计口径。
        assertThat(byCode.get("STREAK_7").unlocked())
                .as("裸键 STREAK_7 经验事件不使 STREAK_7 徽章点亮（需求 8.11）").isFalse();
        assertThat(byCode.get("STREAK_7").current())
                .as("裸键 STREAK_7 不是连续天数，当前值应为 0（需求 8.7）").isZero();
        assertThat(byCode.get("STREAK_30").unlocked()).isFalse();
        assertThat(byCode.get("STREAK_30").current()).isZero();

        // 需求 8.13：BUDGET_MET 徽章条件成立（存在 event_type='BUDGET_MET' 的行）但事件尚未写入。
        assertThat(byCode.get("BUDGET_MET").unlocked()).isFalse();
        assertThat(byCode.get("BUDGET_MET").current()).isEqualTo(1);
        assertThat(byCode.get("BUDGET_MET").unlockedAt()).isNull();

        // INVITE_1 只看 event_key = 'FIRST_INVITE' 的行：BADGE:FIRST_INVITE 诱饵不算（需求 8.11）。
        assertThat(byCode.get("INVITE_1").unlocked()).isFalse();
        assertThat(byCode.get("INVITE_1").current())
                .as("BADGE:FIRST_INVITE 诱饵不构成 INVITE_1 的统计口径（需求 8.11）").isZero();

        // 反向：真实结算只补写 BADGE:BUDGET_MET，经验一分不增，既有 BADGE 行的 created_at 不变。
        settlementService.settle(userId, TriggerSource.RECORD);
        UserGrowth settled = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(settled.getExp())
                .as("点亮徽章使经验增加 0（需求 8.3）").isEqualTo(190L);
        assertThat(settled.getTotalRecordDays()).isZero();
        assertThat(countByType(userId, GrowthEventType.BUDGET_MET))
                .as("BADGE 行不得凭空造出 BUDGET_MET 经验事件（需求 8.11）").isEqualTo(1L);
        assertThat(countByKey(userId, KEY_FIRST_RECORD))
                .as("累计笔数为 0，FIRST_RECORD 经验事件不因 BADGE:FIRST_RECORD 存在而增减（需求 8.11）")
                .isEqualTo(1L);
        assertAllBadgeRowsCarryZeroExp(userId);

        Map<String, LocalDateTime> createdAt = badgeRowCreatedAt(userId);
        assertThat(createdAt.get("FIRST_RECORD"))
                .as("既有 BADGE 行不被覆写（需求 8.4）").isEqualTo(SEEDED_UNLOCK_AT);
        assertThat(createdAt.get("BUDGET_MET"))
                .as("新点亮徽章的解锁时刻取该 BADGE 行 created_at（需求 8.6）").isEqualTo(NOW_LDT);
    }

    // ---------------- 断言助手 ----------------

    /** 16 项的规模、编码 / 名称 / 目标值与展示顺序，并断言两次连续请求顺序相同（需求 8.1、8.5、8.8）。 */
    private void assertCatalogOrder(GrowthOverviewResponse first, GrowthOverviewResponse second) {
        List<BadgeView> badges = first.badges();
        assertThat(badges)
                .as("概览恒返回 16 枚徽章（需求 8.5；achievement-system 需求 12.2 把清单从 9 枚扩到 16 枚）")
                .hasSize(EXPECTED_CODES.length);
        for (int i = 0; i < EXPECTED_CODES.length; i++) {
            assertThat(badges.get(i).code())
                    .as("第 %d 枚徽章的编码与展示顺序（需求 8.1、8.8）", i).isEqualTo(EXPECTED_CODES[i]);
            assertThat(badges.get(i).name())
                    .as("徽章 %s 的展示名称随响应下发（需求 8.5、8.10）", EXPECTED_CODES[i])
                    .isEqualTo(EXPECTED_NAMES[i]);
            assertThat(badges.get(i).target())
                    .as("徽章 %s 的目标值等于门槛数值（需求 8.7）", EXPECTED_CODES[i])
                    .isEqualTo(EXPECTED_TARGETS[i]);
        }
        assertThat(second.badges().stream().map(BadgeView::code).toList())
                .as("两次连续请求返回的徽章顺序相同（需求 8.8）")
                .isEqualTo(badges.stream().map(BadgeView::code).toList());
    }

    /**
     * 逐枚断言点亮依据、解锁时刻与当前值区间（需求 8.4、8.6、8.7、8.11、8.12）。
     *
     * @param unlockedCodes   期望已点亮的编码集合，其唯一来源是库里的 {@code BADGE:<编码>} 行
     * @param badgeCreatedAt  {@code BADGE} 行的 {@code created_at}，按编码索引
     */
    private void assertBadgeViews(GrowthOverviewResponse overview, Stats stats,
                                  Set<String> unlockedCodes, Map<String, LocalDateTime> badgeCreatedAt) {
        for (BadgeView badge : overview.badges()) {
            boolean expectedUnlocked = unlockedCodes.contains(badge.code());
            assertThat(badge.unlocked())
                    .as("徽章 %s 是否点亮只由 BADGE:<编码> 行决定（需求 8.4、8.11）", badge.code())
                    .isEqualTo(expectedUnlocked);

            if (expectedUnlocked) {
                assertThat(badge.current())
                        .as("已点亮徽章 %s 的当前值恒等于目标值（需求 8.12）", badge.code())
                        .isEqualTo(badge.target());
                assertThat(badge.unlockedAt())
                        .as("已点亮徽章 %s 的解锁时刻取该 BADGE 行的 created_at（需求 8.6）", badge.code())
                        .isEqualTo(badgeCreatedAt.get(badge.code()));
            } else {
                assertThat(badge.unlockedAt())
                        .as("未点亮徽章 %s 的解锁时刻为空（需求 8.6）", badge.code())
                        .isNull();
                assertThat(badge.current())
                        .as("未点亮徽章 %s 的当前值等于 min(统计量, 目标值)（需求 8.7、8.12）", badge.code())
                        .isEqualTo((int) Math.min(statOf(badge.code(), stats), badge.target()));
            }

            assertThat(badge.current())
                    .as("徽章 %s 的当前值落在 [0, target]（需求 8.12）", badge.code())
                    .isBetween(0, badge.target());
        }
    }

    /** 全部 {@code BADGE} 行的 {@code exp_amount} 恒为 0（需求 8.3）。 */
    private void assertAllBadgeRowsCarryZeroExp(long userId) {
        Long nonZero = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_type = 'BADGE' "
                        + "AND exp_amount <> 0", Long.class, userId);
        assertThat(nonZero).as("全部 BADGE 行的 exp_amount 为 0（需求 8.3）").isEqualTo(0L);
    }

    // ---------------- 参考实现 ----------------

    /** 徽章判定所需的统计量（测试侧独立建模，不复用 {@code GrowthFacts}）。 */
    private record Stats(long recordCount, int maxStreak, int totalDays,
                         boolean budgetMetEvent, boolean firstInviteEvent) {
    }

    /**
     * 某枚徽章的统计口径当前取值（需求 8.7）。存在型口径映射为 1 / 0。
     *
     * <p>achievement-system 新增的三个口径（储蓄月数 / 协作成员数 / 旅行支出笔数）在本测试的播种里
     * <b>恒为 0</b>：本测试只播种交易、{@code DAILY_RECORD} 日历与若干经验事件，不建账本成员、
     * 不建「旅行」分类、交易的 {@code occurred_at} 全落结算日（在储蓄月回看窗口之外），
     * 因此参考实现按 0 建模是准确的而非省略。{@code BUDGET_MASTER} 与 {@code BUDGET_MET} 共用
     * 「{@code BUDGET_MET} 事件条数」这一个口径，本测试至多播种 1 条，故门槛 3 的那枚恒不成立。</p>
     */
    private static long statOf(String code, Stats stats) {
        return switch (code) {
            case "FIRST_RECORD", "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000" ->
                    stats.recordCount();
            case "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365" -> stats.maxStreak();
            case "DAYS_100" -> stats.totalDays();
            case "BUDGET_MET", "BUDGET_MASTER" -> stats.budgetMetEvent() ? 1L : 0L;
            case "INVITE_1" -> stats.firstInviteEvent() ? 1L : 0L;
            case "COLLAB_1", "SAVING_MASTER", "TRAVEL_MASTER" -> 0L;
            default -> throw new IllegalArgumentException("未知徽章编码：" + code);
        };
    }

    /** 点亮条件已成立的编码集合（需求 8.1 表格的独立参考实现，一律取「大于或等于门槛」）。 */
    private static Set<String> qualifiedCodes(Stats stats) {
        Set<String> codes = new LinkedHashSet<>();
        for (int i = 0; i < EXPECTED_CODES.length; i++) {
            if (statOf(EXPECTED_CODES[i], stats) >= EXPECTED_TARGETS[i]) {
                codes.add(EXPECTED_CODES[i]);
            }
        }
        return codes;
    }

    /** 日历的朴素参考扫描：去重排序后数天数与最长连续段。 */
    private record CalendarRef(int totalDays, int maxStreak) {
    }

    private static CalendarRef calendarRef(List<LocalDate> dates) {
        List<LocalDate> sorted = new ArrayList<>(new TreeSet<>(dates));
        int max = 0;
        int current = 0;
        for (int i = 0; i < sorted.size(); i++) {
            current = (i > 0 && sorted.get(i - 1).plusDays(1).equals(sorted.get(i))) ? current + 1 : 1;
            max = Math.max(max, current);
        }
        return new CalendarRef(sorted.size(), max);
    }

    /**
     * 构造记账日历：一个长度为 {@code streakLength} 的连续段（以 {@link #CALENDAR_ANCHOR} 结尾），
     * 外加若干<b>相互间隔 2 天</b>的孤立日补足到 {@code totalDays}，因此参考最长连续段恰为
     * {@code max(streakLength, 有孤立日 ? 1 : 0)}，全部日期严格早于结算日。
     */
    private static List<LocalDate> calendarOf(int streakLength, int totalDays) {
        List<LocalDate> dates = new ArrayList<>(totalDays);
        for (int i = 0; i < streakLength; i++) {
            dates.add(CALENDAR_ANCHOR.minusDays(streakLength - 1L - i));
        }
        int isolated = totalDays - streakLength;
        for (int i = 0; i < isolated; i++) {
            // 与连续段起点相隔 2 天、孤立日之间也相隔 2 天，绝不与任何日期相邻。
            dates.add(CALENDAR_ANCHOR.minusDays(streakLength + 1L + 2L * i));
        }
        return dates;
    }

    private static int indexOfCode(String code) {
        for (int i = 0; i < EXPECTED_CODES.length; i++) {
            if (EXPECTED_CODES[i].equals(code)) {
                return i;
            }
        }
        throw new IllegalArgumentException("未知徽章编码：" + code);
    }

    private static Map<String, BadgeView> byCode(GrowthOverviewResponse overview) {
        Map<String, BadgeView> map = new HashMap<>();
        for (BadgeView badge : overview.badges()) {
            map.put(badge.code(), badge);
        }
        return map;
    }

    // ---------------- 事实源与事件播种 ----------------

    /**
     * 直插若干「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type = 'expense'}、{@code ledger_id} 非 NULL），{@code created_at} 全落结算日。
     */
    private void seedTransactions(long userId, long ledgerId, int count) {
        if (count == 0) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(new Object[] {SEQ.getAndIncrement(), userId, ledgerId, userId, "expense",
                    new BigDecimal("1.00"), ledgerId, TX_CREATED_AT, TX_CREATED_AT, TX_CREATED_AT, null});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO transactions "
                        + "(id, user_id, ledger_id, created_by, type, amount, account_id, "
                        + "occurred_at, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batch);
    }

    /** 播种 {@code DAILY_RECORD:yyyy-MM-dd} 事件（每日 5 经验），构成该用户的记账日历。 */
    private void seedDailyRecords(long userId, List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(dates.size());
        for (LocalDate date : new TreeSet<>(dates)) {
            batch.add(new Object[] {userId, GrowthEventType.DAILY_RECORD, KEY_DAILY_PREFIX + date, 5,
                    Timestamp.valueOf(NOW_LDT)});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                batch);
    }

    /** 播种任意 {@code BADGE} 行子集，逐枚给不同的 {@code created_at}（便于逐枚校验解锁时刻）。 */
    private Set<String> seedBadges(long userId, List<Boolean> seeded) {
        Set<String> codes = new LinkedHashSet<>();
        for (int i = 0; i < EXPECTED_CODES.length; i++) {
            if (!seeded.get(i)) {
                continue;
            }
            insertEvent(userId, GrowthEventType.BADGE, GrowthBadgeCatalog.eventKeyOf(EXPECTED_CODES[i]), 0,
                    SEEDED_UNLOCK_AT.plusMinutes(i));
            codes.add(EXPECTED_CODES[i]);
        }
        return codes;
    }

    /** 播种三行 {@code BADGE} 命名空间诱饵（编码均不在目录内，必须被彻底忽略，见类级 Javadoc）。 */
    private void seedDecoyBadges(long userId) {
        for (String key : DECOY_BADGE_KEYS) {
            insertEvent(userId, GrowthEventType.BADGE, key, 0, SEEDED_UNLOCK_AT);
        }
    }

    private void insertEvent(long userId, String type, String key, int exp, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, type, key, exp, Timestamp.valueOf(createdAt));
    }

    /** 该用户全部 {@code BADGE} 行的解锁时刻，按「去掉 {@code BADGE:} 前缀」后的编码索引。 */
    private Map<String, LocalDateTime> badgeRowCreatedAt(long userId) {
        List<Map.Entry<String, LocalDateTime>> rows = jdbcTemplate.query(
                "SELECT event_key, created_at FROM growth_events "
                        + "WHERE user_id = ? AND event_type = 'BADGE'",
                (rs, i) -> Map.entry(rs.getString("event_key").substring("BADGE:".length()),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                userId);
        Map<String, LocalDateTime> map = new HashMap<>();
        rows.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
        return map;
    }

    private long countByKey(long userId, String eventKey) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_key = ?",
                Long.class, userId, eventKey);
        return count == null ? 0L : count;
    }

    private long countByType(long userId, String eventType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_type = ?",
                Long.class, userId, eventType);
        return count == null ? 0L : count;
    }

    /** 提供一个 {@code @Primary} 的固定时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日确定。 */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    /** 可归位的时钟（本属性不推进时间，只需每次迭代前归位到基准时刻）。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void reset(Instant to) {
            this.instant = to;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
