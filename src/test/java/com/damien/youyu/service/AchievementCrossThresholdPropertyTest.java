package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.LedgerMember;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 4：跨门槛不漏发，且同批事件 id 序与展示序号一致</b>的属性测试（任务 8.2）。
 *
 * <p><i>对任意</i>统计量跃迁（八个口径各自在 {@code 门槛−1 / 门槛 / 门槛+1} 的档位上取样，
 * 笛卡尔组合）× 初始状态 ∈ {零数据, 已解锁部分低门槛} × 是否在同一次结算内跃迁，断言两条不变式：</p>
 *
 * <ul>
 *   <li><b>解锁集合 == {@code {code : metric(code) ≥ target(code)}}</b>（并上初始就已解锁的那些，
 *       需求 2.6、2.12、2.13）：一次结算内为<b>全部</b>已达门槛且未解锁的成就各写入一条 {@code BADGE}
 *       事件，跨门槛时较低门槛的成就一枚不漏；未达门槛的一枚不发。</li>
 *   <li><b>同批 {@code BADGE} 事件的 {@code id} 序与展示序号一致</b>（需求 2.6 后半句）：
 *       同一次结算写入的那批 {@code BADGE} 行按 {@code id} 升序取出后，其在需求 1.1 表格里的序号
 *       严格升序——播报顺序（需求 5.4 按 {@code id} 升序取）由此确定。</li>
 * </ul>
 *
 * <h2>为什么这条属性「需靠测试排除分歧」</h2>
 *
 * <p>它依赖两处极易在重构中被破坏的实现细节：① {@code GrowthBadgeCatalog.qualified} 遍历<b>整份</b>
 * 清单且各判定之间<b>没有 {@code else}</b>——写成 {@code else if} 的阶梯会让笔数从 0 跃到 1200 时只发
 * {@code RECORD_1000} 一枚；② 它返回 {@code LinkedHashSet} 且按清单序号升序，结算按这个顺序组装
 * {@code pending}、批量插入按 {@code pending} 顺序发出，{@code id} 序才与展示序号一致——换成
 * {@code HashSet} 或另行排序，集合断言仍然全绿而播报顺序变成不可预期的。本类因此把「集合」与
 * 「顺序」分成两条独立断言。</p>
 *
 * <h2>八个口径分别怎么造，以及参考模型为什么是精确的</h2>
 *
 * <p>参考模型（{@link #referenceFacts} + {@link #referenceQualified}）是需求 1.1 表格与需求 3 各口径
 * 定义的<b>独立副本</b>，不复用 {@link GrowthFacts} 与 {@link GrowthBadgeCatalog} 的任何判定代码。
 * 为了让它精确到「等于」而不是「不小于」，播种做了几处刻意约束：</p>
 *
 * <ul>
 *   <li><b>笔数</b>：全部交易的 {@code created_at} 都落在<b>昨天</b>，因此记账日历只多出昨天这一天；
 *       {@code occurred_at} 同样落在昨天，且<b>全部是支出</b>——储蓄月判定要求「收入 ≥ 0.01」，
 *       零收入使任何回看月都不可能被判为储蓄月，储蓄月数因而完全由直插的
 *       {@code SAVING_MONTH} 事件决定。</li>
 *   <li><b>连续 / 累计天数</b>：直插一段<b>以昨天结尾、长度为 L 的连续</b> {@code DAILY_RECORD}，
 *       于是重算后 {@code max_streak_days == total_record_days == max(L, 有交易 ? 1 : 0)}
 *       ——两个口径同时被这一个档位驱动，{@code STREAK_*} 四枚与 {@code DAYS_100} 一起被覆盖。</li>
 *   <li><b>预算达成月数 / 储蓄月数</b>：直插 {@code BUDGET_MET:} / {@code SAVING_MONTH:} 事件。
 *       预算侧另有保障：用户虽然有自有账本，但<b>不设任何总预算</b>，
 *       {@code GrowthBudgetEvaluator} 因而对每个回看月都直接跳过，不会凭空多发一个月。</li>
 *   <li><b>旅行笔数</b>：旅行支出<b>也是</b>有效记账交易，故参考笔数取
 *       {@code max(笔数档, 旅行档)}（旅行档 &gt; 笔数档时用旅行支出补足，见 {@link #seedTransactions}）。
 *       这不是放宽，而是把两个口径的耦合如实写进参考模型。</li>
 *   <li><b>协作成员数 / 首次邀请</b>：自有账本上直插若干 {@code EDITOR} 成员行；邀请侧直插
 *       {@code FIRST_INVITE} 事件（需求 3.8 的存在型口径只看这一行）。</li>
 * </ul>
 *
 * <h2>驱动方式与清理</h2>
 *
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，必须让它<b>真正提交</b>才能在库里观察到
 * 终态，故本测试<b>不</b>用测试级事务包裹；清理不靠回滚，由 {@link #resetState()} 在每次迭代前显式清表，
 * 并用全局自增序号 {@link #SEQ} 保证 {@code userId} 全局唯一。事实源与事件一律走 {@link JdbcTemplate}
 * 直插：{@code BADGE} 与经验事件的写入路径在生产代码里只有结算一处（仓储刻意不提供单行写入方法），
 * 播种任意初始状态只能走原生 SQL。jqwik 属性方法不经 {@code SpringExtension}，依赖注入由
 * {@link TestContextManager} 在 {@link BeforeTry} 里手工完成（上下文缓存复用，多次迭代只加载一次）。</p>
 *
 * <p>记账日一律取<b>昨天</b>还有第二个作用：{@code last_record_date != 结算日} 恒成立，
 * 记账侧 60 秒节流窗口的两个条件（窗口内 <b>且</b> 今天已记过账）永不同时满足，
 * 因此「分两次结算」的那一半场景里第二次结算必定真实执行而非被跳过。</p>
 *
 * <p>Feature: achievement-system, Property 4: 跨门槛不漏发，且同批事件 id 序与展示序号一致</p>
 *
 * <p>Validates: Requirements 2.6, 2.12, 2.13</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-cross-threshold-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class AchievementCrossThresholdPropertyTest {

    /**
     * 16 枚成就的编码与<b>展示顺序</b>（需求 1.1 表格的独立副本）。
     *
     * <p>下标即展示序号，用于把「同批 {@code BADGE} 事件 id 序 == 展示序号序」钉死：断言的一侧读库里的
     * {@code id}，另一侧读本数组，两侧各自独立取值，不会因为共用同一个顺序来源而恒真。</p>
     */
    private static final List<String> CATALOG_CODES = List.of(
            "FIRST_RECORD",
            "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365",
            "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000", "DAYS_100",
            "INVITE_1", "COLLAB_1",
            "BUDGET_MET", "BUDGET_MASTER", "SAVING_MASTER", "TRAVEL_MASTER");

    /** 16 枚成就的门槛数值，下标与 {@link #CATALOG_CODES} 对齐（需求 1.1 表格的独立副本）。 */
    private static final List<Integer> CATALOG_TARGETS = List.of(
            1,
            7, 30, 100, 365,
            10, 100, 500, 1000, 100,
            1, 1,
            1, 3, 3, 10);

    /** 「已解锁部分低门槛」这个初始状态所预置的编码（刻意选三个不同口径的低门槛成就）。 */
    private static final List<String> PRE_UNLOCKED_CODES = List.of("FIRST_RECORD", "STREAK_7", "RECORD_10");

    /** 预置 {@code BADGE} 行的解锁时刻：远早于结算时刻，使「本批」与「历史」可按 id 与时刻两重区分。 */
    private static final LocalDateTime PRE_UNLOCK_AT = LocalDateTime.of(2024, 1, 2, 3, 4, 5);

    /** 累计笔数档：五个 {@code RECORD_COUNT} 门槛（1/10/100/500/1000）各取 {@code 门槛±1} 与门槛。 */
    private static final int[] RECORD_LADDER = {
            0, 1, 2, 9, 10, 11, 99, 100, 101, 499, 500, 501, 999, 1000, 1001};

    /** 记账日历连续段长度档：四个 {@code MAX_STREAK} 门槛与 {@code DAYS_100} 门槛的两侧。 */
    private static final int[] CALENDAR_LADDER = {
            0, 6, 7, 8, 29, 30, 31, 99, 100, 101, 364, 365, 366};

    /** 预算达成月数档：门槛 1（{@code BUDGET_MET}）与门槛 3（{@code BUDGET_MASTER}）的两侧。 */
    private static final int[] BUDGET_LADDER = {0, 1, 2, 3, 4};

    /** 储蓄月数档：门槛 3（{@code SAVING_MASTER}）的两侧。 */
    private static final int[] SAVING_LADDER = {0, 2, 3, 4};

    /** 协作成员数档：门槛 1（{@code COLLAB_1}）的两侧。 */
    private static final int[] COLLAB_LADDER = {0, 1, 2};

    /** 旅行支出笔数档：门槛 10（{@code TRAVEL_MASTER}）的两侧，0 表示不建「旅行」分类。 */
    private static final int[] TRAVEL_LADDER = {0, 9, 10, 11};

    /** 直插 {@code BUDGET_MET:} / {@code SAVING_MONTH:} 事件所用的月份键（远离回看窗口，互不干扰）。 */
    private static final List<String> SEED_MONTHS = List.of("2019-01", "2019-02", "2019-03", "2019-04");

    /** 交易直插语句：列顺序与 {@link #txRow} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    private static final String INSERT_EVENT_SQL =
            "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                    + "VALUES (?, ?, ?, ?, ?)";

    /** 同一个 H2 库跨迭代复用，用序号保证 userId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(970_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Clock clock;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(AchievementCrossThresholdPropertyTest.class).prepareTestInstance(this);
        // 结算真实提交，清理不能靠事务回滚：每次迭代前硬删事实源与成长/成就三表。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM ledger_members");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM categories");
    }

    // ---------------- 生成器 ----------------

    /**
     * 一次跃迁场景：八个口径各取一个档位 × 初始是否已解锁部分低门槛 × 是否在同一次结算内跃迁。
     *
     * @param splitSettlement {@code true} 时分两次结算：先落地「与交易无关」的口径
     *                        （日历 / 邀请 / 协作），再落地其余口径；{@code false} 时一次结算跃迁到位
     */
    record Scenario(int recordKind, int calendarKind, int budgetKind, int savingKind,
                    int collabKind, int travelKind, boolean firstInvite,
                    boolean preUnlocked, boolean splitSettlement) {

        int recordValue() {
            return RECORD_LADDER[recordKind];
        }

        int calendarLength() {
            return CALENDAR_LADDER[calendarKind];
        }

        int budgetValue() {
            return BUDGET_LADDER[budgetKind];
        }

        int savingValue() {
            return SAVING_LADDER[savingKind];
        }

        int collabValue() {
            return COLLAB_LADDER[collabKind];
        }

        int travelValue() {
            return TRAVEL_LADDER[travelKind];
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<Integer>> kinds = Combinators.combine(
                Arbitraries.integers().between(0, RECORD_LADDER.length - 1),
                Arbitraries.integers().between(0, CALENDAR_LADDER.length - 1),
                Arbitraries.integers().between(0, BUDGET_LADDER.length - 1),
                Arbitraries.integers().between(0, SAVING_LADDER.length - 1),
                Arbitraries.integers().between(0, COLLAB_LADDER.length - 1),
                Arbitraries.integers().between(0, TRAVEL_LADDER.length - 1)
        ).as((a, b, c, d, e, f) -> List.of(a, b, c, d, e, f));
        Arbitrary<List<Boolean>> flags = Arbitraries.of(true, false).list().ofSize(3);
        return Combinators.combine(kinds, flags).as((k, f) -> new Scenario(
                k.get(0), k.get(1), k.get(2), k.get(3), k.get(4), k.get(5),
                f.get(0), f.get(1), f.get(2)));
    }

    // ---------------- Property 4 ----------------

    /**
     * Feature: achievement-system, Property 4: 跨门槛不漏发，且同批事件 id 序与展示序号一致
     *
     * <p>播种初始状态与跃迁后的事实源，触发一次（或分两次）结算，随后从库读回全部 {@code BADGE} 行：
     * ① 解锁集合等于「初始已解锁 ∪ 参考模型判定达标的编码」，一枚不漏一枚不多；
     * ② 最后一次结算新写入的那批 {@code BADGE} 行按 {@code id} 升序恰好等于「本次应新增的编码」
     * 按展示序号升序排列的结果。</p>
     *
     * <p>Validates: Requirements 2.6, 2.12, 2.13</p>
     */
    @Property(tries = 20)
    void property4_crossingThresholdsUnlocksEveryQualifiedBadgeInDisplayOrder(
            @ForAll("scenarios") Scenario scenario) {
        long userId = SEQ.getAndIncrement();
        LocalDate today = LocalDate.now(clock);
        LocalDate yesterday = today.minusDays(1);
        long ledgerId = insertLedger(userId, "自有账本");

        Set<String> preUnlocked = scenario.preUnlocked()
                ? new LinkedHashSet<>(PRE_UNLOCKED_CODES) : Set.of();
        if (scenario.preUnlocked()) {
            seedBadges(userId, PRE_UNLOCKED_CODES);
        }

        // ── 与交易无关的口径：日历、首次邀请、协作成员 ─────────────────────────────
        seedCalendar(userId, yesterday, scenario.calendarLength());
        if (scenario.firstInvite()) {
            insertEvent(userId, GrowthEventType.FIRST_INVITE, "FIRST_INVITE", 80, PRE_UNLOCK_AT);
        }
        seedCollaborators(userId, ledgerId, scenario.collabValue());

        Facts finalFacts = referenceFacts(scenario);
        if (scenario.splitSettlement()) {
            // 第一次结算只看得到上面三个口径；其余口径此刻恒为 0（笔数 / 旅行 / 预算 / 储蓄）。
            Facts stageOneFacts = new Facts(0L, scenario.calendarLength(), scenario.calendarLength(),
                    0L, scenario.firstInvite(), 0L, scenario.collabValue(), 0L);
            assertThat(settlementService.settle(userId, TriggerSource.RECORD))
                    .as("第一次结算真实执行（记账日不是今天，记账侧节流条件不成立）")
                    .isEqualTo(SettleOutcome.SETTLED);
            Set<String> afterStageOne = union(preUnlocked, referenceQualified(stageOneFacts));
            assertThat(badgeCodesById(userId))
                    .as("第一次结算后的解锁集合 == 初始已解锁 ∪ 该次事实下达标的编码（需求 2.12）")
                    .containsExactlyInAnyOrderElementsOf(afterStageOne);

            long watermark = maxEventId(userId);
            seedTransactions(userId, ledgerId, yesterday, scenario);
            seedMonthEvents(userId, scenario);

            assertThat(settlementService.settle(userId, TriggerSource.RECORD))
                    .as("第二次结算真实执行（记账日不是今天，记账侧节流条件不成立）")
                    .isEqualTo(SettleOutcome.SETTLED);
            assertBatchAndTotal(userId, watermark, afterStageOne, finalFacts);
        } else {
            // 一次结算内跃迁到位：全部事实源先播种完，再结算一次。
            seedTransactions(userId, ledgerId, yesterday, scenario);
            seedMonthEvents(userId, scenario);

            long watermark = maxEventId(userId);
            assertThat(settlementService.settle(userId, TriggerSource.RECORD))
                    .as("结算真实执行").isEqualTo(SettleOutcome.SETTLED);
            assertBatchAndTotal(userId, watermark, preUnlocked, finalFacts);
        }
    }

    /**
     * 零数据 → 八个口径同时越过<b>全部</b> 16 枚成就的门槛：一次结算解锁 16 枚，
     * {@code id} 升序恰好等于展示顺序（需求 2.6 的极端用例）。
     *
     * <p>这是属性方法覆盖不到的一个角：随机取样几乎不会同时把八个档位都取到最高档。它同时也是
     * 「{@code qualified} 各判定之间没有 {@code else}」这条实现约束最直观的回归锁——写成
     * {@code else if} 阶梯时，本用例只会解锁个别高门槛成就。</p>
     *
     * <p>Validates: Requirements 2.6, 2.12</p>
     */
    @Example
    void allSixteenBadgesUnlockInOneSettlement_inDisplayOrder() {
        long userId = SEQ.getAndIncrement();
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        long ledgerId = insertLedger(userId, "自有账本");

        seedCalendar(userId, yesterday, 365);                                  // STREAK_* 四枚 + DAYS_100
        insertEvent(userId, GrowthEventType.FIRST_INVITE, "FIRST_INVITE", 80, PRE_UNLOCK_AT);
        seedCollaborators(userId, ledgerId, 1);                                // COLLAB_1
        long travelCategoryId = insertCategory(userId, ledgerId, "旅行");
        seedExpenses(userId, ledgerId, yesterday, 10, travelCategoryId);       // TRAVEL_MASTER
        seedExpenses(userId, ledgerId, yesterday, 990, placeholderRef(userId)); // 合计 1000 笔
        for (int i = 0; i < 3; i++) {
            insertEvent(userId, GrowthEventType.BUDGET_MET, "BUDGET_MET:" + SEED_MONTHS.get(i), 50, PRE_UNLOCK_AT);
            insertEvent(userId, GrowthEventType.SAVING_MONTH, "SAVING_MONTH:" + SEED_MONTHS.get(i), 0, PRE_UNLOCK_AT);
        }

        long watermark = maxEventId(userId);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);

        assertThat(badgeCodesById(userId))
                .as("八个口径同时达标 → 16 枚全解锁，且 id 升序即展示顺序（需求 2.6）")
                .containsExactlyElementsOf(CATALOG_CODES);
        assertThat(newBadgeCodesSince(userId, watermark))
                .as("这 16 枚是同一批写入的（需求 2.6 后半句）")
                .containsExactlyElementsOf(CATALOG_CODES);
    }

    // ---------------- 断言助手 ----------------

    /**
     * 断言最后一次结算的「本批新增」与「解锁集合终态」两件事（需求 2.6、2.12、2.13）。
     *
     * @param watermark      最后一次结算<b>之前</b>该用户成长事件的最大 {@code id}；大于它的即本批
     * @param alreadyUnlocked 最后一次结算之前已解锁的编码集合
     * @param facts          最后一次结算所见事实的参考模型
     */
    private void assertBatchAndTotal(long userId, long watermark, Set<String> alreadyUnlocked, Facts facts) {
        Set<String> qualified = referenceQualified(facts);
        Set<String> expectedTotal = union(alreadyUnlocked, qualified);

        assertThat(badgeCodesById(userId))
                .as("解锁集合 == 已解锁 ∪ {code : 统计量 ≥ 门槛}：跨门槛一枚不漏、未达门槛一枚不发（需求 2.6、2.13）")
                .containsExactlyInAnyOrderElementsOf(expectedTotal);

        // 本批 == 达标但此前未解锁的编码，按展示序号升序（需求 2.6 后半句）。
        List<String> expectedBatch = CATALOG_CODES.stream()
                .filter(code -> qualified.contains(code) && !alreadyUnlocked.contains(code))
                .toList();
        List<String> actualBatch = newBadgeCodesSince(userId, watermark);
        assertThat(actualBatch)
                .as("同一批 BADGE 事件按 id 升序取出后，顺序与展示序号顺序一致（需求 2.6 后半句）")
                .containsExactlyElementsOf(expectedBatch);
        assertThat(actualBatch.stream().map(CATALOG_CODES::indexOf).toList())
                .as("本批 BADGE 事件的展示序号严格升序（需求 2.6 后半句）")
                .isSorted();
    }

    // ---------------- 参考模型（需求 1.1 表格与需求 3 各口径定义的独立副本）----------------

    /** 结算所见的八个统计口径取值（测试侧独立建模，不复用 {@link GrowthFacts}）。 */
    private record Facts(long recordCount, int maxStreak, int totalDays, long budgetMetCount,
                         boolean firstInvite, long savingMonthCount, long collabMemberCount,
                         long travelCount) {
    }

    /**
     * 场景 → 跃迁完成后八个口径的参考取值（推导依据见类级 Javadoc「八个口径分别怎么造」）。
     *
     * <p>两处耦合如实建模：① 旅行支出也是有效记账交易，故笔数取 {@code max(笔数档, 旅行档)}；
     * ② 交易的记账日是昨天，若有交易则日历至少含昨天这一天，故两个天数口径取
     * {@code max(连续段长度, 有交易 ? 1 : 0)}。</p>
     */
    private static Facts referenceFacts(Scenario scenario) {
        long recordCount = Math.max(scenario.recordValue(), scenario.travelValue());
        int days = Math.max(scenario.calendarLength(), recordCount >= 1 ? 1 : 0);
        return new Facts(recordCount, days, days, scenario.budgetValue(), scenario.firstInvite(),
                scenario.savingValue(), scenario.collabValue(), scenario.travelValue());
    }

    /** 解锁条件已成立的编码集合：遍历整份清单、逐枚取「大于或等于门槛」，各判定之间没有 {@code else}。 */
    private static Set<String> referenceQualified(Facts facts) {
        Set<String> codes = new LinkedHashSet<>();
        for (int i = 0; i < CATALOG_CODES.size(); i++) {
            if (metricOf(CATALOG_CODES.get(i), facts) >= CATALOG_TARGETS.get(i)) {
                codes.add(CATALOG_CODES.get(i));
            }
        }
        return codes;
    }

    /** 某枚成就的统计口径取值（需求 3 各口径定义的独立副本）；存在型口径映射为 1 / 0。 */
    private static long metricOf(String code, Facts facts) {
        return switch (code) {
            case "FIRST_RECORD", "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000" ->
                    facts.recordCount();
            case "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365" -> facts.maxStreak();
            case "DAYS_100" -> facts.totalDays();
            case "INVITE_1" -> facts.firstInvite() ? 1L : 0L;
            case "COLLAB_1" -> facts.collabMemberCount();
            case "BUDGET_MET", "BUDGET_MASTER" -> facts.budgetMetCount();
            case "SAVING_MASTER" -> facts.savingMonthCount();
            case "TRAVEL_MASTER" -> facts.travelCount();
            default -> throw new IllegalArgumentException("未知成就编码：" + code);
        };
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> merged = new LinkedHashSet<>(left);
        merged.addAll(right);
        return merged;
    }

    // ---------------- 库读取辅助 ----------------

    /** 该用户已解锁的成就编码，按 {@code BADGE} 事件 {@code id} 升序（即写入顺序）。 */
    private List<String> badgeCodesById(long userId) {
        return jdbcTemplate.queryForList(
                        "SELECT event_key FROM growth_events WHERE user_id = ? AND event_type = 'BADGE' "
                                + "ORDER BY id", String.class, userId)
                .stream()
                .map(key -> key.substring("BADGE:".length()))
                .toList();
    }

    /** {@code id} 大于水位线的 {@code BADGE} 行（即最后一次结算写入的那一批），按 {@code id} 升序。 */
    private List<String> newBadgeCodesSince(long userId, long watermark) {
        return jdbcTemplate.queryForList(
                        "SELECT event_key FROM growth_events WHERE user_id = ? AND event_type = 'BADGE' "
                                + "AND id > ? ORDER BY id", String.class, userId, watermark)
                .stream()
                .map(key -> key.substring("BADGE:".length()))
                .toList();
    }

    private long maxEventId(long userId) {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return max == null ? 0L : max;
    }

    // ---------------- 数据播种辅助 ----------------

    /** 预置 {@code BADGE} 行：初始状态「已解锁部分低门槛」。 */
    private void seedBadges(long userId, List<String> codes) {
        for (String code : codes) {
            insertEvent(userId, GrowthEventType.BADGE, GrowthBadgeCatalog.eventKeyOf(code), 0, PRE_UNLOCK_AT);
        }
    }

    /** 以 {@code lastDay} 结尾、长度为 {@code length} 的<b>连续</b> {@code DAILY_RECORD} 日历。 */
    private void seedCalendar(long userId, LocalDate lastDay, int length) {
        if (length <= 0) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(length);
        for (int back = 0; back < length; back++) {
            LocalDate day = lastDay.minusDays(back);
            batch.add(new Object[] {userId, GrowthEventType.DAILY_RECORD, "DAILY_RECORD:" + day, 5,
                    Timestamp.valueOf(PRE_UNLOCK_AT)});
        }
        jdbcTemplate.batchUpdate(INSERT_EVENT_SQL, batch);
    }

    /** 自有账本上的 {@code EDITOR} 成员行（成员各不相同，且都不是本人）。 */
    private void seedCollaborators(long userId, long ledgerId, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("INSERT INTO ledger_members (ledger_id, user_id, role, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    ledgerId, userId + 100_000L + i, LedgerMember.ROLE_EDITOR,
                    Timestamp.valueOf(PRE_UNLOCK_AT));
        }
    }

    /**
     * 交易事实源：{@code 旅行档} 笔旅行支出 + 补足到 {@code 笔数档} 的普通支出，全部落在
     * {@code recordDay} 这一天、全部是支出（零收入使储蓄月判定恒不成立，见类级 Javadoc）。
     */
    private void seedTransactions(long userId, long ledgerId, LocalDate recordDay, Scenario scenario) {
        int travel = scenario.travelValue();
        if (travel > 0) {
            long travelCategoryId = insertCategory(userId, ledgerId, "旅行");
            seedExpenses(userId, ledgerId, recordDay, travel, travelCategoryId);
        }
        int plain = Math.max(0, scenario.recordValue() - travel);
        if (plain > 0) {
            seedExpenses(userId, ledgerId, recordDay, plain, placeholderRef(userId));
        }
    }

    /** 直插 {@code BUDGET_MET:} 与 {@code SAVING_MONTH:} 事件（两个计数型口径的唯一事实源）。 */
    private void seedMonthEvents(long userId, Scenario scenario) {
        for (int i = 0; i < scenario.budgetValue(); i++) {
            insertEvent(userId, GrowthEventType.BUDGET_MET, "BUDGET_MET:" + SEED_MONTHS.get(i), 50, PRE_UNLOCK_AT);
        }
        for (int i = 0; i < scenario.savingValue(); i++) {
            insertEvent(userId, GrowthEventType.SAVING_MONTH, "SAVING_MONTH:" + SEED_MONTHS.get(i), 0,
                    PRE_UNLOCK_AT);
        }
    }

    private void insertEvent(long userId, String eventType, String eventKey, int expAmount, LocalDateTime at) {
        jdbcTemplate.update(INSERT_EVENT_SQL, userId, eventType, eventKey, expAmount, Timestamp.valueOf(at));
    }

    /** 同一记账日上批量直插 {@code count} 笔 {@code 1.00} 支出。 */
    private void seedExpenses(long userId, long ledgerId, LocalDate day, int count, long categoryId) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(txRow(userId, ledgerId, day, categoryId));
        }
        jdbcTemplate.batchUpdate(INSERT_TX_SQL, batch);
    }

    private static Object[] txRow(long userId, long ledgerId, LocalDate day, long categoryId) {
        Timestamp at = Timestamp.valueOf(day.atTime(12, 0));
        return new Object[] {userId, ledgerId, userId, "expense", new BigDecimal("1.00"),
                placeholderRef(userId), categoryId, at, at, at};
    }

    /**
     * 「绝不可能是真实主键」且按用户隔离的 {@code account_id} / {@code category_id} 占位取值。
     *
     * <p>与真实分类主键撞号会让「旅行」判定误命中：多次迭代共用同一个内存库，分类表里确有真实行。</p>
     */
    private static long placeholderRef(long userId) {
        return 900_000_000L + userId;
    }

    private long insertLedger(long userId, String name) {
        Timestamp now = Timestamp.valueOf(PRE_UNLOCK_AT);
        jdbcTemplate.update("INSERT INTO ledgers "
                        + "(user_id, name, type, sort_order, is_default, created_at, updated_at) "
                        + "VALUES (?, ?, 'PERSONAL', 0, FALSE, ?, ?)", userId, name, now, now);
        return maxIdOf("ledgers", userId);
    }

    /** 一级支出分类（{@code kind = 'EXPENSE'}，无父分类）。 */
    private long insertCategory(long userId, long ledgerId, String name) {
        Timestamp now = Timestamp.valueOf(PRE_UNLOCK_AT);
        jdbcTemplate.update("INSERT INTO categories "
                        + "(user_id, ledger_id, parent_id, kind, name, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'EXPENSE', ?, ?, ?)", userId, ledgerId, name, now, now);
        return maxIdOf("categories", userId);
    }

    private long maxIdOf(String table, long userId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM " + table + " WHERE user_id = ?", Long.class, userId);
        if (id == null) {
            throw new IllegalStateException("播种失败：" + table + " 无行，userId=" + userId);
        }
        return id;
    }
}
