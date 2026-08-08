package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Ledger;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 累计统计口径的属性测试（<b>Property 10：累计统计如实反映事实源（删除/恢复对称）</b>）。
 *
 * <p>本测试锁住需求 7 交汇处的一组构造性等式：{@link GrowthQueryService#getOverview} 返回的三项累计统计
 * （累计记账笔数 / 累计支出金额 / 累计收入金额）必须<b>如实反映交易事实源的当前状态</b>，且删除与恢复
 * 严格对称、往返恒等：</p>
 * <ul>
 *   <li><b>三项 == 参考实现</b>（需求 7.1、7.2、7.3、7.4、7.5、7.8）：在内存里按「有效记账交易」四条件
 *       （{@code created_by} 等于本人、{@code deleted_at} 为 NULL、{@code type ∈ {expense,income}}、
 *       {@code ledger_id} 非 NULL）独立过滤并以 {@link BigDecimal} 求和，与被测服务逐项比对。
 *       {@code transfer} 与 {@code ledger_id IS NULL}（余额调整）的行一律不计入；他人 {@code created_by}
 *       的行不计入；<b>跨全部账本合并</b>（含他人拥有的协作账本，只要本人记账即计入）。</li>
 *   <li><b>删除/恢复对称</b>（需求 7.6、7.7）：把一笔有效记账交易移入回收站使笔数减 1、对应金额随之减去
 *       该笔；从回收站恢复使其加回，<b>往返后逐字段回到原值</b>；整个过程 {@code exp}/{@code level}/徽章不动
 *       （由 Property 2/4 覆盖，本测试只锁累计三项）。</li>
 *   <li><b>钳制与非负</b>（需求 7.10、7.14、7.15）：三项恒 ≥ 0；笔数为 0 时两项金额均为 {@code 0.00}；
 *       某项合计超过 {@code 9999999999999999.99} 时以该上界返回、为负（历史脏数据）时以 {@code 0.00}
 *       返回，两种情形都不使请求失败。</li>
 *   <li><b>无浮点、账本无关</b>（需求 7.11、10.12）：金额字段的运行时类型恒为 {@link BigDecimal}、保留 2 位；
 *       {@code getOverview} 只按 {@code userId} 聚合、不接收任何会话账本参数，故对当前账本天然无关（连续两次
 *       调用逐字段相等）。</li>
 * </ul>
 *
 * <h2>生成器</h2>
 * <p>生成一个交易集合（规模 0–120），每笔覆盖 {@code type ∈ {expense,income,transfer}} ×
 * {@code ledger_id ∈ {自有账本, 协作账本, NULL}} × {@code created_by ∈ {本人, 他人}} ×
 * {@code amount ∈ {0.01, 0.02, 随机两位小数, 9999999999999999.99, 负值}} × {初始是否软删}；再生成一段
 * 删除/恢复操作序列（长度 0–30）在集合上往返。全部行经 {@link JdbcTemplate} 直插，以便造出负值、上界值与
 * 软删行（实体带 {@code @SQLRestriction("deleted_at is null")}，经仓储写不出软删行；测试库由 Hibernate
 * 依实体建表、无 {@code ck_tx_amount_positive}，故可造历史脏数据）。</p>
 *
 * <h2>测试层级与清理</h2>
 * <p>{@code getOverview} 内部触发的 {@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，需真实事务管理器，
 * 故走全栈 {@code @SpringBootTest} + H2（{@code MODE=MySQL}，独立命名内存库）。清理<b>不能靠事务回滚</b>：
 * {@link #resetState()} 在每次迭代前显式清相关表并归位时钟，并用全局自增序号 {@link #SEQ} 保证每次迭代的
 * {@code userId} / {@code 交易 id} 全局唯一。jqwik 属性方法不经 {@code SpringExtension}，依赖注入由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文缓存复用，多次迭代只加载一次）。</p>
 *
 * <p>Feature: growth-level-system, Property 10: 累计统计如实反映事实源（删除/恢复对称）</p>
 *
 * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.10, 7.11, 7.14, 7.15, 10.12</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-cumstats-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class GrowthCumulativeStatsPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定在 2025-06-15 08:00（Asia/Shanghai）：全部交易落同一自然日，累计口径与时钟无关。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final LocalDateTime BASE_LDT = LocalDateTime.of(2025, 6, 15, 8, 0, 0);
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** {@code DECIMAL(18,2)} 可表示的最大值：累计金额的上界（需求 7.14）。 */
    private static final BigDecimal AMOUNT_UPPER_BOUND = new BigDecimal("9999999999999999.99");
    private static final BigDecimal ZERO_2DP = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /** 跨迭代复用同一内存库，用序号保证 userId / 交易 id 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(30_000_000L);

    // 交易类型码。
    private static final int TYPE_EXPENSE = 0;
    private static final int TYPE_INCOME = 1;
    private static final int TYPE_TRANSFER = 2;

    // 账本归属码。
    private static final int LEDGER_OWNED = 0;
    private static final int LEDGER_COLLAB = 1;
    private static final int LEDGER_NULL = 2;

    // 金额形态码。
    private static final int AMT_MIN = 0;       // 0.01
    private static final int AMT_MIN2 = 1;      // 0.02
    private static final int AMT_RANDOM = 2;    // 随机两位小数
    private static final int AMT_MAX = 3;       // 9999999999999999.99（用于压 7.14 上界钳制）
    private static final int AMT_NEGATIVE = 4;  // 负值（历史脏数据，用于压 7.15）

    @Autowired
    private GrowthQueryService queryService;
    @Autowired
    private com.damien.youyu.repository.LedgerRepository ledgerRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthCumulativeStatsPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删事实源与成长两表。均无外键，删除顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM ledgers");
    }

    // ---------------- 生成器 ----------------

    /** 一笔交易的生成规格：类型 × 账本归属 × 记账人 × 金额形态 × 随机金额分 × 初始是否软删。 */
    record TxSpec(int typeCode, int ledgerKind, boolean createdBySelf,
                  int amountKind, long randomCents, boolean deleted) {
    }

    @Provide
    Arbitrary<List<TxSpec>> transactionSets() {
        Arbitrary<TxSpec> one = Combinators.combine(
                Arbitraries.integers().between(TYPE_EXPENSE, TYPE_TRANSFER),
                Arbitraries.integers().between(LEDGER_OWNED, LEDGER_NULL),
                Arbitraries.of(true, false),
                Arbitraries.integers().between(AMT_MIN, AMT_NEGATIVE),
                Arbitraries.longs().between(1L, 99_999_999L),
                Arbitraries.of(true, false)
        ).as(TxSpec::new);
        return one.list().ofMinSize(0).ofMaxSize(120);
    }

    /** 删除/恢复操作序列：true = 删除一笔当前未删的行，false = 恢复一笔当前已删的行。 */
    @Provide
    Arbitrary<List<Boolean>> deleteRestoreOps() {
        return Arbitraries.of(true, false).list().ofMinSize(0).ofMaxSize(30);
    }

    // ---------------- Property 10 ----------------

    /**
     * Feature: growth-level-system, Property 10: 累计统计如实反映事实源（删除/恢复对称）
     *
     * <p>把生成的交易集合直插事实源，随后：① 断言 {@code getOverview} 的三项累计等于内存参考实现、
     * 三项 ≥ 0、金额为两位 {@link BigDecimal}、笔数为 0 时金额为 {@code 0.00}、上界与负值被钳制、连续两次
     * 调用逐字段相等（账本无关）；② 逐个应用删除/恢复操作，每步后重算参考实现并比对（删除即减、恢复即加）；
     * ③ 对一笔有效记账交易做删除→恢复往返，断言整份响应逐字段回到原值（删除/恢复对称）。</p>
     *
     * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.10, 7.11, 7.14, 7.15, 10.12</p>
     */
    @Property(tries = 20)
    void property10_cumulativeStatsReflectFactSourceWithDeleteRestoreSymmetry(
            @ForAll("transactionSets") List<TxSpec> specs,
            @ForAll("deleteRestoreOps") List<Boolean> ops) {

        long userId = SEQ.getAndIncrement();
        long otherUserId = SEQ.getAndIncrement();
        long ownedLedgerId = createLedger(userId, true);
        long collabLedgerId = createLedger(otherUserId, false); // 他人拥有、本人记账 => 协作账本

        // 建模并直插全部交易行。
        List<Row> rows = new ArrayList<>(specs.size());
        for (TxSpec spec : specs) {
            long id = SEQ.getAndIncrement();
            long createdBy = spec.createdBySelf() ? userId : otherUserId;
            Long ledgerId = switch (spec.ledgerKind()) {
                case LEDGER_OWNED -> ownedLedgerId;
                case LEDGER_COLLAB -> collabLedgerId;
                default -> null;
            };
            String type = switch (spec.typeCode()) {
                case TYPE_EXPENSE -> "expense";
                case TYPE_INCOME -> "income";
                default -> "transfer";
            };
            BigDecimal amount = amountOf(spec);
            insertTransaction(id, createdBy, ledgerId, type, amount, spec.deleted());
            rows.add(new Row(id, createdBy, ledgerId, type, amount, spec.deleted()));
        }

        // ① 初始状态：三项 == 参考实现，且各项不变式成立。
        GrowthOverviewResponse overview = queryService.getOverview(userId);
        assertStatsMatchReference(overview, rows, userId);

        // 账本无关（需求 10.12）：getOverview 只按 userId 聚合、不接收会话账本；连续两次调用逐字段相等。
        GrowthOverviewResponse again = queryService.getOverview(userId);
        assertThat(again.totalRecordCount()).isEqualTo(overview.totalRecordCount());
        assertThat(again.totalExpense()).isEqualByComparingTo(overview.totalExpense());
        assertThat(again.totalIncome()).isEqualByComparingTo(overview.totalIncome());

        // ② 逐个应用删除/恢复操作，每步后重算参考实现并比对（需求 7.6、7.7）。
        for (boolean isDelete : ops) {
            Row target = isDelete ? firstActive(rows) : firstDeleted(rows);
            if (target == null) {
                continue; // 无可操作对象，跳过（不改变状态）。
            }
            setDeleted(target, isDelete);
            GrowthOverviewResponse afterOp = queryService.getOverview(userId);
            assertStatsMatchReference(afterOp, rows, userId);
        }

        // ③ 删除→恢复往返对称：挑一笔「有效记账交易」，删后减、恢复后逐字段回到原值（需求 7.6、7.7）。
        Row validRecord = firstValidRecord(rows, userId);
        if (validRecord != null) {
            GrowthOverviewResponse before = queryService.getOverview(userId);

            setDeleted(validRecord, true);
            GrowthOverviewResponse afterDelete = queryService.getOverview(userId);
            // 软删一笔有效记账交易后笔数恒减 1（钳制不改变笔数）。金额不做单调断言：需求 7.15 允许历史脏数据
            // 出现负值，删掉一笔负值行会使合计"回升"，故金额只以参考实现比对（下一行），不假设单调不增。
            assertThat(afterDelete.totalRecordCount())
                    .as("软删一笔有效记账交易后累计笔数应减 1")
                    .isEqualTo(before.totalRecordCount() - 1);
            assertStatsMatchReference(afterDelete, rows, userId);

            setDeleted(validRecord, false);
            GrowthOverviewResponse afterRestore = queryService.getOverview(userId);
            // 往返恒等：恢复后逐字段回到删除前的取值。
            assertThat(afterRestore.totalRecordCount())
                    .as("删除→恢复往返后累计笔数应回到原值")
                    .isEqualTo(before.totalRecordCount());
            assertThat(afterRestore.totalExpense())
                    .as("删除→恢复往返后累计支出金额应回到原值")
                    .isEqualByComparingTo(before.totalExpense());
            assertThat(afterRestore.totalIncome())
                    .as("删除→恢复往返后累计收入金额应回到原值")
                    .isEqualByComparingTo(before.totalIncome());
        }
    }

    /** 断言概览三项累计等于内存参考实现，并逐条锁住非负 / 两位小数 / BigDecimal 类型 / 笔数为 0 时金额为 0.00。 */
    private void assertStatsMatchReference(GrowthOverviewResponse overview, List<Row> rows, long userId) {
        Reference ref = reference(rows, userId);

        // 三项 == 参考实现（需求 7.1、7.2、7.3、7.4、7.5、7.8、7.14、7.15）。
        assertThat(overview.totalRecordCount())
                .as("累计记账笔数应等于满足四条件的行数").isEqualTo(ref.count());
        assertThat(overview.totalExpense())
                .as("累计支出金额应等于参考实现").isEqualByComparingTo(ref.expense());
        assertThat(overview.totalIncome())
                .as("累计收入金额应等于参考实现").isEqualByComparingTo(ref.income());

        // 三项 ≥ 0（需求 7.10、7.15）。
        assertThat(overview.totalRecordCount()).as("累计笔数 ≥ 0").isGreaterThanOrEqualTo(0L);
        assertThat(overview.totalExpense()).as("累计支出 ≥ 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(overview.totalIncome()).as("累计收入 ≥ 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // 金额为两位 BigDecimal、不超上界（需求 7.11、7.14）。
        assertThat(overview.totalExpense()).as("支出金额运行时类型为 BigDecimal").isInstanceOf(BigDecimal.class);
        assertThat(overview.totalIncome()).as("收入金额运行时类型为 BigDecimal").isInstanceOf(BigDecimal.class);
        assertThat(overview.totalExpense().scale()).as("支出金额保留 2 位").isEqualTo(2);
        assertThat(overview.totalIncome().scale()).as("收入金额保留 2 位").isEqualTo(2);
        assertThat(overview.totalExpense()).as("支出金额不超上界").isLessThanOrEqualTo(AMOUNT_UPPER_BOUND);
        assertThat(overview.totalIncome()).as("收入金额不超上界").isLessThanOrEqualTo(AMOUNT_UPPER_BOUND);

        // 笔数为 0 时两项金额均为 0.00（需求 7.10）。
        if (overview.totalRecordCount() == 0L) {
            assertThat(overview.totalExpense()).as("笔数为 0 时支出为 0.00").isEqualByComparingTo(ZERO_2DP);
            assertThat(overview.totalIncome()).as("笔数为 0 时收入为 0.00").isEqualByComparingTo(ZERO_2DP);
        }
    }

    /** 内存参考实现：按「有效记账交易」四条件独立过滤后以 BigDecimal 求和，再按 7.14/7.15 钳制。 */
    private Reference reference(List<Row> rows, long userId) {
        long count = 0;
        BigDecimal rawExpense = BigDecimal.ZERO;
        BigDecimal rawIncome = BigDecimal.ZERO;
        for (Row r : rows) {
            boolean valid = r.createdBy == userId              // 归属键 created_by（需求 7.1）
                    && !r.deletedNow                            // deleted_at 为 NULL（需求 7.6）
                    && r.ledgerId != null                       // ledger_id 非 NULL（需求 7.5）
                    && ("expense".equals(r.type) || "income".equals(r.type)); // 排除 transfer（需求 7.4）
            if (!valid) {
                continue;
            }
            count++;
            if ("expense".equals(r.type)) {
                rawExpense = rawExpense.add(r.amount);
            } else {
                rawIncome = rawIncome.add(r.amount);
            }
        }
        return new Reference(count, clamp(rawExpense), clamp(rawIncome));
    }

    /** 与被测口径一致的金额归一化：保留 2 位（HALF_UP）→ 负值以 0.00 返回 → 超上界钳到上界（需求 7.14、7.15）。 */
    private static BigDecimal clamp(BigDecimal raw) {
        BigDecimal scaled = raw.setScale(2, RoundingMode.HALF_UP);
        if (scaled.signum() < 0) {
            return ZERO_2DP;
        }
        if (scaled.compareTo(AMOUNT_UPPER_BOUND) > 0) {
            return AMOUNT_UPPER_BOUND;
        }
        return scaled;
    }

    private static BigDecimal amountOf(TxSpec spec) {
        return switch (spec.amountKind()) {
            case AMT_MIN -> new BigDecimal("0.01");
            case AMT_MIN2 -> new BigDecimal("0.02");
            case AMT_RANDOM -> BigDecimal.valueOf(spec.randomCents(), 2);
            case AMT_MAX -> AMOUNT_UPPER_BOUND;
            default -> BigDecimal.valueOf(spec.randomCents(), 2).negate(); // 历史脏数据：负值
        };
    }

    // ---------------- 事实源播种 ----------------

    /** 建一个账本（{@code owned=true} 归属本人，否则归属他人以模拟协作账本），返回其 id。 */
    private long createLedger(long ownerUserId, boolean owned) {
        Ledger ledger = new Ledger();
        ledger.setUserId(ownerUserId);
        ledger.setName(owned ? "自有账本" : "协作账本");
        ledger.setType(Ledger.TYPE_PERSONAL);
        ledger.setSortOrder(0);
        ledger.setDefault(owned);
        ledger.setCreatedAt(BASE_LDT);
        ledger.setUpdatedAt(BASE_LDT);
        return ledgerRepository.save(ledger).getId();
    }

    /**
     * 直插一笔交易行（走 {@link JdbcTemplate} 而非仓储）：需造出负值、上界值与软删行，
     * 而实体带 {@code @SQLRestriction("deleted_at is null")} 写不出软删行、且金额可为负（测试库无 CHECK）。
     */
    private void insertTransaction(long id, long createdBy, Long ledgerId, String type,
                                   BigDecimal amount, boolean deleted) {
        LocalDateTime deletedAt = deleted ? BASE_LDT : null;
        jdbcTemplate.update(
                "INSERT INTO transactions "
                        + "(id, user_id, ledger_id, created_by, type, amount, account_id, "
                        + "occurred_at, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, createdBy, ledgerId, createdBy, type, amount, ledgerId,
                BASE_LDT, BASE_LDT, BASE_LDT, deletedAt);
    }

    /** 切换某行软删状态并同步到库（{@link JdbcTemplate} 直改，绕过 {@code @SQLRestriction}）。 */
    private void setDeleted(Row row, boolean deleted) {
        LocalDateTime deletedAt = deleted ? BASE_LDT : null;
        jdbcTemplate.update("UPDATE transactions SET deleted_at = ? WHERE id = ?", deletedAt, row.id);
        row.deletedNow = deleted;
    }

    private static Row firstActive(List<Row> rows) {
        for (Row r : rows) {
            if (!r.deletedNow) {
                return r;
            }
        }
        return null;
    }

    private static Row firstDeleted(List<Row> rows) {
        for (Row r : rows) {
            if (r.deletedNow) {
                return r;
            }
        }
        return null;
    }

    /** 第一笔当前有效的记账交易（本人 / 未删 / ledger 非空 / expense|income），用于往返对称断言。 */
    private static Row firstValidRecord(List<Row> rows, long userId) {
        for (Row r : rows) {
            if (r.createdBy == userId && !r.deletedNow && r.ledgerId != null
                    && ("expense".equals(r.type) || "income".equals(r.type))) {
                return r;
            }
        }
        return null;
    }

    /** 内存中的交易行镜像（{@code deletedNow} 随删除/恢复操作变化）。 */
    private static final class Row {
        final long id;
        final long createdBy;
        final Long ledgerId;
        final String type;
        final BigDecimal amount;
        boolean deletedNow;

        Row(long id, long createdBy, Long ledgerId, String type, BigDecimal amount, boolean deletedNow) {
            this.id = id;
            this.createdBy = createdBy;
            this.ledgerId = ledgerId;
            this.type = type;
            this.amount = amount;
            this.deletedNow = deletedNow;
        }
    }

    /** 参考实现的三项累计结果载体。 */
    private record Reference(long count, BigDecimal expense, BigDecimal income) {
    }

    /** 提供一个 {@code @Primary} 的可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日可确定性。 */
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
