package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.UserGrowth;
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
 * <b>Property 3：事件幂等（任意操作序列后每 {@code event_key} 至多一行）。</b>
 *
 * <p>锁住 {@code growth_events} 的两条硬不变式：① 对任一 {@code (user_id, event_key)}，无论经历怎样的
 * 记账 / 删账 / 重复结算 / 并发结算序列，终态行数恒 ≤1（幂等由 {@code uk_growth_events_user_key}
 * 唯一索引在数据库层承担，而非应用层「先查再写」——需求 1.5）；② 已存在那一行在后续任意操作后
 * {@code id / event_type / exp_amount / created_at} 逐列不变、{@code growth_events} 从不被 UPDATE/DELETE
 * （需求 1.4、1.6、3.5、8.4）。并发维度另锁需求 1.8：2–8 个结算并发后终态每键 ≤1 且 {@code exp} 恒等于
 * 事件 {@code exp_amount} 之和。</p>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 * <p>{@link GrowthSettlementService#settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有真实提交才能在库里
 * 观察到结算终态，故本类<b>不用测试级事务包裹</b>：每次迭代直接调用 {@code settle} 让其真实提交，并在
 * {@link #resetState()} 里显式清库（三张表），用全局自增序号 {@link #SEQ} 保证每次迭代 {@code userId} 全局唯一，
 * 双重隔离。时钟用进程共享的可推进 {@link MutableClock}（{@code @Primary} 覆盖 {@code TimeConfig} 的系统时钟），
 * 固定在 {@code Asia/Shanghai} 的 {@code 2025-06-15 08:00}，推进它即可跨越 60 秒记账节流窗口而不换自然日。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 静态上下文缓存复用，多次迭代只加载一次）。</p>
 *
 * <h2>反向断言（ODKU 的回归锁，<b>不标可选</b>）：为什么它在 H2 上仍然有意义</h2>
 * <p>{@link #reverseAssertion_illegalInsertMustBeRejectedByCheckConstraint()} 断言：向 {@code growth_events}
 * 直接插入<b>非法 {@code event_type}</b>（取值不在六元集合内）或<b>负 {@code exp_amount}</b> 时，数据库必须以
 * <b>CHECK 违例</b>拒绝，且表行数不变（需求 11.7）。这条断言把生产批量语句
 * {@code INSERT ... ON DUPLICATE KEY UPDATE id = id}（{@code GrowthSettlementService.INSERT_EVENT_SQL}）的
 * 「只忽略重复键、绝不吞 CHECK 违例」这条实现约束锁死：<b>一旦有人把该批量语句改成 {@code INSERT IGNORE}，
 * 本断言必然失败</b>——{@code INSERT IGNORE} 会把 CHECK 违例、非空违例、超长截断一并<b>静默降级为警告</b>、
 * 不再抛异常，脏数据随之落库。本反向断言正是用一条「非法写入必须抛错」的可执行断言，替 {@code INSERT IGNORE}
 * 这条禁令站岗。</p>
 *
 * <p><b>H2 语义说明（避免把这条断言做成假绿）</b>：测试环境的 H2 表结构由 Hibernate 依实体
 * {@code ddl-auto=create-drop} 生成，而 {@code GrowthEvent} 实体<b>不声明</b>迁移脚本
 * {@code V32__user_growth.sql} 里的两个 CHECK 约束（{@code ck_growth_events_type} /
 * {@code ck_growth_events_exp}），因此裸 H2 表<b>不会</b>自动带上它们——若不处理，本反向断言会因「非法插入
 * 竟然成功」而失去意义（沦为假绿）。故 {@link #resetState()} 在 H2 上<b>显式补建</b>这两个 CHECK 的等价约束
 * （见 {@link #ensureCheckConstraints()}）：用非法值 {@code 'FOO'} 与 {@code exp_amount = -1} 触发，二者无论
 * 大小写敏感与否都必然落在合法集合之外，故断言非空洞。H2 不支持 {@code COLLATE utf8mb4_bin}，因此这里补建的
 * {@code event_type} CHECK 省去该 collate 子句——「区分大小写」这一维度（{@code 'first_record'}、{@code 'Badge'}
 * 被 {@code ERROR 3819} 拒）的<b>最终确认属于真实 MySQL</b>，已在任务 1.5 手工清单完成并回写 design.md
 * 「迁移脚本」小节，本处不冒充。</p>
 *
 * <p>Feature: growth-level-system, Property 3: 事件幂等（任意操作序列后每 event_key 至多一行）</p>
 *
 * <p>Validates: Requirements 1.4, 1.5, 1.6, 1.8, 3.5, 3.7, 8.4, 11.7, 11.8</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-idem-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 并发结算（每个各自 REQUIRES_NEW）在争锁窗口内会短暂占用多个连接，抬高池上限避免误报为「获取连接超时」。
        "spring.datasource.hikari.maximum-pool-size=32"
})
class GrowthEventIdempotencyPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：结算日 = 2025-06-15，全程不越自然日。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 跨迭代复用同一内存库，用序号保证 userId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(700_000L);

    /** 记账侧 60 秒节流窗口之外的推进量：保证下一次 RECORD 结算真实执行而非被跳过。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);

    /** H2 上补建的 CHECK 约束是否已就绪（进程内只需补建一次）。 */
    private static volatile boolean checksReady = false;

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthEventIdempotencyPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        ensureCheckConstraints();
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删三张表。两张成长表均无外键，删除顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    /**
     * 在 H2 上补建迁移脚本的两个 CHECK 约束的等价物，使反向断言非空洞（见类级 Javadoc「H2 语义说明」）。
     * H2 不支持 {@code COLLATE utf8mb4_bin}，故 {@code event_type} 的 CHECK 省去该 collate 子句；
     * 「区分大小写」维度由任务 1.5 的真实 MySQL 手工清单确认。用 {@code IF NOT EXISTS} 保证跨迭代只补建一次。
     */
    private void ensureCheckConstraints() {
        if (checksReady) {
            return;
        }
        synchronized (GrowthEventIdempotencyPropertyTest.class) {
            if (checksReady) {
                return;
            }
            jdbcTemplate.execute(
                    "ALTER TABLE growth_events ADD CONSTRAINT IF NOT EXISTS ck_growth_events_type "
                            + "CHECK (event_type IN "
                            + "('FIRST_RECORD','DAILY_RECORD','STREAK','BUDGET_MET','FIRST_INVITE','BADGE'))");
            jdbcTemplate.execute(
                    "ALTER TABLE growth_events ADD CONSTRAINT IF NOT EXISTS ck_growth_events_exp "
                            + "CHECK (exp_amount >= 0)");
            checksReady = true;
        }
    }

    // ---------------- 生成器 ----------------

    /** 额外散点记账日的偏移集合（相对结算日往前 0–45 天），列表长度 1–15。 */
    @Provide
    Arbitrary<List<Integer>> extraDayOffsets() {
        return Arbitraries.integers().between(0, 45).list().ofMinSize(1).ofMaxSize(15);
    }

    // ---------------- Property 3 主属性 ----------------

    /**
     * 任意（记账 + 并发结算 + 重复结算 + 删账回撤）操作序列后：每 {@code (user_id, event_key)} 至多一行、
     * 已存在行逐列不变、{@code growth_events} 不被删改、{@code exp} 恒等于事件之和。
     *
     * <p>序列构造：以「结算日往前 {@code runLength} 天的连续段」叠加若干散点日作为记账日集合（连续段用于
     * 触发 {@code STREAK_7 / STREAK_30} 与相应徽章，覆盖 3.5、8.4）；每个记账日预置一笔有效交易。随后：</p>
     * <ol>
     *   <li><b>并发结算</b>（并发度 {@code concurrency} ∈ [2,8]）：多线程对同一用户同时 {@code settle}，
     *       个体异常按生产语义就地吞掉（{@code trigger.settleQuietly} 的行为），只看终态——需求 1.8。</li>
     *   <li><b>自愈结算</b>：推进过节流窗口后串行结算一次，确保全部应发事件已物化（并发争锁的败者留下的
     *       欠账在此补齐）。</li>
     *   <li><b>重复结算（幂等）</b>：再串行结算一次；断言某条既有事件（{@code FIRST_RECORD}）的四列快照不变
     *       ——需求 1.6、1.7。</li>
     *   <li><b>删账回撤</b>：软删该用户全部交易后再结算一次；断言全部事件行逐行快照不变——需求 1.4、8.4。</li>
     * </ol>
     * <p>终态断言：不存在任何 {@code event_key} 行数 &gt;1；{@code exp == SUM(exp_amount)}（读库比对，非内存值）。</p>
     *
     * <p>Validates: Requirements 1.4, 1.5, 1.6, 1.8, 3.5, 8.4</p>
     */
    @Property(tries = 25)
    void property3_atMostOneRowPerEventKeyUnderAnySequence(
            @ForAll("extraDayOffsets") List<Integer> extraOffsets,
            @ForAll @IntRange(min = 0, max = 32) int runLength,
            @ForAll @IntRange(min = 2, max = 8) int concurrency) throws Exception {

        long userId = SEQ.getAndIncrement();
        long ledgerId = userId;
        LocalDate settleDate = LocalDate.now(CLOCK);

        // 记账日集合：连续段（结算日往前 runLength 天，含今日）∪ 散点日；去重。
        Set<LocalDate> recordDays = new LinkedHashSet<>();
        for (int i = 0; i < runLength; i++) {
            recordDays.add(settleDate.minusDays(i));
        }
        for (int off : extraOffsets) {
            recordDays.add(settleDate.minusDays(off));
        }
        for (LocalDate day : recordDays) {
            seedValidRecord(userId, ledgerId, day.atTime(10, 0), new BigDecimal("12.30"), TransactionType.EXPENSE);
        }

        // ① 并发结算：多线程同时 settle，个体成败无关，只看终态（需求 1.8）。
        runConcurrentSettlements(userId, concurrency);

        // ② 自愈结算：跨过节流窗口后串行结算一次，确保全部应发事件已物化（并发欠账在此补齐）。
        CLOCK.advance(BEYOND_THROTTLE);
        settlementService.settle(userId, TriggerSource.RECORD);

        // ③ 重复结算（幂等）：既有事件的四列快照必须不变（需求 1.6、1.7）。
        List<List<Object>> firstRecordBefore = snapshotEventsByKey(userId, "FIRST_RECORD");
        assertThat(firstRecordBefore).hasSize(1);
        CLOCK.advance(BEYOND_THROTTLE);
        settlementService.settle(userId, TriggerSource.RECORD);
        assertThat(snapshotEventsByKey(userId, "FIRST_RECORD")).isEqualTo(firstRecordBefore);

        // ④ 删账回撤：软删全部交易后再结算，全部事件行逐行快照必须不变（需求 1.4、8.4）。
        List<List<Object>> allEventsBefore = snapshotAllEvents(userId);
        long eventCountBefore = allEventsBefore.size();
        UserGrowth profileBefore = userGrowthRepository.findById(userId).orElseThrow();
        jdbcTemplate.update("UPDATE transactions SET deleted_at = ? WHERE user_id = ?",
                LocalDateTime.now(CLOCK), userId);
        CLOCK.advance(BEYOND_THROTTLE);
        settlementService.settle(userId, TriggerSource.RECORD);

        assertThat(snapshotAllEvents(userId)).isEqualTo(allEventsBefore);
        assertThat(countEvents(userId)).isEqualTo(eventCountBefore);
        UserGrowth profileAfter = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(profileAfter.getExp()).isEqualTo(profileBefore.getExp());
        assertThat(profileAfter.getLevel()).isEqualTo(profileBefore.getLevel());

        // ── 终态不变式 ─────────────────────────────────────────────────────────
        // 每 event_key 至多一行（读库比对；GROUP BY ... HAVING COUNT(*) > 1 必须为空）。
        List<String> duplicated = jdbcTemplate.queryForList(
                "SELECT event_key FROM growth_events WHERE user_id = ? GROUP BY event_key HAVING COUNT(*) > 1",
                String.class, userId);
        assertThat(duplicated).as("每个 event_key 至多一行").isEmpty();

        // exp 恒等于事件 exp_amount 之和（需求 1.8 / 1.2，读库比对，非内存值）。
        long sumExp = sumExpAmount(userId);
        assertThat(profileAfter.getExp()).as("exp == SUM(exp_amount)").isEqualTo(sumExp);
    }

    /**
     * 反向断言（ODKU 的回归锁，<b>不标可选</b>）：非法 {@code event_type} 或负 {@code exp_amount} 的插入
     * 必须被 CHECK 违例拒绝，且表行数不变（需求 11.7、11.8）。
     *
     * <p>用生产批量语句同款的 {@code INSERT ... ON DUPLICATE KEY UPDATE id = id} 写入非法数据：ODKU 只忽略
     * <b>重复键</b>，CHECK 违例照样抛异常。<b>若把该批量语句改成 {@code INSERT IGNORE}，本断言必然失败</b>
     * ——{@code INSERT IGNORE} 会把 CHECK 违例静默降级为警告、不再抛异常（见类级 Javadoc）。</p>
     *
     * <p>Validates: Requirements 11.7, 11.8, 3.7</p>
     */
    @Example
    void reverseAssertion_illegalInsertMustBeRejectedByCheckConstraint() {
        long userId = SEQ.getAndIncrement();
        long rowsBefore = totalEventRows();
        LocalDateTime now = LocalDateTime.now(CLOCK);

        // 非法 event_type（不在六元合法集合内）：CHECK ck_growth_events_type 拒绝。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                        + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = id",
                userId, "FOO", "FOO:illegal", 5, now))
                .as("非法 event_type 必须被 CHECK 违例拒绝（INSERT IGNORE 会让此断言失败）")
                .isInstanceOf(DataIntegrityViolationException.class);

        // 负 exp_amount：CHECK ck_growth_events_exp 拒绝。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                        + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = id",
                userId, "DAILY_RECORD", "DAILY_RECORD:2025-06-01", -1, now))
                .as("负 exp_amount 必须被 CHECK 违例拒绝（INSERT IGNORE 会让此断言失败）")
                .isInstanceOf(DataIntegrityViolationException.class);

        // 需求 11.7：被拒后表行数不变（无部分写入）。
        assertThat(totalEventRows()).isEqualTo(rowsBefore);
    }

    // ---------------- 测试基础设施 ----------------

    /** 多线程同时对同一用户 {@code settle}：CountDownLatch 齐发，个体异常按生产语义就地吞掉。 */
    private void runConcurrentSettlements(long userId, int concurrency) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                Callable<Void> task = () -> {
                    start.await();
                    try {
                        settlementService.settle(userId, TriggerSource.RECORD);
                    } catch (Exception ignored) {
                        // 生产中由 GrowthSettlementTrigger.settleQuietly 吞掉一切结算故障（含争锁放弃）；
                        // 本属性只锁终态不变式，与个体结算成败无关。
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 提交一笔「有效记账交易」（created_by=用户、deleted_at 为 NULL、type∈{expense,income}、ledger_id 非空）。 */
    private void seedValidRecord(long userId, long ledgerId, LocalDateTime createdAt,
                                 BigDecimal amount, TransactionType type) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setLedgerId(ledgerId);
        tx.setCreatedBy(userId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setAccountId(ledgerId);
        tx.setCategoryId(ledgerId);
        tx.setOccurredAt(createdAt);
        tx.setCreatedAt(createdAt);   // 记账日由 created_at 决定
        tx.setUpdatedAt(createdAt);
        transactionRepository.save(tx);
    }

    /**
     * 取该用户某 event_key 的行四列快照（id/event_type/exp_amount/created_at），按 id 升序。
     * 用 {@code List<Object>} 而非 {@code Object[]} 承载每行：{@code List.equals} 逐元素比较，
     * 而 {@code Object[].equals} 是引用相等，会让「快照不变」的断言误报（两个内容相同的数组不相等）。
     */
    private List<List<Object>> snapshotEventsByKey(long userId, String eventKey) {
        return jdbcTemplate.query(
                "SELECT id, event_type, exp_amount, created_at FROM growth_events "
                        + "WHERE user_id = ? AND event_key = ? ORDER BY id",
                (rs, n) -> List.of(
                        rs.getLong("id"), rs.getString("event_type"),
                        rs.getInt("exp_amount"), rs.getTimestamp("created_at")),
                userId, eventKey);
    }

    /** 取该用户全部事件行的五列快照（id/event_type/event_key/exp_amount/created_at），按 id 升序。 */
    private List<List<Object>> snapshotAllEvents(long userId) {
        return jdbcTemplate.query(
                "SELECT id, event_type, event_key, exp_amount, created_at FROM growth_events "
                        + "WHERE user_id = ? ORDER BY id",
                (rs, n) -> List.of(
                        rs.getLong("id"), rs.getString("event_type"), rs.getString("event_key"),
                        rs.getInt("exp_amount"), rs.getTimestamp("created_at")),
                userId);
    }

    private long countEvents(long userId) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return c == null ? 0L : c;
    }

    private long sumExpAmount(long userId) {
        Long s = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(exp_amount), 0) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return s == null ? 0L : s;
    }

    private long totalEventRows() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM growth_events", Long.class);
        return c == null ? 0L : c;
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
