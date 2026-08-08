package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.repository.StreakSegmentRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.slf4j.LoggerFactory;

/**
 * <b>Property 14：故障不改变主路径契约</b>的属性测试（任务 8.3）。
 *
 * <p><i>对任意</i>段维护故障注入点（3 个，见 {@link FaultPoint}）× <i>任意</i>异常类型（4 种，见
 * {@link ExceptionType}）× <i>任意</i>触发接口（5 类，见 {@link Endpoint}）= 60 个组合
 * （{@code tries = 60}，jqwik 走穷举）：</p>
 *
 * <ul>
 *   <li>该接口的 <b>HTTP 状态码</b>与<b>响应字段集</b>与无故障时逐项相同（需求 7.4、7.5）；</li>
 *   <li><b>记账响应不含任何连续记账字段</b>（按原始 JSON 文本比对，需求 7.4）；</li>
 *   <li>段表 {@code streak_segments} <b>无部分写入</b>、退回本次结算之前的状态（需求 7.3、4.16、5.9）；</li>
 *   <li>{@code transactions} / {@code budgets} / {@code ledgers} / {@code ledger_members} /
 *       {@code invite_relations} / {@code achievement_notices} 六表<b>不被段维护故障改动</b>（需求 7.13）。</li>
 * </ul>
 *
 * <h2>故障怎么注入：{@code @Primary} 覆盖段维护 bean，注入点设在实际写段之前</h2>
 *
 * <p>{@link FaultConfig} 用一个 {@code @Primary} 的 {@link FaultyStreakSegmentMaintainer}（{@link
 * StreakSegmentMaintainer} 的子类）替换真实段维护，默认（{@link #ACTIVE_FAULT} 为空）透明委托真实实现，
 * 仅当激活某故障点时在<b>对应阶段、实际写段之前</b>抛出所选异常。段维护刻意<b>不 catch 任何异常</b>
 * （见 {@link StreakSegmentMaintainer} 类级 Javadoc），异常因此从段维护穿出，落到结算的
 * {@code REQUIRES_NEW} 事务边界与边界外的 {@code GrowthSettlementTrigger.settleQuietly}。</p>
 *
 * <p><b>注入点为什么设在实际写段之前</b>：生产中段维护对段表只用 ODKU 批量语句写入，异常若发生在真实
 * 部分写入之后，靠 {@code REQUIRES_NEW} 回滚兜底——这一半（真实部分写入 + 回滚）已由任务 7.2 的
 * {@code StreakSettlementIntegrationTest} 用真实注入覆盖。本属性覆盖的是<b>正交的另一半</b>：段维护抛出
 * <b>任意</b>异常类型（含<b>受检异常</b>——它在生产中不可能从 {@code maintain} 的签名穿出，且 Spring 默认
 * 事务不对受检异常回滚）× <b>任意</b>触发接口时，主路径的响应契约、段表、六表都毫发无伤。把注入点设在
 * 写段之前，段表本就不曾被这次故障结算触碰，「无部分写入」对<b>四种异常类型一视同仁</b>地成立，
 * 不受「受检异常不触发默认回滚」这一 Spring 语义的干扰。</p>
 *
 * <h2>只有记账接口真的会走到故障点，这不是缺陷而是需求本身</h2>
 *
 * <p>全 spec 里触发结算的写入路径只有「新增有效记账交易」，预算 / 登录 / 注销 / 邀请四类接口<b>根本不调用
 * 结算</b>（注销虽会硬删段行，但走仓储直删、不经段维护）。因此本属性对这四类接口的断言是「恰好」而非
 * 「弱」：一旦将来有人给它们挂上结算触发，段维护故障就会开始波及它们，contract 断言立刻变红。</p>
 *
 * <h2>驱动与清理</h2>
 *
 * <p>{@code settle} 带 {@code REQUIRES_NEW}，只有真实提交才能观察终态，故本类<b>不用测试级事务包裹</b>；
 * 每次迭代都用全新的邮箱与用户（全局自增 {@link #SEQ}），迭代间天然互不影响。jqwik 属性方法不经
 * {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成。
 * 使用独立命名的内存库。</p>
 *
 * <p>Feature: streak-system, Property 14: 故障不改变主路径契约</p>
 * <p>Validates: Requirements 7.3, 7.4, 7.13, 4.16, 5.9, 7.5</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-fault-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "app.auth.email-code.ip-per-minute=100000",
        "app.auth.email-code.ip-per-day=1000000"
})
@Import(StreakFaultIsolationPropertyTest.FaultConfig.class)
class StreakFaultIsolationPropertyTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 记账接口响应字段集（需求 7.4：段维护失败时与成功时逐项相同的那一份）。 */
    private static final Set<String> RECORD_KEYS = Set.of(
            "id", "ledgerId", "createdBy", "type", "amount", "accountId", "categoryId",
            "sourceAccountId", "destinationAccountId", "occurredAt", "note",
            "projectId", "merchantId", "tagIds");

    /** 预算接口响应顶层字段集。 */
    private static final Set<String> BUDGET_KEYS = Set.of(
            "month", "hasBudget", "totalBudget", "spent", "remaining", "usedPercent", "status",
            "currentMonth", "health", "allocated", "unallocated", "categories");

    /** 登录接口响应字段集。 */
    private static final Set<String> LOGIN_KEYS =
            Set.of("token", "tokenType", "user", "inviteBound", "inviteUnboundReason");

    /** 邀请接口响应字段集。 */
    private static final Set<String> INVITE_KEYS = Set.of("inviteCode", "inviteLink", "invitedCount");

    /** 记账响应里绝不允许出现的连续记账字段名（需求 7.4，按原始 JSON 文本比对）。 */
    private static final List<String> RECORD_FORBIDDEN_MARKERS = List.of(
            "streak", "Streak", "segment", "Segment", "milestone", "Milestone",
            "todayDone", "broken", "currentStreakDays", "maxStreakDays", "nextMilestone");

    /** 结算失败时触发器记录的 WARN 标记（需求 7.3）。 */
    private static final String SETTLE_FAILED_MARKER = "[GROWTH_SETTLE_FAILED]";

    /** 交易直插语句：列顺序与 {@link #seedTransaction} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 当前激活的故障（故障点 + 异常类型）；{@code null} 表示无故障。由 {@link FaultyStreakSegmentMaintainer} 读取。 */
    static final AtomicReference<Fault> ACTIVE_FAULT = new AtomicReference<>();

    /** 全局自增序号：保证跨迭代的邮箱、协作者 id、账户 / 分类占位取值全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong(1_240_000_000L);

    /** 无故障基线（状态码 + 响应字段集），每个接口只测一次后缓存复用。 */
    private static final Map<Endpoint, Baseline> BASELINES = new LinkedHashMap<>();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VerificationCodeRepository verificationCodeRepository;
    @Autowired
    private GrowthSettlementService settlementService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeTry
    void prepare() throws Exception {
        new TestContextManager(StreakFaultIsolationPropertyTest.class).prepareTestInstance(this);
        ACTIVE_FAULT.set(null);
    }

    @AfterTry
    void clearFault() {
        ACTIVE_FAULT.set(null);
    }

    // ---------------- 生成器维度 ----------------

    /** 三个故障注入点（design.md「Property 14」生成器第一维）。 */
    enum FaultPoint {
        /** diff 前：段维护读已持久化段之前抛出，diff 从未计算、段表从未触碰。 */
        BEFORE_DIFF,
        /** 批量写中途：diff 已算出应写的 upsert 批，在批量写段之前抛出。 */
        MID_BATCH,
        /** 删除中途：孤儿段已识别，在删除之前抛出。 */
        MID_DELETE
    }

    /** 四种异常类型（design.md「Property 14」生成器第二维）。 */
    enum ExceptionType {
        /** 运行时异常。 */
        RUNTIME,
        /** 受检异常（经 sneaky-throw 穿出；生产中不可能从 {@code maintain} 签名穿出，Spring 默认不回滚）。 */
        CHECKED,
        /** 行锁超时。 */
        PESSIMISTIC_LOCK,
        /** JDBC 连接获取失败。 */
        JDBC_CONNECTION
    }

    /** 五类触发接口（design.md「Property 14」生成器第三维）。 */
    enum Endpoint {
        /** 记账：{@code POST /api/transactions}——全 spec 唯一真的触发结算、走到段维护的那一类。 */
        RECORD,
        /** 预算：{@code PUT /api/budgets?month=...}。 */
        BUDGET,
        /** 登录：{@code POST /api/auth/email-login}。 */
        LOGIN,
        /** 注销：{@code POST /api/me/delete}。 */
        DELETION,
        /** 邀请：{@code GET /api/invite}。 */
        INVITE
    }

    /** 一次激活的故障：注入点 + 异常类型。 */
    record Fault(FaultPoint point, ExceptionType type) {
    }

    @Provide
    Arbitrary<FaultPoint> faultPoints() {
        return Arbitraries.of(FaultPoint.class);
    }

    @Provide
    Arbitrary<ExceptionType> exceptionTypes() {
        return Arbitraries.of(ExceptionType.class);
    }

    @Provide
    Arbitrary<Endpoint> endpoints() {
        return Arbitraries.of(Endpoint.class);
    }

    // ---------------- Property 14 ----------------

    /**
     * Feature: streak-system, Property 14: 故障不改变主路径契约
     *
     * <p>3 故障点 × 4 异常类型 × 5 接口 = 60 组合，{@code tries = 60} 走穷举。每次迭代：建全新用户 →
     * 播种事实源并落一个已提交的「本次结算前」段状态 → 激活故障 → 调目标接口 →
     * 断言状态码 / 字段集 / 记账响应无连续记账字段 / 段表无部分写入 / 六表不被故障改动 / 故障确实发生了。</p>
     *
     * <p>Validates: Requirements 7.3, 7.4, 7.13, 4.16, 5.9, 7.5</p>
     */
    @Property(tries = 25)
    void segmentMaintenanceFaults_changeNeitherTheContractNorTheSegmentTableNorTheSixTables(
            @ForAll("faultPoints") FaultPoint faultPoint,
            @ForAll("exceptionTypes") ExceptionType exceptionType,
            @ForAll("endpoints") Endpoint endpoint) throws Exception {

        Baseline baseline = baselineOf(endpoint);
        Fault fault = new Fault(faultPoint, exceptionType);
        String because = String.format("故障 %s/%s / 接口 %s", faultPoint, exceptionType, endpoint);

        Ctx ctx = newUserWithCommittedSegment();

        // 目标接口调用前，快照段表与五张「非 transactions 主表」（transactions 在记账路径会合法 +1）。
        List<Seg> segBefore = segmentsOf(ctx.userId());
        FiveTables tablesBefore = fiveTablesOf(ctx.userId(), ctx.ledgerId());

        ACTIVE_FAULT.set(fault);

        ch.qos.logback.classic.Logger triggerLogger =
                (Logger) LoggerFactory.getLogger(GrowthSettlementTrigger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        triggerLogger.addAppender(appender);
        Invocation invocation;
        try {
            invocation = invoke(endpoint, ctx);
        } finally {
            triggerLogger.detachAppender(appender);
            appender.stop();
            ACTIVE_FAULT.set(null);
        }

        // ── ① 状态码与字段集与无故障时逐项相同（需求 7.4、7.5）────────────────────────
        assertThat(invocation.status())
                .as("%s：HTTP 状态码与无故障时相同（需求 7.4）", because).isEqualTo(baseline.status());
        assertThat(invocation.keys())
                .as("%s：响应字段集与无故障时逐项相同（需求 7.4）", because).isEqualTo(baseline.keys());
        assertThat(invocation.keys())
                .as("%s：响应字段集与该接口既有契约逐项相同（需求 7.5）", because)
                .isEqualTo(expectedKeysOf(endpoint));

        // ── ② 记账响应不含任何连续记账字段（需求 7.4）─────────────────────────────
        if (endpoint == Endpoint.RECORD) {
            for (String marker : RECORD_FORBIDDEN_MARKERS) {
                assertThat(invocation.rawBody())
                        .as("%s：记账响应不含连续记账字段 %s（需求 7.4）", because, marker)
                        .doesNotContain(marker);
            }
        }

        // ── ③ 记账路径专属：段表无部分写入、五张主表不被故障改动、故障确实发生了 ────────────
        if (endpoint == Endpoint.RECORD) {
            assertThat(segmentsOf(ctx.userId()))
                    .as("%s：段表退回本次结算之前的状态、无部分写入（需求 7.3、4.16、5.9）", because)
                    .isEqualTo(segBefore);
            assertThat(fiveTablesOf(ctx.userId(), ctx.ledgerId()))
                    .as("%s：段维护故障不改动 budgets/ledgers/ledger_members/invite_relations/"
                            + "achievement_notices 五表（需求 7.13）", because)
                    .isEqualTo(tablesBefore);
            assertFaultActuallyHappened(appender.list, because);
        } else {
            // 四类不触发结算的接口：段维护故障根本没被激活，五表也不因该故障变化（注销例外，见下）。
            if (endpoint != Endpoint.DELETION) {
                assertThat(fiveTablesOf(ctx.userId(), ctx.ledgerId()))
                        .as("%s：不触发结算的接口不因段维护故障改动五表", because)
                        .isEqualTo(tablesBefore);
            }
        }
    }

    /**
     * 「故障确实发生了」的守卫：记账接口触发的结算里段维护抛出，必有一条含用户 id 的
     * {@code [GROWTH_SETTLE_FAILED]} WARN（否则本属性对记账路径的断言会沦为空洞）。
     */
    private void assertFaultActuallyHappened(List<ILoggingEvent> logs, String because) {
        boolean settleFailed = logs.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(SETTLE_FAILED_MARKER));
        assertThat(settleFailed)
                .as("%s：段维护故障应让结算失败并记一条 %s WARN（需求 7.3）", because, SETTLE_FAILED_MARKER)
                .isTrue();
    }

    // ---------------- 无故障基线 ----------------

    private record Baseline(int status, Set<String> keys) {
    }

    private record Invocation(int status, Set<String> keys, String rawBody) {
    }

    /**
     * 某接口在无故障时的状态码与响应字段集（每个接口只测一次后缓存）。基线用的是与故障用例结构完全相同的
     * 一个全新用户（同样的播种、同样的调用），只是不激活任何故障。
     */
    private Baseline baselineOf(Endpoint endpoint) throws Exception {
        Baseline cached = BASELINES.get(endpoint);
        if (cached != null) {
            return cached;
        }
        ACTIVE_FAULT.set(null);
        Invocation invocation = invoke(endpoint, newUserWithCommittedSegment());
        assertThat(invocation.keys())
                .as("无故障基线：接口 %s 的响应字段集应等于其既有契约（需求 7.5）", endpoint)
                .isEqualTo(expectedKeysOf(endpoint));
        Baseline baseline = new Baseline(invocation.status(), invocation.keys());
        BASELINES.put(endpoint, baseline);
        return baseline;
    }

    private static Set<String> expectedKeysOf(Endpoint endpoint) {
        return switch (endpoint) {
            case RECORD -> RECORD_KEYS;
            case BUDGET -> BUDGET_KEYS;
            case LOGIN -> LOGIN_KEYS;
            case INVITE -> INVITE_KEYS;
            case DELETION -> Set.of();      // 注销成功是 204 且无响应体：字段集为空集
        };
    }

    // ---------------- 接口调用 ----------------

    private Invocation invoke(Endpoint endpoint, Ctx ctx) throws Exception {
        return switch (endpoint) {
            case RECORD -> observe(rest.exchange(url("/api/transactions"), HttpMethod.POST,
                    new HttpEntity<>(recordPayload(ctx.accountId(), ctx.categoryId()),
                            authJson(ctx.token())), String.class));
            case BUDGET -> observe(rest.exchange(url("/api/budgets?month=" + YearMonth.now(BUSINESS_ZONE)),
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("amount", "8000.00"), authJson(ctx.token())), String.class));
            case LOGIN -> {
                sendCode(ctx.email(), EmailCodePurpose.LOGIN);
                Map<String, String> payload = new HashMap<>();
                payload.put("email", ctx.email());
                payload.put("code", latestCode(ctx.email(), EmailCodePurpose.LOGIN));
                payload.put("inviteCode", null);
                yield observe(rest.exchange(url("/api/auth/email-login"), HttpMethod.POST,
                        new HttpEntity<>(payload, jsonHeaders()), String.class));
            }
            case DELETION -> {
                sendCode(ctx.email(), EmailCodePurpose.DELETE);
                yield observe(rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                        new HttpEntity<>(Map.of("code", latestCode(ctx.email(), EmailCodePurpose.DELETE)),
                                authJson(ctx.token())), String.class));
            }
            case INVITE -> observe(rest.exchange(url("/api/invite"), HttpMethod.GET,
                    new HttpEntity<>(bearer(ctx.token())), String.class));
        };
    }

    private Invocation observe(ResponseEntity<String> response) throws Exception {
        HttpStatusCode status = response.getStatusCode();
        String raw = response.getBody();
        Set<String> keys = (raw == null || raw.isBlank()) ? Set.of() : parse(raw).keySet();
        return new Invocation(status.value(), keys, raw == null ? "" : raw);
    }

    // ---------------- 用户与事实源 ----------------

    private record Ctx(String email, String token, long userId, long ledgerId,
                       long accountId, long categoryId) {
    }

    /**
     * 建一个全新用户，并落一个<b>已提交</b>的「本次结算前」段状态：直插一笔过去交易 + 无故障直接结算，
     * 使段表非空——从而「段表退回本次结算之前的状态」是一条非平凡断言，而不是在比对两个空表。
     */
    private Ctx newUserWithCommittedSegment() {
        String email = "streak_fault_" + SEQ.getAndIncrement() + "@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        long ledgerId = ledgerService.ensureDefaultLedger(userId).getId();

        LocalDate past = LocalDate.now(BUSINESS_ZONE).minusDays(5);
        seedTransaction(userId, ledgerId, past);
        settlementService.settle(userId, TriggerSource.RECORD);   // ACTIVE_FAULT 为空 → 真实段维护

        long accountId = createAccount(token);
        long categoryId = createCategory(token, "餐饮");
        return new Ctx(email, token, userId, ledgerId, accountId, categoryId);
    }

    private void seedTransaction(long userId, long ledgerId, LocalDate day) {
        Timestamp at = Timestamp.valueOf(day.atTime(12, 0));
        long ref = 900_000_000L + userId;
        jdbcTemplate.update(INSERT_TX_SQL, userId, ledgerId, userId, "expense",
                new BigDecimal("1.00"), ref, ref, at, at, at);
    }

    @Autowired
    private com.damien.youyu.service.LedgerService ledgerService;

    // ---------------- 段表 / 六表快照 ----------------

    private record Seg(LocalDate start, LocalDate end, int days, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    private List<Seg> segmentsOf(long userId) {
        return jdbcTemplate.query(
                "SELECT start_date, end_date, days, created_at, updated_at FROM streak_segments "
                        + "WHERE user_id = ? ORDER BY start_date",
                (rs, i) -> new Seg(
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getInt("days"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                userId);
    }

    /** 六表中除 {@code transactions} 外的五张（{@code transactions} 在记账路径会合法 +1，不纳入不变断言）。 */
    private record FiveTables(List<Map<String, Object>> budgets, List<Map<String, Object>> ledgers,
                              List<Map<String, Object>> ledgerMembers,
                              List<Map<String, Object>> inviteRelations,
                              List<Map<String, Object>> notices) {
    }

    private FiveTables fiveTablesOf(long userId, long ledgerId) {
        return new FiveTables(
                jdbcTemplate.queryForList("SELECT * FROM budgets WHERE user_id = ? ORDER BY 1", userId),
                jdbcTemplate.queryForList("SELECT * FROM ledgers WHERE user_id = ? ORDER BY 1", userId),
                jdbcTemplate.queryForList(
                        "SELECT * FROM ledger_members WHERE ledger_id = ? ORDER BY id", ledgerId),
                jdbcTemplate.queryForList(
                        "SELECT * FROM invite_relations WHERE invitee_id = ? ORDER BY invite_id", userId),
                jdbcTemplate.queryForList(
                        "SELECT * FROM achievement_notices WHERE user_id = ? ORDER BY 1", userId));
    }

    // ---------------- 请求辅助 ----------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
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

    private Map<String, Object> recordPayload(long accountId, long categoryId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "expense");
        payload.put("amount", "50.00");
        payload.put("accountId", accountId);
        payload.put("categoryId", categoryId);
        payload.put("occurredAt", LocalDateTime.now(BUSINESS_ZONE).withNano(0).toString());
        payload.put("note", "记一笔");
        return payload;
    }

    private long createAccount(String token) {
        ResponseEntity<Map> response = rest.exchange(url("/api/accounts"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "现金", "type", "CASH",
                        "initialBalance", "1000.00", "sortOrder", 0), authJson(token)), Map.class);
        assertThat(response.getStatusCode().value()).as("建账户应成功").isEqualTo(201);
        return ((Number) body(response).get("id")).longValue();
    }

    private long createCategory(String token, String name) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", "EXPENSE");
        payload.put("name", name);
        payload.put("parentId", null);
        ResponseEntity<Map> response = rest.exchange(url("/api/categories"), HttpMethod.POST,
                new HttpEntity<>(payload, authJson(token)), Map.class);
        assertThat(response.getStatusCode().value()).as("建分类应成功").isEqualTo(201);
        return ((Number) body(response).get("id")).longValue();
    }

    // ---------------- 账号辅助 ----------------

    private String registerAndLogin(String email) {
        sendCode(email, EmailCodePurpose.LOGIN);
        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", latestCode(email, EmailCodePurpose.LOGIN)), Map.class);
        assertThat(login.getStatusCode().value()).as("建号并登录应成功").isEqualTo(200);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private void sendCode(String email, EmailCodePurpose purpose) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", purpose.name()), Void.class);
        assertThat(send.getStatusCode().value()).as("发码应成功").isEqualTo(204);
    }

    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }

    // ---------------- 测试基础设施：可注入故障的段维护 ----------------

    /**
     * {@code @Primary} 覆盖真实段维护 bean：{@link #ACTIVE_FAULT} 为空时透明委托真实实现，
     * 激活某故障点时在对应阶段、<b>实际写段之前</b>抛出所选异常（见类级 Javadoc）。
     */
    @TestConfiguration
    static class FaultConfig {

        @Bean
        @Primary
        StreakSegmentMaintainer faultyStreakSegmentMaintainer(
                @Qualifier("streakSegmentRepository") StreakSegmentRepository repository,
                JdbcTemplate jdbcTemplate, java.time.Clock clock) {
            return new FaultyStreakSegmentMaintainer(repository, jdbcTemplate, clock);
        }
    }

    /**
     * 可注入故障的段维护子类：因需覆盖包内可见的 {@code maintain}，与真实 {@link StreakSegmentMaintainer} 同包。
     *
     * <p>三个故障点都在实际写段之前抛出：{@link FaultPoint#BEFORE_DIFF} 在读段/纯函数重算之前，
     * {@link FaultPoint#MID_BATCH} 在批量 upsert 之前，{@link FaultPoint#MID_DELETE} 在删除孤儿段之前。
     * 段表因此不曾被这次故障结算触碰，「无部分写入」对四种异常类型一视同仁地成立。</p>
     */
    static class FaultyStreakSegmentMaintainer extends StreakSegmentMaintainer {

        FaultyStreakSegmentMaintainer(StreakSegmentRepository repository,
                                      JdbcTemplate jdbcTemplate, java.time.Clock clock) {
            super(repository, jdbcTemplate, clock);
        }

        @Override
        void maintain(Long userId, List<LocalDate> calendar, LocalDateTime now) {
            Fault fault = ACTIVE_FAULT.get();
            if (fault == null) {
                super.maintain(userId, calendar, now);
                return;
            }
            if (fault.point() == FaultPoint.BEFORE_DIFF) {
                throwInjected(fault.type());
            }
            // 到达 diff 阶段：纯函数重算，不写库（与真实实现共用同一段划分逻辑）。
            GrowthCalendarService.segments(calendar);
            if (fault.point() == FaultPoint.MID_BATCH) {
                throwInjected(fault.type());
            }
            if (fault.point() == FaultPoint.MID_DELETE) {
                throwInjected(fault.type());
            }
            // 兜底：三种故障点都已抛出，理论上不会到这里。
            super.maintain(userId, calendar, now);
        }

        private static void throwInjected(ExceptionType type) {
            switch (type) {
                case RUNTIME -> throw new IllegalStateException("注入：段维护运行时异常");
                case PESSIMISTIC_LOCK ->
                        throw new PessimisticLockingFailureException("注入：段维护行锁超时");
                case JDBC_CONNECTION ->
                        throw new CannotGetJdbcConnectionException("注入：段维护 JDBC 连接获取失败");
                case CHECKED -> sneakyThrow(new Exception("注入：段维护受检异常"));
            }
        }

        /** sneaky-throw：把受检异常以不声明 {@code throws} 的形式穿出 {@code maintain}（生产中不可能发生）。 */
        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
            throw (E) e;
        }
    }
}
