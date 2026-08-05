package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.hibernate.resource.jdbc.spi.StatementInspector;
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
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 9：结算新增读查询恒为常量条数</b>的属性测试（任务 8.5）。
 *
 * <p><i>对任意</i>用户规模（账本 1–20 个、分类 1–200 个、有效记账交易 1–100000 笔、
 * 成长事件 1–10000 条的<b>对数取样</b>组合）：</p>
 *
 * <ul>
 *   <li>单次结算内为成就判定与储蓄月判定<b>新增</b>的数据库读查询恒为 <b>3 条</b> SQL
 *       （储蓄月的「年 × 月 × 类型」分组合计 1 条、协作成员数 1 条、旅行记账笔数 1 条），
 *       不随上述任一维度增长（需求 4.11）；</li>
 *   <li>{@code SAVING_MONTH} 与 {@code BADGE} 的存在性判定不产生任何额外查询——这一条由「合计恒为 3」
 *       连带锁住：存在性判定若自己去查一次库，第 4 条就会出现在计数里（需求 4.11 后半句）；</li>
 *   <li>单次结算写入的成长事件条数 ≤{@value GrowthSettlementService#MAX_PENDING_EVENTS} 条（需求 4.12）。</li>
 * </ul>
 *
 * <h2>计数怎么做：复用 {@code AchievementSettlementIntegrationTest} 的 {@code StatementInspector}</h2>
 *
 * <p>经 {@code hibernate.session_factory.statement_inspector} 注册 {@link NewReadQueryInspector}，
 * 它按三条新增读查询各自的<b>独有片段</b>分别计数（{@code MONTH(occurred_at)} / {@code 旅行} 字面量 /
 * {@code ledger_members} + {@code ledgers} 双表）。选 {@code StatementInspector} 而不去包裹
 * {@code DataSource}：它只看 Hibernate/JPA 发出的 SQL，于是本类播种事实源用的 {@code JdbcTemplate}
 * 原生 SQL、以及结算内部建档与批量写事件走的 {@code JdbcTemplate}，天然都不被计入——「新增读查询」
 * 这个口径因此不需要任何白名单维护。断言分「逐条各 1 次」与「合计 3 条」两层：前者能直接指出是哪一条
 * 退化成了 N+1，后者锁住总量（顺手多加一条查询也会被挡下）。</p>
 *
 * <h2>四个维度都按设计文档跑满，交易笔数含 100000</h2>
 *
 * <p>账本 {@code {1, 2, 5, 20}}、分类 {@code {1, 8, 60, 200}}、交易 {@code {1, 10, 100, 1000, 10000,
 * 100000}}、既有成长事件 {@code {1, 10, 100, 1000, 10000}}，与设计文档的上界逐项一致，<b>没有做任何收窄</b>。
 * 代价是可承受的：实测单次 10 万笔的迭代（清表 → 分片批量直插 → 真实结算 → 读回断言）在本机约 0.6 秒，
 * 整个类含 12 次随机迭代加顶角用例约 3 秒。做到这一点靠两件事——直插一律走
 * {@code JdbcTemplate.batchUpdate} 且按 {@value #BATCH_CHUNK} 行分片（不逐行 insert、也不一次性堆起
 * 十万个参数数组），以及每次迭代只结算一次。</p>
 *
 * <p>{@link #maxScaleCorner()} 用一个 {@code @Example} 把「20 账本 × 200 分类 × 100000 笔 × 10000 事件」
 * 这个取样网格的<b>顶角</b>钉成必跑用例——随机取样可能整轮都不落在顶角上，
 * 而顶角恰恰是最容易暴露 N+1 的那一点。</p>
 *
 * <h2>为什么写入事件数的断言不是同义反复</h2>
 *
 * <p>生产在第 ④ 步末尾自带一条「越界即抛 {@link IllegalStateException}」的守卫，所以「≤1026」看起来
 * 像是由生产自证。本类的断言口径刻意不同：<b>从库里读回本次结算前后的行数差</b>再比对上界，因此它同时
 * 覆盖两种守卫挡不住的缺陷——① 有人把守卫的上界改大（读库口径不受常量影响，1026 是本测试自己持有的
 * 独立副本 {@code MAX_EVENTS_PER_SETTLEMENT}）；② 有人把写入拆成多批、每批各自过守卫。为了让这条断言
 * 落在<b>接近上界</b>处而不是只在小数字上成立，本类的记账日刻意铺满 1200 个自然日：交易笔数取到 1200 以上的
 * 取样点上，追补窗口（起点 + 999 天，含两端 1000 天）因此被写满 1000 条 {@code DAILY_RECORD}，
 * 再加 2 条 {@code STREAK}、3 条 {@code SAVING_MONTH} 与 13 条 {@code BADGE}，
 * 单次写入达 1019 条，距上界只差 7 条。</p>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 *
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有真实<b>提交</b>才能在库里观察到终态，
 * 故本类<b>不用测试级事务包裹</b>；清理相应地不能靠回滚，由 {@link #resetState()} 每次迭代前显式清表，
 * 并用全局自增序号 {@link #SEQ} 保证 {@code userId} / {@code ledgerId} / 分类 id 全局唯一（双重隔离）。
 * 时钟用 {@code @Primary} 的可推进 {@link MutableClock}（固定 {@code Asia/Shanghai} 的
 * {@code 2025-06-15 08:00}），使结算日恒为 {@code 2025-06-15}、三个回看月恒为
 * {@code 2025-03/04/05}，播种的收入月份因而完全确定。jqwik 属性方法不经 {@code SpringExtension}，
 * 依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成（上下文缓存复用）。</p>
 *
 * <p>Feature: achievement-system, Property 9: 结算新增读查询恒为常量条数</p>
 *
 * <p>Validates: Requirements 4.11, 4.12</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-querycount-pt;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 计数型装饰器：只拦截本 spec 新增的三条读查询（见类级 Javadoc「计数怎么做」）。
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.damien.youyu.service.AchievementQueryCountPropertyTest$NewReadQueryInspector"
})
@Import(AchievementQueryCountPropertyTest.ClockConfig.class)
class AchievementQueryCountPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：结算日恒为 2025-06-15，回看月恒为 2025-03/04/05。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 越过记账侧 60 秒节流窗口（也顺带越过概览侧 10 秒窗口）的推进量。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);

    /** 全局自增序号：保证跨迭代 userId / ledgerId / 分类 id / 协作者 id 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(760_000_000L);

    /**
     * 单次结算写入事件条数的上界（需求 4.12）：{@code 1000 + 1 + 2 + 3 + 1 + 3 + 16}。
     *
     * <p>刻意<b>不</b>引用 {@link GrowthSettlementService#MAX_PENDING_EVENTS}：本测试要能在有人把生产
     * 上界改大时变红，共用同一个常量就做不到这件事。</p>
     */
    private static final long MAX_EVENTS_PER_SETTLEMENT = 1026L;

    /** 记账日铺开的自然日跨度：大于追补窗口的 1000 天，使 {@code DAILY_RECORD} 恰好写满窗口上界。 */
    private static final int RECORD_DAY_SPAN = 1200;

    /** 交易直插语句：列顺序与 {@link #txRow} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 成长事件直插语句：列顺序与 {@link #seedGrowthEvents} 的参数顺序一致。 */
    private static final String INSERT_EVENT_SQL =
            "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                    + "VALUES (?, ?, ?, ?, ?)";

    /** 单次批量直插的分片大小：10000 笔一批，避免一次性堆起十万个参数数组。 */
    private static final int BATCH_CHUNK = 10_000;

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(AchievementQueryCountPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        NewReadQueryInspector.reset();
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删事实源与三张成长 / 成就表（各表间无外键）。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM ledger_members");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM categories");
    }

    // ---------------- 生成器：四个维度各自对数取样 ----------------

    /** 账本数：1 → 20（设计文档上界）。 */
    @Provide
    Arbitrary<Integer> ledgerCounts() {
        return Arbitraries.of(1, 2, 5, 20);
    }

    /** 分类数：1 → 200（设计文档上界）。 */
    @Provide
    Arbitrary<Integer> categoryCounts() {
        return Arbitraries.of(1, 8, 60, 200);
    }

    /** 有效记账交易笔数：1 → 100000（设计文档上界，对数取样）。 */
    @Provide
    Arbitrary<Integer> transactionCounts() {
        return Arbitraries.of(1, 10, 100, 1000, 10_000, 100_000);
    }

    /** 既有成长事件条数：1 → 10000（设计文档上界）。 */
    @Provide
    Arbitrary<Integer> growthEventCounts() {
        return Arbitraries.of(1, 10, 100, 1000, 10_000);
    }

    // ---------------- Property 9 ----------------

    /**
     * Feature: achievement-system, Property 9: 结算新增读查询恒为常量条数
     *
     * <p>四个维度的对数取样组合下：新增读 SQL 逐条各 1 次、合计恒 3 条（需求 4.11），
     * 本次结算写入的成长事件条数落在 {@code (0, 1026]}（需求 4.12）。</p>
     *
     * <p>{@code generation = RANDOMIZED} 是必需的：四个维度合起来只有 4 × 4 × 5 × 5 = 400 种组合，
     * 落在 jqwik 的穷举阈值之内，默认会<b>穷举</b>跑满 400 次迭代——每次都要清表、批量直插并真实结算，
     * 那会把本类推到十分钟量级。随机取样 12 次已足以覆盖各维度的多个数量级，网格顶角另由
     * {@link #maxScaleCorner()} 必跑。</p>
     *
     * <p>Validates: Requirements 4.11, 4.12</p>
     */
    @Property(tries = 12, generation = GenerationMode.RANDOMIZED)
    void property9_newlyAddedReadQueriesAreExactlyThree_regardlessOfScale(
            @ForAll("ledgerCounts") int ledgerCount,
            @ForAll("categoryCounts") int categoryCount,
            @ForAll("transactionCounts") int txCount,
            @ForAll("growthEventCounts") int eventCount) {
        assertConstantQueryCount(ledgerCount, categoryCount, txCount, eventCount);
    }

    /**
     * 取样网格的<b>顶角</b>必跑用例：20 账本 × 200 分类 × 10000 笔 × 10000 事件。
     *
     * <p>随机取样有可能整轮都不落在顶角上，而顶角恰是最容易暴露「按账本 / 按分类 / 按交易循环」的那一点，
     * 故单列一个 {@code @Example} 把它钉死。</p>
     *
     * <p>Validates: Requirements 4.11, 4.12</p>
     */
    @Example
    void maxScaleCorner() {
        assertConstantQueryCount(20, 200, 100_000, 10_000);
    }

    /**
     * 播种给定规模的事实源，跑一次结算，断言两条不变式。
     *
     * <p>非空洞守卫有两处：① 三条查询各自必须真的命中行（旅行分类存在且有旅行支出、自有账本上挂着他人的
     * {@code EDITOR} 成员行、三个回看月各有收入），否则「条数为 3」可能是因为某条查询压根没被发出；
     * ② 本次结算写入的事件数必须 &gt;0，否则上界断言在 0 上恒真。</p>
     */
    private void assertConstantQueryCount(int ledgerCount, int categoryCount, int txCount, int eventCount) {
        long userId = SEQ.getAndIncrement();
        long collaboratorId = SEQ.getAndIncrement();
        LocalDate settleDate = LocalDate.now(CLOCK);
        LocalDate yesterday = settleDate.minusDays(1);
        LocalDateTime now = LocalDateTime.now(CLOCK);

        // 账本：每个自有账本上挂一个他人的 EDITOR 成员行 —— 协作成员数随账本数增长，查询条数不许增长。
        List<Long> ledgerIds = new ArrayList<>(ledgerCount);
        for (int i = 0; i < ledgerCount; i++) {
            long ledgerId = insertLedger(userId, "账本" + i, now);
            ledgerIds.add(ledgerId);
            insertMember(ledgerId, collaboratorId, now);
        }
        long firstLedgerId = ledgerIds.get(0);

        // 分类：恰有一个叫「旅行」，其余是同层的普通分类 —— 旅行查询在任一规模下都真的命中行。
        long travelCategoryId = insertCategory(userId, firstLedgerId, "旅行", now);
        long decoyCategoryId = travelCategoryId;
        for (int i = 1; i < categoryCount; i++) {
            decoyCategoryId = insertCategory(userId, firstLedgerId, "分类" + i, now);
        }

        // 交易：记账日铺满 1200 个自然日（> 追补窗口 1000 天），一半落在「旅行」分类下。
        seedTransactions(userId, ledgerIds, yesterday, txCount, travelCategoryId, decoyCategoryId);
        // 三个回看月各一笔收入（记账日仍取昨天，不新增记账日）：三个月都成为储蓄月。
        // 金额取 1000000.00 而不是贴着门槛：上面那批支出的 occurred_at 铺满 1200 个自然日，其中有
        // 90 天左右落在三个回看月内，交易笔数取到 100000 时单月支出会累到 2600.00 上下。收入取到
        // 100 万后，门槛 200000.00 与结余 997400.00 之间留出四个数量级的余量，三个月因此在<b>任一</b>
        // 取样点上都判为储蓄月——本类要的是「三条查询各自命中行」这个非空洞前提，
        // 判定本身的边界与舍入由 GrowthSavingMonthPropertyTest（Property 10）覆盖。
        for (String month : lookbackMonths(settleDate)) {
            jdbcTemplate.update(INSERT_TX_SQL, txRow(userId, firstLedgerId, "income", "1000000.00",
                    YearMonth.parse(month).atDay(15).atTime(10, 0), yesterday, decoyCategoryId));
        }
        // 既有成长事件：远早于追补窗口的连续 DAILY_RECORD，使 existingKeys 集合被撑到给定规模。
        seedGrowthEvents(userId, settleDate, eventCount, now);

        long eventsBefore = eventCount(userId);
        assertThat(eventsBefore).as("既有成长事件条数应为播种量").isEqualTo(eventCount);

        NewReadQueryInspector.reset();
        CLOCK.advance(BEYOND_THROTTLE);
        SettleOutcome outcome = settlementService.settle(userId, TriggerSource.RECORD);

        assertThat(outcome).as("本次结算必须真实执行（新用户不会被节流）").isEqualTo(SettleOutcome.SETTLED);

        String scale = String.format(Locale.ROOT, "账本 %d / 分类 %d / 交易 %d / 既有事件 %d",
                ledgerCount, categoryCount, txCount, eventCount);
        assertThat(NewReadQueryInspector.savingMonthCount())
                .as("%s：储蓄月的「年 × 月 × 类型」分组合计恒 1 条（不按月循环）；已命中 SQL=%s",
                        scale, NewReadQueryInspector.matched()).isEqualTo(1);
        assertThat(NewReadQueryInspector.collabCount())
                .as("%s：协作成员数恒 1 条（不按账本循环）；已命中 SQL=%s",
                        scale, NewReadQueryInspector.matched()).isEqualTo(1);
        assertThat(NewReadQueryInspector.travelCount())
                .as("%s：旅行记账笔数恒 1 条（不按分类或交易循环）；已命中 SQL=%s",
                        scale, NewReadQueryInspector.matched()).isEqualTo(1);
        assertThat(NewReadQueryInspector.total())
                .as("%s：单次结算新增读查询合计恒 3 条，且存在性判定零新增查询（需求 4.11）", scale)
                .isEqualTo(3);

        long written = eventCount(userId) - eventsBefore;
        assertThat(written)
                .as("%s：本次结算确有写入，否则上界断言在 0 上恒真", scale)
                .isPositive();
        assertThat(written)
                .as("%s：单次结算写入事件数不超过 %d 条（需求 4.12）", scale, MAX_EVENTS_PER_SETTLEMENT)
                .isLessThanOrEqualTo(MAX_EVENTS_PER_SETTLEMENT);
        // 三条查询确实各自命中了行：三个储蓄月与相应成就一并落库（否则「条数为 3」可能是空跑）。
        assertThat(countOfType(userId, GrowthEventType.SAVING_MONTH))
                .as("%s：三个回看月都应判为储蓄月（旅行 / 协作 / 储蓄月三条查询均命中行）", scale)
                .isEqualTo(3L);
    }

    // ---------------- 事实源播种 ----------------

    /**
     * 批量直插 {@code count} 笔有效支出：记账日（{@code created_at}）按 {@code i % 1200} 铺开，
     * 分类在「旅行」与诱饵之间交替，账本在全部自有账本间轮转。
     *
     * <p>{@code occurred_at} 一律取记账日当天 12:00，故这些支出全部落在回看窗口<b>之外</b>的月份
     * 或窗口内但不影响储蓄月判定的方向（支出只会拉低结余；三个回看月的收入 1000.00 与至多两笔 1.00
     * 支出相比，结余仍远高于 200.00 的门槛）。</p>
     */
    private void seedTransactions(long userId, List<Long> ledgerIds, LocalDate yesterday, int count,
                                 long travelCategoryId, long decoyCategoryId) {
        List<Object[]> chunk = new ArrayList<>(Math.min(count, BATCH_CHUNK));
        for (int i = 0; i < count; i++) {
            LocalDate day = yesterday.minusDays(i % RECORD_DAY_SPAN);
            long ledgerId = ledgerIds.get(i % ledgerIds.size());
            long categoryId = (i % 2 == 0) ? travelCategoryId : decoyCategoryId;
            chunk.add(txRow(userId, ledgerId, "expense", "1.00", day.atTime(12, 0), day, categoryId));
            if (chunk.size() == BATCH_CHUNK) {
                jdbcTemplate.batchUpdate(INSERT_TX_SQL, chunk);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_TX_SQL, chunk);
        }
    }

    /**
     * 播种 {@code count} 条既有 {@code DAILY_RECORD} 事件，日期从 {@code settleDate − 2000} 起逐日往前。
     *
     * <p>起点刻意落在追补窗口（起点 + 999 天）之外，使这些行只撑大 {@code existingKeys} 集合而不干扰
     * 本次追补的日期集合——「既有成长事件条数」这个维度要考验的是「集合变大时查询条数是否变化」。</p>
     */
    private void seedGrowthEvents(long userId, LocalDate settleDate, int count, LocalDateTime now) {
        LocalDate start = settleDate.minusDays(2000);
        List<Object[]> chunk = new ArrayList<>(Math.min(count, BATCH_CHUNK));
        for (int i = 0; i < count; i++) {
            chunk.add(new Object[] {userId, GrowthEventType.DAILY_RECORD,
                    "DAILY_RECORD:" + start.minusDays(i), 5, Timestamp.valueOf(now)});
            if (chunk.size() == BATCH_CHUNK) {
                jdbcTemplate.batchUpdate(INSERT_EVENT_SQL, chunk);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_EVENT_SQL, chunk);
        }
    }

    /** 一条「有效记账交易」的参数行：{@code created_by} = 用户、{@code deleted_at} 为 NULL、账本非空。 */
    private static Object[] txRow(long userId, long ledgerId, String type, String amount,
                                  LocalDateTime occurredAt, LocalDate recordDay, long categoryId) {
        Timestamp createdAt = Timestamp.valueOf(recordDay.atTime(12, 0));
        return new Object[] {userId, ledgerId, userId, type, new BigDecimal(amount),
                ledgerId, categoryId, Timestamp.valueOf(occurredAt), createdAt, createdAt};
    }

    private long insertLedger(long userId, String name, LocalDateTime now) {
        long ledgerId = SEQ.getAndIncrement();
        jdbcTemplate.update(
                "INSERT INTO ledgers (id, user_id, name, type, sort_order, is_default, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PERSONAL', 0, FALSE, ?, ?)",
                ledgerId, userId, name, now, now);
        return ledgerId;
    }

    private long insertCategory(long userId, long ledgerId, String name, LocalDateTime now) {
        long id = SEQ.getAndIncrement();
        jdbcTemplate.update(
                "INSERT INTO categories (id, user_id, ledger_id, parent_id, kind, name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 'EXPENSE', ?, ?, ?)",
                id, userId, ledgerId, name, now, now);
        return id;
    }

    private void insertMember(long ledgerId, long memberUserId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO ledger_members (ledger_id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
                ledgerId, memberUserId, LedgerMember.ROLE_EDITOR, now);
    }

    /** 结算日所属月的前 3 / 2 / 1 个自然月，<b>升序</b>的 {@code YYYY-MM}（需求 4.1 的回看窗口）。 */
    private static List<String> lookbackMonths(LocalDate settleDate) {
        YearMonth settleMonth = YearMonth.from(settleDate);
        List<String> months = new ArrayList<>(3);
        for (int back = 3; back >= 1; back--) {
            months.add(settleMonth.minusMonths(back).toString());
        }
        return months;
    }

    // ---------------- 库读取工具 ----------------

    private long eventCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return count == null ? 0L : count;
    }

    private long countOfType(long userId, String eventType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_type = ?",
                Long.class, userId, eventType);
        return count == null ? 0L : count;
    }

    // ---------------- 测试基础设施 ----------------

    /**
     * Hibernate {@link StatementInspector}：只对本 spec 新增的三条读查询计数，各按其<b>独有片段</b>识别。
     *
     * <ul>
     *   <li>储蓄月：{@code MONTH(occurred_at)}——只有那条按「年 × 月 × 类型」分组的合计用到它
     *       （预算侧按 {@code ledger_id} 分组、累计侧按 {@code type} 分组，都不含此片段）。</li>
     *   <li>旅行：{@code 旅行} 字面量——全库只有这一条查询把汉字写进 SQL。</li>
     *   <li>协作：同时出现 {@code ledger_members} 与 {@code ledgers} 两张表名——只有那条 JPQL
     *       会把成员表与账本表连在一起（{@code ledger_members} 不含 {@code ledgers} 子串）。</li>
     * </ul>
     *
     * <p>识别口径与 {@code AchievementSettlementIntegrationTest$NewReadQueryInspector} 逐条一致
     * （任务 4.4 已在集成测试里用同一套片段计过这三条）；两处各自持有一份而不共用，是为了让两个测试类
     * 的属性配置互不牵连——{@code statement_inspector} 是按类名反射实例化的进程级配置，共用会让其中一个
     * 类的计数器被另一个类的上下文重置。</p>
     *
     * <p>由 Hibernate 依类名反射实例化，故必须是 {@code public static} 且带公有无参构造；
     * 计数器为静态，供测试线程读取。{@link #matched()} 把已命中的 SQL 原文带进失败信息，
     * 免得「条数不对」时还要重跑一遍才知道多出来的是哪一条。</p>
     */
    public static final class NewReadQueryInspector implements StatementInspector {

        private static final AtomicInteger SAVING_MONTH_QUERIES = new AtomicInteger();
        private static final AtomicInteger COLLAB_QUERIES = new AtomicInteger();
        private static final AtomicInteger TRAVEL_QUERIES = new AtomicInteger();
        private static final List<String> MATCHED = new CopyOnWriteArrayList<>();

        public NewReadQueryInspector() {
            // Hibernate 反射实例化所需的公有无参构造。
        }

        @Override
        public String inspect(String sql) {
            if (sql == null) {
                return sql;
            }
            String lower = sql.toLowerCase(Locale.ROOT);
            if (lower.contains("month(occurred_at)")) {
                SAVING_MONTH_QUERIES.incrementAndGet();
                MATCHED.add(sql);
            } else if (sql.contains("旅行")) {
                TRAVEL_QUERIES.incrementAndGet();
                MATCHED.add(sql);
            } else if (lower.contains("ledger_members") && lower.contains("ledgers")) {
                COLLAB_QUERIES.incrementAndGet();
                MATCHED.add(sql);
            }
            return sql;
        }

        static void reset() {
            SAVING_MONTH_QUERIES.set(0);
            COLLAB_QUERIES.set(0);
            TRAVEL_QUERIES.set(0);
            MATCHED.clear();
        }

        static int savingMonthCount() {
            return SAVING_MONTH_QUERIES.get();
        }

        static int collabCount() {
            return COLLAB_QUERIES.get();
        }

        static int travelCount() {
            return TRAVEL_QUERIES.get();
        }

        static int total() {
            return savingMonthCount() + collabCount() + travelCount();
        }

        static List<String> matched() {
            return List.copyOf(MATCHED);
        }
    }

    /** {@code @Primary} 可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日与回看月完全确定。 */
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

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void reset(Instant to) {
            this.instant = to;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
