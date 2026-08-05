package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.service.GrowthLevelCurve;
import com.damien.youyu.service.LedgerService;
import com.damien.youyu.service.GrowthSettlementService;
import com.damien.youyu.service.SettleOutcome;
import com.damien.youyu.service.TriggerSource;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 成长体系既有契约的兼容集成测试（任务 7.4，需求 12.1～12.11）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：真实 HTTP、真实过滤链与 JWT、真实
 * {@link com.damien.youyu.service.GrowthQueryService} / {@link com.damien.youyu.service.AchievementQueryService}
 * 与 H2（{@code MODE=MySQL}）持久化层。账号一律经 {@code /api/auth/email-login} 真实建立。</p>
 *
 * <h2>四组断言</h2>
 * <ol>
 *   <li><b>概览徽章列表与成就清单逐项相等</b>（需求 12.1、12.2、12.3、12.10）：在<b>五种用户状态</b>
 *       ——零数据 / 部分解锁 / 全解锁 / 结算被节流的间隙态 / 结算失败的间隙态——下断言概览徽章列表
 *       第 N 项与成就清单第 N 项在 6 项（编码、名称、是否已点亮、解锁时刻、目标值、当前值）上逐项相等，
 *       且概览顶层仍恰好 15 项、徽章项仍恰好 6 项。两个间隙态是这组断言的关键：结算没跑，
 *       「条件已成立但 {@code BADGE} 行尚未写入」的项在两条路径上都必须是「未点亮 + 当前值等于门槛」，
 *       任何一条路径自行组装 facts 都会在这里错开。</li>
 *   <li><b>五类既有接口的响应字段集与错误码不变</b>（需求 12.4）：记账 / 预算 / 登录 / 注销 / 邀请
 *       各断言成功响应的字段集恰好等于既有取值集合、错误响应的 {@code code} 等于既有错误码且错误体
 *       恰好 3 项；记账响应的原始 JSON 不含任何成就 / 播报 / 徽章字段，本 spec 新增的
 *       {@code ACHIEVEMENT_ACK_PARAM_INVALID} 不出现在这五类接口的任何响应里。</li>
 *   <li><b>经验明细不过滤零经验行</b>（需求 12.5）：造出储蓄月与成就后，明细接口返回
 *       {@code SAVING_MONTH} 与 {@code BADGE} 两类 {@code exp_amount = 0} 的行，且总条数把它们计入
 *       （{@code total} 等于该用户 {@code growth_events} 的真实行数）；顶层与列表项字段集不变。</li>
 *   <li><b>等级阈值与六类经验事件的经验值不变</b>（需求 12.6）：全部 100 级的阈值逐级等于
 *       {@code 2(L−1)² + 8(L−1)}；造出六类经验事件各至少一条，逐类断言 {@code exp_amount} 仍为
 *       5 / 10 / 30 / 100 / 50 / 80，{@code BADGE} 与 {@code SAVING_MONTH} 恒为 0，
 *       且 {@code user_growth.exp} 等于事件 {@code exp_amount} 之和。</li>
 * </ol>
 *
 * <h2>两个间隙态怎么稳定造出来</h2>
 * <p>{@link ProbeSettlementService} 是一个 {@code @Primary} 的 {@link GrowthSettlementService} 子类，
 * 默认<b>委托</b>给真实（被事务代理包裹的）结算 bean，只在委托前后计数与记录结算结果，并可按需在委托前
 * 抛出注入异常。<b>刻意不用 Mockito spy</b>：对带 {@code @Transactional} 的类做 spy 会绕过 Spring 的
 * 事务代理、令 {@code REQUIRES_NEW} 失效。</p>
 * <ul>
 *   <li><b>被节流的间隙态</b>：先记 10 笔并请求一次概览让结算真实执行（同时打上 10 秒节流窗口），
 *       随后再补 90 笔使 {@code RECORD_100} 的条件成立；紧接着的两次请求落在窗口内被跳过
 *       （断言结算结果为 {@link SettleOutcome#SKIPPED_THROTTLED}），于是库里的解锁状态落后于事实。</li>
 *   <li><b>结算失败的间隙态</b>：同样先造出落后状态，再让 {@code settle} 抛异常；两次请求各尝试一次
 *       结算并各自失败，响应照常返回、不含错误码。</li>
 * </ul>
 *
 * <p>结算真实提交，清理不能靠事务回滚：{@link #resetProbe()} 每个用例前重置装饰器。各用例使用<b>各自
 * 独立的邮箱与用户 id</b>（节流器是进程内单例、无清理入口），因此方法间互不影响。使用独立命名的内存库，
 * 避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Validates: Requirements 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.8, 12.9, 12.10, 12.11</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-parity-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
@Import(AchievementOverviewParityIntegrationTest.ProbeConfig.class)
class AchievementOverviewParityIntegrationTest {

    /** 成长概览的 15 个顶层字段（growth-level-system 需求 10.1；本 spec 需求 12.1 要求一字不改）。 */
    private static final Set<String> OVERVIEW_TOP_KEYS = Set.of(
            "level", "exp", "currentLevelExp", "nextLevelExp", "expInCurrentLevel", "expToNextLevel",
            "maxLevel", "maxLevelReached", "totalRecordCount", "totalExpense", "totalIncome",
            "totalRecordDays", "currentStreakDays", "maxStreakDays", "badges");

    /** 徽章列表项的 6 个字段（需求 12.1：不得新增描述 / 分类 / 口径 / 事件 id 四项中的任何一项）。 */
    private static final Set<String> BADGE_KEYS =
            Set.of("code", "name", "unlocked", "unlockedAt", "target", "current");

    /** 徽章项与成就视图逐项相等的 6 个字段，按需求 12.3 的对应关系同名（需求 12.3）。 */
    private static final List<String> SHARED_KEYS =
            List.of("code", "name", "unlocked", "unlockedAt", "target", "current");

    /** 16 枚成就的编码与展示顺序（需求 12.2 表格的独立副本，用于锁住清单与顺序不漂移）。 */
    private static final List<String> CATALOG_CODES = List.of(
            "FIRST_RECORD",
            "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365",
            "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000", "DAYS_100",
            "INVITE_1", "COLLAB_1",
            "BUDGET_MET", "BUDGET_MASTER", "SAVING_MASTER", "TRAVEL_MASTER");

    private static final int TOTAL_ACHIEVEMENTS = 16;

    /** 记账接口响应字段集（需求 12.4：不新增、不删除、不重命名任何字段）。 */
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

    /** 统一错误体字段集（需求 12.4：五类接口的错误形状同样不变）。 */
    private static final Set<String> ERROR_KEYS = Set.of("code", "message", "field");

    /** 经验明细顶层与列表项字段集（需求 12.5：一字不改）。 */
    private static final Set<String> EVENT_PAGE_TOP_KEYS = Set.of("items", "total");
    private static final Set<String> EVENT_ITEM_KEYS =
            Set.of("id", "eventType", "eventKey", "expAmount", "createdAt");

    /**
     * 记账响应里绝不允许出现的成就 / 播报 / 徽章字段名（需求 12.4）。
     *
     * <p>按<b>原始 JSON 文本</b>比对而不是按解析后的键集合：嵌套一层的泄漏（例如给某个子对象挂上
     * {@code badges}）不会改变顶层键集合，只有文本比对能拦住。</p>
     *
     * <p>{@code level} / {@code exp} 两个成长字段刻意写成<b>带引号的键形式</b>：裸子串 {@code exp}
     * 会被 {@code type} 的合法取值 {@code "expense"} 命中，那样的断言不是更严而是恒假。</p>
     */
    private static final List<String> RECORD_FORBIDDEN_MARKERS = List.of(
            "achievement", "Achievement", "badge", "Badge", "unlock", "Unlock",
            "pending", "Pending", "notice", "Notice", "broadcast", "Broadcast",
            "lastNotifiedEventId", "\"level\"", "\"exp\"", "\"badges\"");

    /** 六类经验事件的经验值（growth-level-system 的既有取值；需求 12.6 要求一个不改）。 */
    private static final Map<String, Integer> EXP_BY_EVENT_KEY = Map.of(
            "DAILY_RECORD", 5,
            "FIRST_RECORD", 10,
            "STREAK_7", 30,
            "STREAK_30", 100,
            "BUDGET_MET", 50,
            "FIRST_INVITE", 80);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private VerificationCodeRepository verificationCodeRepository;
    @Autowired
    private GrowthLevelCurve levelCurve;
    @Autowired
    private ProbeSettlementService probe;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetProbe() {
        probe.reset();
    }

    // ============ 1) 五种用户状态下的逐项相等（需求 12.1、12.2、12.3、12.10）============

    /** 状态一：零数据新用户——16 项全未解锁，逐项相等在「未解锁项」上成立。 */
    @Test
    void zeroDataUser_overviewBadgesEqualAchievementViewsItemByItem() {
        String token = registerAndLogin("ach_parity_zero@example.com");
        warmUpSettlement(token);

        assertThat(assertParity("零数据", token))
                .as("零数据用户 16 项全未解锁（需求 12.10）").isZero();
    }

    /** 状态二：部分解锁——已解锁与未解锁两类项同时被逐项相等覆盖（需求 12.3 后半句）。 */
    @Test
    void partiallyUnlockedUser_overviewBadgesEqualAchievementViewsItemByItem() {
        String email = "ach_parity_partial@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        // 10 笔当天有效记账：命中 FIRST_RECORD（门槛 1）与 RECORD_10（门槛 10），其余 14 枚未解锁。
        seedRecordsOn(userId, ledgerIdOf(userId), LocalDate.now(), 10);
        warmUpSettlement(token);

        assertThat(assertParity("部分解锁", token))
                .as("部分解锁：已解锁与未解锁两类项都存在").isEqualTo(2);
    }

    /** 状态三：全解锁——16 项全部已解锁，当前值恒等于门槛、解锁时刻两侧同为非空且相等。 */
    @Test
    void fullyUnlockedUser_overviewBadgesEqualAchievementViewsItemByItem() {
        String email = "ach_parity_full@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        seedRecordsOn(userId, ledgerIdOf(userId), LocalDate.now(), 3);
        // 直接落齐 16 行 BADGE 事件：是否解锁的唯一依据就是这些行（需求 2.3、2.10），
        // 逐枚给不同的 created_at，使「解锁时刻取该行 created_at」在两条路径上可逐枚区分。
        seedAllBadgeRows(userId);
        warmUpSettlement(token);

        assertThat(assertParity("全解锁", token))
                .as("16 枚全部已解锁").isEqualTo(TOTAL_ACHIEVEMENTS);
    }

    /** 状态四：结算被 10 秒窗口节流的间隙态——库里的解锁状态落后于事实（需求 12.10）。 */
    @Test
    void throttledSettlementGap_overviewBadgesEqualAchievementViewsItemByItem() {
        String email = "ach_parity_throttled@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        long ledgerId = ledgerIdOf(userId);

        // 先让结算真实执行一次（并打上 10 秒节流窗口）：解锁 FIRST_RECORD 与 RECORD_10。
        seedRecordsOn(userId, ledgerId, LocalDate.now(), 10);
        warmUpSettlement(token);
        // 再补 90 笔（经仓储直插，不触发结算）：RECORD_100 的条件成立，但 BADGE 行尚未写入。
        seedRecordsOn(userId, ledgerId, LocalDate.now(), 90);

        probe.reset();
        int unlocked = assertParity("结算被节流的间隙态", token);

        // 直接证明两次请求的结算都走了节流分支（需求 12.9：复用概览侧同一节流器，不新增第三类触发时机）。
        assertThat(probe.outcomes())
                .as("两次请求的结算均被 10 秒窗口跳过")
                .containsExactly(SettleOutcome.SKIPPED_THROTTLED, SettleOutcome.SKIPPED_THROTTLED);
        assertThat(unlocked).as("间隙态下已解锁仍为 2 枚（RECORD_100 的行尚未写入）").isEqualTo(2);
        assertGapStateOnRecordHundred(token);
    }

    /** 状态五：结算失败的间隙态——两条读取路径都降级，字段集与相等性一条不少（需求 12.10）。 */
    @Test
    void failedSettlementGap_overviewBadgesEqualAchievementViewsItemByItem() {
        String email = "ach_parity_failed@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        long ledgerId = ledgerIdOf(userId);

        seedRecordsOn(userId, ledgerId, LocalDate.now(), 10);
        warmUpSettlement(token);
        seedRecordsOn(userId, ledgerId, LocalDate.now(), 90);

        // 三表快照：结算失败不得留下任何部分写入（需求 12.10）。
        List<Map<String, Object>> eventsBefore = growthEventsOf(userId);
        List<Map<String, Object>> profileBefore = userGrowthRowsOf(userId);
        long noticesBefore = achievementNoticeCount(userId);

        probe.reset();
        probe.throwOnSettle(new IllegalStateException("注入：结算失败"));
        int unlocked = assertParity("结算失败的间隙态", token);

        assertThat(probe.settleCalls()).as("两条读取路径各尝试了一次结算").isEqualTo(2);
        assertThat(unlocked).as("间隙态下已解锁仍为 2 枚").isEqualTo(2);
        assertGapStateOnRecordHundred(token);

        // 结算失败无部分写入、三表逐行不变（需求 12.10）。
        assertThat(growthEventsOf(userId)).isEqualTo(eventsBefore);
        assertThat(userGrowthRowsOf(userId)).isEqualTo(profileBefore);
        assertThat(achievementNoticeCount(userId)).isEqualTo(noticesBefore);
    }

    // ============ 2) 五类既有接口的字段集与错误码不变（需求 12.4）============

    @Test
    void legacyFiveEndpoints_keepFieldSetsAndErrorCodes_andRecordCarriesNoAchievementField()
            throws Exception {
        String email = "ach_parity_legacy@example.com";

        // --- 登录接口：字段集恰好 5 项 ---
        ResponseEntity<Map> login = emailLogin(email, null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(login).keySet()).as("登录响应字段集不变（需求 12.4）")
                .containsExactlyInAnyOrderElementsOf(LOGIN_KEYS);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();

        // --- 邀请接口：字段集恰好 3 项 ---
        ResponseEntity<Map> invite = get("/api/invite", bearer(token));
        assertThat(invite.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(invite).keySet()).as("邀请响应字段集不变（需求 12.4）")
                .containsExactlyInAnyOrderElementsOf(INVITE_KEYS);

        // --- 记账接口：字段集恰好 14 项，且原始 JSON 不含任何成就 / 播报 / 徽章字段 ---
        long accountId = createAccount(token, "现金", "CASH", "1000.00");
        long categoryId = createCategory(token, "EXPENSE", "餐饮");
        ResponseEntity<String> record = postRecord(token, "50.00", accountId, categoryId);
        assertThat(record.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> recordBody = parse(record.getBody());
        assertThat(recordBody.keySet()).as("记账响应字段集不变（需求 12.4）")
                .containsExactlyInAnyOrderElementsOf(RECORD_KEYS);
        for (String marker : RECORD_FORBIDDEN_MARKERS) {
            assertThat(record.getBody())
                    .as("记账响应不含成就 / 播报 / 徽章字段：" + marker)
                    .doesNotContain(marker);
        }

        // --- 预算接口：字段集恰好 12 项 ---
        ResponseEntity<Map> budget = putBudget(token, YearMonth.now().toString(), "8000.00");
        assertThat(budget.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(budget).keySet()).as("预算响应字段集不变（需求 12.4）")
                .containsExactlyInAnyOrderElementsOf(BUDGET_KEYS);

        // --- 四类既有错误码逐条不变，错误体恰好 3 项 ---
        assertErrorUnchanged(postRecordAsMap(token, "0.00", accountId, categoryId),
                HttpStatus.BAD_REQUEST, "AMOUNT_INVALID", "amount");
        assertErrorUnchanged(putBudget(token, "2025-1", "8000.00"),
                HttpStatus.BAD_REQUEST, "BUDGET_MONTH_INVALID", "month");
        assertErrorUnchanged(emailLoginWithCode(email, "000000"),
                HttpStatus.BAD_REQUEST, "CODE_INVALID", "code");
        assertErrorUnchanged(get("/api/invite", new HttpHeaders()),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        // --- 注销接口：错误码不变（错误的注销验证码）+ 成功仍是 204 且无响应体 ---
        assertErrorUnchanged(postDelete(token, Map.of("code", "000000")),
                HttpStatus.BAD_REQUEST, "CODE_INVALID", "code");
        ResponseEntity<String> deleted = rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                new HttpEntity<>(Map.of("code", freshDeleteCode(email)), authJson(token)), String.class);
        assertThat(deleted.getStatusCode()).as("注销成功仍是 204（需求 12.4）")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deleted.getBody()).as("注销成功无响应体").isNull();
    }

    // ============ 3) 经验明细不过滤零经验行（需求 12.5）============

    @Test
    void experienceDetail_returnsZeroExpSavingMonthAndBadgeRows_andCountsThemInTotal() {
        String email = "ach_parity_events@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        long ledgerId = ledgerIdOf(userId);

        // 储蓄月：上月收入 1000.00、支出 100.00 → 结余 900.00 ≥ 收入的两成（200.00）。
        LocalDate lastMonthDay = LocalDate.now().minusMonths(1).withDayOfMonth(15);
        seedTransaction(userId, ledgerId, "income", "1000.00", lastMonthDay.atTime(10, 0), LocalDate.now());
        seedTransaction(userId, ledgerId, "expense", "100.00", lastMonthDay.atTime(11, 0), LocalDate.now());
        warmUpSettlement(token);

        // size 上界仍是既有的 50（需求 12.5：分页入参一字不改）；本用例的事件数远小于一页。
        Map<String, Object> page = body(get("/api/growth/events?page=0&size=50", bearer(token)));
        assertThat(page.keySet()).as("经验明细顶层字段集不变（需求 12.5）")
                .containsExactlyInAnyOrderElementsOf(EVENT_PAGE_TOP_KEYS);

        List<Map<String, Object>> items = listOf(page, "items");
        for (Map<String, Object> item : items) {
            assertThat(item.keySet()).as("经验明细列表项字段集不变（需求 12.5）")
                    .containsExactlyInAnyOrderElementsOf(EVENT_ITEM_KEYS);
        }

        List<Map<String, Object>> savingMonthRows = itemsOfType(items, GrowthEventType.SAVING_MONTH);
        List<Map<String, Object>> badgeRows = itemsOfType(items, GrowthEventType.BADGE);
        assertThat(savingMonthRows).as("明细返回 SAVING_MONTH 行（exp_amount = 0 不被过滤，需求 12.5）")
                .isNotEmpty();
        assertThat(badgeRows).as("明细返回 BADGE 行（exp_amount = 0 不被过滤，需求 12.5）").isNotEmpty();
        assertThat(savingMonthRows).allSatisfy(row ->
                assertThat(((Number) row.get("expAmount")).intValue()).isZero());
        assertThat(badgeRows).allSatisfy(row ->
                assertThat(((Number) row.get("expAmount")).intValue()).isZero());

        // 总条数把这些零经验行计入，且不按 event_type 或 exp_amount 过滤任何行（需求 12.5）。
        long total = ((Number) page.get("total")).longValue();
        assertThat(total).as("total 等于该用户 growth_events 的真实行数").isEqualTo(growthEventCount(userId));
        assertThat(items).as("一页取完，条数等于 total").hasSize((int) total);
        assertThat(total)
                .as("total 大于「排除零经验行」后的条数：零经验行确实被计入")
                .isGreaterThan(growthEventCountWithPositiveExp(userId));
    }

    // ============ 4) 等级阈值与六类经验事件的经验值不变（需求 12.6）============

    @Test
    void levelThresholdFunctionAndSixExpEventAmounts_areUnchanged() {
        // --- 等级阈值函数：threshold(L) = 2(L−1)² + 8(L−1)，逐级比对，参考实现与被测各自独立 ---
        assertThat(GrowthLevelCurve.MAX_LEVEL).as("最高等级仍为 100").isEqualTo(100);
        for (int level = 1; level <= GrowthLevelCurve.MAX_LEVEL; level++) {
            long n = level - 1L;
            assertThat(levelCurve.threshold(level)).as("threshold(%d)", level)
                    .isEqualTo(2L * n * n + 8L * n);
        }
        assertThat(levelCurve.threshold(1)).isZero();
        assertThat(levelCurve.threshold(2)).isEqualTo(10L);
        assertThat(levelCurve.threshold(100)).isEqualTo(20_394L);
        assertThat(levelCurve.levelOf(0L)).isEqualTo(1);
        assertThat(levelCurve.levelOf(9L)).isEqualTo(1);
        assertThat(levelCurve.levelOf(10L)).as("取等号即升级").isEqualTo(2);
        assertThat(levelCurve.levelOf(20_394L)).isEqualTo(100);
        assertThat(levelCurve.levelOf(Long.MAX_VALUE)).as("满级后等级恒为 100").isEqualTo(100);

        // --- 六类经验事件：造出各至少一条，逐类断言 exp_amount ---
        String email = "ach_parity_exp@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        long ledgerId = ledgerIdOf(userId);

        // ① 连续 30 天各记一笔（记账日历按 created_at）：DAILY_RECORD ×30 + FIRST_RECORD + STREAK_7 + STREAK_30。
        LocalDate today = LocalDate.now();
        for (int back = 29; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            seedTransaction(userId, ledgerId, "expense", "1.00", day.atTime(12, 0), day);
        }
        // ② 上月一笔支出 + 上月总预算（远高于支出）：BUDGET_MET。预算经真实接口写入，口径与生产一致。
        YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
        seedTransaction(userId, ledgerId, "expense", "20.00",
                lastMonth.atDay(15).atTime(12, 0), today);
        ResponseEntity<Map> budget = putBudget(token, lastMonth.toString(), "100000.00");
        assertThat(budget.getStatusCode()).isEqualTo(HttpStatus.OK);
        // ③ 他人携带本人邀请码建号：FIRST_INVITE。
        ResponseEntity<Map> invitee = emailLogin("ach_parity_exp_invitee@example.com", inviteCodeOf(userId));
        assertThat(invitee.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(invitee)).containsEntry("inviteBound", true);

        warmUpSettlement(token);

        // 六类经验事件的经验值逐条不变（需求 12.6）。
        assertThat(expAmountsOfKeyPrefix(userId, "DAILY_RECORD:"))
                .as("DAILY_RECORD 每日 5 经验，30 天各一条")
                .hasSize(30)
                .containsOnly(EXP_BY_EVENT_KEY.get("DAILY_RECORD"));
        assertExpAmountOfKey(userId, "FIRST_RECORD");
        assertExpAmountOfKey(userId, "STREAK_7");
        assertExpAmountOfKey(userId, "STREAK_30");
        assertThat(expAmountsOfKeyPrefix(userId, "BUDGET_MET:"))
                .as("BUDGET_MET 仍是 50 经验")
                .isNotEmpty()
                .containsOnly(EXP_BY_EVENT_KEY.get("BUDGET_MET"));
        assertExpAmountOfKey(userId, "FIRST_INVITE");

        // 本 spec 新增的两类事件不带正经验（需求 12.6：不新增任何带正经验的事件类型）。
        assertThat(expAmountsOfType(userId, GrowthEventType.BADGE))
                .as("BADGE 行的 exp_amount 恒为 0").isNotEmpty().containsOnly(0);
        assertThat(expAmountsOfType(userId, GrowthEventType.SAVING_MONTH))
                .as("SAVING_MONTH 行若存在，exp_amount 恒为 0")
                .allSatisfy(exp -> assertThat(exp).isZero());

        // exp 等于全部事件 exp_amount 之和，level 由阈值函数换算（需求 12.6）。
        long expSum = expSumOf(userId);
        assertThat(profileExpOf(userId))
                .as("user_growth.exp 等于事件 exp_amount 之和").isEqualTo(expSum);
        assertThat(profileLevelOf(userId))
                .as("等级由既有阈值函数换算").isEqualTo(levelCurve.levelOf(expSum));
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /**
     * 概览徽章列表第 N 项与成就清单第 N 项在 6 项上逐项相等（需求 12.3），并断言概览顶层仍 15 项、
     * 徽章项仍 6 项、列表恒 16 项且顺序即清单序号（需求 12.1、12.2）。
     *
     * @return 已解锁项个数，供调用方断言该状态确实覆盖了预期的解锁 / 未解锁组合
     */
    private int assertParity(String label, String token) {
        Map<String, Object> overview = body(get("/api/growth", bearer(token)));
        Map<String, Object> list = body(get("/api/achievements", bearer(token)));

        // 结算失败 / 被节流一律不对外暴露错误码（需求 12.10）。
        assertThat(overview).as(label + " / 概览不返回错误码").doesNotContainKey("code");
        assertThat(list).as(label + " / 成就清单不返回错误码").doesNotContainKey("code");

        assertThat(overview.keySet()).as(label + " / 概览顶层恰好 15 项（需求 12.1）")
                .containsExactlyInAnyOrderElementsOf(OVERVIEW_TOP_KEYS);

        List<Map<String, Object>> badges = listOf(overview, "badges");
        List<Map<String, Object>> views = listOf(list, "achievements");
        assertThat(badges).as(label + " / 徽章列表恒 16 项（需求 12.2）").hasSize(TOTAL_ACHIEVEMENTS);
        assertThat(views).as(label + " / 成就视图恒 16 项").hasSize(TOTAL_ACHIEVEMENTS);
        assertThat(badges.stream().map(badge -> badge.get("code")).toList())
                .as(label + " / 徽章顺序即清单序号 1..16（需求 12.2）").isEqualTo(CATALOG_CODES);
        assertThat(views.stream().map(view -> view.get("code")).toList()).isEqualTo(CATALOG_CODES);

        int unlockedCount = 0;
        for (int i = 0; i < TOTAL_ACHIEVEMENTS; i++) {
            Map<String, Object> badge = badges.get(i);
            Map<String, Object> view = views.get(i);
            String code = (String) badge.get("code");

            assertThat(badge.keySet()).as(label + " / 徽章 " + code + " 的字段集恰好 6 项（需求 12.1）")
                    .containsExactlyInAnyOrderElementsOf(BADGE_KEYS);
            for (String key : SHARED_KEYS) {
                assertThat(badge.get(key))
                        .as(label + " / 第 " + (i + 1) + " 项（" + code + "）的 " + key + " 逐项相等（需求 12.3）")
                        .isEqualTo(view.get(key));
            }

            boolean unlocked = Boolean.TRUE.equals(badge.get("unlocked"));
            int target = ((Number) badge.get("target")).intValue();
            int current = ((Number) badge.get("current")).intValue();
            assertThat(current).as(label + " / " + code + " 的当前值落在 [0, " + target + "]")
                    .isBetween(0, target);
            if (unlocked) {
                unlockedCount++;
                assertThat(badge.get("unlockedAt")).as(label + " / 已解锁 " + code + " 的解锁时刻非空")
                        .isNotNull();
                assertThat(current).as(label + " / 已解锁 " + code + " 的当前值等于门槛").isEqualTo(target);
            } else {
                assertThat(badge.get("unlockedAt")).as(label + " / 未解锁 " + code + " 的解锁时刻为空")
                        .isNull();
            }
        }

        assertThat(((Number) list.get("unlockedCount")).intValue())
                .as(label + " / 已解锁成就数等于列表中已解锁项个数").isEqualTo(unlockedCount);
        assertThat(((Number) list.get("total")).intValue())
                .as(label + " / 成就总数恒 16").isEqualTo(TOTAL_ACHIEVEMENTS);
        return unlockedCount;
    }

    /**
     * 间隙态的可观察证据：{@code RECORD_100} 的条件已成立（累计 100 笔）但 {@code BADGE} 行尚未写入，
     * 因此两条路径都必须给出「未点亮 + 当前值等于门槛 100 + 空解锁时刻」。
     *
     * <p>这是本组断言里唯一能区分「两条路径共用同一份快照」与「各自组装 facts 碰巧对上」的用例：
     * 只要有一条路径自行查库算 facts，它与另一条在这一项上必然错开。</p>
     */
    private void assertGapStateOnRecordHundred(String token) {
        Map<String, Object> badge = itemByCode(listOf(body(get("/api/growth", bearer(token))), "badges"),
                "RECORD_100");
        Map<String, Object> view = itemByCode(
                listOf(body(get("/api/achievements", bearer(token))), "achievements"), "RECORD_100");
        for (Map<String, Object> item : List.of(badge, view)) {
            assertThat(item.get("unlocked")).as("间隙态：RECORD_100 尚未写入 BADGE 行").isEqualTo(Boolean.FALSE);
            assertThat(((Number) item.get("current")).intValue())
                    .as("间隙态：RECORD_100 的当前值等于门槛").isEqualTo(100);
            assertThat(item.get("unlockedAt")).as("间隙态：RECORD_100 无解锁时刻").isNull();
        }
    }

    /**
     * 断言错误响应的状态码、{@code code} 与归因字段与既有取值逐项相同，且错误体形状不变、
     * 不含本 spec 新增的错误码（需求 12.4）。
     *
     * @param field 期望的归因字段；{@code null} 表示该错误不可归因到具体字段，此时错误体只有
     *              {@code code} 与 {@code message} 两项（{@code ErrorResponse} 带
     *              {@code @JsonInclude(NON_NULL)}，空 {@code field} 不出现在 JSON 里）
     */
    private void assertErrorUnchanged(ResponseEntity<Map> response, HttpStatus status,
                                      String code, String field) {
        assertThat(response.getStatusCode()).as("错误码 " + code + " 的状态码").isEqualTo(status);
        Map<String, Object> body = body(response);
        if (field == null) {
            assertThat(body.keySet()).as("错误码 " + code + " 的错误体恰好 code + message 两项（需求 12.4）")
                    .containsExactlyInAnyOrder("code", "message");
        } else {
            assertThat(body.keySet()).as("错误码 " + code + " 的错误体恰好 3 项（需求 12.4）")
                    .containsExactlyInAnyOrderElementsOf(ERROR_KEYS);
            assertThat(body).as("错误码 " + code + " 的归因字段不变").containsEntry("field", field);
        }
        assertThat(body).as("既有错误码不变（需求 12.4）").containsEntry("code", code);
        assertThat(body.get("code"))
                .as("本 spec 新增的 ACHIEVEMENT_ACK_PARAM_INVALID 只用于游标推进接口（需求 12.4）")
                .isNotEqualTo("ACHIEVEMENT_ACK_PARAM_INVALID");
    }

    /** 先请求一次概览让结算真实执行（成长概览是写入型 GET），使解锁状态落地并打上 10 秒节流窗口。 */
    private void warmUpSettlement(String token) {
        ResponseEntity<Map> overview = get("/api/growth", bearer(token));
        assertThat(overview.getStatusCode()).as("预热请求应成功").isEqualTo(HttpStatus.OK);
    }

    private void assertExpAmountOfKey(long userId, String eventKey) {
        assertThat(expAmountsOfKey(userId, eventKey))
                .as("经验事件 %s 的经验值不变（需求 12.6）", eventKey)
                .containsExactly(EXP_BY_EVENT_KEY.get(eventKey));
    }

    // ---------------------------------- 库读取辅助 ----------------------------------

    private List<Map<String, Object>> growthEventsOf(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT id, user_id, event_type, event_key, exp_amount, created_at "
                        + "FROM growth_events WHERE user_id = ? ORDER BY id", userId);
    }

    private List<Map<String, Object>> userGrowthRowsOf(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, exp, level, total_record_days, current_streak_days, max_streak_days, "
                        + "last_record_date, last_settled_at, created_at, updated_at "
                        + "FROM user_growth WHERE user_id = ?", userId);
    }

    private long achievementNoticeCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM achievement_notices WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private long growthEventCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private long growthEventCountWithPositiveExp(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND exp_amount > 0",
                Long.class, userId);
        return n == null ? 0L : n;
    }

    private List<Integer> expAmountsOfKey(long userId, String eventKey) {
        return jdbcTemplate.queryForList(
                "SELECT exp_amount FROM growth_events WHERE user_id = ? AND event_key = ? ORDER BY id",
                Integer.class, userId, eventKey);
    }

    private List<Integer> expAmountsOfKeyPrefix(long userId, String prefix) {
        return jdbcTemplate.queryForList(
                "SELECT exp_amount FROM growth_events WHERE user_id = ? AND event_key LIKE ? ORDER BY id",
                Integer.class, userId, prefix + "%");
    }

    private List<Integer> expAmountsOfType(long userId, String eventType) {
        return jdbcTemplate.queryForList(
                "SELECT exp_amount FROM growth_events WHERE user_id = ? AND event_type = ? ORDER BY id",
                Integer.class, userId, eventType);
    }

    private long profileExpOf(long userId) {
        Long exp = jdbcTemplate.queryForObject(
                "SELECT exp FROM user_growth WHERE user_id = ?", Long.class, userId);
        return exp == null ? 0L : exp;
    }

    private int profileLevelOf(long userId) {
        Integer level = jdbcTemplate.queryForObject(
                "SELECT level FROM user_growth WHERE user_id = ?", Integer.class, userId);
        return level == null ? 0 : level;
    }

    private long expSumOf(long userId) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(exp_amount), 0) FROM growth_events WHERE user_id = ?",
                Long.class, userId);
        return sum == null ? 0L : sum;
    }

    // ---------------------------------- 数据播种辅助 ----------------------------------

    /**
     * 直插若干「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type = 'expense'}、{@code ledger_id} 非 NULL），记账日与发生日均为 {@code day}。
     *
     * <p>经原生 SQL 直插而不走记账接口：本类验的是读取侧契约，记账链路在别处覆盖；直插同时避免
     * {@code afterCommit} 触发额外结算，使「间隙态」的构造是确定的。</p>
     */
    private void seedRecordsOn(long userId, long ledgerId, LocalDate day, int count) {
        for (int i = 0; i < count; i++) {
            seedTransaction(userId, ledgerId, "expense", "1.00", day.atTime(12, 0), day);
        }
    }

    private void seedTransaction(long userId, long ledgerId, String type, String amount,
                                 LocalDateTime occurredAt, LocalDate recordDay) {
        Timestamp createdAt = Timestamp.valueOf(recordDay.atTime(12, 0));
        // account_id / category_id 取一个「绝不可能是真实主键」的高位取值，且按用户隔离：注销前置校验
        // 会拿本人拥有的 account.id 去反查「是否被他人记的交易引用」，若这里沿用账本 id 之类的低位取值，
        // 就可能与另一用例里某个真实 account.id 撞号，把注销误判成 DELETE_BLOCKED_COLLAB。
        long syntheticRef = 900_000_000L + userId;
        jdbcTemplate.update(
                "INSERT INTO transactions "
                        + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                        + "occurred_at, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                userId, ledgerId, userId, type, new BigDecimal(amount), syntheticRef, syntheticRef,
                Timestamp.valueOf(occurredAt), createdAt, createdAt);
    }

    /** 落齐 16 行 {@code BADGE} 事件（逐枚不同的 {@code created_at}），使 16 枚全部已解锁。 */
    private void seedAllBadgeRows(long userId) {
        LocalDateTime base = LocalDateTime.now().minusDays(30).withNano(0);
        for (int i = 0; i < CATALOG_CODES.size(); i++) {
            jdbcTemplate.update(
                    "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                            + "VALUES (?, ?, ?, 0, ?)",
                    userId, GrowthEventType.BADGE, "BADGE:" + CATALOG_CODES.get(i),
                    Timestamp.valueOf(base.plusMinutes(i)));
        }
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
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

    private Map<String, Object> itemByCode(List<Map<String, Object>> items, String code) {
        return items.stream()
                .filter(item -> code.equals(item.get("code")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("列表中不存在编码 " + code));
    }

    private List<Map<String, Object>> itemsOfType(List<Map<String, Object>> items, String eventType) {
        return items.stream().filter(item -> eventType.equals(item.get("eventType"))).toList();
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

    private Map<String, Object> recordPayload(String amount, long accountId, long categoryId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "expense");
        payload.put("amount", amount);
        payload.put("accountId", accountId);
        payload.put("categoryId", categoryId);
        payload.put("occurredAt", LocalDateTime.now().withNano(0).toString());
        payload.put("note", "记一笔");
        return payload;
    }

    /** 记账：取<b>原始 JSON 文本</b>，因为「不含成就 / 播报 / 徽章字段」只能按文本比对。 */
    private ResponseEntity<String> postRecord(String token, String amount, long accountId, long categoryId) {
        return rest.exchange(url("/api/transactions"), HttpMethod.POST,
                new HttpEntity<>(recordPayload(amount, accountId, categoryId), authJson(token)), String.class);
    }

    private ResponseEntity<Map> postRecordAsMap(String token, String amount, long accountId, long categoryId) {
        return rest.exchange(url("/api/transactions"), HttpMethod.POST,
                new HttpEntity<>(recordPayload(amount, accountId, categoryId), authJson(token)), Map.class);
    }

    private ResponseEntity<Map> putBudget(String token, String month, String amount) {
        return rest.exchange(url("/api/budgets?month=" + month), HttpMethod.PUT,
                new HttpEntity<>(Map.of("amount", amount), authJson(token)), Map.class);
    }

    private ResponseEntity<Map> postDelete(String token, Map<String, String> payload) {
        return rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                new HttpEntity<>(payload, authJson(token)), Map.class);
    }

    // ---------------------------------- 账号辅助 ----------------------------------

    private String registerAndLogin(String email) {
        ResponseEntity<Map> login = emailLogin(email, null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 以「新鲜」LOGIN 验证码执行 email-login（清历史码以规避 60s 发码冷却）；{@code inviteCode} 可为 null。 */
    private ResponseEntity<Map> emailLogin(String email, String inviteCode) {
        sendLoginCode(email);
        Map<String, String> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("code", latestCode(email, EmailCodePurpose.LOGIN));
        payload.put("inviteCode", inviteCode);
        return rest.postForEntity(url("/api/auth/email-login"), payload, Map.class);
    }

    /** 以指定（通常是错误的）验证码执行 email-login，用于断言既有错误码不变。 */
    private ResponseEntity<Map> emailLoginWithCode(String email, String code) {
        sendLoginCode(email);
        return rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
    }

    private void sendLoginCode(String email) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private String freshDeleteCode(String email) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "DELETE"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return latestCode(email, EmailCodePurpose.DELETE);
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

    private String inviteCodeOf(long userId) {
        String code = userRepository.findById(userId)
                .orElseThrow(() -> new AssertionError("用户不存在: " + userId))
                .getInviteCode();
        assertThat(code).as("建号时应写入邀请码").isNotBlank();
        return code;
    }

    /**
     * 该用户的默认账本 id。
     *
     * <p>默认账本是<b>惰性创建</b>的（见 {@code CurrentLedger.requireLedger}），建号本身不建账本，
     * 故这里显式确保一个：有效记账交易要求 {@code ledger_id} 非空，预算达成判定又只认自有账本。</p>
     */
    private long ledgerIdOf(long userId) {
        return ledgerService.ensureDefaultLedger(userId).getId();
    }

    // ---------------------------------- 测试基础设施 ----------------------------------

    /**
     * 计数并可注入故障的 {@link GrowthSettlementService}：默认<b>委托</b>给真实（被 Spring 事务代理
     * 包裹的）bean，{@code REQUIRES_NEW} 因而照常生效。它<b>不是</b> Mockito 替身，也不替换真实结算
     * ——只在委托前后记录调用次数与结算结果，并可在委托前抛出注入异常。构造时给父类传 {@code null}：
     * 本类覆盖 {@code settle} 并只委托给 {@code delegate}，父类字段永不被触及。
     */
    static class ProbeSettlementService extends GrowthSettlementService {

        private final GrowthSettlementService delegate;
        private final AtomicInteger settleCalls = new AtomicInteger();
        private final List<SettleOutcome> outcomes = new CopyOnWriteArrayList<>();
        private volatile RuntimeException toThrow;

        ProbeSettlementService(GrowthSettlementService delegate) {
            // 13 个 null：构造参数在 achievement-system 任务 4.1 从 11 个扩到 13 个。本桩全部方法都
            // 转发给 delegate，父类字段一个都不用，因此逐个传 null。
            super(null, null, null, null, null, null, null, null, null, null, null, null, null);
            this.delegate = delegate;
        }

        @Override
        public SettleOutcome settle(Long userId, TriggerSource source) {
            settleCalls.incrementAndGet();
            RuntimeException injected = this.toThrow;
            if (injected != null) {
                throw injected;
            }
            SettleOutcome outcome = delegate.settle(userId, source);   // 经事务代理 → REQUIRES_NEW 生效
            outcomes.add(outcome);
            return outcome;
        }

        void reset() {
            settleCalls.set(0);
            outcomes.clear();
            toThrow = null;
        }

        void throwOnSettle(RuntimeException e) {
            this.toThrow = e;
        }

        int settleCalls() {
            return settleCalls.get();
        }

        List<SettleOutcome> outcomes() {
            return List.copyOf(outcomes);
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        @Primary
        ProbeSettlementService probeSettlementService(
                @Qualifier("growthSettlementService") GrowthSettlementService real) {
            return new ProbeSettlementService(real);
        }
    }
}
