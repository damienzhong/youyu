package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 9：记账日与时区无关</b>的属性测试（任务 9.9）。
 *
 * <p>对<i>任意</i>一批交易的 {@code created_at} 取值（含 {@code 00:00:00}、{@code 23:59:59}、闰日、
 * 月末、年末）与<i>任意</i> JVM 默认时区（{@code UTC}、{@code America/New_York}、{@code Asia/Tokyo}、
 * {@code Pacific/Kiritimati}、{@code Asia/Shanghai}）：同一批交易经真实
 * {@link GrowthSettlementService#settle} 结算后，算出的<b>记账日集合、{@code DAILY_RECORD} 事件键、
 * 累计记账天数（{@code total_record_days}）、连续段长度（{@code current_streak_days}）与历史最长
 * 连续天数（{@code max_streak_days}）完全相同</b>（需求 3.7、3.8、4.1、4.16）。每条 {@code DAILY_RECORD}
 * 事件键恒匹配 {@code DAILY_RECORD:\d{4}-\d{2}-\d{2}} 且长度不超过 64 字符（需求 3.8）。</p>
 *
 * <h2>为什么用「与时区无关的纯 Java 参照」作断言基准</h2>
 * <p>本测试不是把两个 JVM 时区下的输出互相比对（那样只能证明「两个时区彼此一致」，无法钉住到底
 * 哪个才是对的），而是先用纯 {@link LocalDate} 从 {@code created_at} 直接推出一份<b>与任何时区无关</b>
 * 的参照结果（去重日期 → {@link GrowthCalendarService#scan}），再在<b>每一个</b> JVM 默认时区下真实
 * 播种交易、结算、回读，逐项断言回读结果 == 参照结果。既然对全部 5 个时区都成立
 * {@code 回读 == 参照}，那么它们彼此也必然相等，且相等于「记账日就是 {@code created_at} 的东八区挂钟
 * 自然日」这一唯一正确取值。</p>
 *
 * <h2>本测试是「记账日归属与 JVM 默认时区无关」这套内在实现的回归锁</h2>
 * <p>本项目全库 {@code DATETIME} 列存的都是 {@code Asia/Shanghai} 的<b>挂钟时刻</b>。这条正确性
 * <b>曾经</b>依赖两个脆弱前提：① {@code YouyuApplication.main} 在启动时
 * {@code TimeZone.setDefault(Asia/Shanghai)}；② 由此让 Hibernate 经 {@code java.sql.Timestamp} 的
 * 默认时区换算「碰巧」把挂钟值逐字落库。二者在 {@code @SpringBootTest}（不走 {@code main}）且 CI 跑在
 * {@code UTC} 时同时失效——这正是本属性最初暴露的真实缺陷：非 {@code Asia/Shanghai} 的 JVM 默认时区下，
 * {@code created_at} 经 Hibernate 落库/回读时整体平移，{@code CAST(created_at AS DATE)} 取到的记账日随之偏移。</p>
 *
 * <p><b>根治方式（本属性锁死的正是它，不得回退）</b>：让 {@code LocalDateTime ↔ DATETIME}、
 * {@code CAST(...) ↔ LocalDate} 的绑定<b>不经 {@code java.sql.Timestamp/Date} 的默认时区换算</b>、
 * 挂钟值逐字进出，与 JVM 默认时区彻底无关：</p>
 * <ul>
 *   <li><b>写侧</b>：{@code application.yml}（及测试 profile）设
 *       {@code hibernate.type.java_time_use_direct_jdbc=true}，让 Hibernate 用 JDBC 4.2 的
 *       {@code setObject} 直接绑定 {@code java.time} 类型（挂钟值逐字落库）。撤掉它，本属性在非
 *       {@code Asia/Shanghai} 默认时区下必然失败。因此播种<b>必须走 Hibernate 仓储</b>
 *       （{@link TransactionRepository#save}）而非 {@code JdbcTemplate} 直插——否则绕过被守护的那条
 *       Hibernate 绑定路径，使这道回归锁失效。</li>
 *   <li><b>读侧</b>：追补的两条查询（{@code findEarliestRecordCreatedAt} 与
 *       {@code findRecordDatesInWindow}）以 {@code ResultSet#getObject(idx, LocalDateTime/LocalDate.class)}
 *       逐字回读，而非原生 {@code @Query} 标量的 {@code getTimestamp/getDate}（后者取 JVM 默认时区的
 *       旧式 {@code Calendar}，非 {@code Asia/Shanghai} 时整日平移）。改回 {@code java.sql.Date} 回读，
 *       本属性同样必然失败。</li>
 *   <li><b>仍然刻意不设 {@code hibernate.jdbc.time_zone}</b>：它只是在默认时区换算之上再叠一层目标
 *       时区换算，治标不治本（写侧仍经 {@code Timestamp}、且原生 JDBC 写入不受它影响，两条写入路径照样
 *       分叉）。真正的根治是上面的「直接 JDBC 4.2 绑定」。设上它反而会把已经逐字进出的挂钟值重新平移，
 *       本属性会在非 {@code Asia/Shanghai} 默认时区下失败——这一层含义与旧回归锁一致，被本属性继续守护。</li>
 * </ul>
 *
 * <h2>并发与时区还原（必读）</h2>
 * <p>{@link TimeZone#setDefault(TimeZone)} 改的是<b>整个 JVM 的全局默认时区</b>，会污染同一 JVM 内
 * 其它测试。为此本测试类：</p>
 * <ul>
 *   <li><b>必须串行执行</b>，且<b>不得与其它测试类并行</b>。jqwik 的 {@code @Property} 默认串行执行
 *       各次 try，本项目也未开启任何 surefire / junit-platform 并行配置；一旦将来引入测试并行，
 *       本类必须被显式排除或加互斥锁，否则它在某次 try 中途改掉的默认时区会被并行的其它测试读到。</li>
 *   <li>在 {@link #restoreDefaultTimeZone()}（{@code @AfterTry}，形同 finally）里<b>无条件还原</b>
 *       进入本类前捕获的原始默认时区 {@link #ORIGINAL_TIME_ZONE}，即便某次 try 在断言处抛出也照常还原。</li>
 * </ul>
 *
 * <h2>结算日与追补窗口</h2>
 * <p>注入一个 {@code @Primary} 的固定 {@link Clock}（{@code 2026-01-15 08:00 Asia/Shanghai}），使
 * {@code settle} 读到的结算日恒为 {@code 2026-01-15}——它由 {@code Clock} 的时区决定，<b>与 JVM 默认
 * 时区无关</b>，这正是需求 4.16 的应有之义。生成的 {@code created_at} 全部落在 {@code [2024-01-01,
 * 2025-12-31]}，故最早记账日到结算日不足 1000 天，单次结算的追补窗口即可覆盖整批交易，记账日集合
 * 恰好等于这批交易的去重日期，无残留追补。</p>
 *
 * <h2>测试层级与清理</h2>
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有<b>真实提交</b>才能在库里观察到结算
 * 终态，故走全栈 {@code @SpringBootTest} + H2（{@code MODE=MySQL}，独立命名内存库）。清理<b>不能靠
 * 事务回滚</b>：{@link #resetState()} 在每次迭代前显式清三张表并用全局自增序号 {@link #SEQ} 保证每次
 * 时区迭代的 {@code userId} / {@code ledgerId} 全局唯一。jqwik 属性方法不经 {@code SpringExtension}，
 * 依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文缓存复用）。</p>
 *
 * <p>Feature: growth-level-system, Property 9: 记账日与时区无关</p>
 *
 * <p>Validates: Requirements 3.7, 3.8, 4.1, 4.16</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-timezone-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(GrowthTimezoneIndependencePropertyTest.ClockConfig.class)
class GrowthTimezoneIndependencePropertyTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 2026-01-15 08:00（Asia/Shanghai）：结算日 = 2026-01-15，晚于全部生成的记账日。 */
    private static final Instant SETTLE_INSTANT = Instant.parse("2026-01-15T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(SETTLE_INSTANT, SHANGHAI);

    /** 待轮换的 JVM 默认时区：横跨西半球、东半球与国际日期变更线以东（需求 4.16）。 */
    private static final List<ZoneId> ZONES = List.of(
            ZoneId.of("UTC"),
            ZoneId.of("America/New_York"),
            ZoneId.of("Asia/Tokyo"),
            ZoneId.of("Pacific/Kiritimati"),
            ZoneId.of("Asia/Shanghai"));

    /** 进入本类前的默认时区，{@code @AfterTry} 无条件还原到它，避免污染同一 JVM 的其它测试。 */
    private static final TimeZone ORIGINAL_TIME_ZONE = TimeZone.getDefault();

    /** 生成器可选的一天内时刻：覆盖 00:00:00、临界秒、正午、23:59:59 与当日最后一毫秒。 */
    private static final List<LocalTime> TIMES_OF_DAY = List.of(
            LocalTime.of(0, 0, 0),
            LocalTime.of(0, 0, 1),
            LocalTime.of(12, 0, 0),
            LocalTime.of(23, 59, 59),
            LocalTime.of(23, 59, 59, 999_000_000));

    /** 生成器刻意包含的高风险日期：闰日、闰日次日、月末、年末、跨年首日。 */
    private static final List<LocalDate> INTERESTING_DATES = List.of(
            LocalDate.of(2024, 2, 29),   // 闰日
            LocalDate.of(2024, 3, 1),    // 闰日次日（跨月连续段）
            LocalDate.of(2025, 2, 28),   // 平年 2 月末
            LocalDate.of(2024, 1, 31),   // 月末
            LocalDate.of(2024, 4, 30),   // 月末
            LocalDate.of(2024, 12, 31),  // 年末
            LocalDate.of(2025, 1, 1),    // 跨年首日
            LocalDate.of(2025, 12, 31)); // 年末

    private static final long MIN_EPOCH_DAY = LocalDate.of(2024, 1, 1).toEpochDay();
    private static final long MAX_EPOCH_DAY = LocalDate.of(2025, 12, 31).toEpochDay();

    /** 跨迭代复用同一内存库，用序号保证每个时区迭代的 userId / ledgerId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(9_100_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private GrowthEventRepository growthEventRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthTimezoneIndependencePropertyTest.class).prepareTestInstance(this);
        tx = new TransactionTemplate(transactionManager);
    }

    /** 无条件还原默认时区（形同 finally）：即便某次 try 在断言处抛出也执行，避免污染同 JVM 其它测试。 */
    @AfterTry
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(ORIGINAL_TIME_ZONE);
    }

    // ---------------- 生成器 ----------------

    @Provide
    Arbitrary<LocalDate> recordDates() {
        Arbitrary<LocalDate> interesting = Arbitraries.of(INTERESTING_DATES);
        Arbitrary<LocalDate> random = Arbitraries.longs().between(MIN_EPOCH_DAY, MAX_EPOCH_DAY)
                .map(LocalDate::ofEpochDay);
        // 高风险日期与随机日期等权重混合，确保闰日/月末/年末在小规模批次里也高概率出现。
        return Arbitraries.oneOf(interesting, random);
    }

    @Provide
    Arbitrary<LocalDateTime> createdAts() {
        Arbitrary<LocalTime> times = Arbitraries.of(TIMES_OF_DAY);
        return Combinators.combine(recordDates(), times).as(LocalDate::atTime);
    }

    /** 一批交易的 {@code created_at}（1–50 笔，含大量同日/相邻日/跨段组合）。 */
    @Provide
    Arbitrary<List<LocalDateTime>> transactionBatches() {
        return createdAts().list().ofMinSize(1).ofMaxSize(50);
    }

    // ---------------- Property 9 ----------------

    /**
     * Feature: growth-level-system, Property 9: 记账日与时区无关
     *
     * <p>对同一批 {@code created_at}，先算出与时区无关的纯 Java 参照（去重日期 → {@code scan}），再在
     * 全部 5 个 JVM 默认时区下真实播种 + 结算 + 回读，逐项断言回读结果与参照相等；由此推出各时区之间
     * 也彼此相等（需求 3.7、3.8、4.1、4.16）。</p>
     *
     * <p>Validates: Requirements 3.7, 3.8, 4.1, 4.16</p>
     */
    @Property(tries = 15)
    void recordDaysAreIndependentOfJvmDefaultTimeZone(
            @ForAll("transactionBatches") List<LocalDateTime> createdAts) {

        // 与任何时区无关的参照：记账日 = created_at 的挂钟自然日，去重升序后交给纯函数 scan。
        List<LocalDate> expectedDates = distinctSortedDates(createdAts);
        CalendarScan expected = GrowthCalendarService.scan(expectedDates);
        List<String> expectedKeys = dailyRecordKeysOf(expectedDates);

        for (ZoneId zone : ZONES) {
            assertSettlementMatchesReference(zone, createdAts, expectedDates, expected, expectedKeys);
        }
    }

    /**
     * 大批次跨越全部高风险日期的示例：一次性播种闰日、月末、年末与相邻日，验证五项输出在 5 个时区下
     * 仍与参照逐项相等。规模稍大单独作示例跑一次，不拖慢 {@code @Property} 的每轮迭代。
     *
     * <p>Validates: Requirements 3.7, 3.8, 4.1, 4.16</p>
     */
    @Example
    void allEdgeDatesAcrossFiveZones() throws Exception {
        new TestContextManager(GrowthTimezoneIndependencePropertyTest.class).prepareTestInstance(this);
        tx = new TransactionTemplate(transactionManager);

        List<LocalDateTime> createdAts = new ArrayList<>();
        for (LocalDate date : INTERESTING_DATES) {
            for (LocalTime time : TIMES_OF_DAY) {
                createdAts.add(date.atTime(time));
            }
        }

        List<LocalDate> expectedDates = distinctSortedDates(createdAts);
        CalendarScan expected = GrowthCalendarService.scan(expectedDates);
        List<String> expectedKeys = dailyRecordKeysOf(expectedDates);

        try {
            for (ZoneId zone : ZONES) {
                assertSettlementMatchesReference(zone, createdAts, expectedDates, expected, expectedKeys);
            }
        } finally {
            TimeZone.setDefault(ORIGINAL_TIME_ZONE);
        }
    }

    // ---------------- 断言核心 ----------------

    /**
     * 在指定 JVM 默认时区下播种 + 结算 + 回读，断言五项输出与时区无关的参照逐项相等，且每条
     * {@code DAILY_RECORD} 事件键格式合法、长度 ≤64。
     */
    private void assertSettlementMatchesReference(ZoneId zone, List<LocalDateTime> createdAts,
                                                  List<LocalDate> expectedDates, CalendarScan expected,
                                                  List<String> expectedKeys) {
        // 切换 JVM 默认时区必须发生在「播种 + 结算」之前：写入路径若被 hibernate.jdbc.time_zone 污染，
        // 换算就在此时发生。@AfterTry 会无条件还原。
        TimeZone.setDefault(TimeZone.getTimeZone(zone));

        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();

        // 清理不靠回滚：每个时区迭代前硬删三张表（均无外键，删除顺序无约束）。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");

        seedRecords(userId, ledgerId, createdAts);
        settlementService.settle(userId, TriggerSource.RECORD);

        String because = "默认时区 " + zone + " 下记账日与结算结果应与时区无关的参照相等";

        // ① DAILY_RECORD 事件键（升序）== 参照键，逐项相等。
        List<String> actualKeys = growthEventRepository.findDailyRecordKeys(userId);
        assertThat(actualKeys).as(because).isEqualTo(expectedKeys);

        // ② 每条事件键格式合法且长度 ≤64（需求 3.8）。
        for (String key : actualKeys) {
            assertThat(key.matches("DAILY_RECORD:\\d{4}-\\d{2}-\\d{2}"))
                    .as("事件键 %s 应形如 DAILY_RECORD:YYYY-MM-DD", key).isTrue();
            assertThat(key.length()).as("事件键 %s 长度应 ≤64", key).isLessThanOrEqualTo(64);
        }

        // ③ 物化列（累计天数 / 连续段 / 历史最长 / 最近记账日）== 参照 scan，逐项相等。
        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(profile.getTotalRecordDays()).as(because).isEqualTo(expected.totalDays());
        assertThat(profile.getCurrentStreakDays()).as(because).isEqualTo(expected.currentSegment());
        assertThat(profile.getMaxStreakDays()).as(because).isEqualTo(expected.maxStreak());
        assertThat(profile.getLastRecordDate()).as(because).isEqualTo(expected.lastDate());

        // ④ 累计天数恒等于去重日期个数与 DAILY_RECORD 事件条数（需求 4.7）。
        assertThat(profile.getTotalRecordDays()).as(because).isEqualTo(expectedDates.size());
        assertThat(actualKeys).as(because).hasSize(expectedDates.size());
    }

    // ---------------- 事实源播种与参照 ----------------

    /** 在单个事务内批量播种若干「有效记账交易」，记账日由各自的 {@code created_at} 决定。 */
    private void seedRecords(long userId, long ledgerId, List<LocalDateTime> createdAts) {
        tx.executeWithoutResult(status -> {
            int i = 0;
            for (LocalDateTime createdAt : createdAts) {
                TransactionType type = (i++ % 2 == 0) ? TransactionType.EXPENSE : TransactionType.INCOME;
                transactionRepository.save(newValidRecord(userId, ledgerId, createdAt, type));
            }
        });
    }

    /**
     * 构造一笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type ∈ {expense,income}}、{@code ledger_id} 非 NULL）。记账日由 {@code created_at} 决定，
     * {@code occurred_at} 不参与记账日计算，随 {@code created_at} 设同值即可。
     */
    private Transaction newValidRecord(long userId, long ledgerId, LocalDateTime createdAt,
                                       TransactionType type) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setLedgerId(ledgerId);
        t.setCreatedBy(userId);
        t.setType(type);
        t.setAmount(new BigDecimal("12.34"));
        t.setAccountId(ledgerId);
        t.setCategoryId(ledgerId);
        t.setOccurredAt(createdAt);
        t.setCreatedAt(createdAt);
        t.setUpdatedAt(createdAt);
        return t;
    }

    /** 与时区无关的参照：取 {@code created_at} 的挂钟自然日，去重后升序。 */
    private static List<LocalDate> distinctSortedDates(List<LocalDateTime> createdAts) {
        TreeSet<LocalDate> set = new TreeSet<>();
        for (LocalDateTime createdAt : createdAts) {
            set.add(createdAt.toLocalDate());
        }
        return new ArrayList<>(set);
    }

    /** 参照 {@code DAILY_RECORD} 事件键（升序，与仓储 {@code findDailyRecordKeys} 的返回序一致）。 */
    private static List<String> dailyRecordKeysOf(List<LocalDate> ascendingDates) {
        List<String> keys = new ArrayList<>(ascendingDates.size());
        for (LocalDate date : ascendingDates) {
            keys.add("DAILY_RECORD:" + date);
        }
        return keys;
    }

    /** 提供一个 {@code @Primary} 的固定时钟（Asia/Shanghai），使结算日与 JVM 默认时区无关。 */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return FIXED_CLOCK;
        }
    }
}
