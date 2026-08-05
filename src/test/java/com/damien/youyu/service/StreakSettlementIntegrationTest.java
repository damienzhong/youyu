package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.repository.StreakSegmentRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 连续记账结算集成测试（任务 7.2，需求 4.6、4.7、4.8、5.1、5.2、7.3、7.4、7.10、7.11、7.12、7.13）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：注入真实的 {@link GrowthSettlementService}、
 * {@link StreakQueryService} 与其全部协作者，对真实 H2（{@code MODE=MySQL}）读写。段维护寄生在
 * {@code GrowthSettlementService.recalculateAndWriteBack} 的末位，随结算的 {@code REQUIRES_NEW}
 * 事务一起提交，因此本类<b>不用测试级 {@code @Transactional} 包裹</b>——只有让结算真正提交，才能在库里
 * 观察到段的终态；每个用例都是「直插事实源 → 调 {@code settle} → 从库读回断言」，并在
 * {@link #cleanup()} 里硬删相关表。</p>
 *
 * <h2>段行为用例一律用「过去的记账日 + 直接调 settle」，从而不被记账侧 60 秒节流跳过</h2>
 *
 * <p>记账侧节流的两个条件缺一不可（{@code last_settled_at} 距今 &lt;60s <b>且</b>
 * {@code last_record_date == 结算日}）。本类把事实源的记账日一律放在<b>过去</b>（今天往前数几天），
 * 于是 {@code last_record_date != 今天} 恒成立，第二个条件永不满足，连续多次 {@code settle} 全部真实执行。
 * 这样「跨日延长 / 跳日新段 / 同日多笔 / 删除交易」四组段行为就能用确定的多次结算逐一断言，
 * 无需注入可推进时钟（沿用 {@code AchievementSettlementIntegrationTest} 的同一取舍）。</p>
 *
 * <h2>查询计数：Hibernate {@link StreakReadInspector}（只看 Hibernate/JPA 发出的 SQL）</h2>
 *
 * <p>概览请求「为段与档案执行的读 SQL 恒为 3 条」（需求 7.10）、历史分页「恒为 2 条」（需求 7.11）由
 * {@link StreakReadInspector} 锁死：它按表名片段分别计数 {@code from streak_segments}（段读）与
 * {@code from user_growth}（档案读）。计数前先让概览触发的结算落入 10 秒节流窗口——测量时结算被跳过、
 * 零 SQL，于是计到的就只有查询组装自身的读：概览 = 1 档案 + 2 段（聚合 + 端点），历史分页 = 2 段
 * （分页列表 + 总条数）。段维护写段走 {@code JdbcTemplate}、本类播种也走 {@code JdbcTemplate} 原生 SQL，
 * 天然都不被 {@code StatementInspector} 计入。段总数 0→5000、交易笔数 0→2000 变化时条数恒定。</p>
 *
 * <h2>故障注入：{@code @Primary} 代理包住 {@link StreakSegmentRepository}</h2>
 *
 * <p>{@link FaultConfig} 用一个 {@code @Primary} 的 JDK 动态代理包住真实段仓储，默认透明委托，
 * 仅当 {@link #SEGMENT_FAULT} 置位时让 {@code findByUserIdOrderByStartDateAsc}（段维护对账读的那一条，
 * 需求 4.4 的「已知偏差①」）抛异常。<b>刻意不用 Mockito 对结算服务做 spy</b>——对带
 * {@code @Transactional} 的类做 spy 会绕过 Spring 的事务代理、令 {@code REQUIRES_NEW} 失效，
 * 而「整体回滚」正是本组断言要验的东西；把故障下沉到段仓储则结算仍走真实事务代理。异常从段维护穿出、
 * 结算事务回滚，与生产路径逐条一致。</p>
 *
 * <p><b>WARN 标签实测为 {@code [GROWTH_SETTLE_FAILED]}</b>：段维护失败时 {@link StreakSegmentMaintainer}
 * 自身刻意不 catch（异常必须穿出才能回滚 {@code REQUIRES_NEW}），真正记 WARN 的是事务边界之外的
 * {@link GrowthSettlementTrigger}（记账 {@code afterCommit} 路径）与两个 QueryService（概览路径）。
 * 记账 {@code afterCommit} 触发的结算由 {@code GrowthSettlementTrigger.settleQuietly} 吞异常并记
 * {@code [GROWTH_SETTLE_FAILED]}，故本类断言的正是这个标签，而非 {@code StreakSegmentMaintainer} 内的
 * {@code [STREAK_MAINTAIN_SLOW]} / {@code [STREAK_SEGMENT_REPAIRED]}。</p>
 *
 * <p>使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Validates: Requirements 4.6, 4.7, 4.8, 5.1, 5.2, 7.3, 7.4, 7.10, 7.11, 7.12, 7.13</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-settle-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 计数型装饰器：只对段与档案的读 SQL 计数（见类级 Javadoc「查询计数」）。
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.damien.youyu.service.StreakSettlementIntegrationTest$StreakReadInspector"
})
@Import(StreakSettlementIntegrationTest.FaultConfig.class)
class StreakSettlementIntegrationTest {

