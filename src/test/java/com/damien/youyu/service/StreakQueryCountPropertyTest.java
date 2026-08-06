package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 13：概览查询次数为常量上界</b>的属性测试（任务 8.4）。
 *
 * <p><i>对任意</i>段总数（{@code [0, 5000]}）× <i>任意</i>交易笔数（{@code [0, 2000]}）×
 * <i>任意</i> {@code page} / {@code size} 组合：单次连续记账概览为段与成长档案执行的读查询恒为
 * <b>3 条</b>（Q1 档案 + Q2 段聚合 + Q3 段端点，需求 7.10）、单次历史分页读查询恒<b>不超过 2 条</b>
 * （分页列表 + 总条数，需求 7.11），条数不随段总数与交易笔数增长。</p>
 *
 * <h2>计数口径：Hibernate {@link Statistics#getPrepareStatementCount()}</h2>
 * <p>本项目开启 {@code hibernate.generate_statistics} 后，{@code Statistics} 累计记录 Hibernate/JPA
 * 发出的每一条预编译语句。测量前 {@link Statistics#clear()} 归零、测量后读增量即为"这次调用发出的
 * Hibernate SQL 条数"。本类的播种（交易、段行）一律走 {@link JdbcTemplate} 原生 SQL，天然不被
 * {@code Statistics} 计入；因此计到的就只有查询组装自身的读——概览 = 1 档案 + 2 段、历史分页 =
 * 分页列表(+总条数)。</p>
 *
 * <h2>测量期必须排除结算 SQL：预热 10 秒节流窗口</h2>
 * <p>概览是写入型 GET（内含一次 {@code settle(OVERVIEW)}）。若测量时结算真实执行，它自身的读写会污染
 * 计数。故每个用户先做一次<b>预热概览</b>：预热触发的结算把该用户落入 10 秒进程内节流窗口
 * （节流器用注入的固定 {@code Clock}，时刻不推进 ⇒ 窗口恒命中），随后被测量的那次概览里
 * {@code settle(OVERVIEW)} 在读任何库之前就被判定为跳过、零 SQL。预热之后再清空段表并直插恰好
 * {@code segmentCount} 条任意段行——它们落在节流窗口内，被测量的概览不会重算覆盖它们，
 * 计数因此只反映查询组装。</p>
 *
 * <h2>历史分页为何是"≤2"而非恒 2</h2>
 * <p>Spring Data 对"首页且内容不足一页"会省去总条数查询（内容数即为总数），此时只发 1 条；段总数够
 * 填满一页时才发满 2 条（分页列表 + 总条数）。两者都不超过 2、都不随数据量增长，这正是需求 7.11 的
 * <b>上界</b>语义。故本类断言"≤2"，并按<b>被请求页实际返回的条数</b>进一步断言恰为 1 或 2：整页填满
 *（或越界空页）补发总条数查询（2 条），非空但不足一页的部分末页则省去（1 条）——不能仅凭"段总数 ≥
 * 每页条数"就断言恒 2，那会误判部分末页。</p>
 *
 * <h2>驱动方式与清理</h2>
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)} 需真实提交，故本类<b>不用测试级事务包裹</b>；
 * 清理不靠回滚，由 {@link #resetState()} 每次迭代前显式清表，并用全局自增序号 {@link #SEQ} 保证
 * {@code userId} 全局唯一（每次换新用户，预热的结算必真实执行、随后必落入节流窗口）。jqwik 属性方法
 * 不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成。</p>
 *
 * <p>Feature: streak-system, Property 13: 概览查询次数为常量上界</p>
 *
 * <p>Validates: Requirements 7.10, 7.11, 7.8, 7.9</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-querycount-pt;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 打开 Hibernate 统计，用 getPrepareStatementCount() 计数（见类级 Javadoc「计数口径」）。
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import(StreakQueryCountPropertyTest.ClockConfig.class)
class StreakQueryCountPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定时钟：判定日恒为 2025-06-15；节流器用它计窗口，时刻不推进 ⇒ 预热后概览恒被节流。 */
    private static final Instant FIXED_INSTANT = Instant.parse("2025-06-15T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZONE);

    /** 概览为段与档案执行的读查询条数（需求 7.10）：Q1 档案 + Q2 段聚合 + Q3 段端点。 */
    private static final long OVERVIEW_READS = 3L;

    /** 历史分页读查询条数上界（需求 7.11）：分页列表 + 总条数。 */
    private static final long HISTORY_READS_UPPER_BOUND = 2L;

    /** 单次批量直插的分片大小，避免一次性堆起数千个参数数组。 */
    private static final int BATCH_CHUNK = 2_000;

    /** 交易直插语句：列顺序与 {@link #txRow} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 段直插语句：列顺序与 {@link #seedSegments} 一致。 */
    private static final String INSERT_SEGMENT_SQL =
            "INSERT INTO streak_segments (user_id, start_date, end_date, days, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    /** 全局自增序号：保证跨迭代 userId / ledgerId 全局唯一（清理不靠回滚，节流器不可清理）。 */
    private static final AtomicLong SEQ = new AtomicLong(830_000_000L);

    @Autowired
    private StreakQueryService streakQueryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(StreakQueryCountPropertyTest.class).prepareTestInstance(this);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删事实源与派生表（各表间无外键）。
        jdbcTemplate.update("DELETE FROM streak_segments");
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    // ---------------- 生成器 ----------------

    /** 段总数：0 → 5000（设计文档上界，对数取样含 0 与两端）。 */
    @Provide
    Arbitrary<Integer> segmentCounts() {
        return Arbitraries.of(0, 1, 19, 20, 21, 500, 5000);
    }

    /** 交易笔数：0 → 2000（设计文档上界）。 */
    @Provide
    Arbitrary<Integer> transactionCounts() {
        return Arbitraries.of(0, 1, 100, 2000);
    }

    /** 页码：缺省(null) / 0 / 越界大页。 */
    @Provide
    Arbitrary<String> pages() {
        return Arbitraries.of(null, "0", "1", "100000");
    }

    /** 每页条数：缺省(null) / 边界。 */
    @Provide
    Arbitrary<String> sizes() {
        return Arbitraries.of(null, "1", "20", "50");
    }

    // ---------------- Property 13 ----------------

    /**
     * Feature: streak-system, Property 13: 概览查询次数为常量上界
     *
     * <p>任意段总数 × 交易笔数 × 分页组合下：概览读 SQL 恒 3 条、历史分页读 SQL ≤2 条，且不随数据量增长
     * （需求 7.10、7.11）。</p>
     *
     * <p>{@code generation = RANDOMIZED}：四个维度合起来 7 × 4 × 4 × 4 = 448 种组合落在 jqwik 穷举阈值
     * 之内，默认会穷举跑满——每次都要清表、批量直插并真实结算，会把耗时推到数分钟。随机取样 20 次已足以
     * 覆盖各维度的多个数量级，网格顶角另由 {@link #maxScaleCorner()} 必跑。</p>
     *
     * <p>Validates: Requirements 7.10, 7.11, 7.8, 7.9</p>
     */
    @Property(tries = 20, generation = GenerationMode.RANDOMIZED)
    void property13_overviewReadsThree_historyReadsAtMostTwo_regardlessOfScale(
            @ForAll("segmentCounts") int segmentCount,
            @ForAll("transactionCounts") int txCount,
            @ForAll("pages") String rawPage,
            @ForAll("sizes") String rawSize) {
        assertConstantQueryCount(segmentCount, txCount, rawPage, rawSize);
    }

    /**
     * 取样网格的<b>顶角</b>必跑用例：5000 段 × 2000 笔 × 首页 × 满页。
     *
     * <p>随机取样可能整轮都不落在顶角，而顶角恰是最容易暴露 N+1 / 懒加载的那一点，故单列一个
     * {@code @Example} 钉死它。</p>
     *
     * <p>Validates: Requirements 7.10, 7.11, 7.8, 7.9</p>
     */
    @Example
    void maxScaleCorner() {
        assertConstantQueryCount(5000, 2000, "0", "20");
    }

    /**
     * 播种给定规模 → 预热使概览结算落入节流窗口 → 直插恰好 {@code segmentCount} 条段 →
     * 分别测量概览与历史分页的 Hibernate 读 SQL 条数并断言。
     */
    private void assertConstantQueryCount(int segmentCount, int txCount, String rawPage, String rawSize) {
        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();
        LocalDate yesterday = LocalDate.now(FIXED_CLOCK).minusDays(1);

        if (txCount > 0) {
            seedExpenses(userId, ledgerId, yesterday, txCount);
        }

        // 预热：概览触发一次真实结算，随后该用户在 10 秒窗口内被节流（固定时钟 ⇒ 恒命中）。
        streakQueryService.getOverview(userId);

        // 预热结算可能已按日历建了 1 段；清空后直插恰好 segmentCount 条任意段行（计数不校验其与日历一致）。
        jdbcTemplate.update("DELETE FROM streak_segments WHERE user_id = ?", userId);
        if (segmentCount > 0) {
            seedSegments(userId, segmentCount);
        }

        String scale = String.format("段总数 %d / 交易 %d / page=%s / size=%s",
                segmentCount, txCount, rawPage, rawSize);

        // —— 概览：为段与档案执行的读 SQL 恒 3 条（1 档案 + 2 段），结算被节流跳过、零 SQL ——
        statistics.clear();
        streakQueryService.getOverview(userId);
        long overviewReads = statistics.getPrepareStatementCount();
        assertThat(overviewReads)
                .as("%s：单次概览为段与档案执行的读 SQL 恒为 3 条（需求 7.10）", scale)
                .isEqualTo(OVERVIEW_READS);

        // —— 历史分页：读 SQL ≤2 条（分页列表 + 总条数），不随数据量增长 ——
        int effectiveSize = (rawSize == null || rawSize.isBlank()) ? 20 : Integer.parseInt(rawSize.trim());
        int effectivePage = (rawPage == null || rawPage.isBlank()) ? 0 : Integer.parseInt(rawPage.trim());
        statistics.clear();
        streakQueryService.listSegments(userId, rawPage, rawSize);
        long historyReads = statistics.getPrepareStatementCount();
        assertThat(historyReads)
                .as("%s：单次历史分页读 SQL 不超过 2 条（分页列表 + 总条数，需求 7.11）", scale)
                .isBetween(1L, HISTORY_READS_UPPER_BOUND);

        // Spring Data 的 PageableExecutionUtils 何时省去总条数查询（据此断言恰为 1 或 2 条）：
        //   · 首页（offset==0）：内容不足一页时省去（1 条），恰好填满一页时补发（2 条）；
        //   · 非首页（offset>0）：非空但不足一页的「部分末页」省去（1 条）；整页填满、或越界空页则补发（2 条）。
        // 关键：以「被请求页实际返回的条数」判断，而非「段总数 ≥ 每页条数」——后者会误判部分末页
        //（如 21 段、每页 20、第 2 页只余 1 条）为「填满一页」，实则被 Spring Data 省去了总条数查询。
        long offset = (long) effectivePage * effectiveSize;
        long contentOnPage = Math.max(0L, Math.min((long) segmentCount - offset, effectiveSize));
        boolean countQueryFires = (offset == 0)
                ? (contentOnPage == effectiveSize)
                : (contentOnPage == 0 || contentOnPage == effectiveSize);
        long expectedHistoryReads = countQueryFires ? HISTORY_READS_UPPER_BOUND : 1L;
        assertThat(historyReads)
                .as("%s：单次历史分页读 SQL 恰为 %d 条（%s）", scale, expectedHistoryReads,
                        countQueryFires ? "整页填满/越界空页，补发总条数查询" : "部分页，省去总条数查询")
                .isEqualTo(expectedHistoryReads);
    }

    // ---------------- 事实源播种 ----------------

    /** 同一记账日上批量直插 {@code count} 笔 {@code 1.00} 支出。 */
    private void seedExpenses(long userId, long ledgerId, LocalDate day, int count) {
        List<Object[]> chunk = new ArrayList<>(Math.min(count, BATCH_CHUNK));
        for (int i = 0; i < count; i++) {
            chunk.add(txRow(userId, ledgerId, day));
            if (chunk.size() == BATCH_CHUNK) {
                jdbcTemplate.batchUpdate(INSERT_TX_SQL, chunk);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_TX_SQL, chunk);
        }
    }

    /** 直插 {@code n} 条起止日相同、两两不相邻的任意段行（仅查询计数用；不校验其与日历一致）。 */
    private void seedSegments(long userId, int n) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().withNano(0));
        LocalDate base = LocalDate.of(2000, 1, 1);
        List<Object[]> chunk = new ArrayList<>(Math.min(n, BATCH_CHUNK));
        for (int i = 0; i < n; i++) {
            LocalDate d = base.plusDays(i * 2L);        // 两两间隔 1 天，起始日互不相同
            chunk.add(new Object[] {userId, Date.valueOf(d), Date.valueOf(d), 1, now, now});
            if (chunk.size() == BATCH_CHUNK) {
                jdbcTemplate.batchUpdate(INSERT_SEGMENT_SQL, chunk);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_SEGMENT_SQL, chunk);
        }
    }

    /** 一条"有效记账交易"的参数行：记账日由 {@code recordDay}（即 {@code created_at}）决定。 */
    private static Object[] txRow(long userId, long ledgerId, LocalDate recordDay) {
        Timestamp createdAt = Timestamp.valueOf(recordDay.atTime(12, 0));
        return new Object[] {userId, ledgerId, userId, "expense", new BigDecimal("1.00"),
                ref(userId), ref(userId), createdAt, createdAt, createdAt};
    }

    /** "绝不可能是真实主键"且按用户隔离的 {@code account_id} / {@code category_id} 占位取值。 */
    private static long ref(long userId) {
        return 900_000_000L + userId;
    }

    // ---------------- 测试基础设施 ----------------

    /** {@code @Primary} 固定时钟（Asia/Shanghai），使概览侧 10 秒节流窗口在预热后恒命中。 */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return FIXED_CLOCK;
        }
    }
}
