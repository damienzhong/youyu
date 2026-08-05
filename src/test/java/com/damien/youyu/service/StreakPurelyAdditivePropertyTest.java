package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

import com.damien.youyu.repository.StreakSegmentRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 8：段维护不改成长与成就</b>的属性测试（任务 8.3）。
 *
 * <p><i>对任意</i>记账日历（一段「操作序列」，每个日期一笔支出）：把同一份事实源分别喂给
 * <b>执行段维护</b>与<b>不执行段维护</b>两种结算，两种情形下成长与成就侧的产物<b>逐项相同</b>
 * （需求 10.1、10.2、10.3）：</p>
 * <ul>
 *   <li>{@code user_growth} 的六项——{@code exp} / {@code level} / {@code total_record_days} /
 *       {@code current_streak_days} / {@code max_streak_days} / {@code last_record_date}；</li>
 *   <li>{@code growth_events} 的行与列（{@code event_type} / {@code event_key} / {@code exp_amount} /
 *       {@code created_at}）；</li>
 *   <li>{@code achievement_notices} 的行与列；</li>
 *   <li>已解锁成就集合（{@code BADGE} 事件键集合）与解锁时刻（对应 {@code BADGE} 事件的 {@code created_at}）。</li>
 * </ul>
 * <p>段表 {@code streak_segments} 本身在两种情形下当然不同（关掉段维护则为空）——那正是本 spec 的<b>增量</b>
 * 所在，不在比对范围内。本属性锁的是「除这张新表之外，成长与成就侧一个比特都没变」。</p>
 *
 * <h2>如何做到「同种子同 {@code Clock}」的逐位比对</h2>
 *
 * <p>{@link FixtureConfig} 用一个 {@code @Primary} 的<b>固定 {@link Clock}</b>覆盖 {@code TimeConfig} 的系统
 * 时钟：两次结算读到的 {@code now} 逐位相同，因此事件的 {@code created_at}、档案的物化列全都可逐字段比对，
 * 而不必把时间戳排除在外。段维护的开关由 {@link ToggleableStreakSegmentMaintainer} 的一个进程内布尔标志
 * {@link #MAINTAIN_ENABLED} 控制：置真时委托真实段维护，置假时直接返回（不读不写段表），
 * 除此之外结算的其余步骤（③④⑤⑥）逐字节相同。两个用户先后串行结算，标志在两次之间翻转，互不干扰。</p>
 *
 * <h2>反向断言（不可选，锁死「纯增量」）</h2>
 *
 * <p>{@link #reverseAssertion_maintainerThatTouchesProfile_breaksTheProperty()}：把
 * {@link #CORRUPT_PROFILE} 置真，让段维护在做完本职工作后<b>顺手改一行 {@code user_growth}</b>
 * （{@code exp += 1}，模拟「将来有人在 maintainer 里写一行 profile」这种回归）。此时「执行段维护」与
 * 「不执行段维护」的成长产物必然分叉，本属性<b>必须失败</b>——这道反向断言证明前一条正向属性不是恒真的空断言。</p>
 *
 * <p>jqwik 属性方法不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 手工完成（上下文缓存复用）。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Feature: streak-system, Property 8: 段维护不改成长与成就</p>
 * <p>Validates: Requirements 10.1, 10.2, 10.3, 7.12, 3.1, 3.10</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-additive-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(StreakPurelyAdditivePropertyTest.FixtureConfig.class)
class StreakPurelyAdditivePropertyTest {

    /** 业务时区，与固定 {@link Clock} 同一时区。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定「今天」：结算判定日恒为它，生成的记账日一律落在它当日或更早。 */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2025, 3, 12);

    /** 固定时钟的瞬时：{@link #FIXED_TODAY} 当日 10:00（东八区），两次结算读到的 {@code now} 逐位相同。 */
    private static final Instant FIXED_INSTANT =
            FIXED_TODAY.atTime(10, 0).atZone(BUSINESS_ZONE).toInstant();

    /** 段维护开关：置真委托真实段维护，置假直接返回（不读不写段表）。 */
    private static final AtomicBoolean MAINTAIN_ENABLED = new AtomicBoolean(true);

    /** 反向断言开关：置真时段维护顺手改一行 {@code user_growth}（模拟「非纯增量」回归）。 */
    private static final AtomicBoolean CORRUPT_PROFILE = new AtomicBoolean(false);

    /** 交易直插语句：列顺序与 {@link #seedTransaction} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 全局自增序号：保证跨迭代、跨「开 / 关段维护」两个用户的 id 全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong(1_280_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void prepare() throws Exception {
        new TestContextManager(StreakPurelyAdditivePropertyTest.class).prepareTestInstance(this);
        MAINTAIN_ENABLED.set(true);
        CORRUPT_PROFILE.set(false);
    }

    /** 无条件复位两个开关（形同 finally），避免某次断言抛出后污染后续迭代。 */
    @AfterTry
    void resetToggles() {
        MAINTAIN_ENABLED.set(true);
        CORRUPT_PROFILE.set(false);
    }

    // ---------------- 生成器 ----------------

    /**
     * 记账日历：长度 0～40 的一组自然日偏移（相对 {@link #FIXED_TODAY} 往前推），去重升序。
     *
     * <p>偏移稠密覆盖全连续 / 全离散 / 跨月 / 含空隙等形态；全部日期落在固定「今天」当日或更早，
     * 使连续里程碑（{@code STREAK_7} / {@code STREAK_30}）在长连续段下也可能触发，让「成就侧不变」
     * 这条断言在有成就解锁时同样成立。</p>
     */
    @Provide
    Arbitrary<List<LocalDate>> calendars() {
        Arbitrary<Integer> offsets = Arbitraries.integers().between(0, 39);
        return offsets.set().ofMinSize(0).ofMaxSize(40)
                .map(set -> set.stream().sorted().map(FIXED_TODAY::minusDays).toList());
    }

    /** 非空记账日历（反向断言用）：至少含一个记账日，确保成长产物非平凡。 */
    @Provide
    Arbitrary<List<LocalDate>> nonEmptyCalendars() {
        Arbitrary<Integer> offsets = Arbitraries.integers().between(0, 39);
        return offsets.set().ofMinSize(1).ofMaxSize(40)
                .map(set -> set.stream().sorted().map(FIXED_TODAY::minusDays).toList());
    }

    // ---------------- Property 8（正向）----------------

    /**
     * Feature: streak-system, Property 8: 段维护不改成长与成就
     *
     * <p>任意记账日历下，开 / 关段维护两种结算的成长六列、成长事件行与列、播报游标行与列、
     * 已解锁成就集合与解锁时刻全部逐项相同（需求 10.1、10.2、10.3）。</p>
     *
     * <p>Validates: Requirements 10.1, 10.2, 10.3, 7.12, 3.1, 3.10</p>
     */
    @Property(tries = 40)
    void segmentMaintenance_changesNeitherGrowthNorAchievement(
            @ForAll("calendars") List<LocalDate> calendar) {

        GrowthProjection withMaintenance = settleAndProject(calendar, true, false);
        GrowthProjection withoutMaintenance = settleAndProject(calendar, false, false);

        assertThat(withMaintenance)
                .as("开 / 关段维护两种情形下成长与成就侧产物逐项相同（需求 10.1、10.2、10.3）")
                .isEqualTo(withoutMaintenance);
    }

    // ---------------- Property 8（反向断言，不可选）----------------

    /**
     * 反向断言：段维护若顺手改一行 {@code user_growth}，正向属性必须失败——锁死「纯增量」。
     *
     * <p>置 {@link #CORRUPT_PROFILE} 后，开段维护的那次结算把 {@code exp} 多加了 1，而关段维护的那次没有，
     * 于是两侧成长六列分叉。若这道断言没抛出，说明段维护对 {@code user_growth} 的改动被正向属性漏掉了，
     * 那样正向属性就是恒真的空断言。</p>
     */
    @Example
    void reverseAssertion_maintainerThatTouchesProfile_breaksTheProperty() {
        List<LocalDate> calendar = List.of(
                FIXED_TODAY.minusDays(2), FIXED_TODAY.minusDays(1), FIXED_TODAY);

        GrowthProjection corruptedWithMaintenance = settleAndProject(calendar, true, true);
        GrowthProjection withoutMaintenance = settleAndProject(calendar, false, false);

        assertThatThrownBy(() -> assertThat(corruptedWithMaintenance).isEqualTo(withoutMaintenance))
                .as("段维护改了 profile 时，「纯增量」属性必须失败")
                .isInstanceOf(AssertionError.class);
    }

    // ---------------- 结算并投影 ----------------

    /**
     * 建一个全新用户、播种日历对应的一笔/日支出、按给定开关执行一次结算，返回成长与成就侧的可比投影。
     *
     * @param calendar    记账日历（每个日期一笔支出）
     * @param maintain    是否执行段维护
     * @param corrupt     段维护是否顺手改 {@code user_growth}（仅反向断言用）
     */
    private GrowthProjection settleAndProject(List<LocalDate> calendar, boolean maintain, boolean corrupt) {
        long userId = SEQ.getAndIncrement();
        long ledgerId = 900_000_000L + userId;
        for (LocalDate day : calendar) {
            seedTransaction(userId, ledgerId, day);
        }
        MAINTAIN_ENABLED.set(maintain);
        CORRUPT_PROFILE.set(corrupt);
        try {
            settlementService.settle(userId, TriggerSource.RECORD);
        } finally {
            MAINTAIN_ENABLED.set(true);
            CORRUPT_PROFILE.set(false);
        }
        return project(userId);
    }

    // ---------------- 可比投影 ----------------

    /** {@code user_growth} 的六项（不含时间戳与 {@code user_id} / {@code created_at}）。 */
    private record ProfileCols(long exp, int level, int totalRecordDays, int currentStreakDays,
                               int maxStreakDays, LocalDate lastRecordDate) {
    }

    /** 一行 {@code growth_events}（不含 {@code id} / {@code user_id}）。 */
    private record EventRow(String eventType, String eventKey, long expAmount, LocalDateTime createdAt) {
    }

    /** 成长与成就侧的可比投影（{@code record} 的 {@code equals} 逐字段比较）。 */
    private record GrowthProjection(ProfileCols profile, List<EventRow> events,
                                    List<Long> noticeCursors, Map<String, LocalDateTime> unlockedBadges) {
    }

    private GrowthProjection project(long userId) {
        ProfileCols profile = jdbcTemplate.queryForObject(
                "SELECT exp, level, total_record_days, current_streak_days, max_streak_days, "
                        + "last_record_date FROM user_growth WHERE user_id = ?",
                (rs, i) -> new ProfileCols(
                        rs.getLong("exp"), rs.getInt("level"), rs.getInt("total_record_days"),
                        rs.getInt("current_streak_days"), rs.getInt("max_streak_days"),
                        rs.getObject("last_record_date", LocalDate.class)),
                userId);

        List<EventRow> events = jdbcTemplate.query(
                "SELECT event_type, event_key, exp_amount, created_at FROM growth_events "
                        + "WHERE user_id = ? ORDER BY event_key",
                (rs, i) -> new EventRow(
                        rs.getString("event_type"), rs.getString("event_key"),
                        rs.getLong("exp_amount"), rs.getTimestamp("created_at").toLocalDateTime()),
                userId);

        List<Long> noticeCursors = jdbcTemplate.queryForList(
                "SELECT last_notified_event_id FROM achievement_notices WHERE user_id = ? ORDER BY 1",
                Long.class, userId);

        Map<String, LocalDateTime> unlockedBadges = new LinkedHashMap<>();
        for (EventRow event : events) {
            if ("BADGE".equals(event.eventType())) {
                unlockedBadges.put(event.eventKey(), event.createdAt());
            }
        }
        return new GrowthProjection(profile, events, noticeCursors, unlockedBadges);
    }

    // ---------------- 播种辅助 ----------------

    private void seedTransaction(long userId, long ledgerId, LocalDate recordDay) {
        java.sql.Timestamp createdAt = java.sql.Timestamp.valueOf(recordDay.atTime(12, 0));
        long ref = 900_000_000L + userId;
        jdbcTemplate.update(INSERT_TX_SQL, userId, ledgerId, userId, "expense",
                new BigDecimal("1.00"), ref, ref, createdAt, createdAt, createdAt);
    }

    // ---------------- 测试基础设施 ----------------

    /**
     * 固定 {@link Clock} + 可开关的段维护 {@code @Primary} 覆盖。
     *
     * <p>固定时钟让两次结算的 {@code now} 逐位相同，从而事件时间戳可逐字段比对；可开关的段维护把
     * 「执行 / 不执行段维护」收敛为同一段结算代码上的一个布尔分支，除段表外其余步骤逐字节相同。</p>
     */
    @TestConfiguration
    static class FixtureConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_INSTANT, BUSINESS_ZONE);
        }

        @Bean
        @Primary
        StreakSegmentMaintainer toggleableStreakSegmentMaintainer(
                @Qualifier("streakSegmentRepository") StreakSegmentRepository repository,
                JdbcTemplate jdbcTemplate, Clock clock) {
            return new ToggleableStreakSegmentMaintainer(repository, jdbcTemplate, clock);
        }
    }

    /**
     * 可开关的段维护：{@link #MAINTAIN_ENABLED} 为假时直接返回（不读不写段表），为真时委托真实段维护；
     * {@link #CORRUPT_PROFILE} 为真时在段维护后顺手改一行 {@code user_growth}（仅反向断言用）。
     *
     * <p>因需要覆盖包内可见的 {@code maintain} 方法，本类与真实 {@link StreakSegmentMaintainer} 同包。</p>
     */
    static class ToggleableStreakSegmentMaintainer extends StreakSegmentMaintainer {

        private final JdbcTemplate jdbcTemplate;

        ToggleableStreakSegmentMaintainer(StreakSegmentRepository repository,
                                          JdbcTemplate jdbcTemplate, Clock clock) {
            super(repository, jdbcTemplate, clock);
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        void maintain(Long userId, List<LocalDate> calendar, LocalDateTime now) {
            if (!MAINTAIN_ENABLED.get()) {
                return;
            }
            super.maintain(userId, calendar, now);
            if (CORRUPT_PROFILE.get()) {
                // 反向断言：模拟「将来有人在 maintainer 里顺手往成长侧写一行」的非增量回归。
                // 刻意不改 user_growth 的列——那会被结算随后 save(profile) 的整行覆盖而观察不到；
                // 改为插一行 growth_events（成长事实源），这行不会被覆盖，正是「纯增量被破坏」的样子。
                jdbcTemplate.update(
                        "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                                + "VALUES (?, 'DAILY_RECORD', 'DAILY_RECORD:1900-01-01', 5, ?)",
                        userId, java.sql.Timestamp.valueOf(now));
            }
        }
    }
}