    /** 记账接口响应字段集（需求 7.4：段维护失败时与成功时逐项相同的那一份）。 */
    private static final Set<String> RECORD_KEYS = Set.of(
            "id", "ledgerId", "createdBy", "type", "amount", "accountId", "categoryId",
            "sourceAccountId", "destinationAccountId", "occurredAt", "note",
            "projectId", "merchantId", "tagIds");

    /**
     * 记账响应里绝不允许出现的连续记账字段名（需求 7.4：响应不含任何连续记账字段）。
     *
     * <p>取自 {@link StreakOverviewResponse} 的 14 个分量名与「streak / segment / milestone」这三个域词根，
     * 按<b>原始 JSON 文本</b>比对（嵌套一层的泄漏不会改变顶层键集合）。这些词根均不与
     * {@link #RECORD_KEYS} 的任一键冲突（记账字段里没有含 streak / segment / milestone / broken /
     * todayDone 的键），故断言不是恒真。</p>
     */
    private static final List<String> RECORD_FORBIDDEN_MARKERS = List.of(
            "streak", "Streak", "segment", "Segment", "milestone", "Milestone",
            "todayDone", "broken", "currentStreakDays", "maxStreakDays", "nextMilestone");

    /** 交易直插语句：列顺序与 {@link #txRow} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 段直插语句：仅查询计数用例批量播种任意段行时用（列顺序与 {@link #seedSegments} 一致）。 */
    private static final String INSERT_SEGMENT_SQL =
            "INSERT INTO streak_segments (user_id, start_date, end_date, days, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    /** 置位后让段仓储的对账读 {@code findByUserIdOrderByStartDateAsc} 抛异常（见 {@link FaultConfig}）。 */
    private static final AtomicBoolean SEGMENT_FAULT = new AtomicBoolean(false);

