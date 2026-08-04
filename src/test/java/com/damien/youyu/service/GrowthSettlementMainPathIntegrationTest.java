package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;

/**
 * 结算主路径集成测试（任务 4.8，需求 1.7、1.10、3.2、3.6、4.4、4.14、9.1）。
 *
 * <p>全栈 {@code @SpringBootTest}：注入真实的 {@link GrowthSettlementService} 与其全部协作者
 * （日历、预算、徽章、等级曲线、节流器、{@code JdbcTemplate}），对真实 H2（{@code MODE=MySQL}）
 * 读写。<b>不用测试级事务包裹</b>——{@link GrowthSettlementService#settle} 带
 * {@code @Transactional(REQUIRES_NEW)}，只有让它真正提交才能在库里观察到结算终态；因此本类每个
 * 用例都是「先提交交易事实源 → 直接调用 {@code settle} → 从库读回断言」，并用 {@link #cleanup()}
 * 在每次用例前硬删三张表（不能靠回滚清理）。</p>
 *
 * <p>时钟用一个进程共享的可推进 {@link MutableClock}（{@code @Primary} 覆盖 {@code TimeConfig} 的
 * 系统时钟），固定在 {@code Asia/Shanghai} 的 {@code 2025-06-15 08:00}。这样「结算日 / 记账日 /
 * 追补窗口」全部可确定性断言，推进时钟即可跨越 60 秒记账节流窗口而不换自然日。</p>
 *
 * <p>覆盖四条主路径：</p>
 * <ol>
 *   <li><b>首次结算</b>：{@code FIRST_RECORD} + {@code DAILY_RECORD:<今日>} + 相应 {@code BADGE}
 *       全部出现，{@code user_growth} 恰好一行且 {@code level == 2}（需求 3.2、9.1）。</li>
 *   <li><b>幂等</b>：两次结算之间无任何事实源变化时，事件行数与五个物化列取值完全相同（需求 1.7）。</li>
 *   <li><b>同一自然日多次记账</b>：2–100 次记账各触发一次结算 → 该日 {@code DAILY_RECORD} 恰好 1 条、
 *       该日经验合计 5（需求 4.4）。</li>
 *   <li><b>追补窗口末日 &lt; 结算日</b>：不写 {@code DAILY_RECORD:<结算日>}，{@code last_record_date}
 *       取窗口内最大已补发日，下一次结算的追补起点严格更晚（需求 4.14、3.6）。</li>
 * </ol>
 *
 * <p>使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-settle-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class GrowthSettlementMainPathIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：结算日 = 2025-06-15。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 记账侧 60 秒节流窗口之外的推进量（保证下一次 settle 真实执行而非被跳过）。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private GrowthCalendarService calendarService;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private GrowthEventRepository growthEventRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠事务回滚：每个用例前硬删三张表。两表均无外键，删除顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    /**
     * 首次结算：单笔有效记账 → {@code FIRST_RECORD}(10) + {@code DAILY_RECORD:<今日>}(5) 与
     * {@code BADGE:FIRST_RECORD} 全部出现，档案恰好一行、经验 15、等级 2（需求 3.2、9.1）。
     *
     * <p>15 经验落在 {@code threshold(2)=10} 与 {@code threshold(3)=24} 之间，故 {@code level==2}
     * ——这正是「第一笔记账当场升到 Lv2」的刻意设计（需求 2 范围约定）。</p>
     */
    @Test
    void firstSettlement_writesFirstRecordDailyAndBadge_levelTwo() {
        long userId = 10_001L;
        long ledgerId = 20_001L;
        LocalDate today = LocalDate.now(CLOCK);
        seedValidRecord(userId, ledgerId, LocalDateTime.now(CLOCK), new BigDecimal("12.34"), TransactionType.EXPENSE);

        SettleOutcome outcome = settlementService.settle(userId, TriggerSource.RECORD);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        assertThat(userGrowthRepository.count()).isEqualTo(1L);
        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(profile.getExp()).isEqualTo(15L);
        assertThat(profile.getLevel()).isEqualTo(2);
        assertThat(profile.getTotalRecordDays()).isEqualTo(1);
        assertThat(profile.getCurrentStreakDays()).isEqualTo(1);
        assertThat(profile.getMaxStreakDays()).isEqualTo(1);
        assertThat(profile.getLastRecordDate()).isEqualTo(today);

        List<String> keys = growthEventRepository.findEventKeysByUserId(userId);
        assertThat(keys).contains("FIRST_RECORD", "DAILY_RECORD:" + today, "BADGE:FIRST_RECORD");
    }

    /**
     * 幂等：连续两次结算之间无任何事实源变化时，事件行数与五个物化列取值完全相同（需求 1.7）。
     *
     * <p>两次结算之间把时钟推进到 60 秒节流窗口之外，使第二次结算<b>真实执行</b>（而非被节流跳过）
     * ——只有真实重跑一遍取锁、读事实源、组装、批量插入、全量重算，才能证明幂等确实成立，而不是
     * 靠节流掩盖。第二次结算靠 {@code ON DUPLICATE KEY UPDATE} 忽略重复事件、靠 {@code SUM} 重算
     * 得到同一经验，故行数与五列不变。</p>
     */
    @Test
    void idempotency_secondSettlementLeavesRowsAndColumnsIdentical() {
        long userId = 11_001L;
        long ledgerId = 21_001L;
        seedValidRecord(userId, ledgerId, LocalDateTime.now(CLOCK), new BigDecimal("50.00"), TransactionType.EXPENSE);

        settlementService.settle(userId, TriggerSource.RECORD);

        long eventCountAfterFirst = growthEventRepository.countByUserId(userId);
        UserGrowth first = userGrowthRepository.findById(userId).orElseThrow();
        long expFirst = first.getExp();
        int levelFirst = first.getLevel();
        int totalFirst = first.getTotalRecordDays();
        int currentFirst = first.getCurrentStreakDays();
        int maxFirst = first.getMaxStreakDays();

        // 推进到 60 秒节流窗口之外但仍是同一自然日，使第二次结算真实执行。
        CLOCK.advance(Duration.ofSeconds(120));
        SettleOutcome secondOutcome = settlementService.settle(userId, TriggerSource.RECORD);

        assertThat(secondOutcome).isEqualTo(SettleOutcome.SETTLED);
        assertThat(growthEventRepository.countByUserId(userId)).isEqualTo(eventCountAfterFirst);
        UserGrowth second = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(second.getExp()).isEqualTo(expFirst);
        assertThat(second.getLevel()).isEqualTo(levelFirst);
        assertThat(second.getTotalRecordDays()).isEqualTo(totalFirst);
        assertThat(second.getCurrentStreakDays()).isEqualTo(currentFirst);
        assertThat(second.getMaxStreakDays()).isEqualTo(maxFirst);
    }

    /**
     * 同一自然日内 2–100 次记账各触发一次结算 → 该日 {@code DAILY_RECORD} 恰好 1 条、该日经验合计 5
     * （需求 4.4）。
     *
     * <p>每次记账后把时钟推进 61 秒（跨过 60 秒节流窗口），使每一次 {@code settle} 都<b>真实执行</b>；
     * 100 次 × 61 秒 ≈ 1 小时 41 分，从 08:00 起仍落在同一自然日内，不越日。首笔结算写入当日
     * {@code DAILY_RECORD}，其后每次结算发现该键已存在、追补起点已越过今日，故不再新增——由
     * {@code (user_id, event_key)} 唯一键 + 已存在过滤共同保证「每个记账日一条」。</p>
     */
    @ParameterizedTest
    @ValueSource(ints = {2, 100})
    void sameDayMultipleRecords_yieldExactlyOneDailyRecordWorthFiveExp(int recordCount) {
        long userId = 12_000L + recordCount;
        long ledgerId = 22_000L + recordCount;
        LocalDate today = LocalDate.now(CLOCK);

        for (int i = 0; i < recordCount; i++) {
            seedValidRecord(userId, ledgerId, LocalDateTime.now(CLOCK),
                    new BigDecimal("9.99"), TransactionType.EXPENSE);
            settlementService.settle(userId, TriggerSource.RECORD);
            CLOCK.advance(BEYOND_THROTTLE);
        }

        String dailyKey = "DAILY_RECORD:" + today;
        List<GrowthEvent> dailyEvents = allEvents(userId).stream()
                .filter(e -> dailyKey.equals(e.getEventKey()))
                .toList();
        assertThat(dailyEvents).hasSize(1);
        assertThat(dailyEvents.get(0).getExpAmount()).isEqualTo(5);
        // 该日因 DAILY_RECORD 增加的经验合计恰为 5（需求 4.4）。
        int dailyExpSum = dailyEvents.stream().mapToInt(GrowthEvent::getExpAmount).sum();
        assertThat(dailyExpSum).isEqualTo(5);
    }

    /**
     * 追补窗口末日早于结算日：不写 {@code DAILY_RECORD:<结算日>}，{@code last_record_date} 取窗口内
     * 最大已补发日，下一次结算的追补起点严格更晚（需求 4.14、3.6）。
     *
     * <p>构造：最早记账日 = 结算日 − 1500 天，故追补起点 = 结算日 − 1500，窗口末日 =
     * {@code min(起点 + 999, 结算日)} = 结算日 − 501（严格早于结算日）。在窗口末日那天也放一笔交易使其
     * 成为窗口内最大记账日；另在结算日当天放一笔，用以验证它<b>不</b>被本次结算写入
     * （窗口末日 &lt; 结算日 → 不越过尚未补发的历史日，需求 4.14 的无空洞不变式）。</p>
     */
    @Test
    void backfillWindowEndBeforeSettleDate_skipsTodayAndAdvancesStartMonotonically() {
        long userId = 13_001L;
        long ledgerId = 23_001L;
        LocalDate settleDate = LocalDate.now(CLOCK);
        LocalDate earliestDate = settleDate.minusDays(1500);
        LocalDate windowEndDate = settleDate.minusDays(501); // earliestDate + 999

        seedValidRecord(userId, ledgerId, earliestDate.atTime(9, 0), new BigDecimal("10.00"), TransactionType.EXPENSE);
        seedValidRecord(userId, ledgerId, windowEndDate.atTime(9, 0), new BigDecimal("10.00"), TransactionType.EXPENSE);
        seedValidRecord(userId, ledgerId, settleDate.atTime(9, 0), new BigDecimal("10.00"), TransactionType.EXPENSE);

        // 首次结算前的追补起点（lastRecordDate 为空）。
        BackfillResult before = calendarService.backfillDates(userId, null, settleDate);
        assertThat(before.windowStart()).isEqualTo(earliestDate);
        assertThat(before.windowEnd()).isEqualTo(windowEndDate);
        assertThat(before.windowEnd()).isBefore(settleDate);

        settlementService.settle(userId, TriggerSource.RECORD);

        // 不写 DAILY_RECORD:<结算日>（需求 4.14）。
        List<String> keys = growthEventRepository.findEventKeysByUserId(userId);
        assertThat(keys).doesNotContain("DAILY_RECORD:" + settleDate);
        assertThat(keys).contains("DAILY_RECORD:" + earliestDate, "DAILY_RECORD:" + windowEndDate);

        // last_record_date 取窗口内最大已补发日。
        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(profile.getLastRecordDate()).isEqualTo(windowEndDate);

        // 下一次结算的追补起点严格更晚（以更新后的 last_record_date 为依据）。
        BackfillResult next = calendarService.backfillDates(userId, profile.getLastRecordDate(), settleDate);
        assertThat(next.windowStart()).isAfter(before.windowStart());
    }

    // ---- 事实源播种 ----

    /**
     * 提交一笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type ∈ {expense,income}}、{@code ledger_id} 非 NULL）。记账日由 {@code created_at} 决定，
     * 故显式设定 {@code createdAt}；{@code occurredAt} 与之取同值（本任务不关心预算按月聚合口径）。
     */
    private void seedValidRecord(long userId, long ledgerId, LocalDateTime createdAt,
                                 BigDecimal amount, TransactionType type) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setLedgerId(ledgerId);
        tx.setCreatedBy(userId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setAccountId(ledgerId); // 收支需账户 id 非空；具体值与本任务断言无关。
        tx.setCategoryId(ledgerId);
        tx.setOccurredAt(createdAt);
        tx.setCreatedAt(createdAt);
        tx.setUpdatedAt(createdAt);
        transactionRepository.save(tx);
    }

    private List<GrowthEvent> allEvents(long userId) {
        return growthEventRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, 2000)).getContent();
    }

    /**
     * 提供一个 {@code @Primary} 的可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日可确定性断言。
     */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    // ---- 可推进的时钟（可 reset，供每个用例前归位）----

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
