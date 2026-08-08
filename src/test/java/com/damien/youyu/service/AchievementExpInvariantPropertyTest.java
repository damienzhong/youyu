package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
import com.damien.youyu.domain.LedgerMember;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 5：成就与储蓄月不改变经验与等级</b>的属性测试（任务 8.3）。
 *
 * <p><i>对任意</i>解锁成就与写入储蓄月事件的组合（由长度 1–40 的操作序列驱动：加普通支出、加旅行支出、
 * 加协作成员、把某个回看月造成储蓄月、直接结算、请求成就清单），断言三条不变式：</p>
 *
 * <ul>
 *   <li><b>{@code exp(after) == exp(before)}</b>：{@code user_growth.exp} 与这些事件写入前逐项相等
 *       （需求 1.11、12.6）；</li>
 *   <li><b>{@code level(after) == level(before)}</b>：等级同样一格不动；</li>
 *   <li><b>{@code exp == Σ exp_amount}</b>：档案经验恒等于该用户全部 {@code growth_events} 行
 *       {@code exp_amount} 之和（读库聚合比对，不比内存值）。</li>
 * </ul>
 *
 * <h2>怎么把「只写零经验事件」这件事隔离出来</h2>
 *
 * <p>难点在于：成就与储蓄月的事实源同时也是经验事件的事实源，随便造几笔交易就会顺带写出
 * {@code DAILY_RECORD}（5 经验）或 {@code STREAK_*}（30/100 经验），那时 {@code exp} 变了却与本属性无关。
 * 本类用两条约束把变量收敛到只剩 {@code BADGE} 与 {@code SAVING_MONTH}：</p>
 *
 * <ol>
 *   <li><b>基线的经验事件全部直插，再走生产的 {@link GrowthSettlementService#recalculateOnly}
 *       把 {@code exp} / {@code level} 算出来。</b>{@code recalculateOnly} 与 {@code settle} 共用同一条
 *       第 ⑥ 步重算（{@code exp} 一律取 {@code SUM(exp_amount)} 数据库聚合），但<b>不组装、不插入</b>
 *       任何事件——于是基线状态里「经验事件已齐、成就与储蓄月一条没有」，随后每次结算写入的就只有
 *       零经验的那两类。</li>
 *   <li><b>全部交易的 {@code created_at} 一律落在基线日历的最后一天（昨天）。</b>记账日历按
 *       {@code created_at} 取自然日（见 {@link GrowthCalendarService#backfillDates}），追补下界是
 *       {@code last_record_date + 1 天}，因此这些交易一天新记账日也补不出来，
 *       {@code DAILY_RECORD} 与两个 {@code STREAK} 门槛都不会被再次触发。储蓄月判定看的是
 *       {@code occurred_at}，与 {@code created_at} 无关，所以「造储蓄月」和「不动日历」互不冲突。</li>
 * </ol>
 *
 * <p>另外<b>刻意不建任何总预算、不建任何邀请关系</b>：{@code BUDGET_MET}（50 经验）与
 * {@code FIRST_INVITE}（80 经验）因此不可能在序列中途被写出来。基线里 {@code FIRST_RECORD} /
 * {@code STREAK_7} 的事件行已直插，键已存在，结算的 {@code add(...)} 会按 {@code existingKeys} 跳过。</p>
 *
 * <h2>非空洞守卫</h2>
 *
 * <p>基线日历恰好 7 个连续记账日，故 {@code max_streak_days = 7}，只要序列里发生过任意一次结算，
 * {@code BADGE:STREAK_7} 必然落库——属性因此不会退化成「一条零经验事件都没写，等式恒真」。
 * 序列末尾另有一次收尾结算，并断言 {@code BADGE} 行数 ≥1。</p>
 *
 * <h2>驱动方式与清理</h2>
 *
 * <p>{@code settle} / {@code recalculateOnly} 带 {@code @Transactional(REQUIRES_NEW)}，只有真实<b>提交</b>
 * 才能在库里观察到终态，故本类<b>不用测试级事务包裹</b>；清理不靠回滚，由 {@link #resetState()} 每次
 * 迭代前显式清表，并用全局自增序号 {@link #SEQ} 保证 {@code userId} / {@code ledgerId} / 分类 id 全局
 * 唯一（双重隔离）。时钟用 {@code @Primary} 的可推进 {@link MutableClock}（固定 {@code Asia/Shanghai} 的
 * {@code 2025-06-15 08:00}，结算日恒为 {@code 2025-06-15}、三个回看月恒为 {@code 2025-03/04/05}），
 * 每次结算前推进 61 秒越过记账侧 60 秒与概览侧 10 秒两个节流窗口而不跨自然日。jqwik 属性方法不经
 * {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成。</p>
 *
 * <h2>反向断言：把 {@code BADGE} / {@code SAVING_MONTH} 的 {@code expAmount} 改成非 0 时必须变红</h2>
 *
 * <p>见 {@link #reverseAssertion_nonZeroExpAmountWouldBreakThisProperty()}。它由两层机械保证组成，
 * 不靠注释里的承诺（详见该方法的 Javadoc，内含<b>实测记录</b>）。</p>
 *
 * <p>Feature: achievement-system, Property 5: 成就与储蓄月不改变经验与等级</p>
 *
 * <p>Validates: Requirements 1.11, 12.6</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-exp-invariant-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(AchievementExpInvariantPropertyTest.ClockConfig.class)
class AchievementExpInvariantPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 2025-06-15 08:00（Asia/Shanghai）：结算日恒为 2025-06-15，回看月恒为 2025-03/04/05。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 越过记账侧 60 秒节流窗口（也顺带越过概览侧 10 秒窗口）的推进量。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);

    /** 结算日与「昨天」：全部交易的 {@code created_at} 一律取昨天，故一天新记账日也补不出来。 */
    private static final LocalDate SETTLE_DATE = LocalDate.of(2025, 6, 15);
    private static final LocalDate YESTERDAY = SETTLE_DATE.minusDays(1);
    private static final LocalDateTime RECORD_CREATED_AT = YESTERDAY.atTime(9, 0);

    /** 三个回看月（{@code settleDate.withDayOfMonth(1)} 往前 1/2/3 个月，升序）。 */
    private static final List<YearMonth> LOOKBACK_MONTHS = List.of(
            YearMonth.of(2025, 3), YearMonth.of(2025, 4), YearMonth.of(2025, 5));

    /** 基线日历长度：7 个连续记账日 → {@code max_streak = 7} → {@code BADGE:STREAK_7} 必然可解锁。 */
    private static final int BASELINE_CALENDAR_DAYS = 7;

    /** 六类经验事件的既有经验值（growth-level-system 的取值，本 spec 一个不改）。 */
    private static final int EXP_DAILY_RECORD = 5;
    private static final int EXP_FIRST_RECORD = 10;
    private static final int EXP_STREAK_7 = 30;

    /** 16 枚成就的编码与展示顺序（需求 1.1 表格的独立副本）。 */
    private static final List<String> CATALOG_CODES = List.of(
            "FIRST_RECORD",
            "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365",
            "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000", "DAYS_100",
            "INVITE_1", "COLLAB_1",
            "BUDGET_MET", "BUDGET_MASTER", "SAVING_MASTER", "TRAVEL_MASTER");

    /** 交易直插语句：列顺序与 {@link #txRow} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 播种用的事件直插语句（不带 ODKU：播种的键两两不同，无需幂等语义）。 */
    private static final String SEED_EVENT_SQL =
            "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                    + "VALUES (?, ?, ?, ?, ?)";

    /** 播种事件的 {@code created_at}：远早于结算时刻，与本次写入的行可按时刻区分。 */
    private static final LocalDateTime SEED_EVENT_AT = LocalDateTime.of(2024, 1, 2, 3, 4, 5);

    /** 全局自增序号：保证跨迭代 userId / ledgerId / 分类 id / 成员 id 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(530_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private AchievementQueryService achievementQueryService;
    @Autowired
    private GrowthLevelCurve levelCurve;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(AchievementExpInvariantPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删事实源与三张成长 / 成就表（各表间无外键）。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM budgets");
        jdbcTemplate.update("DELETE FROM ledger_members");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM categories");
        jdbcTemplate.update("DELETE FROM invite_relations");
    }

    // ---------------- 生成器 ----------------

    /**
     * 一次操作。前四项只改「零经验事件」的事实源（成就口径与储蓄月口径），后两项是把它们落库的两条
     * 写入路径。<b>刻意不含</b>改预算与加邀请关系：那两者会写出带正经验的
     * {@code BUDGET_MET} / {@code FIRST_INVITE}，与本属性要隔离的变量冲突。
     */
    enum Op {
        /** 10 笔普通支出（推动 {@code RECORD_COUNT}，{@code created_at} 落在昨天故不动日历）。 */
        ADD_PLAIN_EXPENSES,
        /** 5 笔「旅行」分类下的支出（推动 {@code TRAVEL_RECORD_COUNT} 与 {@code RECORD_COUNT}）。 */
        ADD_TRAVEL_EXPENSES,
        /** 自有账本上加一个他人的 {@code EDITOR} 成员（推动 {@code COLLAB_MEMBER_COUNT}）。 */
        ADD_COLLABORATOR,
        /** 把某个回看月造成储蓄月（收入 1000.00 + 支出 100.00，结余 900.00 ≥ 两成门槛 200.00）。 */
        MAKE_SAVING_MONTH,
        /** 直接结算一次（{@code RECORD} 来源）。 */
        SETTLE,
        /** 请求成就清单（写入型 GET，内含一次 {@code OVERVIEW} 结算）。 */
        LIST
    }

    /** 操作序列：长度 1–40，元素随机（含重复）。 */
    @Provide
    Arbitrary<List<Op>> operations() {
        return Arbitraries.of(Op.class).list().ofMinSize(1).ofMaxSize(40);
    }

    // ---------------- Property 5 ----------------

    /**
     * Feature: achievement-system, Property 5: 成就与储蓄月不改变经验与等级
     *
     * <p>基线把经验事件直插齐并用 {@code recalculateOnly} 算出 {@code exp} / {@code level}，记下这两个
     * 取值；随后施加 1–40 个操作，<b>每个操作之后</b>复核三条不变式（{@code exp} 不变、{@code level}
     * 不变、{@code exp == Σ exp_amount}），并逐行断言 {@code BADGE} 与 {@code SAVING_MONTH} 的
     * {@code exp_amount} 恒为 0。序列末尾再收尾结算一次并复核。</p>
     *
     * <p>Validates: Requirements 1.11, 12.6</p>
     */
    @Property(tries = 10)
    void property5_badgesAndSavingMonthsNeverChangeExpOrLevel(@ForAll("operations") List<Op> ops) {
        Ctx ctx = newUser();
        long expBefore = profileExp(ctx.userId());
        int levelBefore = profileLevel(ctx.userId());

        // 基线的经验事件之和：7 × 5 + 10 + 30 = 75，且已由生产的重算路径写进档案。
        assertThat(expBefore)
                .as("基线经验 = 7 天 DAILY_RECORD + FIRST_RECORD + STREAK_7")
                .isEqualTo(BASELINE_CALENDAR_DAYS * EXP_DAILY_RECORD + EXP_FIRST_RECORD + EXP_STREAK_7);
        assertThat(levelBefore).isEqualTo(levelCurve.levelOf(expBefore));
        assertThat(zeroExpEventCount(ctx.userId())).as("基线里一条零经验事件都没有").isZero();

        for (int i = 0; i < ops.size(); i++) {
            applyOp(ops.get(i), i, ctx);
            assertInvariants(ctx.userId(), expBefore, levelBefore,
                    "第 " + (i + 1) + " 个操作 " + ops.get(i));
        }

        // 收尾结算：把序列里最后一批已达门槛的成就与储蓄月落库，再复核一次。
        CLOCK.advance(BEYOND_THROTTLE);
        settlementService.settle(ctx.userId(), TriggerSource.RECORD);
        assertInvariants(ctx.userId(), expBefore, levelBefore, "序列末尾的收尾结算");

        // 非空洞守卫：基线日历 7 天使 BADGE:STREAK_7 必然已解锁，上面的等式因而确实在考验零经验事件。
        assertThat(badgeCodes(ctx.userId()))
                .as("基线的 7 天连续日历应使 STREAK_7 必然解锁，否则本属性沦为空洞")
                .contains("STREAK_7");
    }

    /**
     * 极端用例：<b>同一次结算内解锁 16 枚成就 + 写入 3 条储蓄月</b>，{@code exp} 与 {@code level}
     * 一格不动（需求 1.11 的「一次结算解锁 1 至 16 枚」上界）。
     *
     * <p>随机序列几乎不可能把八个口径同时顶到最高档，故单列一个用例。构造方式与属性方法同一套：
     * 经验事件（365 天 {@code DAILY_RECORD} + {@code FIRST_RECORD} + 两个 {@code STREAK} +
     * 3 条 {@code BUDGET_MET} + {@code FIRST_INVITE}）全部直插并经 {@code recalculateOnly} 落进档案，
     * 事实源则把八个口径一次顶到全部 16 枚成就的门槛之上；随后<b>一次</b>结算写入 19 行零经验事件。</p>
     *
     * <p>{@code SAVING_MASTER} 能与第 3 条储蓄月<b>同批</b>解锁，靠的是结算第 ④ 步里
     * {@code SAVING_MONTH} 排在 {@code BADGE} 之前（任务 4.3）；这里顺带把它锁住：本次新增行恰好是
     * 16 条 {@code BADGE} + 3 条 {@code SAVING_MONTH}，一条不多一条不少。</p>
     *
     * <p>Validates: Requirements 1.11, 12.6</p>
     */
    @Example
    void sixteenBadgesAndThreeSavingMonthsInOneSettlement_leaveExpAndLevelUntouched() {
        long userId = SEQ.getAndIncrement();
        long ledgerId = insertLedger(userId);
        long travelCategoryId = insertCategory(userId, ledgerId, "旅行");
        long decoyCategoryId = insertCategory(userId, ledgerId, "餐饮");

        // ── 经验事件全部直插：365 天连续日历（STREAK_100 / STREAK_365 / DAYS_100 的事实源）───────
        long expectedExp = 0L;
        List<Object[]> events = new ArrayList<>();
        for (int back = 0; back < 365; back++) {
            events.add(eventRow(userId, GrowthEventType.DAILY_RECORD,
                    "DAILY_RECORD:" + YESTERDAY.minusDays(back), EXP_DAILY_RECORD));
            expectedExp += EXP_DAILY_RECORD;
        }
        events.add(eventRow(userId, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", EXP_FIRST_RECORD));
        events.add(eventRow(userId, GrowthEventType.STREAK, "STREAK_7", EXP_STREAK_7));
        events.add(eventRow(userId, GrowthEventType.STREAK, "STREAK_30", 100));
        events.add(eventRow(userId, GrowthEventType.FIRST_INVITE, "FIRST_INVITE", 80));
        expectedExp += EXP_FIRST_RECORD + EXP_STREAK_7 + 100 + 80;
        // 3 条 BUDGET_MET：BUDGET_MET（门槛 1）与 BUDGET_MASTER（门槛 3）的事实源就是这三条行的前缀计数。
        for (int i = 0; i < 3; i++) {
            events.add(eventRow(userId, GrowthEventType.BUDGET_MET, "BUDGET_MET:2019-0" + (i + 1), 50));
            expectedExp += 50;
        }
        jdbcTemplate.batchUpdate(SEED_EVENT_SQL, events);

        // ── 事实源：1000 笔以上有效记账（含 10 笔旅行）+ 1 个协作成员 + 3 个储蓄月 ──────────────
        seedExpenses(userId, ledgerId, 10, travelCategoryId);
        seedExpenses(userId, ledgerId, 990, decoyCategoryId);
        addCollaborator(userId, ledgerId);
        for (YearMonth month : LOOKBACK_MONTHS) {
            makeSavingMonth(userId, ledgerId, month, decoyCategoryId);
        }

        // 走生产的重算路径把 exp / level 落进档案：它不组装、不插入任何事件（与 settle 共用第 ⑥ 步）。
        settlementService.recalculateOnly(userId);
        long expBefore = profileExp(userId);
        int levelBefore = profileLevel(userId);
        assertThat(expBefore).as("基线经验等于直插的经验事件之和").isEqualTo(expectedExp);
        assertThat(zeroExpEventCount(userId)).as("结算前一条零经验事件都没有").isZero();

        long watermark = maxEventId(userId);
        CLOCK.advance(BEYOND_THROTTLE);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD))
                .as("结算真实执行（last_record_date 是昨天，记账侧节流条件不成立）")
                .isEqualTo(SettleOutcome.SETTLED);

        // 本次新增的行恰好是 16 条 BADGE + 3 条 SAVING_MONTH，一条不多一条不少。
        assertThat(newEventTypesSince(userId, watermark))
                .as("同一次结算写入 16 枚成就与 3 条储蓄月")
                .containsExactlyInAnyOrderElementsOf(expectedNineteenRows());
        assertThat(badgeCodes(userId)).as("16 枚全解锁，顺序即展示序号").isEqualTo(CATALOG_CODES);
        assertThat(savingMonthKeys(userId))
                .as("3 条储蓄月恰好是三个回看月")
                .containsExactly("SAVING_MONTH:2025-03", "SAVING_MONTH:2025-04", "SAVING_MONTH:2025-05");

        assertInvariants(userId, expBefore, levelBefore, "16 枚 + 3 条储蓄月的单次结算");
    }

    /**
     * <b>反向断言（不标可选）：把任一 {@code BADGE} 或 {@code SAVING_MONTH} 的 {@code expAmount}
     * 改成非 0 时，本属性必须失败。</b>
     *
     * <p>这条保证由两层<b>机械</b>手段构成，任一层单独都不够：</p>
     *
     * <ol>
     *   <li><b>正向：生产写出来的行必须是 0。</b>先跑一次真实结算，再把该用户全部 {@code BADGE} 与
     *       {@code SAVING_MONTH} 行的 {@code exp_amount} 从库里<b>读回</b>逐行断言为 0（并断言两类行各至少
     *       一条，避免在空集上假绿）。{@code GrowthSettlementService} 第 ④ 步给这两类事件传的是字面量
     *       {@code 0}，字面量无法用反射读取，但只要有人把它改成非 0，本层立刻变红——它读的是<b>生产写
     *       进库里的真实取值</b>，不是测试自己抄的常量。</li>
     *   <li><b>反向：非 0 会让 {@code exp} 与 {@code level} 双双改变。</b>用反射取出生产的批量插入语句
     *       {@code GrowthSettlementService.INSERT_EVENT_SQL}（与
     *       {@code AchievementIdempotencyPropertyTest} 的反向断言同一手法），照它的列顺序写入两行
     *       「假如实现改成非 0 就会长这样」的行——一条 {@code BADGE}、一条 {@code SAVING_MONTH}，
     *       {@code exp_amount} 之和刚好把该用户顶过下一级阈值；随后调用<b>生产的</b>
     *       {@link GrowthSettlementService#recalculateOnly}（与 {@code settle} 共用同一条第 ⑥ 步重算），
     *       断言 {@code exp} 与 {@code level} <b>都</b>变了。这就机械地证明了：第 ①/③ 步那两条等式
     *       （{@code exp(after) == exp(before)}、{@code level(after) == level(before)}）正是靠
     *       「这两类事件的 {@code exp_amount} 恒为 0」才成立的——它们是承重断言，不是恒真断言。
     *       最后删掉这两行再重算一次，断言 {@code exp} / {@code level} 回到原值，实验自我复原。</li>
     * </ol>
     *
     * <h2>已实测：把生产的字面量改成非 0 时本类确实变红（两次实验，均已复原）</h2>
     *
     * <p><b>实验一（{@code BADGE} 侧）</b>：在 {@code GrowthSettlementService} 第 ④ 步把 {@code BADGE}
     * 那一行的 {@code expAmount} 实参从 {@code 0} 改成 {@code 7}（{@code add(pending, existingKeys,
     * userId, GrowthEventType.BADGE, GrowthBadgeCatalog.eventKeyOf(code), 7, now)}，其余一字不改）
     * 后单独重跑本类，<b>3 个方法全部变红</b>（{@code Tests run: 3, Failures: 3}）：</p>
     * <ul>
     *   <li>属性方法炸在 {@code exp} 等式上：{@code [序列末尾的收尾结算 之后 exp 必须与零经验事件写入前
     *       逐项相等] expected: 75L but was: 96L}（缩样后的反例解锁了 3 枚成就，3 × 7 = 21）；</li>
     *   <li>极端用例炸在同一条等式上：{@code expected: 2195L but was: 2307L}（16 × 7 = 112）；</li>
     *   <li>本方法炸在第 ① 层：{@code [生产写出来的 BADGE 行 exp_amount 恒为 0] and element(s) not
     *       expected}。</li>
     * </ul>
     *
     * <p><b>实验二（{@code SAVING_MONTH} 侧）</b>：把 {@code BADGE} 改回 {@code 0}、改把
     * {@code SAVING_MONTH} 那一行的 {@code expAmount} 从 {@code 0} 改成 {@code 3} 后重跑，同样
     * <b>3 个方法全部变红</b>：属性方法 {@code expected: 75L but was: 78L}（1 个储蓄月 × 3）、
     * 极端用例 {@code expected: 2195L but was: 2204L}（3 × 3 = 9）、
     * 本方法炸在第 ① 层的 {@code [生产写出来的 SAVING_MONTH 行 exp_amount 恒为 0]} 上。</p>
     *
     * <p>两次实验后均把 {@code GrowthSettlementService} 复原（改回两个 {@code 0}），并以文件校验和
     * 确认与实验前逐字节相同，本类随即恢复 {@code Tests run: 3, Failures: 0}。</p>
     *
     * <p>Validates: Requirements 1.11, 12.6</p>
     */
    @Example
    void reverseAssertion_nonZeroExpAmountWouldBreakThisProperty() throws Exception {
        Ctx ctx = newUser();
        long userId = ctx.userId();
        // 造出两类零经验事件各至少一条：旅行支出 + 储蓄月，使第 ① 层的逐行断言非空洞。
        seedExpenses(userId, ctx.ledgerId(), 10, ctx.travelCategoryId());
        makeSavingMonth(userId, ctx.ledgerId(), LOOKBACK_MONTHS.get(0), ctx.decoyCategoryId());
        CLOCK.advance(BEYOND_THROTTLE);
        settlementService.settle(userId, TriggerSource.RECORD);

        long expBefore = profileExp(userId);
        int levelBefore = profileLevel(userId);

        // ── ① 正向：生产写进库里的那些行，exp_amount 必须逐行为 0 ─────────────────────────
        List<Integer> badgeExps = expAmountsOfType(userId, GrowthEventType.BADGE);
        List<Integer> savingExps = expAmountsOfType(userId, GrowthEventType.SAVING_MONTH);
        assertThat(badgeExps).as("本用例应已解锁若干成就，否则第 ① 层是空集假绿").isNotEmpty();
        assertThat(savingExps).as("本用例应已写入储蓄月，否则第 ① 层是空集假绿").isNotEmpty();
        assertThat(badgeExps)
                .as("生产写出来的 BADGE 行 exp_amount 恒为 0（改成非 0 时本条立刻变红，需求 1.11）")
                .containsOnly(0);
        assertThat(savingExps)
                .as("生产写出来的 SAVING_MONTH 行 exp_amount 恒为 0（需求 4.2、12.6）")
                .containsOnly(0);
        assertInvariants(userId, expBefore, levelBefore, "真实结算之后");

        // ── ② 反向：假如实现给这两类事件发了正经验，exp 与 level 会双双改变 ───────────────────
        assertThat(levelBefore).as("反向实验需要上方还有一级可升").isLessThan(GrowthLevelCurve.MAX_LEVEL);
        long toNextLevel = levelCurve.threshold(levelBefore + 1) - expBefore;
        assertThat(toNextLevel).as("距下一级阈值应为正").isPositive();

        String productionSql = productionInsertEventSql();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        // 两行「假如 expAmount 改成非 0 就会长这样」的行：走生产语句、按生产列顺序写入。
        jdbcTemplate.update(productionSql, userId, GrowthEventType.BADGE,
                GrowthBadgeCatalog.eventKeyOf("BUDGET_MASTER"), (int) toNextLevel, now);
        jdbcTemplate.update(productionSql, userId, GrowthEventType.SAVING_MONTH,
                "SAVING_MONTH:2019-12", 1, now);

        settlementService.recalculateOnly(userId);       // 与 settle 共用同一条第 ⑥ 步重算
        long expAfter = profileExp(userId);
        int levelAfter = profileLevel(userId);

        assertThat(expAfter)
                .as("非 0 的 exp_amount 会被 SUM(exp_amount) 计入 → exp 等式失败")
                .isEqualTo(expBefore + toNextLevel + 1);
        assertThat(levelAfter)
                .as("并且刚好顶过下一级阈值 → level 等式也失败")
                .isGreaterThan(levelBefore);
        assertThat(levelAfter).isEqualTo(levelCurve.levelOf(expAfter));

        // ── 复原：删掉这两行再重算，exp / level 回到原值（实验自我复原，不留脏状态）──────────────
        jdbcTemplate.update("DELETE FROM growth_events WHERE user_id = ? AND event_key IN (?, ?)",
                userId, GrowthBadgeCatalog.eventKeyOf("BUDGET_MASTER"), "SAVING_MONTH:2019-12");
        settlementService.recalculateOnly(userId);
        assertInvariants(userId, expBefore, levelBefore, "撤销注入行之后");
    }

    /**
     * 用反射取回生产的事件批量插入语句（{@code GrowthSettlementService.INSERT_EVENT_SQL}）。
     *
     * <p>该常量刻意是 {@code private}——它只该被结算路径使用，不该为了测试而放宽可见性。反射在这里是
     * 有意的耦合：反向断言写入的必须是<b>生产当前那一句</b>（列顺序、ODKU 语义都跟着变），
     * 测试里另抄一份 SQL 做不到这一点。</p>
     */
    private static String productionInsertEventSql() throws Exception {
        Field field = GrowthSettlementService.class.getDeclaredField("INSERT_EVENT_SQL");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    // ---------------- 不变式断言 ----------------

    /**
     * 复核三条不变式：{@code exp} 不变、{@code level} 不变、{@code exp == Σ exp_amount}；
     * 并逐行断言 {@code BADGE} 与 {@code SAVING_MONTH} 的 {@code exp_amount} 恒为 0。
     *
     * @param stage 出错时用于定位是哪一步之后破的
     */
    private void assertInvariants(long userId, long expBefore, int levelBefore, String stage) {
        long exp = profileExp(userId);
        assertThat(exp)
                .as("%s 之后 exp 必须与零经验事件写入前逐项相等（需求 1.11、12.6）", stage)
                .isEqualTo(expBefore);
        assertThat(profileLevel(userId))
                .as("%s 之后 level 必须与写入前逐项相等（需求 1.11、12.6）", stage)
                .isEqualTo(levelBefore);
        assertThat(exp)
                .as("%s 之后 exp 恒等于全部成长事件 exp_amount 之和（需求 12.6）", stage)
                .isEqualTo(expSum(userId));
        assertThat(expAmountsOfType(userId, GrowthEventType.BADGE))
                .as("%s 之后 BADGE 行的 exp_amount 恒为 0（需求 1.11）", stage)
                .allSatisfy(value -> assertThat(value).isZero());
        assertThat(expAmountsOfType(userId, GrowthEventType.SAVING_MONTH))
                .as("%s 之后 SAVING_MONTH 行的 exp_amount 恒为 0（需求 4.2、12.6）", stage)
                .allSatisfy(value -> assertThat(value).isZero());
    }

    /** 极端用例期望的 19 行新增：16 条 {@code BADGE} + 3 条 {@code SAVING_MONTH}。 */
    private static List<String> expectedNineteenRows() {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < CATALOG_CODES.size(); i++) {
            types.add(GrowthEventType.BADGE);
        }
        for (int i = 0; i < LOOKBACK_MONTHS.size(); i++) {
            types.add(GrowthEventType.SAVING_MONTH);
        }
        return types;
    }

    // ---------------- 操作执行 ----------------

    /** 施加一个操作。事实源一律走 {@link JdbcTemplate} 直插，使「本次结算看见什么」完全确定。 */
    private void applyOp(Op op, int index, Ctx ctx) {
        switch (op) {
            case ADD_PLAIN_EXPENSES -> seedExpenses(ctx.userId(), ctx.ledgerId(), 10, ctx.decoyCategoryId());
            case ADD_TRAVEL_EXPENSES -> seedExpenses(ctx.userId(), ctx.ledgerId(), 5, ctx.travelCategoryId());
            case ADD_COLLABORATOR -> addCollaborator(ctx.userId(), ctx.ledgerId());
            case MAKE_SAVING_MONTH -> makeSavingMonth(ctx.userId(), ctx.ledgerId(),
                    LOOKBACK_MONTHS.get(index % LOOKBACK_MONTHS.size()), ctx.decoyCategoryId());
            case SETTLE -> {
                CLOCK.advance(BEYOND_THROTTLE);
                settlementService.settle(ctx.userId(), TriggerSource.RECORD);
            }
            case LIST -> {
                CLOCK.advance(BEYOND_THROTTLE);
                // 写入型 GET：内含一次 OVERVIEW 结算，因此也是零经验事件的写入路径之一。
                assertThat(achievementQueryService.getAchievements(ctx.userId()).achievements())
                        .as("成就清单恒 16 项").hasSize(CATALOG_CODES.size());
            }
            default -> throw new IllegalStateException("未覆盖的操作: " + op);
        }
    }

    // ---------------- 事实源播种 ----------------

    /** 一个用户的固定上下文：自有账本、「旅行」父分类、一个普通分类。 */
    private record Ctx(long userId, long ledgerId, long travelCategoryId, long decoyCategoryId) {
    }

    /**
     * 建一个用户：自有账本 + 两个分类 + 基线经验事件（7 天连续日历 + {@code FIRST_RECORD} +
     * {@code STREAK_7}），并走生产的 {@code recalculateOnly} 把 {@code exp} / {@code level} 落进档案。
     *
     * <p>基线<b>不含</b>任何 {@code BADGE} 与 {@code SAVING_MONTH} 行，也不建总预算与邀请关系：
     * 于是后续每次结算写入的只可能是这两类零经验事件（见类级 Javadoc）。</p>
     */
    private Ctx newUser() {
        long userId = SEQ.getAndIncrement();
        long ledgerId = insertLedger(userId);
        Ctx ctx = new Ctx(userId, ledgerId,
                insertCategory(userId, ledgerId, "旅行"), insertCategory(userId, ledgerId, "餐饮"));

        List<Object[]> events = new ArrayList<>();
        for (int back = 0; back < BASELINE_CALENDAR_DAYS; back++) {
            events.add(eventRow(userId, GrowthEventType.DAILY_RECORD,
                    "DAILY_RECORD:" + YESTERDAY.minusDays(back), EXP_DAILY_RECORD));
        }
        events.add(eventRow(userId, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", EXP_FIRST_RECORD));
        events.add(eventRow(userId, GrowthEventType.STREAK, "STREAK_7", EXP_STREAK_7));
        jdbcTemplate.batchUpdate(SEED_EVENT_SQL, events);

        // 只重算不写事件：档案的 exp / level / 三个天数列 / last_record_date 由上面这些行推导出来。
        settlementService.recalculateOnly(userId);
        return ctx;
    }

    /** 同一记账日（昨天）上批量直插 {@code count} 笔 {@code 1.00} 支出。 */
    private void seedExpenses(long userId, long ledgerId, int count, long categoryId) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(txRow(userId, ledgerId, "expense", "1.00", YESTERDAY.atTime(12, 0), categoryId));
        }
        jdbcTemplate.batchUpdate(INSERT_TX_SQL, batch);
    }

    /**
     * 把某个回看月造成储蓄月：该月收入 {@code 1000.00} + 支出 {@code 100.00}，结余 {@code 900.00}
     * 不小于两成门槛 {@code 200.00}（幂等：同一月份重复调用只是把收支各加一笔，判定结论不变）。
     *
     * <p>两笔交易的 {@code occurred_at} 落在该月、{@code created_at} 一律落在昨天——月份归属看
     * {@code occurred_at}，记账日历看 {@code created_at}，因此造储蓄月不会顺带补出新的记账日。</p>
     */
    private void makeSavingMonth(long userId, long ledgerId, YearMonth month, long categoryId) {
        LocalDateTime occurredAt = month.atDay(10).atTime(10, 0);
        jdbcTemplate.update(INSERT_TX_SQL,
                txRow(userId, ledgerId, "income", "1000.00", occurredAt, categoryId));
        jdbcTemplate.update(INSERT_TX_SQL,
                txRow(userId, ledgerId, "expense", "100.00", occurredAt, categoryId));
    }

    /** 自有账本上加一个他人的 {@code EDITOR} 成员行。 */
    private void addCollaborator(long userId, long ledgerId) {
        jdbcTemplate.update(
                "INSERT INTO ledger_members (ledger_id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
                ledgerId, SEQ.getAndIncrement(), LedgerMember.ROLE_EDITOR, Timestamp.valueOf(SEED_EVENT_AT));
    }

    /** 一笔「有效记账交易」：{@code created_by} = 用户、{@code deleted_at} 为 NULL、{@code ledger_id} 非空。 */
    private static Object[] txRow(long userId, long ledgerId, String type, String amount,
                                  LocalDateTime occurredAt, long categoryId) {
        Timestamp createdAt = Timestamp.valueOf(RECORD_CREATED_AT);
        return new Object[] {userId, ledgerId, userId, type, new BigDecimal(amount),
                placeholderRef(userId), categoryId, Timestamp.valueOf(occurredAt), createdAt, createdAt};
    }

    private static Object[] eventRow(long userId, String eventType, String eventKey, int expAmount) {
        return new Object[] {userId, eventType, eventKey, expAmount, Timestamp.valueOf(SEED_EVENT_AT)};
    }

    /**
     * 「绝不可能是真实主键」且按用户隔离的 {@code account_id} 占位取值。
     *
     * <p>多次迭代共用同一个内存库，与真实分类 / 账户主键撞号会让「旅行」判定误命中。</p>
     */
    private static long placeholderRef(long userId) {
        return 900_000_000L + userId;
    }

    private long insertLedger(long userId) {
        long ledgerId = SEQ.getAndIncrement();
        Timestamp now = Timestamp.valueOf(SEED_EVENT_AT);
        jdbcTemplate.update(
                "INSERT INTO ledgers (id, user_id, name, type, sort_order, is_default, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PERSONAL', 0, FALSE, ?, ?)",
                ledgerId, userId, "exp-inv-" + userId, now, now);
        return ledgerId;
    }

    /** 一级支出分类（{@code kind = 'EXPENSE'}，无父分类）。 */
    private long insertCategory(long userId, long ledgerId, String name) {
        long id = SEQ.getAndIncrement();
        Timestamp now = Timestamp.valueOf(SEED_EVENT_AT);
        jdbcTemplate.update(
                "INSERT INTO categories (id, user_id, ledger_id, parent_id, kind, name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 'EXPENSE', ?, ?, ?)",
                id, userId, ledgerId, name, now, now);
        return id;
    }

    // ---------------- 库读取工具 ----------------

    private long profileExp(long userId) {
        Long exp = jdbcTemplate.queryForObject(
                "SELECT exp FROM user_growth WHERE user_id = ?", Long.class, userId);
        assertThat(exp).as("成长档案行应已建立：userId=%s", userId).isNotNull();
        return exp;
    }

    private int profileLevel(long userId) {
        Integer level = jdbcTemplate.queryForObject(
                "SELECT level FROM user_growth WHERE user_id = ?", Integer.class, userId);
        assertThat(level).as("成长档案行应已建立：userId=%s", userId).isNotNull();
        return level;
    }

    /** 该用户全部成长事件 {@code exp_amount} 之和（数据库聚合，不用内存累加）。 */
    private long expSum(long userId) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(exp_amount), 0) FROM growth_events WHERE user_id = ?",
                Long.class, userId);
        return sum == null ? 0L : sum;
    }

    private List<Integer> expAmountsOfType(long userId, String eventType) {
        return jdbcTemplate.queryForList(
                "SELECT exp_amount FROM growth_events WHERE user_id = ? AND event_type = ? ORDER BY id",
                Integer.class, userId, eventType);
    }

    /** {@code BADGE} 与 {@code SAVING_MONTH} 两类行的总条数（本 spec 新增的两类零经验事件）。 */
    private long zeroExpEventCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_type IN (?, ?)",
                Long.class, userId, GrowthEventType.BADGE, GrowthEventType.SAVING_MONTH);
        return count == null ? 0L : count;
    }

    /** 该用户已解锁的成就编码，按 {@code BADGE} 事件 {@code id} 升序（即写入顺序）。 */
    private List<String> badgeCodes(long userId) {
        return jdbcTemplate.queryForList(
                        "SELECT event_key FROM growth_events WHERE user_id = ? AND event_type = 'BADGE' "
                                + "ORDER BY id", String.class, userId)
                .stream()
                .map(key -> key.substring(GrowthBadgeCatalog.BADGE_KEY_PREFIX.length()))
                .toList();
    }

    private List<String> savingMonthKeys(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT event_key FROM growth_events WHERE user_id = ? AND event_type = ? ORDER BY id",
                String.class, userId, GrowthEventType.SAVING_MONTH);
    }

    /** {@code id} 大于水位线的行的 {@code event_type} 列表（即最后一次结算写入的那一批）。 */
    private List<String> newEventTypesSince(long userId, long watermark) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM growth_events WHERE user_id = ? AND id > ? ORDER BY id",
                String.class, userId, watermark);
    }

    private long maxEventId(long userId) {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return max == null ? 0L : max;
    }

    // ---------------- 基础设施 ----------------

    /** {@code @Primary} 可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日与回看月固定可控。 */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
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