    @LocalServerPort
    private int port;

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private StreakQueryService streakQueryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanup() {
        SEGMENT_FAULT.set(false);
        StreakReadInspector.reset();
        // 结算真实提交，清理不能靠事务回滚：每个用例前硬删事实源与派生表（各表间无强约束顺序）。
        jdbcTemplate.update("DELETE FROM streak_segments");
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM invite_relations");
        jdbcTemplate.update("DELETE FROM budgets");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM ledger_members");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM categories");
    }

    // ============ 1) 记账 → 结算 → 段行落库（需求 4.6、5.1）============

    /**
     * 首次记账并结算 → 落一条起止日均为该记账日、段天数 1 的段行（需求 4.7 首段建立、5.1）。
     */
    @Test
    void firstRecordThenSettle_writesSingleDaySegment() {
        long userId = 710_001L;
        long ledgerId = 910_001L;
        LocalDate d1 = LocalDate.now().minusDays(5);

        seedTransaction(userId, ledgerId, "expense", "1.00", d1);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);

        List<Seg> segments = segmentsOf(userId);
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).start()).isEqualTo(d1);
        assertThat(segments.get(0).end()).isEqualTo(d1);
        assertThat(segments.get(0).days()).isEqualTo(1);
    }

    // ============ 2) 跨日再记账 → 尾段延长（需求 4.6）============

    /**
     * 在最近记账日的次日再记账并结算 → 尾段的 {@code end_date}/{@code days} 更新、
     * {@code start_date}/{@code created_at} 不变、段总数仍为 1、写入行数为 1（需求 4.6）。
     */
    @Test
    void crossDayRecord_extendsTailSegment_keepsStartAndCreatedAt() {
        long userId = 720_001L;
        long ledgerId = 920_001L;
        LocalDate d1 = LocalDate.now().minusDays(5);
        LocalDate d2 = d1.plusDays(1);

        seedTransaction(userId, ledgerId, "expense", "1.00", d1);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);
        Seg before = segmentsOf(userId).get(0);

        seedTransaction(userId, ledgerId, "expense", "1.00", d2);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);

        List<Seg> after = segmentsOf(userId);
        assertThat(after).as("尾段延长不新增段行，段总数仍为 1").hasSize(1);
        Seg tail = after.get(0);
        assertThat(tail.end()).as("end_date 更新为次日").isEqualTo(d2);
        assertThat(tail.days()).as("days 加 1").isEqualTo(2);
        assertThat(tail.start()).as("start_date 不变").isEqualTo(d1);
        assertThat(tail.createdAt()).as("created_at 不变（ODKU 冲突转更新不动 created_at）")
                .isEqualTo(before.createdAt());
    }

    // ============ 3) 跳一天再记账 → 新段 + 旧段全列不变、段总数 +1（需求 4.7、5.1）============

    /**
     * 中断一天后再记账并结算 → 插入一条 {@code days=1} 的新段、旧段全列不变、段总数 +1（需求 4.7、5.1）。
     */
    @Test
    void skipDayRecord_startsNewSegment_keepsOldSegmentIntact_countPlusOne() {
        long userId = 730_001L;
        long ledgerId = 930_001L;
        LocalDate d1 = LocalDate.now().minusDays(5);
        LocalDate d3 = d1.plusDays(2);          // 跳过 d1+1

        seedTransaction(userId, ledgerId, "expense", "1.00", d1);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);
        Seg oldSeg = segmentsOf(userId).get(0);

        seedTransaction(userId, ledgerId, "expense", "1.00", d3);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);

        List<Seg> after = segmentsOf(userId);       // 按 start_date 升序
        assertThat(after).as("段总数由 1 增至 2").hasSize(2);
        assertThat(after.get(0)).as("旧段全列不变（起止日、段天数、created_at、updated_at）")
                .isEqualTo(oldSeg);
        Seg fresh = after.get(1);
        assertThat(fresh.start()).isEqualTo(d3);
        assertThat(fresh.end()).isEqualTo(d3);
        assertThat(fresh.days()).isEqualTo(1);
    }

    // ============ 4) 同日多笔 → 段不变、零写入（需求 4.8）============

    /**
     * 同一记账日再记一笔并结算 → 记账日历未新增日期，段一行不改、零写入（需求 4.8）。
     */
    @Test
    void sameDayMultipleRecords_leavesSegmentsUnchanged() {
        long userId = 740_001L;
        long ledgerId = 940_001L;
        LocalDate d1 = LocalDate.now().minusDays(5);

        seedTransaction(userId, ledgerId, "expense", "1.00", d1);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);
        List<Seg> before = segmentsOf(userId);

        // 同一自然日第二笔：日历不新增日期 → 段维护 diff 为空 → 段行（含 updated_at）逐列不变。
        seedTransaction(userId, ledgerId, "expense", "2.00", d1);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);

        assertThat(segmentsOf(userId)).as("同日多笔不改变段序列（含 updated_at）").isEqualTo(before);
    }

    // ============ 5) 删除交易 → 段不变（记账日历只追加，需求 4.8）============

    /**
     * 删除已计入记账日历的交易后再结算 → 段一行不改（记账日历只追加不删除，需求 4.8）。
     */
    @Test
    void deleteTransaction_leavesSegmentsUnchanged() {
        long userId = 750_001L;
        long ledgerId = 950_001L;
        LocalDate d1 = LocalDate.now().minusDays(5);

        seedTransaction(userId, ledgerId, "expense", "1.00", d1);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);
        List<Seg> before = segmentsOf(userId);

        // 软删该用户在 d1 的全部有效交易：记账日历（growth_events 的 DAILY_RECORD）不因此删除该日。
        jdbcTemplate.update("UPDATE transactions SET deleted_at = ? WHERE created_by = ?",
                Timestamp.valueOf(LocalDateTime.now().withNano(0)), userId);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);

        assertThat(segmentsOf(userId)).as("删除交易不改变段序列（日历只追加）").isEqualTo(before);
    }

    // ============ 6) 概览读 SQL 恒 3 条 / 历史分页恒 2 条（需求 7.10、7.11）============

    /**
     * 单次连续记账概览为段与档案执行的读 SQL 恒为 <b>3 条</b>（1 档案 + 2 段：聚合 + 端点，需求 7.10）、
     * 单次历史分页恒为 <b>2 条</b>（分页列表 + 总条数，需求 7.11），且条数不随段总数（0 → 5000）与
     * 交易笔数（0 → 2000）增长。
     *
     * <p>先用一次概览把结算落入 10 秒节流窗口，再直插任意段行；测量时结算被节流跳过、零 SQL，
     * 计到的就只有查询组装自身的读。段行在概览节流窗口内直插，段维护不会在测量期重算覆盖它们。</p>
     */
    @ParameterizedTest(name = "段总数 {0} / 交易笔数 {1}")
    @CsvSource({"0, 0", "5000, 2000"})
    void overviewReadsThree_segmentsReadsTwo_regardlessOfScale(int segmentCount, int txCount) {
        long userId = 760_000L + segmentCount;
        long ledgerId = 960_000L + segmentCount;
        LocalDate yesterday = LocalDate.now().minusDays(1);

        if (txCount > 0) {
            seedExpenses(userId, ledgerId, yesterday, txCount);
        }

        // 预热：概览触发一次真实结算，随后 10 秒内该用户概览被节流（零 SQL）。
        streakQueryService.getOverview(userId);

        // 预热结算可能已按日历建了 1 段；清空后直插恰好 segmentCount 条任意段行（计数不校验其与日历一致）。
        jdbcTemplate.update("DELETE FROM streak_segments WHERE user_id = ?", userId);
        if (segmentCount > 0) {
            seedSegments(userId, segmentCount);
        }

        // —— 概览：3 条读（1 档案 + 2 段）——
        StreakReadInspector.reset();
        streakQueryService.getOverview(userId);
        assertThat(StreakReadInspector.profileReads())
                .as("概览读成长档案恒 1 条；已命中读 SQL=%s", StreakReadInspector.matched()).isEqualTo(1);
        assertThat(StreakReadInspector.segmentReads())
                .as("概览读段恒 2 条（聚合 + 端点）；已命中读 SQL=%s", StreakReadInspector.matched()).isEqualTo(2);
        assertThat(StreakReadInspector.total())
                .as("单次概览为段与档案执行的读 SQL 恒为 3 条（需求 7.10）").isEqualTo(3);

        // —— 历史分页：读 SQL 不超过 2 条（分页列表 + 总条数），不读档案（需求 7.11 是上界）——
        // 段总数为 0 时 Spring Data 省去总条数查询（首页且内容不足一页 ⇒ 总数即内容数），故只发 1 条；
        // 段总数够填满一页时（5000）发满 2 条。两者都不超过 2，且都不随段总数与交易笔数增长。
        StreakReadInspector.reset();
        streakQueryService.listSegments(userId, "0", "20");
        assertThat(StreakReadInspector.profileReads())
                .as("历史分页不读成长档案").isZero();
        assertThat(StreakReadInspector.segmentReads())
                .as("历史分页读段不超过 2 条（分页列表 + 总条数）；已命中读 SQL=%s", StreakReadInspector.matched())
                .isBetween(1, 2);
        assertThat(StreakReadInspector.total())
                .as("单次历史分页读 SQL 不超过 2 条（需求 7.11）").isLessThanOrEqualTo(2);
        // 段总数够填满一页（5000）时确应发满 2 条：分页列表 + 总条数。
        if (segmentCount >= 20) {
            assertThat(StreakReadInspector.total())
                    .as("段总数填满一页时读满 2 条（分页列表 + 总条数）").isEqualTo(2);
        }
    }

    // ============ 7) 段维护失败：整体回滚、记账不受影响、WARN、下次自愈（需求 7.3、7.4、5.2）============

    /**
     * 记账 {@code afterCommit} 触发的结算里段维护失败 → 本次结算 {@code REQUIRES_NEW} 事务整体回滚、
     * 段表无部分写入、退回本次结算前状态、记一条 {@code [GROWTH_SETTLE_FAILED]} WARN、
     * 记账接口状态码与响应字段集与成功时相同且不含任何连续记账字段、随后的一次结算把段补齐（需求 7.3、7.4、5.2）。
     *
     * <p>本用例走真实 HTTP 的记账接口（真实过滤链 + JWT + {@code afterCommit} 触发的结算），
     * 因为「记账接口状态码与响应字段集不变」只能在接口层观察。先用一次直插 + 直接结算落一个已提交的
     * 「本次结算前状态」（{@code yesterday} 的段），再让今天的记账在段维护处失败——从而能断言「退回到
     * yesterday 的段而非空表」。</p>
     */
    @Test
    void segmentMaintainFault_viaRecordApi_rollsBack_keepsRecordIntact_logsWarn_andHeals() throws Exception {
        String email = "streak_settle_fault@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        long accountId = createAccount(token, "现金", "CASH", "1000.00");
        long categoryId = createCategory(token, "EXPENSE", "餐饮");
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        // 已提交的「本次结算前状态」：昨天一段（yesterday, yesterday, 1）。直插记账日昨天，直接结算。
        seedTransaction(userId, 990_001L, "expense", "1.00", yesterday);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);
        List<Seg> preFaultSegments = segmentsOf(userId);
        assertThat(preFaultSegments).as("结算前已有昨天一段").hasSize(1);
        long growthEventsBefore = growthEventCount(userId);
        LocalDate lastRecordBefore = lastRecordDate(userId);
        assertThat(lastRecordBefore).isEqualTo(yesterday);

        Logger triggerLogger = (Logger) LoggerFactory.getLogger(GrowthSettlementTrigger.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        triggerLogger.addAppender(logAppender);
        try {
            SEGMENT_FAULT.set(true);

            ResponseEntity<String> faulted = postRecord(token, "50.00", accountId, categoryId);

            // 记账接口：状态码与字段集与段维护成功时相同，且响应不含任何连续记账字段（需求 7.4）。
            assertThat(faulted.getStatusCode()).as("段维护失败不改变记账状态码（需求 7.4）")
                    .isEqualTo(HttpStatus.CREATED);
            Map<String, Object> faultedBody = parse(faulted.getBody());
            assertThat(faultedBody.keySet()).as("段维护失败不改变记账响应字段集（需求 7.4）")
                    .containsExactlyInAnyOrderElementsOf(RECORD_KEYS);
            for (String marker : RECORD_FORBIDDEN_MARKERS) {
                assertThat(faulted.getBody()).as("记账响应不含连续记账字段：" + marker).doesNotContain(marker);
            }

            // 记账本身已提交，本次结算整体回滚：退回本次结算前状态（需求 7.3、5.2）。
            assertThat(validRecordCount(userId)).as("记账结果已提交，不受段维护失败影响").isEqualTo(2L);
            assertThat(growthEventCount(userId)).as("growth_events 退回本次结算前（今天的 DAILY_RECORD 被回滚）")
                    .isEqualTo(growthEventsBefore);
            assertThat(lastRecordDate(userId)).as("user_growth.last_record_date 退回昨天")
                    .isEqualTo(lastRecordBefore);
            assertThat(segmentsOf(userId)).as("段表无部分写入，退回本次结算前状态（需求 7.3）")
                    .isEqualTo(preFaultSegments);

            // 一条 [GROWTH_SETTLE_FAILED] WARN，含用户 id、不含金额 / 邮箱（需求 7.3）。
            List<ILoggingEvent> warns = logAppender.list.stream()
                    .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .toList();
            assertThat(warns).as("段维护失败记一条 WARN").anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("[GROWTH_SETTLE_FAILED]");
                assertThat(event.getFormattedMessage()).contains("userId=" + userId);
                assertThat(event.getFormattedMessage()).doesNotContain(email).doesNotContain("50.00");
            });

            // 故障解除后再次触发结算：把昨天+今天两天的段补齐为一段（yesterday, today, 2）（需求 5.2）。
            SEGMENT_FAULT.set(false);
            ResponseEntity<String> healed = postRecord(token, "60.00", accountId, categoryId);

            assertThat(healed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(parse(healed.getBody()).keySet())
                    .as("成功与失败两次记账的响应字段集逐项相同（需求 7.4）")
                    .containsExactlyInAnyOrderElementsOf(faultedBody.keySet());
            List<Seg> healedSegments = segmentsOf(userId);
            assertThat(healedSegments).as("自愈后仍为一段").hasSize(1);
            assertThat(healedSegments.get(0).start()).isEqualTo(yesterday);
            assertThat(healedSegments.get(0).end()).isEqualTo(today);
            assertThat(healedSegments.get(0).days()).isEqualTo(2);
        } finally {
            triggerLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    // ============ 8) 段维护失败：六表任何行不变（需求 7.12、7.13）============

    /**
     * 段维护失败导致的结算整体回滚，不改动 {@code transactions} / {@code budgets} / {@code ledgers} /
     * {@code ledger_members} / {@code invite_relations} / {@code achievement_notices} 六表的任何行
     * （需求 7.13），且 {@code growth_events} / {@code user_growth} 只被读取、退回结算前状态（需求 7.12、7.3）。
     *
     * <p>直接调 {@code settle}（非 HTTP）从而完全掌控这六表的内容：预置一批已提交的行 → 段维护失败让
     * {@code settle} 抛出、{@code REQUIRES_NEW} 回滚 → 逐表快照比对回滚前后逐行相同。段维护只对
     * {@code streak_segments} 落 DML，这六表的任何写入都不会来自连续记账系统；即便结算的成长/成就写入
     * 也随整体回滚一并撤销，故预置行保持不变。</p>
     */
    @Test
    void segmentMaintainFault_viaDirectSettle_leavesSixTablesUnchanged() {
        long userId = 780_001L;
        long ledgerId = 980_001L;
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate today = LocalDate.now();

        // 已提交的结算前状态：昨天一段。
        seedTransaction(userId, ledgerId, "expense", "1.00", yesterday);
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);
        List<Seg> preFaultSegments = segmentsOf(userId);
        assertThat(preFaultSegments).hasSize(1);

        // 预置六表中除 transactions 外五表的已提交行（budgets 有指向 users 的外键，本用例不预置，断言其恒 0）。
        seedLedger(userId, ledgerId, "自有账本");
        seedLedgerMember(ledgerId, userId + 1);
        seedInviteRelation(userId + 2, userId);
        seedAchievementNotice(userId);

        // 今天再记一笔（使段维护本应有活干）。这一笔是「业务」直插、独立提交，不属于随后失败的结算事务；
        // 因此六表快照在它落库<b>之后</b>才取——回滚只应撤销结算的成长/成就写入，绝不回滚这笔已提交的交易。
        seedTransaction(userId, ledgerId, "expense", "2.00", today);

        // 逐表快照（结算前，含刚落库的今日交易）。
        List<Map<String, Object>> txBefore = snapshot("transactions", userId);
        List<Map<String, Object>> budgetsBefore = snapshot("budgets", userId);
        List<Map<String, Object>> ledgersBefore = snapshot("ledgers", userId);
        List<Map<String, Object>> membersBefore = rowsOf("SELECT * FROM ledger_members WHERE ledger_id = ? ORDER BY id", ledgerId);
        List<Map<String, Object>> invitesBefore = rowsOf("SELECT * FROM invite_relations WHERE invitee_id = ? ORDER BY invite_id", userId);
        List<Map<String, Object>> noticesBefore = snapshot("achievement_notices", userId);
        long growthEventsBefore = growthEventCount(userId);
        LocalDate lastRecordBefore = lastRecordDate(userId);

        // 段维护失败 → settle 抛出、整体回滚。
        SEGMENT_FAULT.set(true);
        assertThatThrownBy(() -> settlementService.settle(userId, TriggerSource.RECORD))
                .as("段维护异常必须穿出以回滚 REQUIRES_NEW（需求 7.3）")
                .isInstanceOf(RuntimeException.class);
        SEGMENT_FAULT.set(false);

        // 六表逐行不变（需求 7.13）。
        assertThat(snapshot("transactions", userId)).as("transactions 任何行不变").isEqualTo(txBefore);
        assertThat(snapshot("budgets", userId)).as("budgets 任何行不变").isEqualTo(budgetsBefore);
        assertThat(snapshot("ledgers", userId)).as("ledgers 任何行不变").isEqualTo(ledgersBefore);
        assertThat(rowsOf("SELECT * FROM ledger_members WHERE ledger_id = ? ORDER BY id", ledgerId))
                .as("ledger_members 任何行不变").isEqualTo(membersBefore);
        assertThat(rowsOf("SELECT * FROM invite_relations WHERE invitee_id = ? ORDER BY invite_id", userId))
                .as("invite_relations 任何行不变").isEqualTo(invitesBefore);
        assertThat(snapshot("achievement_notices", userId)).as("achievement_notices 任何行不变").isEqualTo(noticesBefore);

        // growth_events / user_growth 退回结算前状态（需求 7.12、7.3）；段表无部分写入。
        assertThat(growthEventCount(userId)).as("growth_events 退回结算前").isEqualTo(growthEventsBefore);
        assertThat(lastRecordDate(userId)).as("user_growth.last_record_date 退回昨天").isEqualTo(lastRecordBefore);
        assertThat(segmentsOf(userId)).as("段表无部分写入").isEqualTo(preFaultSegments);

        // 再次结算补齐（需求 5.2）：昨天+今天连成一段。
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);
        List<Seg> healed = segmentsOf(userId);
        assertThat(healed).hasSize(1);
        assertThat(healed.get(0).start()).isEqualTo(yesterday);
        assertThat(healed.get(0).end()).isEqualTo(today);
        assertThat(healed.get(0).days()).isEqualTo(2);
    }

    // ---------------------------------- 库读取辅助 ----------------------------------

    /** 段行的不可变投影（起止日、段天数、创建/更新时刻），供逐列相等断言。 */
    private record Seg(LocalDate start, LocalDate end, int days, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 该用户全部段行，按 {@code start_date} 升序。 */
    private List<Seg> segmentsOf(long userId) {
        return jdbcTemplate.query(
                "SELECT start_date, end_date, days, created_at, updated_at "
                        + "FROM streak_segments WHERE user_id = ? ORDER BY start_date",
                (rs, i) -> new Seg(
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getInt("days"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                userId);
    }

    private long growthEventCount(long userId) {
        return count("SELECT COUNT(*) FROM growth_events WHERE user_id = ?", userId);
    }

    private LocalDate lastRecordDate(long userId) {
        List<LocalDate> dates = jdbcTemplate.query(
                "SELECT last_record_date FROM user_growth WHERE user_id = ?",
                (rs, i) -> rs.getObject("last_record_date", LocalDate.class), userId);
        return dates.isEmpty() ? null : dates.get(0);
    }

    private long validRecordCount(long userId) {
        return count("SELECT COUNT(*) FROM transactions WHERE created_by = ? AND deleted_at IS NULL "
                + "AND type IN ('expense','income') AND ledger_id IS NOT NULL", userId);
    }

    private List<Map<String, Object>> snapshot(String table, long userId) {
        return jdbcTemplate.queryForList("SELECT * FROM " + table + " WHERE user_id = ? ORDER BY 1", userId);
    }

    private List<Map<String, Object>> rowsOf(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    // ---------------------------------- 数据播种辅助 ----------------------------------

    /**
     * 「绝不可能是真实主键」且按用户隔离的 {@code account_id} / {@code category_id} 占位取值。
     */
    private static long ref(long userId) {
        return 900_000_000L + userId;
    }

    /** 一条「有效记账交易」的参数行：记账日由 {@code recordDay}（即 {@code created_at}）决定。 */
    private static Object[] txRow(long userId, long ledgerId, String type, String amount, LocalDate recordDay) {
        Timestamp createdAt = Timestamp.valueOf(recordDay.atTime(12, 0));
        return new Object[] {userId, ledgerId, userId, type, new BigDecimal(amount),
                ref(userId), ref(userId), createdAt, createdAt, createdAt};
    }

    private void seedTransaction(long userId, long ledgerId, String type, String amount, LocalDate recordDay) {
        jdbcTemplate.update(INSERT_TX_SQL, txRow(userId, ledgerId, type, amount, recordDay));
    }

    /** 同一记账日上批量直插 {@code count} 笔 {@code 1.00} 支出。 */
    private void seedExpenses(long userId, long ledgerId, LocalDate day, int count) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(txRow(userId, ledgerId, "expense", "1.00", day));
        }
        jdbcTemplate.batchUpdate(INSERT_TX_SQL, batch);
    }

    /** 直插 {@code n} 条起止日相同、两两不相邻的任意段行（仅查询计数用例用；不校验其与日历一致）。 */
    private void seedSegments(long userId, int n) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().withNano(0));
        LocalDate base = LocalDate.of(2000, 1, 1);
        List<Object[]> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LocalDate d = base.plusDays(i * 2L);        // 两两间隔 1 天，起始日互不相同
            batch.add(new Object[] {userId, Date.valueOf(d), Date.valueOf(d), 1, now, now});
        }
        jdbcTemplate.batchUpdate(INSERT_SEGMENT_SQL, batch);
    }

    private void seedLedger(long userId, long ledgerId, String name) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().withNano(0));
        jdbcTemplate.update("INSERT INTO ledgers "
                        + "(id, user_id, name, type, sort_order, is_default, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PERSONAL', 0, FALSE, ?, ?)",
                ledgerId, userId, name, now, now);
    }

    private void seedLedgerMember(long ledgerId, long memberUserId) {
        jdbcTemplate.update("INSERT INTO ledger_members (ledger_id, user_id, role, created_at) "
                        + "VALUES (?, ?, 'EDITOR', ?)",
                ledgerId, memberUserId, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    private void seedInviteRelation(long inviterId, long inviteeId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().withNano(0));
        jdbcTemplate.update("INSERT INTO invite_relations "
                        + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'REGISTERED', ?, ?)",
                inviterId, inviteeId, now, now, now);
    }

    private void seedAchievementNotice(long userId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().withNano(0));
        jdbcTemplate.update("INSERT INTO achievement_notices "
                        + "(user_id, last_notified_event_id, created_at, updated_at) VALUES (?, 0, ?, ?)",
                userId, now, now);
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String rawJson) throws Exception {
        return objectMapper.readValue(rawJson, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    /** 记账：取<b>原始 JSON 文本</b>，因为「不含连续记账字段」只能按文本比对。 */
    private ResponseEntity<String> postRecord(String token, String amount, long accountId, long categoryId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "expense");
        payload.put("amount", amount);
        payload.put("accountId", accountId);
        payload.put("categoryId", categoryId);
        payload.put("occurredAt", LocalDateTime.now().withNano(0).toString());
        payload.put("note", "记一笔");
        return rest.exchange(url("/api/transactions"), HttpMethod.POST,
                new HttpEntity<>(payload, authJson(token)), String.class);
    }

    private long createAccount(String token, String name, String type, String initialBalance) {
        ResponseEntity<Map> resp = rest.exchange(url("/api/accounts"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "type", type,
                        "initialBalance", initialBalance, "sortOrder", 0), authJson(token)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) body(resp).get("id")).longValue();
    }

    private long createCategory(String token, String kind, String name) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", kind);
        payload.put("name", name);
        payload.put("parentId", null);
        ResponseEntity<Map> resp = rest.exchange(url("/api/categories"), HttpMethod.POST,
                new HttpEntity<>(payload, authJson(token)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) body(resp).get("id")).longValue();
    }

    // ---------------------------------- 账号辅助 ----------------------------------

    private String registerAndLogin(String email) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String code = verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, EmailCodePurpose.LOGIN)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email))
                .getCode();
        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }

    // ---------------------------------- 测试基础设施 ----------------------------------

    /**
     * Hibernate {@link StatementInspector}：对连续记账查询组装为段与档案执行的读 SQL 分表计数。
     *
     * <ul>
     *   <li>段读：SQL 含 {@code from streak_segments}——概览的段聚合（{@code COUNT/SUM/MAX}）、端点
     *       ({@code UNION ALL})，以及历史分页的分页列表与总条数，都从这张表读。</li>
     *   <li>档案读：SQL 含 {@code from user_growth}——概览的 Q1 {@code findById}。</li>
     * </ul>
     *
     * <p>只看 Hibernate/JPA 发出的 SQL：段维护写段与本类播种走的 {@code JdbcTemplate} 原生 SQL 天然不被
     * 计入；概览触发的结算在测量前已落入 10 秒节流窗口，测量期零结算 SQL，故计到的就只有查询组装自身的读。
     * 由 Hibernate 依类名反射实例化，故必须 {@code public static} 且带公有无参构造。</p>
     */
    public static final class StreakReadInspector implements StatementInspector {

        private static final AtomicInteger SEGMENT_READS = new AtomicInteger();
        private static final AtomicInteger PROFILE_READS = new AtomicInteger();
        private static final List<String> MATCHED = new java.util.concurrent.CopyOnWriteArrayList<>();

        public StreakReadInspector() {
            // Hibernate 反射实例化所需的公有无参构造。
        }

        @Override
        public String inspect(String sql) {
            if (sql == null) {
                return sql;
            }
            String lower = sql.toLowerCase(Locale.ROOT);
            if (lower.contains("from streak_segments")) {
                SEGMENT_READS.incrementAndGet();
                MATCHED.add(sql);
            } else if (lower.contains("from user_growth")) {
                PROFILE_READS.incrementAndGet();
                MATCHED.add(sql);
            }
            return sql;
        }

        static void reset() {
            SEGMENT_READS.set(0);
            PROFILE_READS.set(0);
            MATCHED.clear();
        }

        static int segmentReads() {
            return SEGMENT_READS.get();
        }

        static int profileReads() {
            return PROFILE_READS.get();
        }

        static int total() {
            return segmentReads() + profileReads();
        }

        static List<String> matched() {
            return List.copyOf(MATCHED);
        }
    }

    /**
     * 可注入故障的 {@code StreakSegmentRepository}：{@code @Primary} 的 JDK 动态代理，默认全部方法透明
     * 委托给真实仓储，仅当 {@link #SEGMENT_FAULT} 置位时让段维护的对账读
     * {@code findByUserIdOrderByStartDateAsc} 抛异常。
     *
     * <p>故障刻意下沉到段仓储层而不是对 {@link GrowthSettlementService} 做 Mockito spy：对带
     * {@code @Transactional} 的类做 spy 会绕过 Spring 的事务代理、令 {@code REQUIRES_NEW} 失效，
     * 而「结算整体回滚」正是这组断言要验的东西。经代理注入故障后，结算服务仍是真实 bean、
     * 仍走真实事务代理，异常从段维护穿出、事务回滚，与生产路径逐条一致。</p>
     */
    @TestConfiguration
    static class FaultConfig {
        @Bean
        @Primary
        StreakSegmentRepository probeStreakSegmentRepository(
                @Qualifier("streakSegmentRepository") StreakSegmentRepository real) {
            return (StreakSegmentRepository) Proxy.newProxyInstance(
                    StreakSegmentRepository.class.getClassLoader(),
                    new Class<?>[] {StreakSegmentRepository.class},
                    (proxy, method, args) -> {
                        if ("findByUserIdOrderByStartDateAsc".equals(method.getName()) && SEGMENT_FAULT.get()) {
                            throw new IllegalStateException("注入：段仓储对账读失败");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        }
    }
}
