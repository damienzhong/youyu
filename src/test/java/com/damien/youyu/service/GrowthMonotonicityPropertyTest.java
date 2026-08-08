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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Budget;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.Ledger;
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
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 成长体系的<b>单调不减</b>回归锁（<b>Property 4：经验与等级单调不减（删账不降级）</b>）。
 *
 * <p>本属性锁住成长体系最核心的一条承诺：经验是一本<b>只往前记的账</b>。{@code growth_events} 是
 * 只追加表（结算从不对已插入的事件行执行 {@code DELETE} 或 {@code UPDATE}，见
 * {@link GrowthSettlementService} 的 {@code INSERT ... ON DUPLICATE KEY UPDATE id = id}），
 * 而档案 {@code exp} 恒取 {@code SUM(exp_amount)} 的数据库聚合、{@code level = levelOf(exp)}
 * （曲线单调）。这两点合起来使「删除交易、清空回收站、修改交易、下调或删除预算、被邀请人注销」
 * 这类<b>回撤型</b>操作只会缩减事实源，却<b>动不了已经记下的经验事件</b>——因此 {@code exp} 与
 * {@code level} 在任意操作序列上单调不减，已点亮的徽章保持点亮。累计笔数与金额则如实反映删除（可以
 * 回落），这正是「删掉的账不再算在累计里，但已经赚到的经验不被抹掉」的双面性。</p>
 *
 * <h2>驱动方式：全栈 {@code @SpringBootTest} + 真实提交，不用测试级事务</h2>
 * <p>{@link GrowthSettlementService#settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有让它真正
 * <b>提交</b>才能在库里观察到结算终态。因此本测试不加测试级 {@code @Transactional}（那会在方法结束
 * 时回滚，掩盖真实写入），而是直接调用 {@code settle} 并从库读回断言；清理不能靠回滚，故
 * {@link #resetState()} 每次迭代前显式清库、并用全局自增序号 {@link #SEQ} 保证 {@code userId} /
 * {@code ledgerId} / {@code inviteeId} 每次迭代唯一（双重隔离）。时钟用一个 {@code @Primary} 的可推进
 * {@link MutableClock}（覆盖 {@code TimeConfig} 的系统时钟），固定在 {@code Asia/Shanghai} 的
 * {@code 2025-06-15 08:00}，使结算日 / 记账日 / 追补窗口全部可确定性断言。</p>
 *
 * <p>jqwik 属性方法不经 JUnit Jupiter 引擎、{@code SpringExtension} 不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 手工完成（Spring 静态上下文缓存复用，多次迭代只加载
 * 一次上下文）。用独立命名的内存库避免污染其它共享库的切片测试。</p>
 *
 * <h2>测试形状：先攒后撤</h2>
 * <ol>
 *   <li><b>攒</b>：用一段连续记账（{@code recordDays} 天，驱动 {@code FIRST_RECORD} / 若干
 *       {@code DAILY_RECORD} / 达门槛的 {@code STREAK} 与相应 {@code BADGE}）、可选的预算达成
 *       （{@code BUDGET_MET}）、可选的若干邀请（{@code FIRST_INVITE}）把用户推到某等级与若干徽章，
 *       结算一次得到<b>基线</b>。</li>
 *   <li><b>撤</b>：生成 1–12 次回撤操作（软删全部交易 / 清空回收站 / 硬删全部交易 / 预算下调到 0.01 /
 *       删除预算 / 全部邀请关系置 {@code INVALID}），每次回撤后推进时钟越过 60 秒记账节流窗口再结算一次。</li>
 * </ol>
 *
 * <h2>不变式（每次回撤结算后逐条断言）</h2>
 * <ul>
 *   <li>{@code exp} 与 {@code level} 单调不减：{@code expAfter >= expBefore ∧ levelAfter >= levelBefore}；
 *       且因回撤只减不增事实源、应发经验早已在基线写入，二者恒<b>等于基线</b>（需求 1.4、5.8、6.3、7.7）。</li>
 *   <li>成长事件<b>逐行快照</b>与基线完全相同：行数、以及每行的 {@code id / event_type / event_key /
 *       exp_amount / created_at} 全部不变（{@code growth_events} 只追加、结算不删不改，需求 1.4、8.4）。</li>
 *   <li>已点亮徽章保持点亮：基线的 {@code BADGE} 事件键集合 ⊆ 回撤后的集合（实为相等，需求 8.4、8.12）。</li>
 *   <li>累计有效记账笔数<b>可以回落</b>但绝不超过基线：{@code validRecordsAfter <= validRecordsBaseline}
 *       ——删除如实反映在累计口径里，与经验只增不减形成对照（需求 7.6、7.7）。</li>
 * </ul>
 *
 * <h2>破坏性改动的失败点（回归锁的意义）</h2>
 * <p>把批量插入改成会删事件的语句、把 {@code exp} 改成「旧值 + 本次新增」的内存累加而漏掉重算、或让
 * 结算在事实源缩减时回写更小的 {@code exp}/{@code level}——任一改动都会让「逐行快照相等」或「单调不减」
 * 断言变红。</p>
 *
 * <p>Feature: growth-level-system, Property 4: 经验与等级单调不减（删账不降级）</p>
 *
 * <p>Validates: Requirements 1.4, 5.8, 6.3, 7.6, 7.7, 8.4, 8.12</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-prop4-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class GrowthMonotonicityPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：结算日 = 2025-06-15。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 记账侧 60 秒节流窗口之外的推进量（保证每次回撤后的 settle 真实执行而非被跳过）。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);

    /** 结算日所属月的前 1 个自然月，用于构造 BUDGET_MET 场景（2025-06 → 2025-05）。 */
    private static final String PREV_MONTH = "2025-05";
    private static final LocalDate PREV_MONTH_DAY = LocalDate.of(2025, 5, 10);

    /** 全局自增序号：保证每次迭代 userId / ledgerId / inviteeId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(3_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
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
        new TestContextManager(GrowthMonotonicityPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删相关表（成长两表无外键，删除顺序无约束）。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM budgets");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM invite_relations");
    }

    // ---------------- 生成器 ----------------

    /** 回撤操作类型：每一种都只缩减事实源，不新增任何应发经验。 */
    enum RollbackOp {
        /** 软删全部交易（移入回收站）：deleted_at 置为非空。 */
        SOFT_DELETE_ALL_TX,
        /** 清空回收站：硬删全部已软删的交易。 */
        PURGE_RECYCLE_BIN,
        /** 硬删全部交易（等价于把每个记账日的全部交易删净）。 */
        HARD_DELETE_ALL_TX,
        /** 预算下调到 0.01（使原本达成的月份不再达成）。 */
        LOWER_BUDGET_TO_MIN,
        /** 删除预算行。 */
        DELETE_BUDGET,
        /** 全部邀请关系置 INVALID（被邀请人注销的效果）。 */
        INVALIDATE_INVITES
    }

    /** 回撤操作序列：长度 1–12，各类型随机（含重复，考验幂等）。 */
    @Provide
    Arbitrary<List<RollbackOp>> rollbackSequences() {
        return Arbitraries.of(RollbackOp.class).list().ofMinSize(1).ofMaxSize(12);
    }

    // ---------------- Property 4 ----------------

    /**
     * 先用记账 / 预算 / 邀请把用户攒到某等级与若干徽章（基线），再施加任意回撤序列，每次回撤结算后：
     * {@code exp} 与 {@code level} 单调不减且恒等于基线、成长事件逐行快照与基线相同、已点亮徽章保持点亮、
     * 累计有效记账笔数可回落但不超过基线（需求 1.4、5.8、6.3、7.6、7.7、8.4、8.12）。
     */
    @Property(tries = 12)
    void property4_expAndLevelNeverDecreaseWhenFactsAreRetracted(
            @ForAll @IntRange(min = 1, max = 35) int recordDays,
            @ForAll boolean withBudget,
            @ForAll @IntRange(min = 0, max = 3) int inviteeCount,
            @ForAll("rollbackSequences") List<RollbackOp> ops) {

        long userId = SEQ.getAndIncrement();
        long ledgerId = createOwnedLedger(userId);
        LocalDate settleDate = LocalDate.now(CLOCK);

        // ── 攒：连续 recordDays 天的有效记账（以 settleDate 为终点向前铺）──────────────────
        for (int i = 0; i < recordDays; i++) {
            LocalDate day = settleDate.minusDays(recordDays - 1L - i);
            insertValidExpense(userId, ledgerId, day.atTime(9, 0), new BigDecimal("12.34"));
        }
        // ── 攒：可选的预算达成（前一个自然月）───────────────────────────────────────────
        if (withBudget) {
            insertBudget(userId, ledgerId, PREV_MONTH, new BigDecimal("1000.00"));
            // 该月一笔支出（spent=100 ≤ 预算 1000 且 >0 → 达成）；created_at 也落在该月，顺带成为一个 DAILY_RECORD。
            insertValidExpense(userId, ledgerId, PREV_MONTH_DAY.atTime(9, 0), new BigDecimal("100.00"));
        }
        // ── 攒：可选的若干邀请（inviter_id = 本用户，status = REGISTERED）─────────────────
        for (int i = 0; i < inviteeCount; i++) {
            insertInviteRelation(userId, SEQ.getAndIncrement(), InviteStatus.REGISTERED);
        }

        // 基线结算（越过节流：初次无档案，不会被节流）。
        settlementService.settle(userId, TriggerSource.RECORD);

        UserGrowth baselineProfile = userGrowthRepository.findById(userId).orElseThrow();
        long baselineExp = baselineProfile.getExp();
        int baselineLevel = baselineProfile.getLevel();
        List<Map<String, Object>> baselineEvents = eventSnapshot(userId);
        Set<String> baselineBadges = badgeKeys(baselineEvents);
        long baselineValidRecords = transactionRepository.countValidRecordsByCreatedBy(userId);

        // 攒起来的基线本身应满足承诺（否则后续「等于基线」的断言失去意义）。
        assertThat(baselineExp).isGreaterThanOrEqualTo(0L);
        assertThat(baselineLevel).isBetween(1, 100);
        assertThat(baselineEvents).isNotEmpty();

        long prevExp = baselineExp;
        int prevLevel = baselineLevel;

        // ── 撤：逐个施加回撤操作，每次回撤后结算并断言单调不减 ─────────────────────────────
        for (RollbackOp op : ops) {
            applyRollback(op, userId);

            // 越过 60 秒记账节流窗口（仍是同一自然日，settleDate 不变），使这次 settle 真实执行。
            CLOCK.advance(BEYOND_THROTTLE);
            settlementService.settle(userId, TriggerSource.RECORD);

            UserGrowth after = userGrowthRepository.findById(userId).orElseThrow();

            // 经验与等级单调不减，且恒等于基线（回撤只减事实源、应发经验早已写入）。
            assertThat(after.getExp())
                    .as("回撤 %s 后经验不得回落", op)
                    .isGreaterThanOrEqualTo(prevExp)
                    .isEqualTo(baselineExp);
            assertThat(after.getLevel())
                    .as("回撤 %s 后等级不得回落", op)
                    .isGreaterThanOrEqualTo(prevLevel)
                    .isEqualTo(baselineLevel);

            // 成长事件逐行快照与基线完全相同（只追加表，结算不删不改）。
            assertThat(eventSnapshot(userId))
                    .as("回撤 %s 后成长事件行数与全部列取值必须不变", op)
                    .isEqualTo(baselineEvents);

            // 已点亮徽章保持点亮。
            assertThat(badgeKeys(eventSnapshot(userId)))
                    .as("回撤 %s 后已点亮徽章必须保持点亮", op)
                    .containsAll(baselineBadges);

            // 累计有效记账笔数可以回落，但绝不超过基线（删除如实反映在累计里）。
            assertThat(transactionRepository.countValidRecordsByCreatedBy(userId))
                    .as("回撤 %s 后累计有效记账笔数不得超过基线", op)
                    .isLessThanOrEqualTo(baselineValidRecords);

            prevExp = after.getExp();
            prevLevel = after.getLevel();
        }
    }

    // ---------------- 回撤操作 ----------------

    private void applyRollback(RollbackOp op, long userId) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        switch (op) {
            case SOFT_DELETE_ALL_TX -> jdbcTemplate.update(
                    "UPDATE transactions SET deleted_at = ? WHERE created_by = ? AND deleted_at IS NULL",
                    now, userId);
            case PURGE_RECYCLE_BIN -> jdbcTemplate.update(
                    "DELETE FROM transactions WHERE created_by = ? AND deleted_at IS NOT NULL", userId);
            case HARD_DELETE_ALL_TX -> jdbcTemplate.update(
                    "DELETE FROM transactions WHERE created_by = ?", userId);
            case LOWER_BUDGET_TO_MIN -> jdbcTemplate.update(
                    "UPDATE budgets SET amount = 0.01 WHERE user_id = ?", userId);
            case DELETE_BUDGET -> jdbcTemplate.update(
                    "DELETE FROM budgets WHERE user_id = ?", userId);
            case INVALIDATE_INVITES -> jdbcTemplate.update(
                    "UPDATE invite_relations SET status = 'INVALID' WHERE inviter_id = ?", userId);
            default -> throw new IllegalStateException("未覆盖的回撤操作: " + op);
        }
    }

    // ---------------- 事实源播种 ----------------

    /** 创建一个该用户拥有的个人账本，返回其 id（预算判定只看自有账本）。 */
    private long createOwnedLedger(long userId) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        Ledger ledger = new Ledger();
        ledger.setUserId(userId);
        ledger.setName("prop4-" + userId);
        ledger.setType(Ledger.TYPE_PERSONAL);
        ledger.setSortOrder(0);
        ledger.setDefault(true);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        return ledgerRepository.save(ledger).getId();
    }

    /**
     * 插入一笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type = expense}、{@code ledger_id} 非 NULL）。记账日由 {@code created_at} 决定，
     * 预算按月聚合按 {@code occurred_at}，此处二者取同值。走 JDBC 以便后续能设/读 {@code deleted_at}。
     */
    private void insertValidExpense(long userId, long ledgerId, LocalDateTime at, BigDecimal amount) {
        jdbcTemplate.update(
                "INSERT INTO transactions "
                        + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                        + "occurred_at, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                userId, ledgerId, userId, TransactionType.EXPENSE.getCode(), amount,
                ledgerId, ledgerId, at, at, at);
    }

    /** 插入一条月度总预算行（自有账本、指定自然月、指定金额）。 */
    private void insertBudget(long userId, long ledgerId, String month, BigDecimal amount) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setLedgerId(ledgerId);
        budget.setMonth(month);
        budget.setAmount(amount);
        budget.setCreatedAt(now);
        budget.setUpdatedAt(now);
        budgetRepository.save(budget);
    }

    /** 插入一条邀请关系（inviter_id = 本用户，invitee_id 为唯一悬空 id，该列无外键）。 */
    private void insertInviteRelation(long inviterId, long inviteeId, InviteStatus status) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        jdbcTemplate.update(
                "INSERT INTO invite_relations "
                        + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                inviterId, inviteeId, now, status.name(), now, now);
    }

    // ---------------- 读回工具 ----------------

    /** 该用户全部成长事件的逐行快照（全部列，按 id 升序），用于逐行比对不变。 */
    private List<Map<String, Object>> eventSnapshot(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT id, user_id, event_type, event_key, exp_amount, created_at "
                        + "FROM growth_events WHERE user_id = ? ORDER BY id ASC",
                userId);
    }

    /** 从事件快照里筛出 BADGE 事件键集合（点亮的徽章）。 */
    private static Set<String> badgeKeys(List<Map<String, Object>> events) {
        return events.stream()
                .filter(e -> "BADGE".equals(e.get("event_type")))
                .map(e -> (String) e.get("event_key"))
                .collect(Collectors.toSet());
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
