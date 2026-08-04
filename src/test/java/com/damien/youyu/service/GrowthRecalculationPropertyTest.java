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
import java.util.List;
import java.util.Map;
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
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 5：增量维护结果等于全量重算结果</b>的属性测试（任务 9.5）。
 *
 * <p>对<i>任意</i>操作序列，先执行任意次 {@link GrowthSettlementService#settle}（增量维护路径：
 * 追补事件 + 全量重算写回），再执行一次 {@link GrowthSettlementService#recalculateOnly}
 * （纯重算路径：跳过组装与插入、直接重算写回）。断言重算<b>前后</b>成长档案的五个物化列
 * （{@code exp}、{@code level}、{@code total_record_days}、{@code current_streak_days}、
 * {@code max_streak_days}）逐列相等，{@code last_record_date} 亦不变，且重算不新增、不修改、
 * 不删除任何 {@code growth_events} 行（事件条数与逐行快照均不变）。</p>
 *
 * <h2>为什么这是构造性成立、而本测试只是把它锁住</h2>
 * <p>{@code settle} 与 {@code recalculateOnly} <b>共用同一段</b>第 ⑥ 步重算代码
 * （{@code GrowthSettlementService.recalculateAndWriteBack}）：从库重读完整 {@code DAILY_RECORD}
 * 日历 → {@code GrowthCalendarService.scan} 纯函数 → 写回四个物化列；{@code exp} 一律取
 * {@code SUM(exp_amount)} 数据库聚合、{@code level = levelOf(exp)}。{@code settle} 只是在这条路径
 * <b>之前</b>多做了「读事实源 → 固定顺序组装事件 → 批量插入」；把这些步骤去掉，剩下的重算与写回
 * 逐字节相同。因此「增量维护结果 == 全量重算结果」不是靠两份实现凑巧对上，而是构造性成立。
 * 本测试的价值在于：一旦有人未来出于性能直觉把重算拆成「旧值 + 本次增量」这类真正的增量公式、
 * 让两条路径产生分歧，本属性立刻变红。</p>
 *
 * <h2>驱动方式（对齐 {@code GrowthSettlementTriggerPropertyTest} 的约定）</h2>
 * <p>{@code settle} / {@code recalculateOnly} 均带 {@code @Transactional(REQUIRES_NEW)}，只有让它们
 * <b>真正提交</b>才能在库里观察到终态并让「重算前 / 重算后」两次读取跨越各自独立的事务边界。因此
 * 本测试<b>不</b>用测试级事务包裹（那会让 Spring Test 在方法结束时回滚，把两次结算折叠进同一个从不
 * 提交的事务），而是让每次调用各自提交；相应地清理不能靠回滚，改由 {@link #resetState()} 在每次
 * 迭代前显式清库，并用全局自增序号 {@link #SEQ} 保证每次迭代的 {@code userId} / {@code ledgerId}
 * 全局唯一，双重隔离。</p>
 *
 * <p>注入一个 {@code @Primary} 的可推进 {@link MutableClock}（覆盖 {@code TimeConfig} 的系统时钟，
 * 固定 {@code Asia/Shanghai}），使「结算日 / 记账日 / 追补窗口」可确定性推进——含跨越 60 秒记账
 * 节流窗口、跨自然日与跨月。生成器刻意覆盖「追补窗口未覆盖到结算日」的存量大户场景
 * （历史记账日偏移可达 1500 天），大规模历史（约 1300 天）由 {@link #bigHistoryUser_recalcEqualsIncremental()}
 * 单独作示例测试跑一次。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 静态上下文缓存复用，多次迭代
 * 只加载一次上下文）。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Feature: growth-level-system, Property 5: 增量维护结果等于全量重算结果</p>
 *
 * <p>Validates: Requirements 1.7, 1.12, 4.13</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-recalc-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(GrowthRecalculationPropertyTest.ClockConfig.class)
class GrowthRecalculationPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：初始结算日 = 2025-06-15。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 同一个 H2 库跨迭代复用，用序号保证 userId / ledgerId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(700_000L);

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
        new TestContextManager(GrowthRecalculationPropertyTest.class).prepareTestInstance(this);
        tx = new TransactionTemplate(transactionManager);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠事务回滚：每次迭代前硬删三张表。两表均无外键，删除顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    // ---------------- 生成器 ----------------

    /**
     * 一次场景：一批历史记账日偏移（相对初始结算日，0–1500 天，覆盖「追补窗口未覆盖到结算日」）、
     * 一个结算次数（1–10）、以及每次结算之间时钟的推进秒数序列（混合 &lt;60s / &gt;60s / 跨日 / 跨月）。
     */
    static final class Scenario {
        final List<Integer> dayOffsets;
        final int settleCount;
        final List<Long> advanceSeconds;

        Scenario(List<Integer> dayOffsets, int settleCount, List<Long> advanceSeconds) {
            this.dayOffsets = dayOffsets;
            this.settleCount = settleCount;
            this.advanceSeconds = advanceSeconds;
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<Integer>> dayOffsets = Arbitraries.integers().between(0, 1500)
                .list().ofMinSize(1).ofMaxSize(40);
        Arbitrary<Integer> settleCount = Arbitraries.integers().between(1, 10);
        // 30s（窗口内、可能被节流）、120s（跨窗口）、1h、1 天、31 天（跨月）。
        Arbitrary<List<Long>> advances = Arbitraries.of(30L, 120L, 3600L, 86_400L, 86_400L * 31)
                .list().ofMinSize(0).ofMaxSize(9);
        return Combinators.combine(dayOffsets, settleCount, advances).as(Scenario::new);
    }

    // ---------------- Property 5 ----------------

    /**
     * 先任意次 {@code settle}、再一次 {@code recalculateOnly}：五个物化列 + {@code last_record_date}
     * 逐列相等，且事件条数与逐行快照不变（需求 1.7、1.12、4.13）。
     *
     * <p>Validates: Requirements 1.7, 1.12, 4.13</p>
     */
    @Property(tries = 60)
    void recalculateOnly_leavesMaterializedColumnsAndEventsIdentical(@ForAll("scenarios") Scenario scenario) {
        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();
        LocalDate settleDate = LocalDate.now(CLOCK);

        // 预置历史记账事实源（去重前后不影响：同一天多笔只贡献一个 DAILY_RECORD）。
        seedRecords(userId, ledgerId, settleDate, scenario.dayOffsets);

        // ① 任意次结算（增量维护路径）；每次之间推进时钟（含跨窗口 / 跨日 / 跨月）。
        for (int i = 0; i < scenario.settleCount; i++) {
            settlementService.settle(userId, TriggerSource.RECORD);
            if (i < scenario.settleCount - 1) {
                long secs = scenario.advanceSeconds.isEmpty()
                        ? 120L
                        : scenario.advanceSeconds.get(i % scenario.advanceSeconds.size());
                CLOCK.advance(Duration.ofSeconds(secs));
            }
        }

        // 重算前快照：五列 + last_record_date + 事件逐行。
        UserGrowth before = userGrowthRepository.findById(userId).orElseThrow();
        long expBefore = before.getExp();
        int levelBefore = before.getLevel();
        int totalBefore = before.getTotalRecordDays();
        int currentBefore = before.getCurrentStreakDays();
        int maxBefore = before.getMaxStreakDays();
        LocalDate lastDateBefore = before.getLastRecordDate();
        long eventCountBefore = growthEventRepository.countByUserId(userId);
        List<Map<String, Object>> eventsBefore = eventSnapshot(userId);

        // ② 一次全量重算（纯重算路径）。
        settlementService.recalculateOnly(userId);

        // 重算后：五列逐列相等、last_record_date 不变。
        UserGrowth after = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(after.getExp()).isEqualTo(expBefore);
        assertThat(after.getLevel()).isEqualTo(levelBefore);
        assertThat(after.getTotalRecordDays()).isEqualTo(totalBefore);
        assertThat(after.getCurrentStreakDays()).isEqualTo(currentBefore);
        assertThat(after.getMaxStreakDays()).isEqualTo(maxBefore);
        assertThat(after.getLastRecordDate()).isEqualTo(lastDateBefore);

        // 重算不新增 / 不修改 / 不删除任何 growth_events 行。
        assertThat(growthEventRepository.countByUserId(userId)).isEqualTo(eventCountBefore);
        assertThat(eventSnapshot(userId)).isEqualTo(eventsBefore);
    }

    /**
     * 存量大户示例：预置约 1300 个连续历史记账日（追补窗口单次只覆盖 1000 天，需多次结算收敛），
     * 多次结算后再一次全量重算，五列 + {@code last_record_date} 与事件快照仍逐列 / 逐行相等。
     *
     * <p>规模较大单独作示例跑一次，避免拖慢 {@code @Property} 的每轮迭代。</p>
     *
     * <p>Validates: Requirements 1.7, 1.12, 4.13</p>
     */
    @Example
    void bigHistoryUser_recalcEqualsIncremental() {
        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();
        LocalDate settleDate = LocalDate.now(CLOCK);

        // 1300 个连续历史记账日：偏移 1..1300（含追补窗口 1000 天覆盖不到最早段的场景）。
        List<Integer> offsets = new ArrayList<>();
        for (int d = 1; d <= 1300; d++) {
            offsets.add(d);
        }
        seedRecordsInOneTransaction(userId, ledgerId, settleDate, offsets);

        // 多次结算逐步追补（每次跨过节流窗口且不越自然日：120s × 5 仍在同一天内）。
        for (int i = 0; i < 5; i++) {
            settlementService.settle(userId, TriggerSource.RECORD);
            CLOCK.advance(Duration.ofSeconds(120));
        }

        UserGrowth before = userGrowthRepository.findById(userId).orElseThrow();
        long expBefore = before.getExp();
        int levelBefore = before.getLevel();
        int totalBefore = before.getTotalRecordDays();
        int currentBefore = before.getCurrentStreakDays();
        int maxBefore = before.getMaxStreakDays();
        LocalDate lastDateBefore = before.getLastRecordDate();
        long eventCountBefore = growthEventRepository.countByUserId(userId);
        List<Map<String, Object>> eventsBefore = eventSnapshot(userId);

        settlementService.recalculateOnly(userId);

        UserGrowth after = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(after.getExp()).isEqualTo(expBefore);
        assertThat(after.getLevel()).isEqualTo(levelBefore);
        assertThat(after.getTotalRecordDays()).isEqualTo(totalBefore);
        assertThat(after.getCurrentStreakDays()).isEqualTo(currentBefore);
        assertThat(after.getMaxStreakDays()).isEqualTo(maxBefore);
        assertThat(after.getLastRecordDate()).isEqualTo(lastDateBefore);
        assertThat(growthEventRepository.countByUserId(userId)).isEqualTo(eventCountBefore);
        assertThat(eventSnapshot(userId)).isEqualTo(eventsBefore);
    }

    // ---------------- 事实源播种与快照 ----------------

    /** 逐笔提交若干「有效记账交易」，记账日 = 初始结算日 − 偏移天。 */
    private void seedRecords(long userId, long ledgerId, LocalDate settleDate, List<Integer> dayOffsets) {
        for (int offset : dayOffsets) {
            LocalDate date = settleDate.minusDays(offset);
            TransactionType type = (offset % 2 == 0) ? TransactionType.EXPENSE : TransactionType.INCOME;
            transactionRepository.save(newValidRecord(userId, ledgerId, date.atTime(9, 0),
                    new BigDecimal("12.34"), type));
        }
    }

    /** 大规模播种：在单个事务内批量保存，避免逐笔独立事务的开销。 */
    private void seedRecordsInOneTransaction(long userId, long ledgerId, LocalDate settleDate, List<Integer> offsets) {
        tx.executeWithoutResult(status -> {
            for (int offset : offsets) {
                LocalDate date = settleDate.minusDays(offset);
                TransactionType type = (offset % 2 == 0) ? TransactionType.EXPENSE : TransactionType.INCOME;
                transactionRepository.save(newValidRecord(userId, ledgerId, date.atTime(9, 0),
                        new BigDecimal("12.34"), type));
            }
        });
    }

    /**
     * 构造一笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type ∈ {expense,income}}、{@code ledger_id} 非 NULL）。记账日由 {@code created_at} 决定。
     */
    private Transaction newValidRecord(long userId, long ledgerId, LocalDateTime createdAt,
                                       BigDecimal amount, TransactionType type) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setLedgerId(ledgerId);
        t.setCreatedBy(userId);
        t.setType(type);
        t.setAmount(amount);
        t.setAccountId(ledgerId);
        t.setCategoryId(ledgerId);
        t.setOccurredAt(createdAt);
        t.setCreatedAt(createdAt);
        t.setUpdatedAt(createdAt);
        return t;
    }

    /** 按 id 升序取该用户全部事件的稳定投影（type / key / exp），用于「重算不改动任何事件行」的逐行断言。 */
    private List<Map<String, Object>> eventSnapshot(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT id, event_type, event_key, exp_amount FROM growth_events "
                        + "WHERE user_id = ? ORDER BY id ASC",
                userId);
    }

    /**
     * 提供一个 {@code @Primary} 的可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日可确定性推进。
     */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    // ---- 可推进的时钟（可 reset，供每次迭代前归位）----

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
