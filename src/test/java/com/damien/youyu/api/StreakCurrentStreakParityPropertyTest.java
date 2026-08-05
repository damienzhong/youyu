package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

import com.damien.youyu.service.GrowthOverviewResponse;
import com.damien.youyu.service.GrowthQueryService;
import com.damien.youyu.service.GrowthSettlementService;
import com.damien.youyu.service.SettleOutcome;
import com.damien.youyu.service.StreakOverviewResponse;
import com.damien.youyu.service.StreakQueryService;
import com.damien.youyu.service.TriggerSource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 9：当前连续天数两处相等</b>的属性测试（任务 8.4）。
 *
 * <p><i>对任意</i>记账日历 × <i>任意</i>判定日（覆盖最近记账日的前后 3 天）：连续记账概览
 * （{@link StreakQueryService#getOverview}）与成长概览（{@link GrowthQueryService#getOverview}）
 * 返回的 {@code currentStreakDays} 与 {@code maxStreakDays} 两项取值<b>逐项相等</b>（需求 2.3、10.5）。</p>
 *
 * <h2>相等性是构造性的，本测试锁住任务 6.1 的重构没改变取值</h2>
 * <p>两条读取路径都把「当前连续天数」判定<b>委托</b>给同一份 {@code StreakJudgment.currentStreakDays}
 * （任务 6.1 把 {@code GrowthQueryService.correctedCurrentStreak} 从自行判定改为委托），
 * {@code maxStreakDays} 两处都直接读同一列 {@code user_growth.max_streak_days}。因此相等性构造性成立
 * ——本属性的价值不在于"发现两份实现的分歧"（已无第二份实现），而在于<b>钉死这次重构后取值逐例不变</b>：
 * 一旦有人给某一处的当前连续天数补上第二套判定分支、或让某一处读别的列，本属性立刻在某个判定日上变红。</p>
 *
 * <h2>判定日如何覆盖"最近记账日前后 3 天"：固定日历 + 可推进时钟</h2>
 * <p>判定日由注入的 {@code Clock} 决定（{@code LocalDate.now(clock)}），故本类用一个 {@code @Primary}
 * 的{@link MutableClock}把日历的最近记账日固定在 {@link #ANCHOR}，再让判定日在 {@code ANCHOR ± 3} 之间
 * 逐一取值：{@code offset < 0} 是"判定日早于最近记账日"的时钟偏移态（{@code StreakJudgment} 两处同返 0），
 * {@code offset == 0} 是"今日已完成"、{@code offset == +1} 是"最近记账日为判定日前一日"（两处同返当前段长），
 * {@code offset >= +2} 是已中断态（两处同返 0）。</p>
 *
 * <p>物化在<b>结算日 = {@code ANCHOR}</b> 时一次完成（此时全部记账日 ≤ 结算日，日历完整落库、
 * {@code current_streak_days} / {@code max_streak_days} / {@code last_record_date} 取定值），随后
 * 把时钟移到 {@code ANCHOR + offset} 只影响<b>读取侧</b>的判定日。向<b>过去</b>移动时（{@code offset < 0}）
 * 概览触发的结算落在 10 秒窗口内被跳过（{@code clock.millis() - 上次结算时刻 < 0 < 10000}），
 * 因此不会以更早的结算日重算而"裁掉"记账日；向<b>未来</b>移动时结算虽真实执行，但日历里没有
 * {@code ANCHOR} 之后的记账日，全量重算的结果与 {@code ANCHOR} 时逐列相同（幂等），
 * 两处仍读同一份档案。无论哪种情形，两处的当前连续天数与历史最长连续天数都相等。</p>
 *
 * <h2>驱动方式与清理</h2>
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有真实提交才能在库里观察到终态，故本类
 * <b>不用测试级事务包裹</b>；清理不靠回滚，由 {@link #resetState()} 每次迭代前显式清表，并用全局自增
 * 序号 {@link #SEQ} 保证 {@code userId} / {@code ledgerId} 全局唯一（概览侧节流器是进程内单例、无清理
 * 入口，每次换新 {@code userId} 才能让"首次请求必放行"成立）。请求走<b>服务层</b>而非 HTTP：属性方法要跑
 * 几十次迭代，Property 9 的不变式落在两个响应对象的两个字段上，与过滤链、序列化无关（HTTP 契约由
 * {@code StreakApiContractIntegrationTest} 覆盖）。jqwik 属性方法不经 {@code SpringExtension}，
 * 依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成（上下文缓存复用）。</p>
 *
 * <p>Feature: streak-system, Property 9: 当前连续天数两处相等</p>
 *
 * <p>Validates: Requirements 2.3, 10.5, 2.1, 2.4</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-parity-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(StreakCurrentStreakParityPropertyTest.ClockConfig.class)
class StreakCurrentStreakParityPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 日历的最近记账日恒锚定在此；判定日在 {@code ANCHOR ± 3} 之间取值。 */
    private static final LocalDate ANCHOR = LocalDate.of(2025, 6, 15);

    /** 物化时刻：{@code ANCHOR} 当天 08:00（Asia/Shanghai）——结算日 = ANCHOR，晚于全部记账日的时刻无关紧要。 */
    private static final Instant MATERIALIZE_INSTANT = ANCHOR.atStartOfDay(ZONE).plusHours(8).toInstant();

    private static final MutableClock CLOCK = new MutableClock(MATERIALIZE_INSTANT, ZONE);

    /** 交易直插语句：列顺序与 {@link #txRow} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 全局自增序号：保证跨迭代 userId / ledgerId 全局唯一（清理不靠回滚，节流器不可清理）。 */
    private static final AtomicLong SEQ = new AtomicLong(820_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private GrowthQueryService growthQueryService;
    @Autowired
    private StreakQueryService streakQueryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(StreakCurrentStreakParityPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(MATERIALIZE_INSTANT);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删事实源与派生表（各表间无外键）。
        jdbcTemplate.update("DELETE FROM streak_segments");
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    // ---------------- 生成器 ----------------

    /**
     * 一份记账日历：以 {@link #ANCHOR} 为最近记账日、由"距锚点的天数偏移"集合展开
     * （恒含 0 使 {@code ANCHOR} 在日历内），偏移落在 {@code [0, 60]}，去重后 1–30 个。
     * 由此覆盖全连续、含缺口、跨月、跨段等多种段形状。
     */
    @Provide
    Arbitrary<List<LocalDate>> calendars() {
        Arbitrary<TreeSet<Integer>> deltaSets = Arbitraries.integers().between(0, 60)
                .set().ofMinSize(1).ofMaxSize(30)
                .map(TreeSet::new);
        return deltaSets.map(deltas -> {
            deltas.add(0);                         // 恒含锚点，使最近记账日 == ANCHOR
            TreeSet<LocalDate> dates = new TreeSet<>();
            for (int delta : deltas) {
                dates.add(ANCHOR.minusDays(delta));
            }
            return List.copyOf(dates);
        });
    }

    // ---------------- Property 9 ----------------

    /**
     * Feature: streak-system, Property 9: 当前连续天数两处相等
     *
     * <p>对任意日历 × 判定日（{@code ANCHOR ± 3}）：两处的 {@code currentStreakDays} 与
     * {@code maxStreakDays} 逐项相等（需求 2.3、10.5）。</p>
     *
     * <p>Validates: Requirements 2.3, 10.5, 2.1, 2.4</p>
     */
    @Property(tries = 40)
    void property9_currentStreakAndMaxStreakEqualAcrossBothOverviews(
            @ForAll("calendars") List<LocalDate> calendar,
            @ForAll @IntRange(min = -3, max = 3) int judgmentOffset) {
        assertParity(calendar, judgmentOffset);
    }

    /**
     * 把"判定日的 7 个偏移 × 一份非平凡的连续日历"逐一穷举一遍，保证 {@code StreakJudgment} 的
     * 今日 / 昨日 / 中断 / 时钟偏移四类分支都被覆盖到，而不是靠随机取样碰巧命中。
     *
     * <p>该日历最近记账日为 {@code ANCHOR}、末段长度为 5（{@code ANCHOR-4 .. ANCHOR}）。据此额外断言：
     * 判定日为"今日"（offset 0）或"最近记账日的次日"（offset +1）时两处当前连续天数<b>同为 5</b>、
     * 其余偏移两处<b>同为 0</b>——把"相等"进一步钉到具体取值上。</p>
     *
     * <p>Validates: Requirements 2.3, 10.5, 2.1, 2.4</p>
     */
    @Example
    void allSevenJudgmentOffsetsAroundAnchor_areCoveredExhaustively() throws Exception {
        new TestContextManager(StreakCurrentStreakParityPropertyTest.class).prepareTestInstance(this);
        // 末段 5 天（ANCHOR-4 .. ANCHOR），前面隔一天再来一段旧的连续 3 天，制造 max=5、current=5。
        List<LocalDate> calendar = List.of(
                ANCHOR.minusDays(10), ANCHOR.minusDays(9), ANCHOR.minusDays(8),   // 旧段（3 天）
                ANCHOR.minusDays(4), ANCHOR.minusDays(3), ANCHOR.minusDays(2),
                ANCHOR.minusDays(1), ANCHOR);                                     // 末段（5 天）

        for (int offset = -3; offset <= 3; offset++) {
            resetState();
            int[] pair = assertParity(calendar, offset);
            int expectedCurrent = (offset == 0 || offset == 1) ? 5 : 0;
            assertThat(pair[0])
                    .as("判定日偏移 %d：两处当前连续天数应同为 %d", offset, expectedCurrent)
                    .isEqualTo(expectedCurrent);
            assertThat(pair[1]).as("判定日偏移 %d：末段与旧段的历史最长连续天数为 5", offset).isEqualTo(5);
        }
    }

    // ---------------- 场景执行 ----------------

    /**
     * 播种日历 → 在 {@code ANCHOR} 物化一次 → 把判定日移到 {@code ANCHOR + offset} → 两处各取一次概览，
     * 断言 {@code currentStreakDays} 与 {@code maxStreakDays} 逐项相等。
     *
     * @return {@code [currentStreakDays, maxStreakDays]}（两处相等，返回其一供调用方进一步断言）
     */
    private int[] assertParity(List<LocalDate> calendar, int judgmentOffset) {
        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();

        // ① 在 ANCHOR 播种全部记账日并物化档案（结算日 = ANCHOR ≥ 全部记账日，日历完整落库）。
        CLOCK.reset(MATERIALIZE_INSTANT);
        for (LocalDate day : calendar) {
            jdbcTemplate.update(INSERT_TX_SQL, txRow(userId, ledgerId, day));
        }
        assertThat(settlementService.settle(userId, TriggerSource.RECORD))
                .as("首次结算必真实执行").isEqualTo(SettleOutcome.SETTLED);

        // ② 预热概览侧 10 秒节流窗口（在 ANCHOR 标记），使随后向过去移动时的概览结算被跳过、
        //    不以更早的结算日重算而裁掉记账日。
        streakQueryService.getOverview(userId);

        // ③ 判定日移到 ANCHOR + offset：只改读取侧的 LocalDate.now(clock)。
        CLOCK.reset(ANCHOR.plusDays(judgmentOffset).atStartOfDay(ZONE).plusHours(8).toInstant());

        // ④ 两处各取一次概览并比对（先概览侧再成长侧，顺序不影响：日历不再变化）。
        StreakOverviewResponse streak = streakQueryService.getOverview(userId);
        GrowthOverviewResponse growth = growthQueryService.getOverview(userId);

        String label = "日历 " + calendar.size() + " 天 / 判定日偏移 " + judgmentOffset;
        assertThat(streak.currentStreakDays())
                .as(label + "：连续记账概览与成长概览的当前连续天数相等（需求 2.3）")
                .isEqualTo(growth.currentStreakDays());
        assertThat(streak.maxStreakDays())
                .as(label + "：连续记账概览与成长概览的历史最长连续天数相等（需求 10.5）")
                .isEqualTo(growth.maxStreakDays());

        return new int[] {streak.currentStreakDays(), streak.maxStreakDays()};
    }

    // ---------------- 事实源播种 ----------------

    /** 一条"有效记账交易"的参数行：记账日由 {@code recordDay}（即 {@code created_at}）决定。 */
    private static Object[] txRow(long userId, long ledgerId, LocalDate recordDay) {
        Timestamp createdAt = Timestamp.valueOf(recordDay.atTime(12, 0));
        return new Object[] {userId, ledgerId, userId, "expense", new BigDecimal("1.00"),
                ref(userId), ref(userId), createdAt, createdAt, createdAt};
    }

    /** "绝不可能是真实主键"且按用户隔离的 {@code account_id} / {@code category_id} 占位取值。 */
    private static long ref(long userId) {
        return 900_000_000L + userId;
    }

    // ---------------- 测试基础设施 ----------------

    /** {@code @Primary} 可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使判定日可确定性驱动。 */
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
