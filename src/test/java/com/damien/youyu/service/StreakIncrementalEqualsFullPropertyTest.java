package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 4：增量维护结果 == 全量重算结果</b>的属性测试（任务 8.2）。
 *
 * <p><i>对任意</i>「日历追加序列」（把一份记账日历拆成 1–20 批逐批追加，每批之后执行一次段维护）：
 * 逐批增量维护得到的终态段序列，与直接用完整日历执行一次段维护得到的段序列<b>逐项相同</b>
 * （起始日 / 结束日 / 段天数三列，按起始日升序逐段比对，需求 4.9、4.10）。</p>
 *
 * <h2>为什么这条构造性成立、却仍要用属性测试锁住</h2>
 *
 * <p>{@link StreakSegmentMaintainer#maintain} 只有<b>一条</b>全量对账路径：每次都用「本次已加载的
 * 完整日历」重算应有段序列、与已持久化段逐项 diff、只写差异行。它不存在「增量捷径」，因此增量的终态
 * 必然等于全量——差异写入不改变<b>值幂等</b>的最终形态。属性测试的职责是把这条推论钉死：一旦有人为了
 * 「优化」加一条只处理新增日期的增量分支并与全量产生分歧，本测试立刻变红（需求 4.4）。</p>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 *
 * <p>{@code maintain} 走 {@code JdbcTemplate} 的 ODKU 与 JPA 硬删，本类直接对真实 H2 读写、每次迭代
 * 真实提交，故<b>不用测试级 {@code @Transactional} 包裹</b>；清理由 {@link #resetState()} 每次迭代前
 * 显式清表，并用全局自增序号 {@link #SEQ} 给「全量用户」与「增量用户」各分配全局唯一的 {@code userId}，
 * 两者互不干扰。jqwik 属性方法不经 {@code SpringExtension}，依赖注入在 {@link BeforeTry} 里由
 * {@link TestContextManager} 手工完成（上下文缓存复用）。使用独立命名的内存库，避免污染其它切片测试。</p>
 *
 * <p>Feature: streak-system, Property 4: 增量维护结果 == 全量重算结果</p>
 *
 * <p>Validates: Requirements 4.9, 4.10, 4.4</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-incremental-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class StreakIncrementalEqualsFullPropertyTest {

    /** 全局自增序号：保证跨迭代、跨「全量 / 增量」用户的 {@code userId} 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(841_000_000L);

    /** 段维护的 {@code created_at} / {@code updated_at} 入参：固定值即可，本属性不比对时间戳。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2025, 6, 15, 8, 0);

    /** 日历偏移的基准日：跨月跨年由随机偏移量自然覆盖。 */
    private static final LocalDate BASE = LocalDate.of(2020, 1, 1);

    @Autowired
    private StreakSegmentMaintainer maintainer;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager txManager;

    /**
     * 段维护挂在结算的 {@code REQUIRES_NEW} 事务内，其孤儿段删除走 JPA {@code @Modifying}（需事务）。
     * 增量追加会把已持久化段的起始日「让位」给更早的日期（如先落 {@code [3]}=seg(3,3)、再追加 {@code 2}
     * ⇒ seg(2,3)，起始日 3 成孤儿），从而触发那条删除。本类直接调 {@code maintain}，故用它复刻生产的事务边界。
     */
    private TransactionTemplate tx;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(StreakIncrementalEqualsFullPropertyTest.class).prepareTestInstance(this);
        tx = new TransactionTemplate(txManager);
        jdbcTemplate.update("DELETE FROM streak_segments");
    }

    /** 在事务内执行一次段维护，复刻生产的 {@code REQUIRES_NEW} 事务边界。 */
    private void maintainInTx(long userId, List<LocalDate> calendar, LocalDateTime now) {
        tx.executeWithoutResult(status -> maintainer.maintain(userId, calendar, now));
    }

    /**
     * 日历追加序列：1–20 批，每批 0–30 个日期偏移（{@code [0, 500]}）。
     *
     * <p>偏移量随机 ⇒ 段序列天然覆盖全连续（相邻偏移）/ 全离散（大间隔）/ 重复（同偏移）/ 乱序
     * （批内批间均不排序）/ 跨月跨年（偏移跨度足够大）。空批、空整体（全部批为空）也在取值空间内。</p>
     */
    @Provide
    Arbitrary<List<List<Integer>>> appendSequences() {
        Arbitrary<List<Integer>> batch =
                Arbitraries.integers().between(0, 500).list().ofMaxSize(30);
        return batch.list().ofMinSize(1).ofMaxSize(20);
    }

    /**
     * Feature: streak-system, Property 4: 增量维护结果 == 全量重算结果
     *
     * <p>逐批增量维护的终态与全量一次维护逐项相同（需求 4.9、4.10）。</p>
     *
     * <p>Validates: Requirements 4.9, 4.10, 4.4</p>
     */
    @Property(tries = 20)
    void incrementalMaintenanceEqualsFullRecompute(@ForAll("appendSequences") List<List<Integer>> batches) {
        assertIncrementalEqualsFull(batches);
    }

    /** 顶角必跑：单点、全连续、全离散、跨年各来一批，锁住取样可能漏掉的确定性形态。 */
    @Example
    void deterministicCorner() {
        assertIncrementalEqualsFull(List.of(
                List.of(0),                                 // 单点
                List.of(1, 2, 3, 4, 5),                     // 全连续
                List.of(10, 20, 30, 40),                    // 全离散
                List.of(365, 366, 800, 800),                // 跨年 + 重复
                List.of()));                                // 空批
    }

    private void assertIncrementalEqualsFull(List<List<Integer>> batches) {
        long fullUserId = SEQ.getAndIncrement();
        long incrUserId = SEQ.getAndIncrement();

        // —— 增量：逐批追加，每批后维护一次；日历是「到目前为止的全部日期」——
        List<LocalDate> cumulative = new ArrayList<>();
        for (List<Integer> batch : batches) {
            for (int offset : batch) {
                cumulative.add(BASE.plusDays(offset));
            }
            maintainInTx(incrUserId, new ArrayList<>(cumulative), NOW);
        }

        // —— 全量：把全部批展平成完整日历，一次维护 ——
        List<LocalDate> full = new ArrayList<>();
        for (List<Integer> batch : batches) {
            for (int offset : batch) {
                full.add(BASE.plusDays(offset));
            }
        }
        maintainInTx(fullUserId, full, NOW);

        // —— 终态逐项相同（起止日 / 段天数，按起始日升序）——
        assertThat(segmentsOf(incrUserId))
                .as("逐批增量维护的终态段序列必须与全量一次维护逐项相同（需求 4.9、4.10）")
                .isEqualTo(segmentsOf(fullUserId));
    }

    /** 段行的业务投影（起止日 + 段天数），按 {@code start_date} 升序，供逐项相等断言。 */
    private record Seg(LocalDate start, LocalDate end, int days) {
    }

    private List<Seg> segmentsOf(long userId) {
        return jdbcTemplate.query(
                "SELECT start_date, end_date, days FROM streak_segments WHERE user_id = ? ORDER BY start_date",
                (rs, i) -> new Seg(
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getInt("days")),
                userId);
    }
}
