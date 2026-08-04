package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Budget;
import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.InviteRelation;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 成长结算的核心账目不变式的属性测试（<b>Property 2：经验值等于事件之和，等级与经验一致</b>）。
 *
 * <p>本测试锁住需求 1 与需求 2 交汇处的三条构造性等式，它们必须在<b>任意操作序列</b>后的结算终态上成立：</p>
 * <ul>
 *   <li><b>经验 = 事件之和</b>（需求 1.2）：{@code user_growth.exp} 恒等于该用户全部
 *       {@code growth_events.exp_amount} 之和。测试用<b>独立</b>的求和（读回全部事件行、在测试内累加）
 *       与档案物化的 {@code exp} 比对，而非复用被测的 {@code SUM} 聚合查询。</li>
 *   <li><b>等级 = 经验换算</b>（需求 2.8/2.9/2.10、1.1）：{@code level == levelOf(exp)}，且概览响应里
 *       等级换算的六个派生字段自洽——{@code currentLevelExp == threshold(level)}、未满级时
 *       {@code nextLevelExp == threshold(level+1)}、{@code expInCurrentLevel == exp - currentLevelExp ≥ 0}、
 *       {@code expToNextLevel == nextLevelExp - exp ≥ 1}，满级时后两者为 {@code null}。</li>
 *   <li><b>经验只增不减的地基</b>（需求 1.1、1.3）：{@code exp ≥ 0}、{@code level ∈ [1, 100]}、
 *       每条事件的 {@code exp_amount ≥ 0}。</li>
 * </ul>
 *
 * <h2>操作序列</h2>
 * <p>生成器产出一串操作码，覆盖<b>记账、软删除、回收站恢复、预算达成、首次邀请、结算</b>六类
 * （删账/恢复只改累计事实源、不改经验，用以证明经验对这类变更的免疫；预算/邀请/记账则驱动经验增长）。
 * 每类操作直接写事实源表（{@code transactions} / {@code budgets} / {@code ledgers} /
 * {@code invite_relations}），再由真实 {@link GrowthSettlementService#settle} 结算——这样一次迭代内能压出
 * 多种经验组合与多次结算的叠加，而无需经过完整的记账业务栈。</p>
 *
 * <h2>可推进时钟</h2>
 * <p>注入一个进程共享的可推进 {@link MutableClock}（{@code @Primary} 覆盖 {@code TimeConfig} 的系统时钟），
 * 固定在 {@code Asia/Shanghai} 的 {@code 2025-06-15 08:00}。每次结算前把时钟推进到 60 秒记账节流窗口之外，
 * 使每次 {@code settle} 都<b>真实执行</b>而非被节流跳过；同时所有操作落在同一自然日内，结算日确定为
 * {@code 2025-06-15}，预算回看月固定为 {@code 2025-05/04/03}，全程可确定性断言。</p>
 *
 * <h2>测试层级与清理</h2>
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有真实提交才能在库里观察到结算终态，故走全栈
 * {@code @SpringBootTest} + H2（{@code MODE=MySQL}，独立命名内存库），且清理<b>不能靠事务回滚</b>：
 * {@link #resetState()} 在每次迭代前显式清六张表并归位时钟，并用全局自增序号 {@link #SEQ} 保证每次迭代的
 * {@code userId} / {@code inviteeId} 全局唯一。jqwik 属性方法不经 {@code SpringExtension}，依赖注入由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文缓存复用，多次迭代只加载一次）。</p>
 *
 * <p>Feature: growth-level-system, Property 2: 经验值等于事件之和，等级与经验一致</p>
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.3, 2.8, 2.9, 2.10</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-expconsistency-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class GrowthExpLevelConsistencyPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：结算日 = 2025-06-15，预算回看月 = 2025-05/04/03。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 每次结算前推进量（跨过 60 秒记账节流窗口，保证 settle 真实执行）。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);
    /** 非结算操作之间的小步推进（仍落在同一自然日内）。 */
    private static final Duration SMALL_STEP = Duration.ofSeconds(1);

    /** 跨迭代复用同一内存库，用序号保证 userId / inviteeId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(5_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private GrowthQueryService queryService;
    @Autowired
    private GrowthLevelCurve levelCurve;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private GrowthEventRepository growthEventRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private BudgetRepository budgetRepository;
    @Autowired
    private InviteRelationRepository inviteRelationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthExpLevelConsistencyPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删事实源与成长两表。均无外键，删除顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM budgets");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM invite_relations");
    }

    // ---------------- 操作码 ----------------

    private static final int OP_RECORD = 0;
    private static final int OP_DELETE = 1;
    private static final int OP_RESTORE = 2;
    private static final int OP_BUDGET = 3;
    private static final int OP_INVITE = 4;
    private static final int OP_SETTLE = 5;

    /** 操作序列：每个元素是操作码（0–5），长度 1–20，含大量重复以叠加多次结算与多种经验组合。 */
    @Provide
    Arbitrary<List<Integer>> operationSequences() {
        return Arbitraries.integers().between(OP_RECORD, OP_SETTLE).list().ofMinSize(1).ofMaxSize(20);
    }

    // ---------------- Property 2 ----------------

    /**
     * Feature: growth-level-system, Property 2: 经验值等于事件之和，等级与经验一致
     *
     * <p>把生成的操作序列逐个应用到事实源，其间穿插真实结算；序列执行完后再做一次<b>保证执行</b>的结算，
     * 然后断言结算终态满足三条等式（见类级 Javadoc）。序列内每次结算之后也就地校验「经验 = 事件之和」与
     * 「等级 = 经验换算」，使违背在最早的结算处即暴露。</p>
     *
     * <p>Validates: Requirements 1.1, 1.2, 1.3, 2.8, 2.9, 2.10</p>
     */
    @Property(tries = 50)
    void property2_expEqualsEventSumAndLevelIsConsistent(
            @ForAll("operationSequences") List<Integer> ops) {

        long userId = SEQ.getAndIncrement();
        long ledgerId = createOwnedLedger(userId);

        List<Long> activeTxIds = new ArrayList<>();
        List<Long> deletedTxIds = new ArrayList<>();
        Set<String> seededBudgetMonths = new HashSet<>();
        int budgetOpCount = 0;
        boolean inviteCreated = false;

        for (int op : ops) {
            switch (op) {
                case OP_RECORD -> {
                    LocalDateTime now = LocalDateTime.now(CLOCK);
                    long txId = insertValidRecord(userId, ledgerId, now, now,
                            new BigDecimal("12.50"), TransactionType.EXPENSE);
                    activeTxIds.add(txId);
                    CLOCK.advance(SMALL_STEP);
                }
                case OP_DELETE -> {
                    if (!activeTxIds.isEmpty()) {
                        long txId = activeTxIds.remove(activeTxIds.size() - 1);
                        softDelete(txId, LocalDateTime.now(CLOCK));
                        deletedTxIds.add(txId);
                    }
                    CLOCK.advance(SMALL_STEP);
                }
                case OP_RESTORE -> {
                    if (!deletedTxIds.isEmpty()) {
                        long txId = deletedTxIds.remove(deletedTxIds.size() - 1);
                        restore(txId);
                        activeTxIds.add(txId);
                    }
                    CLOCK.advance(SMALL_STEP);
                }
                case OP_BUDGET -> {
                    int monthsBack = (budgetOpCount % GrowthBudgetEvaluator.LOOKBACK_MONTHS) + 1;
                    budgetOpCount++;
                    seedBudgetMetMonth(userId, ledgerId, monthsBack, seededBudgetMonths, activeTxIds);
                    CLOCK.advance(SMALL_STEP);
                }
                case OP_INVITE -> {
                    if (!inviteCreated) {
                        seedRegisteredInvite(userId, LocalDateTime.now(CLOCK));
                        inviteCreated = true;
                    }
                    CLOCK.advance(SMALL_STEP);
                }
                case OP_SETTLE -> {
                    CLOCK.advance(BEYOND_THROTTLE);
                    settlementService.settle(userId, TriggerSource.RECORD);
                    assertExpEqualsEventSum(userId);
                }
                default -> throw new IllegalStateException("未知操作码: " + op);
            }
        }

        // 保证一次结算写入终态（即便序列里没有 SETTLE），使断言总有档案可读。
        CLOCK.advance(BEYOND_THROTTLE);
        settlementService.settle(userId, TriggerSource.RECORD);

        // ── 终态账目断言 ──────────────────────────────────────────────────────────
        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();
        long dbExp = profile.getExp();
        int dbLevel = profile.getLevel();

        // 需求 1.3：每条事件 exp_amount ≥ 0；并独立求和（不复用被测的 SUM 聚合）。
        long independentSum = 0L;
        for (GrowthEvent e : allEvents(userId)) {
            assertThat(e.getExpAmount())
                    .as("事件 %s 的 exp_amount 应 ≥ 0", e.getEventKey())
                    .isGreaterThanOrEqualTo(0);
            independentSum += e.getExpAmount();
        }

        // 需求 1.2：经验恒等于事件之和。
        assertThat(dbExp)
                .as("user_growth.exp 应等于全部 growth_events.exp_amount 之和")
                .isEqualTo(independentSum);
        // 需求 1.1：exp ≥ 0、level ∈ [1, 100]。
        assertThat(dbExp).as("exp ≥ 0").isGreaterThanOrEqualTo(0L);
        assertThat(dbLevel).as("level ∈ [1, 100]").isBetween(1, GrowthLevelCurve.MAX_LEVEL);
        // 需求 2.x：等级由经验换算而来。
        assertThat(dbLevel)
                .as("level 应等于 levelOf(exp)")
                .isEqualTo(levelCurve.levelOf(dbExp));

        // ── 概览响应的等级派生字段自洽（需求 2.8、2.9、2.10）─────────────────────────
        GrowthOverviewResponse overview = queryService.getOverview(userId);
        assertThat(overview.exp()).as("概览 exp 应与档案一致").isEqualTo(dbExp);
        assertThat(overview.level()).as("概览 level 应与档案一致").isEqualTo(dbLevel);
        assertThat(overview.maxLevel()).isEqualTo(GrowthLevelCurve.MAX_LEVEL);
        assertThat(overview.maxLevelReached()).isEqualTo(dbLevel >= GrowthLevelCurve.MAX_LEVEL);

        // currentLevelExp == threshold(level)（需求 2.8）。
        assertThat(overview.currentLevelExp())
                .as("currentLevelExp 应等于 threshold(level)")
                .isEqualTo(levelCurve.threshold(dbLevel));
        // expInCurrentLevel == exp - currentLevelExp ≥ 0（需求 2.8、2.10）。
        assertThat(overview.expInCurrentLevel())
                .as("expInCurrentLevel 应等于 exp - currentLevelExp")
                .isEqualTo(dbExp - overview.currentLevelExp());
        assertThat(overview.expInCurrentLevel()).as("本级已获得经验 ≥ 0").isGreaterThanOrEqualTo(0L);

        if (overview.maxLevelReached()) {
            // 满级：下一级两项为空（需求 2.9）。
            assertThat(overview.nextLevelExp()).as("满级时 nextLevelExp 为 null").isNull();
            assertThat(overview.expToNextLevel()).as("满级时 expToNextLevel 为 null").isNull();
        } else {
            // 未满级：nextLevelExp == threshold(level+1)（需求 2.8）。
            assertThat(overview.nextLevelExp())
                    .as("nextLevelExp 应等于 threshold(level+1)")
                    .isEqualTo(levelCurve.threshold(dbLevel + 1));
            // expToNextLevel == nextLevelExp - exp，且 ≥ 1（需求 2.10）。
            assertThat(overview.expToNextLevel())
                    .as("expToNextLevel 应等于 nextLevelExp - exp")
                    .isEqualTo(overview.nextLevelExp() - dbExp);
            assertThat(overview.expToNextLevel()).as("未满级时升级还需经验 ≥ 1").isGreaterThanOrEqualTo(1L);
        }
    }

    /** 序列内每次结算后就地校验「经验 = 事件之和」「等级 = 经验换算」，使违背在最早处暴露。 */
    private void assertExpEqualsEventSum(long userId) {
        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();
        long independentSum = 0L;
        for (GrowthEvent e : allEvents(userId)) {
            independentSum += e.getExpAmount();
        }
        assertThat(profile.getExp())
                .as("每次结算后 exp 应等于事件之和")
                .isEqualTo(independentSum);
        assertThat(profile.getLevel())
                .as("每次结算后 level 应等于 levelOf(exp)")
                .isEqualTo(levelCurve.levelOf(profile.getExp()));
    }

    // ---------------- 事实源播种 ----------------

    /** 建一个该用户自有的账本（预算达成判定需要 {@code ledgers.user_id} 匹配），返回其 id。 */
    private long createOwnedLedger(long userId) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        Ledger ledger = new Ledger();
        ledger.setUserId(userId);
        ledger.setName("成长测试账本");
        ledger.setType(Ledger.TYPE_PERSONAL);
        ledger.setSortOrder(0);
        ledger.setDefault(true);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        return ledgerRepository.save(ledger).getId();
    }

    /**
     * 提交一笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type ∈ {expense,income}}、{@code ledger_id} 非 NULL），返回其 id。
     */
    private long insertValidRecord(long userId, long ledgerId, LocalDateTime createdAt,
                                   LocalDateTime occurredAt, BigDecimal amount, TransactionType type) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setLedgerId(ledgerId);
        tx.setCreatedBy(userId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setAccountId(ledgerId);
        tx.setCategoryId(ledgerId);
        tx.setOccurredAt(occurredAt);
        tx.setCreatedAt(createdAt);
        tx.setUpdatedAt(createdAt);
        return transactionRepository.save(tx).getId();
    }

    /**
     * 软删除一笔交易（移入回收站）。走 {@link JdbcTemplate} 直接置 {@code deleted_at}：实体带
     * {@code @SQLRestriction("deleted_at is null")}，经仓储读写会把软删行隐藏，无法直接操作。
     */
    private void softDelete(long txId, LocalDateTime now) {
        jdbcTemplate.update("UPDATE transactions SET deleted_at = ? WHERE id = ?", now, txId);
    }

    /** 从回收站恢复一笔交易（清空 {@code deleted_at}）。 */
    private void restore(long txId) {
        jdbcTemplate.update("UPDATE transactions SET deleted_at = NULL WHERE id = ?", txId);
    }

    /**
     * 让某个回看月（{@code monthsBack} ∈ [1,3]）达成预算：为自有账本建该月总预算行（若尚未建），
     * 并在该月内（按 {@code occurred_at}）放一笔小额支出，使月度支出合计 &gt; 0 且 ≤ 预算。
     */
    private void seedBudgetMetMonth(long userId, long ledgerId, int monthsBack,
                                    Set<String> seededBudgetMonths, List<Long> activeTxIds) {
        YearMonth month = YearMonth.from(LocalDate.now(CLOCK)).minusMonths(monthsBack);
        String monthKey = month.toString();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        if (seededBudgetMonths.add(monthKey)) {
            Budget budget = new Budget();
            budget.setUserId(userId);
            budget.setLedgerId(ledgerId);
            budget.setMonth(monthKey);
            budget.setAmount(new BigDecimal("1000.00"));
            budget.setCreatedAt(now);
            budget.setUpdatedAt(now);
            budgetRepository.save(budget);
        }
        // 该月内的一笔支出：occurred_at 落在该月（预算按 occurred_at 聚合），created_at 为今日
        // （故它对记账日历只贡献今日那条 DAILY_RECORD，不影响预算月判定）。
        LocalDateTime occurredInMonth = month.atDay(15).atStartOfDay();
        long txId = insertValidRecord(userId, ledgerId, now, occurredInMonth,
                new BigDecimal("10.00"), TransactionType.EXPENSE);
        activeTxIds.add(txId);
    }

    /** 建一条该用户作为邀请人、状态 {@code REGISTERED} 的邀请关系（触发 {@code FIRST_INVITE}）。 */
    private void seedRegisteredInvite(long userId, LocalDateTime now) {
        InviteRelation relation = new InviteRelation();
        relation.setInviterId(userId);
        relation.setInviteeId(SEQ.getAndIncrement());
        relation.setRegisterTime(now);
        relation.setStatus(InviteStatus.REGISTERED);
        relation.setCreatedAt(now);
        relation.setUpdatedAt(now);
        inviteRelationRepository.save(relation);
    }

    private List<GrowthEvent> allEvents(long userId) {
        return growthEventRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, 5000)).getContent();
    }

    /** 提供一个 {@code @Primary} 的可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日可确定性断言。 */
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
