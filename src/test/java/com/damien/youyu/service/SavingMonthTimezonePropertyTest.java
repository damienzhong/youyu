package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.TransactionRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 11：时区无关性</b>的属性测试（任务 8.6）。
 *
 * <p><i>对任意</i> JVM 默认时区（8 个覆盖 UTC−12 到 UTC+14 的时区）、<i>任意</i>结算时刻
 * （月首 {@code 00:00:00.000} / 月首前 1ms / 月末 {@code 23:59:59.999} / 1 月 1 日 / 闰日）与
 * <i>任意</i>交易 {@code occurred_at}（月边界 ±1ms）：储蓄月的<b>月份归属</b>、回看窗口的<b>三个月份</b>、
 * 落库的 {@code SAVING_MONTH} 事件键、记账日历（{@code DAILY_RECORD} 键）与
 * {@code MAX_STREAK} / {@code TOTAL_DAYS} / 连续段 / {@code last_record_date}
 * 均与在基准时区 {@code Asia/Shanghai} 下运行时<b>逐项相同</b>；且恰好落在次月边界时刻
 * （{@code 次月 1 日 00:00:00.000}）的交易归<b>次月</b>（需求 4.6、4.7、3.2）。</p>
 *
 * <h2>断言的两个基准，缺一不可</h2>
 *
 * <p>每次迭代都同时比对<b>两个</b>基准，而不是只比对「另一个时区」：</p>
 * <ol>
 *   <li><b>与时区无关的纯 Java 参照</b>：储蓄月集合、回看三个月份、记账日集合与三个天数列全部由
 *       {@link YearMonth} / {@link LocalDate} 直接推出（见 {@link #referenceOf}），不经数据库、不经被测代码。
 *       它钉住「到底哪个取值才是对的」。</li>
 *   <li><b>基准时区 {@code Asia/Shanghai} 下的实测结果</b>（{@link #BASELINES} 缓存，每个
 *       「结算时刻 × 边界偏移」组合只算一次）。它钉住需求 4.6 那句「与在 {@code Asia/Shanghai} 下运行时
 *       逐项相同」的字面含义。</li>
 * </ol>
 *
 * <p>只比对「两个时区彼此相等」是不够的——两个时区可以一起错。加上纯 Java 参照之后，
 * 「对全部 8 个时区都成立 {@code 实测 == 参照}」推出它们彼此也相等，且相等于唯一正确取值。</p>
 *
 * <h2>±1ms 的边界偏移怎么变成可观察的差异</h2>
 *
 * <p>光把交易摆在边界上是测不出东西的：得让「归哪个月」直接决定「哪个月是储蓄月」。本类的构造
 * （见 {@link #seedBoundaryProbeUser}）把三个回看月 M3 &lt; M2 &lt; M1 摆成「各自都<b>不是</b>储蓄月」，
 * 再放一笔 {@code 10000.00} 的收入在 <b>M2 月首 ± 偏移</b>上：</p>
 * <ul>
 *   <li>偏移 <b>−1ms</b>（落在 M3 的最后一毫秒）⇒ 这笔收入归 M3，M3 结余 {@code 10000.00} ≥ 门槛
 *       {@code 2020.00} ⇒ 储蓄月集合是 <b>{M3}</b>；</li>
 *   <li>偏移 <b>0</b>（恰好等于 M2 月首，即「次月边界时刻」）与 <b>+1ms</b> ⇒ 这笔收入归 M2，
 *       M2 结余 {@code 2000.00} 恰好等于门槛 {@code 2000.00}（取等号即成立）⇒ 储蓄月集合是 <b>{M2}</b>。</li>
 * </ul>
 *
 * <p>于是「偏移 −1ms 与偏移 0 给出<b>不同</b>的储蓄月」这件事本身就是需求 4.6 半开区间语义的直接观测：
 * 边界时刻归次月。区间左右两端各另放一颗<b>哨兵</b>收入（同为 {@code 10000.00}）：一颗在
 * 「M3 月首 − 1ms」（窗口之外的上一个月），一颗在「结算月月首」（右开边界之外）。任何一颗被算进窗口内，
 * 对应月份都会翻成储蓄月、期望集合立刻不符——这两颗哨兵把「窗口两端各偏一个月」这类缺陷挡在门外。</p>
 *
 * <p>另外还断言 {@code occurred_at} 的<b>毫秒往返</b>：从库里重读探针交易的 {@code occurred_at}，
 * 断言与写入值逐纳秒相等（见 {@link #assertOutcome}）。这条守卫防止 ±1ms 这一维度悄悄沦为空洞
 * ——若某天列精度被降到秒，{@code 23:59:59.999} 会被截断/进位，本断言先炸，而不是让属性假绿。
 * <b>注意</b>：测试库表结构由 Hibernate 依实体生成（{@code ddl-auto=create-drop}），保留了小数秒；
 * 生产迁移脚本里 {@code occurred_at} 是 {@code DATETIME}（MySQL 上精度为 0，写入时会对小数秒<b>四舍五入</b>），
 * 因此「毫秒级边界」在真实 MySQL 上的最终确认属于人工验证清单，本类不冒充。半开区间与「边界归次月」
 * 这条语义本身与列精度无关，在任何精度下都由本类覆盖。</p>
 *
 * <h2>为什么记账日历与三个天数列也在断言范围内（需求 3.2）</h2>
 *
 * <p>需求 3.2 要求 {@code MAX_STREAK} / {@code TOTAL_DAYS} 按 {@code Asia/Shanghai} 界定自然日边界。
 * 本类给每个用户播种<b>恰好 3 个连续记账日</b>（结算日往前第 1/2/3 天，由各交易的 {@code created_at} 决定），
 * 因此参照值恒为 {@code totalDays = 3}、{@code maxStreak = 3}、{@code currentSegment = 3}、
 * {@code lastRecordDate = 结算日 − 1 天}。这三个物化列在非基准时区下若整日平移，断言立刻变红。</p>
 *
 * <p><b>播种必须走 Hibernate 仓储</b>（{@link TransactionRepository#save}）而不是 {@code JdbcTemplate}
 * 直插：本属性守护的正是 {@code hibernate.type.java_time_use_direct_jdbc=true} 那条「挂钟值逐字进出、
 * 零时区换算」的绑定路径（详见 {@code GrowthTimezoneIndependencePropertyTest} 的类级 Javadoc）。
 * 用原生 SQL 直插会绕过它，使这道回归锁失效。</p>
 *
 * <h2>时区还原与串行执行（必读）</h2>
 *
 * <p>{@link TimeZone#setDefault(TimeZone)} 改的是<b>整个 JVM 的全局默认时区</b>。为此本类：
 * ① 在 {@link #runUnderTimeZone} 内用 {@code try/finally} 无条件还原，
 * ② 再在 {@link #restoreDefaultTimeZone()}（{@code @AfterTry}）兜一层，即使断言在 finally 之外抛出也还原；
 * ③ <b>必须串行执行</b>——jqwik 默认串行跑各次 try，本项目也未开启任何 surefire / junit-platform 并行配置；
 * 一旦将来引入测试并行，本类必须被显式排除，否则它中途改掉的默认时区会被并行的其它测试读到。</p>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 *
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有真实<b>提交</b>才能在库里观察到终态，
 * 故本类<b>不用测试级事务包裹</b>；清理相应地不能靠回滚，由 {@link #cleanTables()} 在每次运行前显式清表，
 * 并用全局自增序号 {@link #SEQ} 保证 {@code userId} / {@code ledgerId} 全局唯一（双重隔离）。
 * 结算日由 {@code @Primary} 的可设定 {@link MutableClock}（固定 {@code Asia/Shanghai}）决定，
 * 因此它<b>与 JVM 默认时区无关</b>——这正是需求 4.1 的应有之义。jqwik 属性方法不经
 * {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成
 * （上下文缓存复用）。</p>
 *
 * <p>Feature: achievement-system, Property 11: 时区无关性</p>
 *
 * <p>Validates: Requirements 4.6, 4.7, 3.2</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-tz-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(SavingMonthTimezonePropertyTest.ClockConfig.class)
class SavingMonthTimezonePropertyTest {

    /** 基准时区：全库 {@code DATETIME} 列存的都是这个时区的挂钟时刻（需求 3.2、4.1）。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /**
     * 待轮换的 8 个 JVM 默认时区，覆盖 UTC−12 到 UTC+14。
     *
     * <p>刻意含三类「最容易把日期算歪」的时区：两端极值（{@code Etc/GMT+12} = UTC−12、
     * {@code Pacific/Kiritimati} = UTC+14，二者相差 26 小时，跨国际日期变更线）、
     * 半小时偏移（{@code Asia/Kolkata} = UTC+05:30）与带夏令时的（{@code America/New_York}、
     * {@code Europe/Berlin}）。基准时区 {@code Asia/Shanghai} 也在列，使「基准与自身相等」这条平凡情形
     * 一并被跑到（它必须恒绿，否则说明测试自身不稳定）。</p>
     */
    private static final List<ZoneId> ZONES = List.of(
            ZoneId.of("Etc/GMT+12"),          // UTC−12（最西）
            ZoneId.of("Pacific/Honolulu"),    // UTC−10
            ZoneId.of("America/New_York"),    // UTC−05/−04（夏令时）
            ZoneId.of("UTC"),                 // UTC±00
            ZoneId.of("Europe/Berlin"),       // UTC+01/+02（夏令时）
            ZoneId.of("Asia/Kolkata"),        // UTC+05:30（半小时偏移）
            SHANGHAI,                         // UTC+08（基准）
            ZoneId.of("Pacific/Kiritimati")); // UTC+14（最东）

    /**
     * 结算时刻（{@code Asia/Shanghai} 挂钟），覆盖需求 4.1 / 4.6 的五类高风险时刻。
     *
     * <p>前两项是一对：{@code 2025-06-01 00:00:00.000} 与它<b>前 1 毫秒</b>
     * （{@code 2025-05-31 23:59:59.999}）分属两个自然月，因此回看窗口整体差一个月
     * （{@code 2025-03/04/05} 对 {@code 2025-02/03/04}）——结算日的月份归属一旦被时区平移，
     * 这两项里至少一项会失配。</p>
     */
    private static final List<LocalDateTime> SETTLEMENT_MOMENTS = List.of(
            LocalDateTime.of(2025, 6, 1, 0, 0, 0, 0),                     // 月首 00:00:00.000
            LocalDateTime.of(2025, 5, 31, 23, 59, 59, 999_000_000),       // 月首前 1ms
            LocalDateTime.of(2025, 6, 30, 23, 59, 59, 999_000_000),       // 月末 23:59:59.999
            LocalDateTime.of(2026, 1, 1, 0, 0, 0, 0),                     // 1 月 1 日（回看上年 10/11/12）
            LocalDateTime.of(2024, 2, 29, 12, 0, 0, 0));                  // 闰日

    /** 一毫秒（{@link LocalDateTime#plusNanos} 的参数单位是纳秒）。 */
    private static final long ONE_MILLI_NANOS = 1_000_000L;

    /** 回看窗口固定 3 个已结束自然月（需求 4.1），与 {@code GrowthSavingMonthEvaluator} 的常量对应。 */
    private static final int LOOKBACK_MONTHS = 3;

    /** 参照值：本类给每个用户播种恰好 3 个连续记账日（结算日往前第 1/2/3 天）。 */
    private static final int RECORD_DAYS = 3;

    /** 探针 / 哨兵收入金额：足够大，落进哪个月就把那个月翻成储蓄月。 */
    private static final BigDecimal PROBE_INCOME = new BigDecimal("10000.00");

    /** 全局自增序号：保证跨迭代 userId / ledgerId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(1_130_000_000L);

    /** {@code @Primary} 可设定时钟：结算日由它决定，与 JVM 默认时区无关。 */
    private static final MutableClock CLOCK =
            new MutableClock(SETTLEMENT_MOMENTS.get(0).atZone(SHANGHAI).toInstant(), SHANGHAI);

    /** 进入本类前的默认时区，{@code @AfterTry} 无条件还原到它，避免污染同一 JVM 的其它测试。 */
    private static final TimeZone ORIGINAL_TIME_ZONE = TimeZone.getDefault();

    /**
     * 基准时区下的实测结果缓存：键为「结算时刻 + 边界偏移」，值为 {@code Asia/Shanghai} 下跑出来的结果。
     *
     * <p>缓存只是省时间（15 个组合各算一次，而不是每次迭代都重算一遍基准），不影响语义：
     * 每个基准都在生成它的那次运行里先与纯 Java 参照比对过（见 {@link #baselineOf}）。</p>
     */
    private static final Map<String, Outcome> BASELINES = new LinkedHashMap<>();

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private GrowthSavingMonthEvaluator savingMonthEvaluator;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;

    @BeforeTry
    void prepare() throws Exception {
        new TestContextManager(SavingMonthTimezonePropertyTest.class).prepareTestInstance(this);
        tx = new TransactionTemplate(transactionManager);
    }

    /** 无条件还原默认时区（形同 finally）：即便某次 try 在断言处抛出也执行，避免污染同 JVM 其它测试。 */
    @AfterTry
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(ORIGINAL_TIME_ZONE);
    }

    // ---------------- 生成器 ----------------

    /** 探针交易相对「M2 月首 {@code 00:00:00.000}」的偏移：月边界 ±1ms 与恰好落在边界上。 */
    enum BoundaryOffset {
        /** 月首前 1 毫秒：归<b>上一个</b>月（M3）。 */
        MINUS_ONE_MILLI(-ONE_MILLI_NANOS),
        /** 恰好等于月首：归<b>本</b>月（M2）——需求 4.6「边界时刻归次月」。 */
        EXACT(0L),
        /** 月首后 1 毫秒：归本月（M2）。 */
        PLUS_ONE_MILLI(ONE_MILLI_NANOS);

        private final long nanos;

        BoundaryOffset(long nanos) {
            this.nanos = nanos;
        }

        long nanos() {
            return nanos;
        }
    }

    @Provide
    Arbitrary<ZoneId> zones() {
        return Arbitraries.of(ZONES);
    }

    @Provide
    Arbitrary<LocalDateTime> settlementMoments() {
        return Arbitraries.of(SETTLEMENT_MOMENTS);
    }

    // ---------------- Property 11 ----------------

    /**
     * Feature: achievement-system, Property 11: 时区无关性
     *
     * <p>输入空间 8 个时区 × 5 个结算时刻 × 3 个边界偏移 = 120 个组合，{@code tries = 120} 使 jqwik
     * 走穷举生成、把这 120 个组合逐个跑到。每个组合：在指定 JVM 默认时区下播种（走 Hibernate）→
     * 直接调储蓄月判定 → 真实结算并从库读回，随后同时比对<b>纯 Java 参照</b>与<b>基准时区实测结果</b>。</p>
     *
     * <p>Validates: Requirements 4.6, 4.7, 3.2</p>
     */
    @Property(tries = 120)
    void savingMonthAndRecordCalendarAreIndependentOfJvmDefaultTimeZone(
            @ForAll("zones") ZoneId zone,
            @ForAll("settlementMoments") LocalDateTime settlementMoment,
            @ForAll BoundaryOffset offset) {

        Reference reference = referenceOf(settlementMoment, offset);
        Outcome baseline = baselineOf(settlementMoment, offset, reference);

        Outcome actual = runUnderTimeZone(zone, settlementMoment, offset);

        assertOutcome(zone, settlementMoment, offset, actual, reference);
        assertThat(actual)
                .as("默认时区 %s / 结算时刻 %s / 边界偏移 %s：全部结果应与基准时区 Asia/Shanghai 下逐项相同"
                        + "（需求 4.6、4.7、3.2）", zone, settlementMoment, offset)
                .isEqualTo(baseline);
    }

    /**
     * 「恰好落在次月边界时刻的交易归次月」在两端极值时区下的定向示例（需求 4.6 后半句）。
     *
     * <p>{@code Etc/GMT+12}（UTC−12）与 {@code Pacific/Kiritimati}（UTC+14）相差 26 小时，是最容易把
     * 「月首 {@code 00:00:00.000}」算到上一个月去的两个默认时区。本示例在这两个时区下逐个断言：
     * 同一笔 {@code 10000.00} 收入摆在 <b>M2 月首</b>时储蓄月是 {M2}、摆在 <b>M2 月首前 1 毫秒</b>时
     * 储蓄月是 {M3}——即边界时刻归次月、前一毫秒归上月。</p>
     *
     * <p>这条与属性方法覆盖的组合有重叠，单独留一个示例是为了让「边界归次月」这句需求原文在测试代码里
     * 有一处<b>直接可读</b>的对照，失败信息也直接指出是哪一端时区、哪一侧偏移。</p>
     *
     * <p>Validates: Requirements 4.6</p>
     */
    @Example
    void boundaryInstantBelongsToNextMonth_evenAtTheTwoExtremeTimeZones() throws Exception {
        new TestContextManager(SavingMonthTimezonePropertyTest.class).prepareTestInstance(this);
        tx = new TransactionTemplate(transactionManager);

        List<ZoneId> extremes = List.of(ZoneId.of("Etc/GMT+12"), ZoneId.of("Pacific/Kiritimati"));
        LocalDateTime moment = SETTLEMENT_MOMENTS.get(0);
        List<String> months = lookbackMonths(moment.toLocalDate());
        String monthOfProbeBoundary = months.get(1);      // M2：探针边界所在月
        String monthBeforeBoundary = months.get(0);       // M3：边界前 1 毫秒所属月

        try {
            for (ZoneId zone : extremes) {
                Outcome onBoundary = runUnderTimeZone(zone, moment, BoundaryOffset.EXACT);
                assertThat(onBoundary.savingMonths())
                        .as("默认时区 %s：恰好落在 %s 月首 00:00:00.000 的交易归该月（需求 4.6）",
                                zone, monthOfProbeBoundary)
                        .containsExactly(monthOfProbeBoundary);

                Outcome beforeBoundary = runUnderTimeZone(zone, moment, BoundaryOffset.MINUS_ONE_MILLI);
                assertThat(beforeBoundary.savingMonths())
                        .as("默认时区 %s：落在 %s 月首前 1 毫秒的交易归上一个月 %s（需求 4.6）",
                                zone, monthOfProbeBoundary, monthBeforeBoundary)
                        .containsExactly(monthBeforeBoundary);
            }
        } finally {
            TimeZone.setDefault(ORIGINAL_TIME_ZONE);
        }
    }

    // ---------------- 一次运行 ----------------

    /**
     * 在指定 JVM 默认时区下完整跑一次：设定时钟 → 清表 → 播种（走 Hibernate）→ 直接调储蓄月判定 →
     * 真实结算 → 从库读回全部观察量。
     *
     * <p>切换默认时区必须发生在<b>播种与结算之前</b>：写入路径若被时区换算污染，换算就在此时发生。
     * {@code finally} 无条件还原（{@code @AfterTry} 再兜一层）。</p>
     */
    private Outcome runUnderTimeZone(ZoneId zone, LocalDateTime settlementMoment, BoundaryOffset offset) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try {
            CLOCK.setTo(settlementMoment);
            cleanTables();

            LocalDate settleDate = settlementMoment.toLocalDate();
            List<String> months = lookbackMonths(settleDate);

            // ① 边界探针用户：三个回看月本身都不是储蓄月，唯一的大额收入摆在 M2 月首 ± 偏移上。
            Ctx probeUser = newUser();
            LocalDateTime probeOccurredAt = seedBoundaryProbeUser(probeUser, settleDate, months, offset);

            // ② 窗口探针用户：三个回看月各一笔纯收入 + 窗口两侧各一笔，用于把「回看窗口是哪三个月」钉死。
            Ctx windowUser = newUser();
            seedWindowProbeUser(windowUser, settleDate, months);

            // 直接调判定（不经结算）：existingKeys 传空集，故判定结果不受任何已落库事件影响。
            List<String> savingMonths =
                    savingMonthEvaluator.savingMonths(probeUser.userId(), settleDate, Set.of());
            List<String> windowMonths =
                    savingMonthEvaluator.savingMonths(windowUser.userId(), settleDate, Set.of());

            // 再走一次真实结算，把「判定结果 → 落库事件键」这一段也纳入时区无关性的断言范围。
            assertThat(settlementService.settle(probeUser.userId(), TriggerSource.RECORD))
                    .as("新用户首次结算不会被节流跳过").isEqualTo(SettleOutcome.SETTLED);

            return new Outcome(
                    savingMonths,
                    windowMonths,
                    eventKeysOfType(probeUser.userId(), GrowthEventType.SAVING_MONTH),
                    eventKeysOfType(probeUser.userId(), GrowthEventType.DAILY_RECORD),
                    profileInt(probeUser.userId(), "total_record_days"),
                    profileInt(probeUser.userId(), "current_streak_days"),
                    profileInt(probeUser.userId(), "max_streak_days"),
                    profileDate(probeUser.userId(), "last_record_date"),
                    reloadOccurredAt(probeUser.userId(), probeOccurredAt));
        } finally {
            TimeZone.setDefault(ORIGINAL_TIME_ZONE);
        }
    }

    /** 基准时区下的结果（每个「结算时刻 × 偏移」只算一次），并在生成时即与纯 Java 参照比对。 */
    private Outcome baselineOf(LocalDateTime settlementMoment, BoundaryOffset offset, Reference reference) {
        String key = settlementMoment + "|" + offset;
        Outcome cached = BASELINES.get(key);
        if (cached != null) {
            return cached;
        }
        Outcome baseline = runUnderTimeZone(SHANGHAI, settlementMoment, offset);
        assertOutcome(SHANGHAI, settlementMoment, offset, baseline, reference);
        BASELINES.put(key, baseline);
        return baseline;
    }

    // ---------------- 与时区无关的纯 Java 参照 ----------------

    /**
     * 与任何时区无关的期望值，全部由 {@link YearMonth} / {@link LocalDate} 直接推出。
     *
     * @param savingMonths 边界探针用户的储蓄月（{@code −1ms → {M3}}，{@code 0 / +1ms → {M2}}）
     * @param windowMonths 窗口探针用户的储蓄月，恒为回看窗口的三个月份（升序）
     * @param recordDates  记账日（升序），恒为结算日往前第 3/2/1 天
     */
    private record Reference(List<String> savingMonths, List<String> windowMonths,
                             List<LocalDate> recordDates) {
    }

    private static Reference referenceOf(LocalDateTime settlementMoment, BoundaryOffset offset) {
        LocalDate settleDate = settlementMoment.toLocalDate();
        List<String> months = lookbackMonths(settleDate);
        // 偏移为负 ⇒ 探针收入落在 M2 月首之前，归 M3；否则（恰好边界 / 之后）归 M2（需求 4.6）。
        String expectedSavingMonth = (offset == BoundaryOffset.MINUS_ONE_MILLI) ? months.get(0) : months.get(1);

        List<LocalDate> recordDates = new ArrayList<>(RECORD_DAYS);
        for (int back = RECORD_DAYS; back >= 1; back--) {
            recordDates.add(settleDate.minusDays(back));
        }
        return new Reference(List.of(expectedSavingMonth), months, recordDates);
    }

    /** 结算日所属月的前 3 / 2 / 1 个自然月，<b>升序</b>的 {@code YYYY-MM}（需求 4.1 的回看窗口）。 */
    private static List<String> lookbackMonths(LocalDate settleDate) {
        YearMonth settleMonth = YearMonth.from(settleDate);
        List<String> months = new ArrayList<>(LOOKBACK_MONTHS);
        for (int back = LOOKBACK_MONTHS; back >= 1; back--) {
            months.add(settleMonth.minusMonths(back).toString());
        }
        return months;
    }

    // ---------------- 不变式断言 ----------------

    /** 把一次运行的结果与与时区无关的纯 Java 参照逐项比对。 */
    private void assertOutcome(ZoneId zone, LocalDateTime settlementMoment, BoundaryOffset offset,
                               Outcome actual, Reference reference) {
        String because = String.format("默认时区 %s / 结算时刻 %s / 边界偏移 %s", zone, settlementMoment, offset);

        // ① 月份归属：边界前 1 毫秒归上月，恰好边界与之后归本月（需求 4.6）。
        assertThat(actual.savingMonths())
                .as("%s：储蓄月的月份归属应与与时区无关的参照相同（需求 4.6）", because)
                .isEqualTo(reference.savingMonths());

        // ② 回看窗口恰好是那三个已结束自然月，且不含结算日所属月与窗口之外的月（需求 4.1、4.6）。
        assertThat(actual.windowMonths())
                .as("%s：回看窗口的三个月份应与参照逐项相同（需求 4.1）", because)
                .isEqualTo(reference.windowMonths());
        assertThat(actual.windowMonths())
                .as("%s：回看窗口不含结算日所属自然月", because)
                .doesNotContain(YearMonth.from(settlementMoment.toLocalDate()).toString());

        // ③ 落库的 SAVING_MONTH 事件键与判定结果一致，键长恒 20（需求 4.2）。
        assertThat(actual.savingEventKeys())
                .as("%s：落库的 SAVING_MONTH 事件键应与判定结果一致", because)
                .isEqualTo(reference.savingMonths().stream().map(month -> "SAVING_MONTH:" + month).toList());
        assertThat(actual.savingEventKeys()).allSatisfy(key -> assertThat(key).hasSize(20));

        // ④ 记账日历与三个天数列（需求 3.2）。
        assertThat(actual.dailyRecordKeys())
                .as("%s：DAILY_RECORD 事件键应与参照记账日逐项相同（需求 3.2）", because)
                .isEqualTo(reference.recordDates().stream().map(date -> "DAILY_RECORD:" + date).toList());
        assertThat(actual.totalRecordDays())
                .as("%s：累计记账天数（TOTAL_DAYS）应与参照相同（需求 3.2）", because).isEqualTo(RECORD_DAYS);
        assertThat(actual.maxStreakDays())
                .as("%s：历史最长连续天数（MAX_STREAK）应与参照相同（需求 3.2）", because).isEqualTo(RECORD_DAYS);
        assertThat(actual.currentStreakDays())
                .as("%s：当前连续段应与参照相同（需求 3.2）", because).isEqualTo(RECORD_DAYS);
        assertThat(actual.lastRecordDate())
                .as("%s：最近记账日应与参照相同（需求 3.2）", because)
                .isEqualTo(reference.recordDates().get(RECORD_DAYS - 1));

        // ⑤ 毫秒往返守卫：探针 occurred_at 逐纳秒原样读回，使 ±1ms 这一维度非空洞（见类级 Javadoc）。
        assertThat(actual.probeOccurredAt())
                .as("%s：探针交易的 occurred_at 应逐纳秒原样读回，否则 ±1ms 维度沦为空洞", because)
                .isEqualTo(expectedProbeOccurredAt(settlementMoment, offset));
    }

    private static LocalDateTime expectedProbeOccurredAt(LocalDateTime settlementMoment,
                                                         BoundaryOffset offset) {
        List<String> months = lookbackMonths(settlementMoment.toLocalDate());
        return firstInstantOf(months.get(1)).plusNanos(offset.nanos());
    }

    // ---------------- 事实源播种 ----------------

    /** 一个用户的固定上下文（本类不建账本行：协作 / 预算 / 旅行三个口径本就应为 0）。 */
    private record Ctx(long userId, long ledgerId) {
    }

    private Ctx newUser() {
        return new Ctx(SEQ.getAndIncrement(), SEQ.getAndIncrement());
    }

    /**
     * 边界探针用户：三个回看月<b>各自都不是</b>储蓄月，唯一的大额收入摆在 M2 月首 ± 偏移上。
     *
     * <table border="1">
     *   <caption>基线构造（不含探针）</caption>
     *   <tr><th>月</th><th>收入</th><th>支出</th><th>结余</th><th>门槛</th><th>是否储蓄月</th></tr>
     *   <tr><td>M3</td><td>100.00</td><td>100.00</td><td>0.00</td><td>20.00</td><td>否</td></tr>
     *   <tr><td>M2</td><td>0.00</td><td>8000.00</td><td>−8000.00</td><td>0.00</td><td>否（收入 &lt; 0.01）</td></tr>
     *   <tr><td>M1</td><td>500.00</td><td>500.00</td><td>0.00</td><td>100.00</td><td>否</td></tr>
     * </table>
     *
     * <p>加上 {@code 10000.00} 的探针收入之后：落进 M3 则 M3 结余 {@code 10000.00} ≥ 门槛
     * {@code 2020.00} ⇒ M3 是储蓄月；落进 M2 则 M2 结余 {@code 2000.00} <b>恰好等于</b>门槛
     * {@code 2000.00} ⇒ M2 是储蓄月（取等号即成立，需求 4.3）。两种结果互斥，故「归哪个月」直接可观测。</p>
     *
     * <p>另有两颗<b>哨兵</b>收入（同为 {@code 10000.00}）：一颗在「M3 月首 − 1 毫秒」（窗口左端之外的上一个月），
     * 一颗在「结算月月首 {@code 00:00:00.000}」（窗口右开边界之外）。它们任何一颗被算进窗口，
     * 对应月份都会翻成储蓄月、期望集合立刻不符。</p>
     *
     * @return 探针交易的 {@code occurred_at}（供毫秒往返守卫比对）
     */
    private LocalDateTime seedBoundaryProbeUser(Ctx ctx, LocalDate settleDate, List<String> months,
                                                BoundaryOffset offset) {
        LocalDateTime probeOccurredAt = firstInstantOf(months.get(1)).plusNanos(offset.nanos());
        List<Object[]> rows = new ArrayList<>();

        // M3：收入 100.00 / 支出 100.00 → 结余 0.00 < 门槛 20.00。
        rows.add(new Object[] {TransactionType.INCOME, new BigDecimal("100.00"), midOf(months.get(0))});
        rows.add(new Object[] {TransactionType.EXPENSE, new BigDecimal("100.00"), midOf(months.get(0))});
        // M2：只有支出 8000.00，收入全靠探针。
        rows.add(new Object[] {TransactionType.EXPENSE, new BigDecimal("8000.00"), midOf(months.get(1))});
        // M1：收入 500.00 / 支出 500.00 → 结余 0.00 < 门槛 100.00。
        rows.add(new Object[] {TransactionType.INCOME, new BigDecimal("500.00"), midOf(months.get(2))});
        rows.add(new Object[] {TransactionType.EXPENSE, new BigDecimal("500.00"), midOf(months.get(2))});
        // 探针：M2 月首 ± 偏移。
        rows.add(new Object[] {TransactionType.INCOME, PROBE_INCOME, probeOccurredAt});
        // 哨兵①：窗口左端之外（M3 月首前 1 毫秒 → 属于 M4）。
        rows.add(new Object[] {TransactionType.INCOME, PROBE_INCOME,
                firstInstantOf(months.get(0)).minusNanos(ONE_MILLI_NANOS)});
        // 哨兵②：窗口右开边界之外（结算月月首 00:00:00.000）。
        rows.add(new Object[] {TransactionType.INCOME, PROBE_INCOME,
                settleDate.withDayOfMonth(1).atStartOfDay()});

        seed(ctx, settleDate, rows);
        return probeOccurredAt;
    }

    /**
     * 窗口探针用户：三个回看月各一笔纯收入（{@code 1000.00}，无支出 ⇒ 结余 {@code 1000.00} ≥ 门槛
     * {@code 200.00}），因此储蓄月集合恰好等于<b>回看窗口的三个月份</b>；另在窗口两侧各放一笔同额收入
     * （窗口左端之外的上一个月中旬、结算月中旬），它们若被算进来会让集合多出月份、断言立刻变红。
     */
    private void seedWindowProbeUser(Ctx ctx, LocalDate settleDate, List<String> months) {
        List<Object[]> rows = new ArrayList<>();
        for (String month : months) {
            rows.add(new Object[] {TransactionType.INCOME, new BigDecimal("1000.00"), midOf(month)});
        }
        String monthBeforeWindow = YearMonth.parse(months.get(0)).minusMonths(1).toString();
        rows.add(new Object[] {TransactionType.INCOME, new BigDecimal("1000.00"), midOf(monthBeforeWindow)});
        rows.add(new Object[] {TransactionType.INCOME, new BigDecimal("1000.00"),
                midOf(YearMonth.from(settleDate).toString())});
        seed(ctx, settleDate, rows);
    }

    /**
     * 在单个事务内批量播种若干「有效记账交易」。
     *
     * <p>{@code created_at} 按行序在「结算日往前第 1/2/3 天」之间轮转，使记账日集合恒为那 3 个连续自然日
     * （需求 3.2 的参照值）；{@code occurred_at} 则由各行自带，与 {@code created_at} 刻意<b>不同</b>
     * ——「这笔钱花在哪个月」与「哪天来记账」是两个口径（需求 4.6）。</p>
     *
     * <p>播种走 {@link TransactionRepository#save}（Hibernate）而不是 {@code JdbcTemplate} 直插，
     * 理由见类级 Javadoc：本属性守护的正是那条「挂钟值逐字进出」的 Hibernate 绑定路径。</p>
     */
    private void seed(Ctx ctx, LocalDate settleDate, List<Object[]> rows) {
        tx.executeWithoutResult(status -> {
            int i = 0;
            for (Object[] row : rows) {
                LocalDate recordDay = settleDate.minusDays(1L + (i++ % RECORD_DAYS));
                Transaction t = new Transaction();
                t.setUserId(ctx.userId());
                t.setLedgerId(ctx.ledgerId());
                t.setCreatedBy(ctx.userId());
                t.setType((TransactionType) row[0]);
                t.setAmount((BigDecimal) row[1]);
                t.setAccountId(ctx.ledgerId());
                t.setCategoryId(ctx.ledgerId());
                t.setOccurredAt((LocalDateTime) row[2]);
                t.setCreatedAt(recordDay.atTime(12, 0));
                t.setUpdatedAt(recordDay.atTime(12, 0));
                transactionRepository.save(t);
            }
        });
    }

    /** 某 {@code YYYY-MM} 的月首 {@code 00:00:00.000}（也是上一个月的右开边界）。 */
    private static LocalDateTime firstInstantOf(String yearMonth) {
        return YearMonth.parse(yearMonth).atDay(1).atStartOfDay();
    }

    /** 某 {@code YYYY-MM} 的月中一个远离任何边界的时刻。 */
    private static LocalDateTime midOf(String yearMonth) {
        return YearMonth.parse(yearMonth).atDay(15).atTime(12, 0);
    }

    // ---------------- 库读取工具 ----------------

    /** 一次运行的全部观察量；{@code record} 的 {@code equals} 逐字段比较，直接用于「与基准逐项相同」。 */
    private record Outcome(List<String> savingMonths, List<String> windowMonths,
                           List<String> savingEventKeys, List<String> dailyRecordKeys,
                           int totalRecordDays, int currentStreakDays, int maxStreakDays,
                           LocalDate lastRecordDate, LocalDateTime probeOccurredAt) {
    }

    private List<String> eventKeysOfType(long userId, String eventType) {
        return jdbcTemplate.queryForList(
                "SELECT event_key FROM growth_events WHERE user_id = ? AND event_type = ? ORDER BY id",
                String.class, userId, eventType);
    }

    private int profileInt(long userId, String column) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM user_growth WHERE user_id = ?", Integer.class, userId);
        return value == null ? 0 : value;
    }

    /**
     * 读回 {@code user_growth} 的 {@code last_record_date}。
     *
     * <p>用 {@code getObject(idx, LocalDate.class)} 而不是 {@code getDate}：后者取 JVM 默认时区的旧式
     * {@code Calendar}，在非 {@code Asia/Shanghai} 的默认时区下会整日平移——那样读出来的东西本身就是错的，
     * 本属性会因为<b>读取工具</b>的缺陷而变红，而不是因为被测代码。</p>
     */
    private LocalDate profileDate(long userId, String column) {
        return jdbcTemplate.query(
                "SELECT " + column + " FROM user_growth WHERE user_id = ?",
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null,
                userId);
    }

    /**
     * 从库里重读探针交易的 {@code occurred_at}（毫秒往返守卫）。
     *
     * <p>同样用 {@code getObject(idx, LocalDateTime.class)} 逐字回读，理由同 {@link #profileDate}。</p>
     */
    private LocalDateTime reloadOccurredAt(long userId, LocalDateTime probeOccurredAt) {
        return jdbcTemplate.query(
                "SELECT occurred_at FROM transactions WHERE created_by = ? AND amount = ? "
                        + "AND type = 'income' AND occurred_at = ? ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null,
                userId, PROBE_INCOME, probeOccurredAt);
    }

    /** 结算真实提交，清理不能靠回滚：每次运行前硬删事实源与两张成长表（各表间无外键）。 */
    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    // ---------------- 基础设施 ----------------

    /**
     * {@code @Primary} 可设定时钟（固定 {@code Asia/Shanghai}），覆盖 {@code TimeConfig} 的系统时钟。
     *
     * <p>结算日 = {@code LocalDateTime.now(clock).toLocalDate()}，由本时钟的<b>时区</b>决定，
     * 因此与 JVM 默认时区无关——这正是需求 4.1 要求的那条口径。</p>
     */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    /** 可设定到任意挂钟时刻的时钟；时区恒为 {@code Asia/Shanghai}。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        /** 把时钟设到指定的 {@code Asia/Shanghai} 挂钟时刻（毫秒精度原样保留）。 */
        void setTo(LocalDateTime wallClockInShanghai) {
            this.instant = wallClockInShanghai.atZone(SHANGHAI).toInstant();
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
