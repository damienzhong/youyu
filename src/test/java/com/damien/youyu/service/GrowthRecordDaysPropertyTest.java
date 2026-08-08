package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 记账日历与累计天数的<b>只增不减</b>回归锁（<b>Property 8：累计天数与经验事件条数一致，
 * 且不随交易删除回落</b>）。
 *
 * <p>记账日历（{@code event_type = 'DAILY_RECORD'} 的事件集合）是<b>唯一</b>只追加的日期账本：
 * 结算把每个记账日落成一条 {@code DAILY_RECORD:<yyyy-MM-dd>} 事件、经验恒 5，累计记账天数、
 * 连续段长度与历史最长连续天数三个物化列全部由这本日历（而非交易事实源）重算而来
 * （{@code GrowthCalendarService.scan} 纯函数）。因此本属性锁住四条构造性事实：</p>
 * <ul>
 *   <li><b>累计天数 == 事件条数</b>（需求 4.7）：{@code user_growth.total_record_days} 恒等于该用户
 *       {@code DAILY_RECORD} 事件条数，而后者又恒等于有记账发生的不同自然日个数。</li>
 *   <li><b>同日多笔只发一条、经验恰为 5</b>（需求 3.1、4.4）：同一自然日内任意 1–100 笔有效记账只
 *       产生 1 条 {@code DAILY_RECORD}，该日 {@code DAILY_RECORD} 经验合计恰为 5。</li>
 *   <li><b>删除不回落</b>（需求 4.8）：删净或删部分某记账日的有效记账交易后，重算得到的
 *       {@code total_record_days}、{@code current_streak_days}、{@code max_streak_days} 三列取值
 *       与删除前完全相同——日历只追加、不因事实源缩减而删日。</li>
 *   <li><b>结构不变式</b>（需求 4.9、4.10）：{@code max_streak_days >= current_streak_days} 恒成立；
 *       日历为空时三列均为 0 且 {@code last_record_date} 为空值（见 {@link #property8_emptyCalendarYieldsZeros()}）。</li>
 * </ul>
 *
 * <h2>驱动方式：全栈 {@code @SpringBootTest} + 真实提交，不用测试级事务</h2>
 * <p>{@link GrowthSettlementService#settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有真正
 * <b>提交</b>才能在库里观察到结算终态。故本测试不加测试级 {@code @Transactional}（那会在方法结束时回滚，
 * 掩盖真实写入），而是直接调用 {@code settle} 并从库读回断言；清理不能靠回滚，
 * {@link #resetState()} 每次迭代前显式清库，并用全局自增序号 {@link #SEQ} 保证 {@code userId} /
 * {@code ledgerId} 每次迭代全局唯一（双重隔离）。时钟用一个 {@code @Primary} 的可推进
 * {@link MutableClock}（覆盖 {@code TimeConfig} 的系统时钟），固定在 {@code Asia/Shanghai} 的
 * {@code 2025-06-15 08:00}，使结算日恒为 {@code 2025-06-15}，记账日与追补窗口可确定性断言。</p>
 *
 * <p>jqwik 属性方法不经 JUnit Jupiter 引擎、{@code SpringExtension} 不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 手工完成（Spring 静态上下文缓存复用，多次迭代
 * 只加载一次上下文）。用独立命名的内存库避免污染其它共享库的切片测试。</p>
 *
 * <h2>记账日范围的刻意约束</h2>
 * <p>记账日相对结算日的回溯天数刻意压在 [0, 60] 内：这使追补窗口末日恒等于结算日
 * （窗口 = min(起点 + 999 天, 结算日)，60 &lt; 999），全部历史记账日在<b>一次</b>结算内补齐，
 * total 恰等于不同记账日个数。「窗口末日 &lt; 结算日」的多次结算收敛分支不属于本属性，由 Property 7 覆盖。</p>
 *
 * <p>Feature: growth-level-system, Property 8: 累计天数与经验事件条数一致，且不随交易删除回落</p>
 *
 * <p>Validates: Requirements 3.1, 4.4, 4.7, 4.8, 4.9, 4.10</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-prop8-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class GrowthRecordDaysPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：结算日 = 2025-06-15。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 记账侧 60 秒节流窗口之外的推进量（保证删除后的 settle 真实执行而非被跳过）。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);

    private static final String DAILY_PREFIX = GrowthEventType.DAILY_RECORD + ":";

    /** 全局自增序号：保证每次迭代 userId / ledgerId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(8_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthRecordDaysPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删相关表（成长两表无外键，删除顺序无约束）。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM ledgers");
    }

    // ---------------- 生成器 ----------------

    /**
     * 记账日规格序列：每个元素是「相对结算日回溯天数（0–60）× 该日有效记账笔数（1–100）」。
     * 列表可含重复回溯天数（同一天被生成多次），测试内按回溯天数去重、笔数累加，从而覆盖
     * 「同日多笔（跨多个生成项）只发一条 DAILY_RECORD」的情形。
     */
    @Provide
    Arbitrary<List<DaySpec>> daySpecs() {
        Arbitrary<Integer> offset = Arbitraries.integers().between(0, 60);
        Arbitrary<Integer> count = Arbitraries.integers().between(1, 100);
        return net.jqwik.api.Combinators.combine(offset, count).as(DaySpec::new)
                .list().ofMinSize(1).ofMaxSize(8);
    }

    /** 一个记账日规格：回溯天数与该日笔数。 */
    record DaySpec(int offsetBack, int recordCount) { }

    // ---------------- Property 8（主属性）----------------

    /**
     * Feature: growth-level-system, Property 8: 累计天数与经验事件条数一致，且不随交易删除回落
     *
     * <p>把生成的记账日规格铺进 {@code transactions}（同一自然日的多笔用同一日期、不同秒的 {@code created_at}）、
     * 结算一次，断言累计天数 == {@code DAILY_RECORD} 条数 == 不同记账日个数、每个记账日恰 1 条且经验合计为 5、
     * {@code max >= current}；随后对某个记账日删净或删部分有效记账交易、越过节流再结算一次，断言三个物化列
     * 与删除前逐列相等（日历只追加、不回落）。</p>
     *
     * <p>Validates: Requirements 3.1, 4.4, 4.7, 4.8, 4.9, 4.10</p>
     */
    @Property(tries = 15)
    void property8_recordDaysMatchEventsAndNeverFallBackOnDelete(
            @ForAll("daySpecs") List<DaySpec> specs,
            @ForAll boolean deleteAll,
            @ForAll @IntRange(min = 0, max = 7) int deleteDayPicker) {

        long userId = SEQ.getAndIncrement();
        long ledgerId = createOwnedLedger(userId);
        LocalDate settleDate = LocalDate.now(CLOCK);

        // 回溯天数去重、笔数累加：offsetBack -> 该日总笔数（覆盖「同日多个生成项」的合并）。
        Map<Integer, Integer> countByOffset = new LinkedHashMap<>();
        for (DaySpec spec : specs) {
            countByOffset.merge(spec.offsetBack(), spec.recordCount(), Integer::sum);
        }

        // 铺交易：同一自然日的每笔用同一日期、9:00 起逐秒递增的 created_at（100 笔仍落在同一天）。
        // 记录每个记账日写入的交易 id，供后续按日删除。
        Map<Integer, List<Long>> txIdsByOffset = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : countByOffset.entrySet()) {
            int offsetBack = e.getKey();
            int recordCount = e.getValue();
            LocalDate day = settleDate.minusDays(offsetBack);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < recordCount; i++) {
                LocalDateTime at = day.atTime(9, 0).plusSeconds(i);
                ids.add(insertValidExpense(userId, ledgerId, at, new BigDecimal("12.34")));
            }
            txIdsByOffset.put(offsetBack, ids);
        }

        int distinctDays = countByOffset.size();

        // ── 结算并断言主不变式 ─────────────────────────────────────────────────────────
        settlementService.settle(userId, TriggerSource.RECORD);
        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();

        long dailyEventCount = countDailyRecordEvents(userId);

        // 需求 4.7：累计天数 == DAILY_RECORD 事件条数。
        assertThat(profile.getTotalRecordDays())
                .as("total_record_days 应等于 DAILY_RECORD 事件条数")
                .isEqualTo((int) dailyEventCount);
        // 全部记账日都在 60 天窗口内、一次结算补齐 ⇒ 条数恰等于不同记账日个数。
        assertThat(dailyEventCount)
                .as("DAILY_RECORD 事件条数应等于不同记账日个数")
                .isEqualTo(distinctDays);

        // 需求 3.1、4.4：每个记账日恰 1 条 DAILY_RECORD，该日经验合计恰为 5。
        for (int offsetBack : countByOffset.keySet()) {
            LocalDate day = settleDate.minusDays(offsetBack);
            String eventKey = DAILY_PREFIX + day;
            assertThat(dailyRecordRowCount(userId, eventKey))
                    .as("记账日 %s 的 DAILY_RECORD 事件应恰 1 条（同日多笔只发一条）", day)
                    .isEqualTo(1);
            assertThat(dailyRecordExpSum(userId, eventKey))
                    .as("记账日 %s 的 DAILY_RECORD 经验合计应恰为 5", day)
                    .isEqualTo(5L);
        }
        // DAILY_RECORD 经验总合计 == 5 × 记账日个数。
        assertThat(allDailyRecordExpSum(userId))
                .as("DAILY_RECORD 经验总合计应等于 5 × 记账日个数")
                .isEqualTo(5L * distinctDays);

        // 需求 4.9：max >= current，且 last_record_date == 记账日历最大日期。
        assertThat(profile.getMaxStreakDays())
                .as("max_streak_days 应 >= current_streak_days")
                .isGreaterThanOrEqualTo(profile.getCurrentStreakDays());
        LocalDate maxDay = settleDate.minusDays(countByOffset.keySet().stream()
                .mapToInt(Integer::intValue).min().orElseThrow());
        assertThat(profile.getLastRecordDate())
                .as("last_record_date 应等于记账日历中的最大日期")
                .isEqualTo(maxDay);

        // ── 删除某记账日的交易后，三个物化列不得回落 ─────────────────────────────────────
        int baselineTotal = profile.getTotalRecordDays();
        int baselineCurrent = profile.getCurrentStreakDays();
        int baselineMax = profile.getMaxStreakDays();
        LocalDate baselineLastDate = profile.getLastRecordDate();
        long baselineDailyCount = dailyEventCount;

        List<Integer> offsets = new ArrayList<>(txIdsByOffset.keySet());
        int targetOffset = offsets.get(deleteDayPicker % offsets.size());
        List<Long> targetIds = txIdsByOffset.get(targetOffset);
        int toDelete = deleteAll ? targetIds.size() : Math.max(1, targetIds.size() / 2);
        LocalDateTime deleteMoment = LocalDateTime.now(CLOCK);
        for (int i = 0; i < toDelete; i++) {
            softDelete(targetIds.get(i), deleteMoment);
        }

        // 越过 60 秒记账节流窗口（仍是同一自然日，settleDate 不变），使这次 settle 真实执行。
        CLOCK.advance(BEYOND_THROTTLE);
        settlementService.settle(userId, TriggerSource.RECORD);

        UserGrowth afterDelete = userGrowthRepository.findById(userId).orElseThrow();
        String mode = deleteAll ? "删净" : "删部分";

        // 需求 4.8：删除某记账日的有效记账交易后，三列取值不变（日历只追加）。
        assertThat(afterDelete.getTotalRecordDays())
                .as("%s 记账日 %s 的交易后 total_record_days 不得回落", mode, settleDate.minusDays(targetOffset))
                .isEqualTo(baselineTotal);
        assertThat(afterDelete.getCurrentStreakDays())
                .as("%s 交易后 current_streak_days 不得回落", mode)
                .isEqualTo(baselineCurrent);
        assertThat(afterDelete.getMaxStreakDays())
                .as("%s 交易后 max_streak_days 不得回落", mode)
                .isEqualTo(baselineMax);
        assertThat(afterDelete.getLastRecordDate())
                .as("%s 交易后 last_record_date 不变", mode)
                .isEqualTo(baselineLastDate);
        // DAILY_RECORD 事件条数同样不变（append-only）。
        assertThat(countDailyRecordEvents(userId))
                .as("%s 交易后 DAILY_RECORD 事件条数不得回落", mode)
                .isEqualTo(baselineDailyCount);
        // 结构不变式恒成立。
        assertThat(afterDelete.getMaxStreakDays())
                .as("删除后 max_streak_days 仍应 >= current_streak_days")
                .isGreaterThanOrEqualTo(afterDelete.getCurrentStreakDays());
    }

    // ---------------- Property 8（空日历分支）----------------

    /**
     * Feature: growth-level-system, Property 8: 累计天数与经验事件条数一致，且不随交易删除回落
     *
     * <p>空日历分支（需求 4.10）：一个从未记账的用户结算后，档案建立但记账日历为空——
     * {@code total_record_days}、{@code current_streak_days}、{@code max_streak_days} 三列均为 0，
     * {@code last_record_date} 为空值，且 {@code DAILY_RECORD} 事件条数为 0。</p>
     *
     * <p>Validates: Requirements 4.9, 4.10</p>
     */
    @Example
    void property8_emptyCalendarYieldsZeros() throws Exception {
        resetState();
        long userId = SEQ.getAndIncrement();
        createOwnedLedger(userId);

        settlementService.settle(userId, TriggerSource.RECORD);

        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(profile.getTotalRecordDays()).as("空日历 total_record_days 为 0").isZero();
        assertThat(profile.getCurrentStreakDays()).as("空日历 current_streak_days 为 0").isZero();
        assertThat(profile.getMaxStreakDays()).as("空日历 max_streak_days 为 0").isZero();
        assertThat(profile.getLastRecordDate()).as("空日历 last_record_date 为空值").isNull();
        assertThat(countDailyRecordEvents(userId)).as("空日历 DAILY_RECORD 事件条数为 0").isZero();
    }

    // ---------------- 事实源播种 ----------------

    /** 创建一个该用户拥有的个人账本，返回其 id。 */
    private long createOwnedLedger(long userId) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        Ledger ledger = new Ledger();
        ledger.setUserId(userId);
        ledger.setName("prop8-" + userId);
        ledger.setType(Ledger.TYPE_PERSONAL);
        ledger.setSortOrder(0);
        ledger.setDefault(true);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        return ledgerRepository.save(ledger).getId();
    }

    /**
     * 插入一笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type = expense}、{@code ledger_id} 非 NULL），记账日由 {@code created_at} 决定，返回其 id。
     */
    private long insertValidExpense(long userId, long ledgerId, LocalDateTime at, BigDecimal amount) {
        com.damien.youyu.domain.Transaction tx = new com.damien.youyu.domain.Transaction();
        tx.setUserId(userId);
        tx.setLedgerId(ledgerId);
        tx.setCreatedBy(userId);
        tx.setType(TransactionType.EXPENSE);
        tx.setAmount(amount);
        tx.setAccountId(ledgerId);
        tx.setCategoryId(ledgerId);
        tx.setOccurredAt(at);
        tx.setCreatedAt(at);
        tx.setUpdatedAt(at);
        return transactionRepository.save(tx).getId();
    }

    /**
     * 软删除一笔交易（移入回收站）。走 {@link JdbcTemplate} 直接置 {@code deleted_at}：实体带
     * {@code @SQLRestriction("deleted_at is null")}，经仓储读写会把软删行隐藏，无法直接操作。
     */
    private void softDelete(long txId, LocalDateTime now) {
        jdbcTemplate.update("UPDATE transactions SET deleted_at = ? WHERE id = ?", now, txId);
    }

    // ---------------- 读回工具 ----------------

    /** 该用户 DAILY_RECORD 事件条数（累计记账天数的独立计数依据，不复用被测聚合）。 */
    private long countDailyRecordEvents(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_type = ?",
                Long.class, userId, GrowthEventType.DAILY_RECORD);
        return n == null ? 0L : n;
    }

    /** 指定 event_key 的行数（应恒为 0 或 1，用于验证同日多笔只发一条）。 */
    private long dailyRecordRowCount(long userId, String eventKey) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_key = ?",
                Long.class, userId, eventKey);
        return n == null ? 0L : n;
    }

    /** 指定 event_key 的经验合计（应恒为 5）。 */
    private long dailyRecordExpSum(long userId, String eventKey) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(exp_amount), 0) FROM growth_events WHERE user_id = ? AND event_key = ?",
                Long.class, userId, eventKey);
        return n == null ? 0L : n;
    }

    /** 全部 DAILY_RECORD 事件的经验合计（应为 5 × 记账日个数）。 */
    private long allDailyRecordExpSum(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(exp_amount), 0) FROM growth_events WHERE user_id = ? AND event_type = ?",
                Long.class, userId, GrowthEventType.DAILY_RECORD);
        return n == null ? 0L : n;
    }

    // ---------------- 基础设施 ----------------

    /** {@code @Primary} 可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日可确定性断言。 */
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

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
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
