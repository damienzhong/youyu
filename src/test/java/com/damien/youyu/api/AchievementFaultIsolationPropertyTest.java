package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.service.GrowthSettlementTrigger;
import com.damien.youyu.service.LedgerService;
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
 * <b>Property 12：成就故障不改变主路径的响应契约</b>的属性测试（任务 8.6）。
 *
 * <p><i>对任意</i>成就侧故障注入（6 种，见 {@link FaultPoint}）× <i>任意</i>触发接口
 * （5 类，见 {@link Endpoint}）：</p>
 *
 * <ul>
 *   <li>该接口的 <b>HTTP 状态码</b>与<b>响应字段集</b>与无故障时逐项相同（需求 4.15、12.4）；</li>
 *   <li><b>记账响应不含任何成就 / 播报 / 徽章字段</b>（按原始 JSON 文本比对，需求 4.15）；</li>
 *   <li>{@code growth_events} / {@code user_growth} / {@code achievement_notices} 三表<b>无部分写入</b>
 *       （需求 4.14）；</li>
 *   <li>成就清单响应的字段集<b>不随结算成败与是否被节流变化</b>（需求 6.7）。</li>
 * </ul>
 *
 * <h2>故障怎么注入：沿用 {@code AchievementSettlementIntegrationTest} 的 {@code FaultConfig} 范式</h2>
 *
 * <p>{@link FaultConfig} 用 {@code @Primary} 的 <b>JDK 动态代理</b>包住三个真实仓储
 * （{@link TransactionRepository}、{@link LedgerMemberRepository}、{@link UserGrowthRepository}），
 * 默认全部方法透明委托，仅当 {@link #ACTIVE_FAULT} 指向对应故障点时让特定方法抛异常。</p>
 *
 * <p><b>刻意不用 Mockito 对结算服务做 spy</b>：{@code GrowthSettlementService.settle} 带
 * {@code @Transactional(REQUIRES_NEW)}，对它做 spy 会绕过 Spring 的事务代理、令 {@code REQUIRES_NEW}
 * 失效——而「异常穿出使这次独立事务整体回滚、三表无部分写入」正是本属性要验的东西。把故障下沉到
 * 仓储层则结算仍是真实 bean、仍走真实事务代理，异常从第 ③ 步穿出、事务回滚，与生产路径逐条一致。</p>
 *
 * <h2>每个故障点都带一条「它真的发生了」的守卫</h2>
 *
 * <p>否则本属性极易沦为空洞——「响应字段集不变」在故障根本没触发时当然成立。逐条守卫：</p>
 * <ul>
 *   <li>四个<b>抛异常</b>的故障点（{@link FaultPoint#ACHIEVEMENT_EVAL_THROWS}、
 *       {@link FaultPoint#SAVING_MONTH_EVAL_THROWS}、{@link FaultPoint#SINGLE_AGGREGATE_THROWS}、
 *       {@link FaultPoint#LOCK_ABANDONED}）：记账请求之后必须出现一条
 *       {@code [GROWTH_SETTLE_FAILED]} WARN，且三表零写入（需求 4.14）；</li>
 *   <li>{@link FaultPoint#SETTLEMENT_THROTTLED}：记账请求之后三表逐行不变，
 *       <b>且不出现</b> {@code [GROWTH_SETTLE_FAILED]}——「被跳过」与「失败了」是两种不同的降级；</li>
 *   <li>{@link FaultPoint#SINGLE_AGGREGATE_THROWS}：成就清单里 {@code TRAVEL_MASTER} 的当前值降级为
 *       <b>0</b>（该用户明明有 2 笔旅行支出），而其余项照常给出真实取值（需求 3.14）；</li>
 *   <li>{@link FaultPoint#ACHIEVEMENT_EVAL_THROWS}：同理 {@code COLLAB_1} 的当前值降级为 <b>0</b>
 *       （该用户明明有 1 个他人的 {@code EDITOR} 成员行）；</li>
 *   <li>{@link FaultPoint#UNKNOWN_BADGE_ROW}：成就清单仍恰好 16 项、列表里不出现那个未知编码，
 *       且该行的全部列取值一字不变（需求 1.12、12.7）。</li>
 * </ul>
 *
 * <h2>只有记账接口真的会走到故障点，这不是缺陷而是需求本身</h2>
 *
 * <p>全 spec 里触发结算的写入路径只有「新增有效记账交易」（{@code TransactionService.create} 与两个导入
 * 服务），预算 / 登录 / 注销 / 邀请四类接口<b>根本不调用结算</b>。需求 4.14 那句「不向记账、预算、登录、
 * 注销与邀请路径传播该异常」要保证的正是这件事：这四类接口在成就侧故障下必须<b>毫无反应</b>
 * ——状态码、字段集、三表快照全都不动。因此本属性对它们的断言不是「弱」，而是「恰好」：
 * 一旦将来有人给预算或登录挂上结算触发（那会引入需求 4.13 明令禁止的新结算时机），
 * 这四类接口的三表快照就会开始变化，断言立刻变红。注销是唯一会写成就侧数据的一类
 * ——它在同一事务内删除该用户的播报游标行（需求 11.1），故它的表断言是「三表<b>该用户</b>零残留」。</p>
 *
 * <h2>不覆盖 {@code Clock}（与 {@code AchievementSettlementIntegrationTest} 同一取舍）</h2>
 *
 * <p>本类走真实 HTTP：JWT 签发与验证、邮箱验证码有效期都读真实时钟，把进程时钟挪走会让令牌与验证码
 * 的有效期判定与真实时钟错开。故日期一律相对 {@code LocalDate.now()} 表达；需要「结算被节流」时
 * 直接把 {@code user_growth} 的 {@code last_settled_at} 与 {@code last_record_date} 摆成节流条件成立的
 * 取值（见 {@link FaultPoint#SETTLEMENT_THROTTLED}），比推时钟更直接也更稳。</p>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 *
 * <p>{@code settle} 带 {@code REQUIRES_NEW}，只有真实提交才能在库里观察到终态，故本类<b>不用测试级
 * 事务包裹</b>；清理相应地不靠回滚，而是<b>每次迭代都用全新的邮箱与用户</b>
 * （全局自增序号 {@link #SEQ}），因此迭代之间天然互不影响，无需清表。jqwik 属性方法不经
 * {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成
 * （上下文缓存复用）。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Feature: achievement-system, Property 12: 成就故障不改变主路径的响应契约</p>
 *
 * <p>Validates: Requirements 4.14, 4.15, 4.16, 6.7, 12.4</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-fault-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试每次迭代都要建账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=100000",
        "app.auth.email-code.ip-per-day=1000000"
})
@Import(AchievementFaultIsolationPropertyTest.FaultConfig.class)
class AchievementFaultIsolationPropertyTest {

    /** 成就清单顶层字段集，恰好 3 项（需求 6.1）。 */
    private static final Set<String> LIST_TOP_KEYS = Set.of("achievements", "unlockedCount", "total");

    /** 成就视图字段集，恰好 9 项（需求 6.3）。 */
    private static final Set<String> VIEW_KEYS = Set.of(
            "code", "name", "description", "category", "target", "current", "unlocked",
            "unlockedAt", "eventId");

    /** 成就总数恒为 16（需求 1.1）。 */
    private static final int TOTAL_ACHIEVEMENTS = 16;

    /**
     * 业务时区，与 {@code TimeConfig} 的 {@code Clock}（{@code Clock.system(Asia/Shanghai)}）同一时区。
     *
     * <p>凡是要与服务端「当前时刻/当日」对齐的直插取值（节流窗口的 {@code last_settled_at} /
     * {@code last_record_date}、播种交易的 {@code occurred_at}、记账 payload 的 {@code occurredAt}、
     * 以及自愈断言里比对的 {@code DAILY_RECORD:<日期>}）都必须用本时区的挂钟，
     * <b>不能</b>用 JVM 默认时区的 {@code LocalDate.now()} / {@code LocalDateTime.now()}——
     * 后者在 UTC 的 CI 上比服务端早 8 小时，会让 {@code SETTLEMENT_THROTTLED} 的 60 秒窗口失效
     * （本地东八区下两者重合，故只在 CI 暴露）。</p>
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 记账接口响应字段集（需求 4.15、12.4：判定失败时与判定成功时逐项相同的那一份）。 */
    private static final Set<String> RECORD_KEYS = Set.of(
            "id", "ledgerId", "createdBy", "type", "amount", "accountId", "categoryId",
            "sourceAccountId", "destinationAccountId", "occurredAt", "note",
            "projectId", "merchantId", "tagIds");

    /** 预算接口响应顶层字段集（需求 12.4）。 */
    private static final Set<String> BUDGET_KEYS = Set.of(
            "month", "hasBudget", "totalBudget", "spent", "remaining", "usedPercent", "status",
            "currentMonth", "health", "allocated", "unallocated", "categories");

    /** 登录接口响应字段集（需求 12.4）。 */
    private static final Set<String> LOGIN_KEYS =
            Set.of("token", "tokenType", "user", "inviteBound", "inviteUnboundReason");

    /** 邀请接口响应字段集（需求 12.4）。 */
    private static final Set<String> INVITE_KEYS = Set.of("inviteCode", "inviteLink", "invitedCount");

    /**
     * 记账响应里绝不允许出现的成就 / 播报 / 徽章字段名（需求 4.15）。
     *
     * <p>按<b>原始 JSON 文本</b>比对而不是按解析后的顶层键集合：嵌套一层的泄漏不会改变顶层键集合。
     * {@code level} / {@code exp} 写成<b>带引号的键形式</b>：裸子串 {@code exp} 会被 {@code type} 的
     * 合法取值 {@code "expense"} 命中，那样的断言不是更严而是恒假。</p>
     */
    private static final List<String> RECORD_FORBIDDEN_MARKERS = List.of(
            "achievement", "Achievement", "badge", "Badge", "unlock", "Unlock",
            "pending", "Pending", "notice", "Notice", "broadcast", "Broadcast",
            "lastNotifiedEventId", "\"level\"", "\"exp\"", "\"badges\"");

    /** 库里那条「编码不在 16 项清单内」的 {@code BADGE} 行（需求 1.12）。 */
    private static final String UNKNOWN_BADGE_CODE = "NOT_IN_CATALOG_AT_ALL";
    private static final String UNKNOWN_BADGE_KEY = "BADGE:" + UNKNOWN_BADGE_CODE;

    /** 结算失败时触发器记录的 WARN 标记（需求 4.14）。 */
    private static final String SETTLE_FAILED_MARKER = "[GROWTH_SETTLE_FAILED]";

    /**
     * 四个会让结算<b>抛异常</b>的故障点：{@code REQUIRES_NEW} 事务整体回滚 + 一条
     * {@code [GROWTH_SETTLE_FAILED]} WARN + 下一次结算补齐（需求 4.14、4.16）。
     *
     * <p>另外两个故障点不属于「失败」：被节流是<b>跳过</b>（写入之前就返回），未知 {@code BADGE} 行是
     * <b>忽略</b>（结算照常成功提交）。三者的表现刻意分开断言，混为一谈会让表断言失去分辨力。</p>
     */
    private static final Set<FaultPoint> THROWING_FAULTS = Set.of(
            FaultPoint.ACHIEVEMENT_EVAL_THROWS, FaultPoint.SAVING_MONTH_EVAL_THROWS,
            FaultPoint.SINGLE_AGGREGATE_THROWS, FaultPoint.LOCK_ABANDONED);

    /** 交易直插语句：列顺序与 {@link #seedTransaction} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 当前激活的故障点；{@code null} 表示无故障（基线）。由 {@link FaultConfig} 的代理读取。 */
    private static final AtomicReference<FaultPoint> ACTIVE_FAULT = new AtomicReference<>();

    /** 全局自增序号：保证跨迭代的邮箱、协作者 id 与账户 / 分类占位取值全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong(1_140_000_000L);

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
    private LedgerService ledgerService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeTry
    void prepare() throws Exception {
        new TestContextManager(AchievementFaultIsolationPropertyTest.class).prepareTestInstance(this);
        ACTIVE_FAULT.set(null);
    }

    /** 无条件解除故障（形同 finally）：即便某次 try 在断言处抛出也执行，避免污染后续迭代。 */
    @AfterTry
    void clearFault() {
        ACTIVE_FAULT.set(null);
    }

    // ---------------- 生成器 ----------------

    /** 六个故障点（design.md「Property 12」的生成器第一维）。 */
    enum FaultPoint {
        /**
         * 成就判定抛异常：协作成员数聚合（{@code countEditorsOfOwnedLedgers}）失败。
         *
         * <p>该口径只服务成就判定（{@code COLLAB_1}），结算路径上异常穿出使事务整体回滚；
         * 查询路径上由 {@code AchievementSnapshotService} 降级为 0（需求 3.14）。</p>
         */
        ACHIEVEMENT_EVAL_THROWS,
        /** 储蓄月判定抛异常：月度收支分组合计（{@code sumMonthlyAmounts...}）失败，只在结算路径上被调用。 */
        SAVING_MONTH_EVAL_THROWS,
        /** 单个聚合抛异常：旅行记账笔数（{@code countTravelExpenses}）失败——需求 3.14 的逐口径降级。 */
        SINGLE_AGGREGATE_THROWS,
        /** 库里有未知 {@code BADGE} 行：编码不在 16 项清单内，须被忽略且原行一字不动（需求 1.12）。 */
        UNKNOWN_BADGE_ROW,
        /**
         * 结算被节流：把 {@code user_growth} 摆成「{@code last_settled_at} 距今 &lt;60s <b>且</b>
         * {@code last_record_date} 等于结算日」，记账侧 60 秒窗口的两个条件同时成立（需求 9.15）。
         */
        SETTLEMENT_THROTTLED,
        /**
         * 行锁放弃：{@code findForUpdateById} 恒抛 {@link PessimisticLockingFailureException}，
         * 使 500ms 墙钟预算内的退避重试全部失败、最终抛 {@code GrowthLockAbandonedException}。
         */
        LOCK_ABANDONED
    }

    /** 五类触发接口（design.md「Property 12」的生成器第二维，即需求 4.14 / 12.4 点名的那五类）。 */
    enum Endpoint {
        /** 记账：{@code POST /api/transactions}——全 spec 唯一会真的触发结算的那一类。 */
        RECORD,
        /** 预算：{@code PUT /api/budgets?month=...}。 */
        BUDGET,
        /** 登录：{@code POST /api/auth/email-login}。 */
        LOGIN,
        /** 注销：{@code POST /api/me/delete}——唯一会写成就侧数据（删播报游标行）的一类。 */
        DELETION,
        /** 邀请：{@code GET /api/invite}。 */
        INVITE
    }

    @Provide
    Arbitrary<FaultPoint> faultPoints() {
        return Arbitraries.of(FaultPoint.class);
    }

    @Provide
    Arbitrary<Endpoint> endpoints() {
        return Arbitraries.of(Endpoint.class);
    }

    // ---------------- Property 12 ----------------

    /**
     * Feature: achievement-system, Property 12: 成就故障不改变主路径的响应契约
     *
     * <p>输入空间 6 个故障点 × 5 类接口 = 30 个组合，{@code tries = 30} 使 jqwik 走穷举生成、
     * 把 30 个组合逐个跑到。每次迭代：建两个全新用户（一个供目标接口、一个供成就清单）→ 播种事实源 →
     * 激活故障 → 断言成就清单契约（含被节流的第二次请求）→ 快照三表 → 调目标接口 →
     * 断言状态码 / 字段集 / 记账响应无成就字段 / 三表无部分写入 / 失败自愈。</p>
     *
     * <p>Validates: Requirements 4.14, 4.15, 4.16, 6.7, 12.4</p>
     */
    @Property(tries = 30)
    void achievementFaults_changeNeitherTheFiveEndpointContractsNorTheThreeTables(
            @ForAll("faultPoints") FaultPoint fault,
            @ForAll("endpoints") Endpoint endpoint) throws Exception {

        Baseline baseline = baselineOf(endpoint);

        // 目标用户刻意不带协作成员行：注销的前置校验会因「自有账本存在他人成员」抛
        // DELETE_BLOCKED_COLLAB（409），那样注销这一类就永远走不到成功路径上（见 newUserWithFacts）。
        Ctx target = newUserWithFacts(false);
        Ctx listProbe = newUserWithFacts(true);
        applyFault(fault, target);
        applyFault(fault, listProbe);
        ACTIVE_FAULT.set(fault);

        // ── ① 成就清单：字段集不随结算成败与是否被节流变化（需求 6.7）───────────────────────
        // 连续两次请求：第一次让 OVERVIEW 结算真实尝试，第二次必落在 10 秒窗口内被节流跳过。
        Map<String, Object> firstList = assertListContract(fault, listProbe, "首次请求（结算真实尝试）");
        Map<String, Object> throttledList = assertListContract(fault, listProbe, "第二次请求（结算被节流）");
        assertThat(throttledList.keySet())
                .as("故障 %s：成就清单的字段集不随是否被节流变化（需求 6.7）", fault)
                .isEqualTo(firstList.keySet());

        // ── ② 目标接口：状态码与字段集与无故障时逐项相同 ────────────────────────────────
        TableSnapshot before = snapshotOf(target.userId());

        Logger triggerLogger = (Logger) LoggerFactory.getLogger(GrowthSettlementTrigger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        triggerLogger.addAppender(appender);
        Invocation invocation;
        try {
            invocation = invoke(endpoint, target);
        } finally {
            triggerLogger.detachAppender(appender);
            appender.stop();
        }

        String because = String.format("故障 %s / 接口 %s", fault, endpoint);
        assertThat(invocation.status())
                .as("%s：HTTP 状态码与无故障时相同（需求 4.15、12.4）", because)
                .isEqualTo(baseline.status());
        assertThat(invocation.keys())
                .as("%s：响应字段集与无故障时逐项相同（需求 4.15、12.4）", because)
                .isEqualTo(baseline.keys());
        assertThat(invocation.keys())
                .as("%s：响应字段集与该接口的既有契约逐项相同（需求 12.4）", because)
                .isEqualTo(expectedKeysOf(endpoint));

        // ── ③ 记账响应不含任何成就 / 播报 / 徽章字段（需求 4.15）────────────────────────
        if (endpoint == Endpoint.RECORD) {
            for (String marker : RECORD_FORBIDDEN_MARKERS) {
                assertThat(invocation.rawBody())
                        .as("%s：记账响应不含成就 / 播报 / 徽章字段 %s（需求 4.15）", because, marker)
                        .doesNotContain(marker);
            }
        }

        // ── ④ 三表无部分写入（需求 4.14）+ 故障确实发生了的守卫 ─────────────────────────
        assertNoPartialWrites(fault, endpoint, target, before, because);
        assertFaultActuallyHappened(fault, endpoint, appender.list, because);

        // ── ⑤ 失败自愈：故障解除后再次触发结算，补齐上次未写入的事件（需求 4.16）───────────
        if (endpoint == Endpoint.RECORD && THROWING_FAULTS.contains(fault)) {
            assertHealsOnNextSettlement(target, because);
        }
    }

    /**
     * 故障解除后再记一笔 → 该次结算把上次未写入的事件<b>补齐</b>（需求 4.16：判定幂等可重入，失败自愈）。
     *
     * <p>本次结算不会被 60 秒记账节流窗口跳过：上一次结算整体回滚，连 {@code user_growth} 建档行都没留下
     * （{@link #assertNoPartialWrites} 刚断言过三表逐行不变），故
     * {@code GrowthSettlementService.isThrottled} 读不到档案行、直接放行。</p>
     *
     * <p>账户与分类沿用 {@link Ctx} 里那两条（上一次记账用的同一对），避免重名建号带来的无关失败。</p>
     */
    private void assertHealsOnNextSettlement(Ctx ctx, String because) {
        ACTIVE_FAULT.set(null);
        ResponseEntity<String> healed = rest.exchange(url("/api/transactions"), HttpMethod.POST,
                new HttpEntity<>(recordPayload(ctx.accountId(), ctx.categoryId()),
                        authJson(ctx.token())), String.class);

        assertThat(healed.getStatusCode().value())
                .as("%s：故障解除后的记账仍是 201", because).isEqualTo(201);
        assertThat(eventKeysOf(ctx.userId()))
                .as("%s：下一次结算补齐上次未写入的事件（需求 4.16）", because)
                .contains("DAILY_RECORD:" + LocalDate.now(BUSINESS_ZONE), "FIRST_RECORD",
                        "BADGE:FIRST_RECORD");
    }

    private List<String> eventKeysOf(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT event_key FROM growth_events WHERE user_id = ? ORDER BY id", String.class, userId);
    }

    // ---------------- 断言：成就清单契约（需求 6.7）----------------

    /**
     * 断言成就清单在该故障下仍返回完整契约：顶层恰好 3 项、恒 16 个视图、每个视图恰好 9 项、
     * 不返回任何错误码（需求 6.1、6.3、6.7），并逐故障点加一条「降级确实发生了」的守卫。
     *
     * @return 该次响应的响应体，供调用方比对两次请求的字段集
     */
    private Map<String, Object> assertListContract(FaultPoint fault, Ctx ctx, String stage) {
        ResponseEntity<Map> response = get("/api/achievements", bearer(ctx.token()));
        String because = String.format("故障 %s / 成就清单 %s", fault, stage);

        assertThat(response.getStatusCode().value())
                .as("%s：结算失败或被节流不改变成就清单的状态码（需求 6.7）", because).isEqualTo(200);
        Map<String, Object> body = body(response);
        assertThat(body.keySet())
                .as("%s：顶层恰好 3 项且不含错误码（需求 6.7）", because)
                .containsExactlyInAnyOrderElementsOf(LIST_TOP_KEYS);

        List<Map<String, Object>> views = listOf(body, "achievements");
        assertThat(views).as("%s：成就视图恒 16 项（需求 1.12）", because).hasSize(TOTAL_ACHIEVEMENTS);
        for (Map<String, Object> view : views) {
            assertThat(view.keySet())
                    .as("%s：成就视图 %s 恰好 9 项（需求 6.3）", because, view.get("code"))
                    .containsExactlyInAnyOrderElementsOf(VIEW_KEYS);
        }
        assertThat(((Number) body.get("total")).intValue())
                .as("%s：成就总数恒 16", because).isEqualTo(TOTAL_ACHIEVEMENTS);

        // 逐故障点的「降级确实发生了」守卫（见类级 Javadoc）。
        switch (fault) {
            case SINGLE_AGGREGATE_THROWS -> {
                assertThat(currentOf(views, "TRAVEL_MASTER"))
                        .as("%s：旅行口径聚合失败 ⇒ 本次以 0 计（该用户明明有 2 笔旅行支出，需求 3.14）",
                                because)
                        .isZero();
                assertThat(currentOf(views, "COLLAB_1"))
                        .as("%s：其余口径照常返回真实取值（逐口径独立降级，需求 3.14）", because)
                        .isEqualTo(1);
            }
            case ACHIEVEMENT_EVAL_THROWS -> {
                assertThat(currentOf(views, "COLLAB_1"))
                        .as("%s：协作口径聚合失败 ⇒ 本次以 0 计（该用户明明有 1 个 EDITOR 成员行，需求 3.14）",
                                because)
                        .isZero();
                assertThat(currentOf(views, "TRAVEL_MASTER"))
                        .as("%s：其余口径照常返回真实取值（逐口径独立降级，需求 3.14）", because)
                        .isEqualTo(2);
            }
            case UNKNOWN_BADGE_ROW -> assertThat(views.stream().map(view -> view.get("code")).toList())
                    .as("%s：未知编码不出现在成就清单里（需求 1.12）", because)
                    .doesNotContain(UNKNOWN_BADGE_CODE);
            default -> {
                // 其余三个故障点不影响读取侧的口径取值：两条聚合都应给出真实取值。
                assertThat(currentOf(views, "TRAVEL_MASTER"))
                        .as("%s：旅行口径未被注入故障，应给出真实取值 2", because).isEqualTo(2);
                assertThat(currentOf(views, "COLLAB_1"))
                        .as("%s：协作口径未被注入故障，应给出真实取值 1", because).isEqualTo(1);
            }
        }
        return body;
    }

    private static int currentOf(List<Map<String, Object>> views, String code) {
        Map<String, Object> view = views.stream()
                .filter(item -> code.equals(item.get("code")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("成就清单里不存在编码 " + code));
        return ((Number) view.get("current")).intValue();
    }

    // ---------------- 断言：三表无部分写入（需求 4.14）----------------

    /**
     * 三表无部分写入。规则按「本次是否真的会写成就侧数据」分三档，每一档都是<b>该情形下最强</b>的断言：
     *
     * <ol>
     *   <li><b>注销</b>：该用户在三表的行数终态恒为 0——删干净而不是删一半（需求 11.1、11.3）。</li>
     *   <li><b>记账 + {@link FaultPoint#UNKNOWN_BADGE_ROW}</b>：这是唯一「故障之下结算仍会成功提交」的
     *       组合（未知行只被忽略，不使结算失败）。因此不能断言快照不变，改断言<b>只追加不改写</b>：
     *       请求前的每一行仍原样存在（含那条未知 {@code BADGE} 行的全部列，需求 12.7），
     *       且 {@code exp} 恒等于该用户全部事件 {@code exp_amount} 之和——后者是「没有写到一半」的
     *       完整性等式，部分写入必然破坏它。</li>
     *   <li><b>其余全部组合</b>：三表快照与请求前<b>逐行逐列相等</b>。这一档覆盖了四个抛异常的故障点
     *       （{@code REQUIRES_NEW} 整体回滚，连第 ② 步的建档行都不许留下）、被节流的故障点
     *       （节流判定发生在写入任何行之前）以及四类根本不触发结算的接口。</li>
     * </ol>
     */
    private void assertNoPartialWrites(FaultPoint fault, Endpoint endpoint, Ctx ctx,
                                       TableSnapshot before, String because) {
        long userId = ctx.userId();
        TableSnapshot after = snapshotOf(userId);

        if (endpoint == Endpoint.DELETION) {
            assertThat(after.events()).as("%s：注销后该用户成长事件零残留（需求 11.1）", because).isEmpty();
            assertThat(after.profiles()).as("%s：注销后该用户成长档案零残留（需求 11.1）", because).isEmpty();
            assertThat(after.notices()).as("%s：注销后该用户播报游标零残留（需求 11.1）", because).isEmpty();
            return;
        }

        if (endpoint == Endpoint.RECORD && fault == FaultPoint.UNKNOWN_BADGE_ROW) {
            assertThat(after.events())
                    .as("%s：growth_events 只追加不改写，请求前的每一行仍原样存在（需求 12.7）", because)
                    .containsAll(before.events());
            assertThat(rowOfKey(after.events(), UNKNOWN_BADGE_KEY))
                    .as("%s：未知 BADGE 行的全部列取值一字不变（需求 1.12）", because)
                    .isEqualTo(rowOfKey(before.events(), UNKNOWN_BADGE_KEY));
            assertThat(profileExpOf(userId))
                    .as("%s：exp 恒等于事件 exp_amount 之和——部分写入必然破坏这条等式", because)
                    .isEqualTo(expSumOf(userId));
            return;
        }

        assertThat(after)
                .as("%s：三表逐行逐列不变，无任何部分写入（需求 4.14）", because)
                .isEqualTo(before);
    }

    /**
     * 「故障确实发生了」的守卫：只在记账接口上可观测（它是唯一触发结算的那一类）。
     *
     * <p>四个抛异常的故障点必须留下一条含用户 id 的 {@code [GROWTH_SETTLE_FAILED]} WARN
     * （需求 4.14）；被节流与未知 {@code BADGE} 行两个故障点<b>必须没有</b>这条 WARN
     * ——「被跳过」「被忽略」与「失败了」是三种不同的降级，混为一谈就会让上面的表断言失去分辨力。</p>
     */
    private void assertFaultActuallyHappened(FaultPoint fault, Endpoint endpoint,
                                             List<ILoggingEvent> logs, String because) {
        if (endpoint != Endpoint.RECORD) {
            return;
        }
        List<String> warns = logs.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        boolean settleFailed = warns.stream().anyMatch(message -> message.contains(SETTLE_FAILED_MARKER));

        if (THROWING_FAULTS.contains(fault)) {
            assertThat(settleFailed)
                    .as("%s：结算应因该故障失败并记一条 %s WARN（需求 4.14）；实际 WARN=%s",
                            because, SETTLE_FAILED_MARKER, warns)
                    .isTrue();
        } else {
            assertThat(settleFailed)
                    .as("%s：本故障不应让结算失败（被跳过 / 被忽略不是失败）；实际 WARN=%s", because, warns)
                    .isFalse();
        }
    }

    // ---------------- 无故障基线 ----------------

    /** 某接口在无故障时的状态码与响应字段集（{@code null} 键集合表示 204 无响应体）。 */
    private record Baseline(int status, Set<String> keys) {
    }

    /** 一次接口调用的观察量：状态码、响应字段集与原始 JSON 文本。 */
    private record Invocation(int status, Set<String> keys, String rawBody) {
    }

    /**
     * 该接口的无故障基线（每个接口只测一次后缓存）。
     *
     * <p>基线用的是与故障用例<b>结构完全相同</b>的一个全新用户（同样的播种、同样的调用序列），
     * 只是不激活任何故障——否则「与无故障时逐项相同」比的就不是同一件事。</p>
     */
    private Baseline baselineOf(Endpoint endpoint) throws Exception {
        Baseline cached = BASELINES.get(endpoint);
        if (cached != null) {
            return cached;
        }
        ACTIVE_FAULT.set(null);
        Invocation invocation = invoke(endpoint, newUserWithFacts(false));
        assertThat(invocation.keys())
                .as("无故障基线：接口 %s 的响应字段集应等于其既有契约（需求 12.4）", endpoint)
                .isEqualTo(expectedKeysOf(endpoint));
        Baseline baseline = new Baseline(invocation.status(), invocation.keys());
        BASELINES.put(endpoint, baseline);
        return baseline;
    }

    /** 各接口的既有响应字段集（独立副本，用于把契约本身也钉住，而不只是「两次调用彼此相等」）。 */
    private static Set<String> expectedKeysOf(Endpoint endpoint) {
        return switch (endpoint) {
            case RECORD -> RECORD_KEYS;
            case BUDGET -> BUDGET_KEYS;
            case LOGIN -> LOGIN_KEYS;
            case INVITE -> INVITE_KEYS;
            // 注销成功是 204 且无响应体：字段集为空集，而不是「有字段但取值为空」。
            case DELETION -> Set.of();
        };
    }

    // ---------------- 接口调用 ----------------

    /** 调一次目标接口，返回状态码、响应字段集与原始 JSON 文本。 */
    private Invocation invoke(Endpoint endpoint, Ctx ctx) throws Exception {
        return switch (endpoint) {
            case RECORD -> observe(rest.exchange(url("/api/transactions"), HttpMethod.POST,
                    new HttpEntity<>(recordPayload(ctx.accountId(), ctx.categoryId()),
                            authJson(ctx.token())), String.class));
            case BUDGET -> observe(rest.exchange(url("/api/budgets?month=" + YearMonth.now()),
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("amount", "8000.00"), authJson(ctx.token())), String.class));
            case LOGIN -> {
                // 同一邮箱再登录一次：账号已存在，走的是「已有用户登录」这条主路径。
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

    /** 把响应折成 {@link Invocation}：空响应体（204）的字段集是空集。 */
    private Invocation observe(ResponseEntity<String> response) throws Exception {
        HttpStatusCode status = response.getStatusCode();
        String raw = response.getBody();
        Set<String> keys = (raw == null || raw.isBlank()) ? Set.of() : parse(raw).keySet();
        return new Invocation(status.value(), keys, raw == null ? "" : raw);
    }

    // ---------------- 故障激活 ----------------

    /**
     * 激活故障中<b>需要预置数据</b>的那两个（其余四个由 {@link FaultConfig} 的代理在运行期抛异常实现）。
     *
     * <ul>
     *   <li>{@link FaultPoint#UNKNOWN_BADGE_ROW}：直插一条 {@code BADGE:<不在清单内的编码>} 行；</li>
     *   <li>{@link FaultPoint#SETTLEMENT_THROTTLED}：直插一行 {@code user_growth}，把
     *       {@code last_settled_at} 摆在当前时刻、{@code last_record_date} 摆在今天，使记账侧 60 秒
     *       窗口的<b>两个</b>条件同时成立（缺一不可，见 {@code GrowthSettlementService.isThrottled}）。</li>
     * </ul>
     */
    private void applyFault(FaultPoint fault, Ctx ctx) {
        // 预置的 last_settled_at / last_record_date 必须与服务端判定节流用的时钟同一时区。
        // GrowthSettlementThrottle 一律用注入的 Clock（TimeConfig 固定 Asia/Shanghai）算「现在」，
        // 而 DATETIME 列存的是东八区挂钟（application.yml 刻意不设 hibernate.jdbc.time_zone）。
        // 若这里改用 JVM 默认时区的 LocalDateTime.now()，在 UTC 的 CI 上写入的挂钟会比服务端的「现在」
        // 早 8 小时，60 秒窗口判定不成立，SETTLEMENT_THROTTLED 用例便不再被节流（本地东八区下巧合通过）。
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE).withNano(0);
        switch (fault) {
            case UNKNOWN_BADGE_ROW -> jdbcTemplate.update(
                    "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                            + "VALUES (?, ?, ?, 0, ?)",
                    ctx.userId(), GrowthEventType.BADGE, UNKNOWN_BADGE_KEY, Timestamp.valueOf(now));
            case SETTLEMENT_THROTTLED -> jdbcTemplate.update(
                    "INSERT INTO user_growth "
                            + "(user_id, exp, level, total_record_days, current_streak_days, "
                            + "max_streak_days, last_record_date, last_settled_at, created_at, updated_at) "
                            + "VALUES (?, 0, 1, 1, 1, 1, ?, ?, ?, ?)",
                    ctx.userId(), LocalDate.now(BUSINESS_ZONE), Timestamp.valueOf(now),
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
            default -> {
                // 其余四个故障点无需预置数据：由 FaultConfig 的动态代理在被调用时抛异常。
            }
        }
    }

    // ---------------- 用户与事实源 ----------------

    /**
     * 一次迭代用到的用户上下文。
     *
     * <p>{@code accountId} / {@code categoryId} 是经真实接口建好的一个账户与一个支出分类：记账接口
     * 与「失败自愈」那次补记都用它们，因此两次记账落在同一个账户与同一个分类上，不引入重名建号
     * 之类与本属性无关的失败。</p>
     */
    private record Ctx(String email, String token, long userId, long ledgerId,
                       long accountId, long categoryId) {
    }

    /**
     * 建一个全新用户并播种一份让两条聚合<b>都非零</b>的事实源。
     *
     * <p>播种内容：今天 3 笔普通支出（使记账日历与笔数口径非零）、2 笔落在「旅行」分类下的支出
     * （{@code TRAVEL_RECORD_COUNT} = 2），以及按 {@code withCollaborator} 决定是否加 1 个他人以
     * {@code EDITOR} 身份加入自有账本的成员行（{@code COLLAB_MEMBER_COUNT} = 1）。两个非零取值是
     * {@link FaultPoint#SINGLE_AGGREGATE_THROWS} 与 {@link FaultPoint#ACHIEVEMENT_EVAL_THROWS}
     * 「降级为 0」那条守卫的前提——若播种成 0，「降级到 0」与「本来就是 0」无从区分。</p>
     *
     * <p><b>{@code withCollaborator} 为什么必须可关</b>：注销的前置校验
     * （{@code AccountDeletionService.requireDeletable}）一旦发现自有账本上存在他人成员行，就抛
     * {@code DELETE_BLOCKED_COLLAB}（409）。带着成员行的用户根本走不到注销成功路径上，
     * 「注销这一类接口的状态码与字段集不变」就变成了在比对两个 409 错误体——那不是需求 12.4 说的东西。
     * 故只有成就清单探针用户带成员行（协作口径的降级守卫需要它），目标用户一律不带。</p>
     *
     * <p>事实源一律经 {@link JdbcTemplate} 直插而不走记账接口：这样「本次是第几次结算」是确定的
     * （记账接口会在 {@code afterCommit} 里自己触发一次结算），三表快照的比对才有意义。
     * {@code account_id} 取「绝不可能是真实主键」且按用户隔离的高位占位取值，免得与另一迭代里的真实
     * {@code account.id} 撞号——注销前置校验会拿本人账户 id 去反查「是否被他人记的交易引用」，
     * 撞号会把注销误判成 {@code DELETE_BLOCKED_COLLAB}。</p>
     */
    private Ctx newUserWithFacts(boolean withCollaborator) {
        String email = "ach_fault_" + SEQ.getAndIncrement() + "@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        long ledgerId = ledgerService.ensureDefaultLedger(userId).getId();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        long ref = 900_000_000L + userId;
        for (int i = 0; i < 3; i++) {
            seedTransaction(userId, ledgerId, "expense", "12.34", today, ref);
        }
        // 「旅行」父分类 + 2 笔支出 → TRAVEL_RECORD_COUNT = 2。
        long travelCategoryId = insertCategory(userId, ledgerId, "旅行");
        seedTransaction(userId, ledgerId, "expense", "56.78", today, travelCategoryId);
        seedTransaction(userId, ledgerId, "expense", "56.78", today, travelCategoryId);
        if (withCollaborator) {
            // 他人以 EDITOR 加入自有账本 → COLLAB_MEMBER_COUNT = 1。
            jdbcTemplate.update(
                    "INSERT INTO ledger_members (ledger_id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
                    ledgerId, SEQ.getAndIncrement(), LedgerMember.ROLE_EDITOR,
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        }

        // 记账接口用到的账户与分类：经真实接口建好，供记账与「失败自愈」那次补记复用。
        // 上面直插的交易一律用占位 account_id，故这个真实账户不被任何交易引用，不会挡住注销。
        long accountId = createAccount(token);
        long categoryId = createCategory(token, "餐饮");

        return new Ctx(email, token, userId, ledgerId, accountId, categoryId);
    }

    /** 一笔「有效记账交易」：{@code created_by} = 用户、{@code deleted_at} 为 NULL、{@code ledger_id} 非空。 */
    private void seedTransaction(long userId, long ledgerId, String type, String amount,
                                LocalDate day, long categoryId) {
        Timestamp at = Timestamp.valueOf(day.atTime(12, 0));
        jdbcTemplate.update(INSERT_TX_SQL, userId, ledgerId, userId, type, new BigDecimal(amount),
                900_000_000L + userId, categoryId, at, at, at);
    }

    private long insertCategory(long userId, long ledgerId, String name) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().withNano(0));
        jdbcTemplate.update("INSERT INTO categories "
                        + "(user_id, ledger_id, parent_id, kind, name, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'EXPENSE', ?, ?, ?)",
                userId, ledgerId, name, now, now);
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM categories WHERE user_id = ? AND name = ?", Long.class, userId, name);
        assertThat(id).as("分类应已建立").isNotNull();
        return id;
    }

    // ---------------- 三表快照 ----------------

    /**
     * 三表中属于该用户的全部行（逐列），{@code record} 的 {@code equals} 逐字段比较。
     *
     * <p>比对<b>整行</b>而不是行数：只数行数看不出「某一行被改写」这类部分写入。</p>
     */
    private record TableSnapshot(List<Map<String, Object>> events, List<Map<String, Object>> profiles,
                                 List<Map<String, Object>> notices) {
    }

    private TableSnapshot snapshotOf(long userId) {
        return new TableSnapshot(
                jdbcTemplate.queryForList(
                        "SELECT id, user_id, event_type, event_key, exp_amount, created_at "
                                + "FROM growth_events WHERE user_id = ? ORDER BY id", userId),
                jdbcTemplate.queryForList(
                        "SELECT user_id, exp, level, total_record_days, current_streak_days, "
                                + "max_streak_days, last_record_date, last_settled_at, created_at, "
                                + "updated_at FROM user_growth WHERE user_id = ?", userId),
                jdbcTemplate.queryForList(
                        "SELECT user_id, last_notified_event_id, created_at, updated_at "
                                + "FROM achievement_notices WHERE user_id = ?", userId));
    }

    private static Map<String, Object> rowOfKey(List<Map<String, Object>> rows, String eventKey) {
        return rows.stream()
                .filter(row -> eventKey.equals(row.get("event_key")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("成长事件里不存在事件键 " + eventKey));
    }

    private long profileExpOf(long userId) {
        Long exp = jdbcTemplate.queryForObject(
                "SELECT exp FROM user_growth WHERE user_id = ?", Long.class, userId);
        return exp == null ? 0L : exp;
    }

    private long expSumOf(long userId) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(exp_amount), 0) FROM growth_events WHERE user_id = ?",
                Long.class, userId);
        return sum == null ? 0L : sum;
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

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String rawJson) throws Exception {
        return objectMapper.readValue(rawJson, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
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

    /** 发一条「新鲜」验证码（先清历史码以规避 60s 发码冷却）。 */
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

    // ---------------- 测试基础设施 ----------------

    /**
     * 可注入故障的三个仓储：{@code @Primary} 的 JDK 动态代理，默认全部方法透明委托给真实仓储，
     * 仅当 {@link #ACTIVE_FAULT} 指向对应故障点时让特定方法抛异常。
     *
     * <p>故障刻意下沉到仓储层而不是对 {@code GrowthSettlementService} 做 Mockito spy：对带
     * {@code @Transactional} 的类做 spy 会绕过 Spring 的事务代理、令 {@code REQUIRES_NEW} 失效，
     * 而「结算整体回滚、三表无部分写入」正是本属性要验的东西（详见类级 Javadoc）。</p>
     */
    @TestConfiguration
    static class FaultConfig {

        @Bean
        @Primary
        TransactionRepository faultyTransactionRepository(
                @Qualifier("transactionRepository") TransactionRepository real) {
            return proxy(TransactionRepository.class, real, Map.of(
                    "countTravelExpenses", FaultPoint.SINGLE_AGGREGATE_THROWS,
                    "sumMonthlyAmountsByCreatedByGroupByMonthAndType",
                    FaultPoint.SAVING_MONTH_EVAL_THROWS));
        }

        @Bean
        @Primary
        LedgerMemberRepository faultyLedgerMemberRepository(
                @Qualifier("ledgerMemberRepository") LedgerMemberRepository real) {
            return proxy(LedgerMemberRepository.class, real,
                    Map.of("countEditorsOfOwnedLedgers", FaultPoint.ACHIEVEMENT_EVAL_THROWS));
        }

        @Bean
        @Primary
        UserGrowthRepository faultyUserGrowthRepository(
                @Qualifier("userGrowthRepository") UserGrowthRepository real) {
            return proxy(UserGrowthRepository.class, real,
                    Map.of("findForUpdateById", FaultPoint.LOCK_ABANDONED));
        }

        /**
         * 建一个透明委托的动态代理：{@code faults} 里的方法名在对应故障点激活时抛异常，其余一律委托。
         *
         * <p>{@code findForUpdateById} 抛 {@link PessimisticLockingFailureException} 而不是随便一个
         * 运行时异常：只有它会被 {@code lockProfileWithBudget} 的退避循环识别，从而在 500ms 墙钟预算
         * 与 3 次退避用尽后抛出 {@code GrowthLockAbandonedException}——「行锁放弃」这条分支要的是
         * 那条真实路径，直接抛别的异常等于跳过了退避逻辑。</p>
         */
        private static <T> T proxy(Class<T> type, T real, Map<String, FaultPoint> faults) {
            return type.cast(Proxy.newProxyInstance(
                    type.getClassLoader(), new Class<?>[] {type},
                    (proxyRef, method, args) -> {
                        FaultPoint activeFault = faults.get(method.getName());
                        if (activeFault != null && activeFault == ACTIVE_FAULT.get()) {
                            if (activeFault == FaultPoint.LOCK_ABANDONED) {
                                throw new PessimisticLockingFailureException(
                                        "注入：FOR UPDATE NOWAIT 取不到行级写锁");
                            }
                            throw new IllegalStateException("注入：" + method.getName() + " 查询失败");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }));
        }
    }
}
