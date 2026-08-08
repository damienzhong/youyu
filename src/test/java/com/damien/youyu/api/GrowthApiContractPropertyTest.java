package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.UserRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 成长接口契约的属性测试（<b>Property 16：两个接口的字段集、分页与越权防护</b>）。
 *
 * <p>本属性锁住需求 10 交汇处的契约不变式，它们必须在<b>任意</b>（成长事件集合、分页参数、令牌形态、
 * 伪造入参、账本头）组合下成立。走全栈 {@code @SpringBootTest}(RANDOM_PORT) + 真实 HTTP +
 * 真实 Spring Security 过滤链 + 真实 JWT + H2 持久化层，对齐既有 {@code GrowthApiSecurityIntegrationTest}
 * （任务 6.5）的令牌签发与 HTTP 请求辅助写法，只是把其固定用例推广为 jqwik 生成组合。</p>
 *
 * <h2>每次迭代断言的不变式</h2>
 * <ul>
 *   <li><b>字段集恰好相等（非包含）</b>（需求 10.1、10.3、10.13）：成长概览成功响应的顶层键集合
 *       <b>恰好</b>等于 15 项、每枚徽章项的键集合恰好等于 6 项；经验明细顶层键集合恰好为
 *       {@code {items, total}}、每个列表项的键集合恰好等于 5 项。两个响应的 JSON 文本一律不出现
 *       {@code email} / {@code wx_openid} / {@code wx_unionid} / {@code invite_code} / {@code plan}
 *       / {@code role} 六个键与取值。</li>
 *   <li><b>分页不重不漏</b>（需求 10.4、10.5、10.10）：以同一 {@code size} 逐页取完全部页时，各页
 *       条数之和等于 {@code total}、各页项并集等于全集且互不重复、单页条数 ≤ 生效 {@code size}、
 *       按 {@code id} 倒序为全序列的稳定切片；{@code total} 不受分页影响；页码越界或无事件时返回
 *       空列表 + 真实 {@code total} 且不报错。</li>
 *   <li><b>分页参数非法</b>（需求 10.9、10.15）：{@code page} / {@code size} 不可解析或越界时返回
 *       {@code GROWTH_PAGE_PARAM_INVALID}，且响应体不含任何列表项与任何计数值。</li>
 *   <li><b>鉴权优先</b>（需求 10.6、10.7）：令牌缺失 / 验签失败 / 过期 / 令牌用户已不存在 / 空 Bearer
 *       一律返回 {@code UNAUTHENTICATED}，<b>优先于</b>分页参数错误，且不改动两表数据。</li>
 *   <li><b>伪造入参无法越权</b>（需求 10.8）：以 A 的令牌附加任意用于指定目标用户的伪造入参
 *       （{@code userId} / {@code targetUserId} / {@code uid} / {@code level} / {@code exp}，全部指向 B），
 *       响应恒等于 A 不带这些入参时的响应。</li>
 *   <li><b>明细不触发结算、与账本无关</b>（需求 10.11、10.12）：经验明细请求前后两表逐行相等
 *       （尤其不为该用户建 {@code user_growth} 档案）；带/不带 {@code X-Ledger-Id} 响应逐字段相等。</li>
 * </ul>
 *
 * <h2>测试层级与清理</h2>
 * <p>成长概览是本项目唯一的写入型 GET（内含结算，带 {@code @Transactional(REQUIRES_NEW)}），只有真实
 * 提交才能观察到结算终态，故走全栈 {@code @SpringBootTest}(RANDOM_PORT) + H2（{@code MODE=MySQL}，
 * 独立命名内存库）；清理<b>不能靠事务回滚</b>：{@link #resetState()} 每次迭代前显式清六张表，并用全局
 * 自增序号 {@link #SEQ} 保证每次迭代的 {@code userId} / 邮箱 / 邀请码全局唯一。成长事件直接经
 * {@link JdbcTemplate} 预置（不走 200×N 次业务接口），使「事件集合」这一维度可精确控制。</p>
 *
 * <p>jqwik 属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，依赖注入（含
 * {@link LocalServerPort} 端口与 {@link TestRestTemplate}）由 {@link TestContextManager} 在
 * {@link BeforeTry} 中手工完成——Spring 的静态上下文缓存跨迭代复用，嵌入式 Web 服务器只启动一次。</p>
 *
 * <p>Feature: growth-level-system, Property 16: 两个接口的字段集、分页与越权防护</p>
 *
 * <p>Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10, 10.11, 10.13, 10.15</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-api-p16-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class GrowthApiContractPropertyTest {

    /** 成长概览成功响应的顶层字段集<b>恰好</b>这 15 项（需求 10.1）。 */
    private static final Set<String> OVERVIEW_KEYS = Set.of(
            "level", "exp", "currentLevelExp", "nextLevelExp", "expInCurrentLevel", "expToNextLevel",
            "maxLevel", "maxLevelReached", "totalRecordCount", "totalExpense", "totalIncome",
            "totalRecordDays", "currentStreakDays", "maxStreakDays", "badges");

    /** 徽章项的字段集<b>恰好</b>这 6 项（需求 8.5）。 */
    private static final Set<String> BADGE_KEYS =
            Set.of("code", "name", "unlocked", "unlockedAt", "target", "current");

    /** 经验明细顶层字段集<b>恰好</b>这 2 项（需求 10.13）。 */
    private static final Set<String> EVENTS_TOP_KEYS = Set.of("items", "total");

    /** 经验明细列表项的字段集<b>恰好</b>这 5 项（需求 10.3）。 */
    private static final Set<String> EVENT_ITEM_KEYS =
            Set.of("id", "eventType", "eventKey", "expAmount", "createdAt");

    /** 两个响应一律不得出现的六个敏感键（需求 10.13）。 */
    private static final List<String> FORBIDDEN_FIELDS =
            List.of("email", "wx_openid", "wx_unionid", "invite_code", "plan", "role");

    /** 与 {@code app.jwt.secret} 不同的密钥，用于制造验签失败的令牌（长度满足 HS256 要求）。 */
    private static final String FOREIGN_SECRET =
            "foreign-secret-key-only-for-growth-contract-property-test-do-not-use";

    /** 分页参数校验的取值范围（需求 10.2、10.9），与 {@code GrowthQueryService} 一致，用于预判合法性。 */
    private static final int PAGE_MIN = 0;
    private static final int PAGE_MAX = 100000;
    private static final int SIZE_MIN = 1;
    private static final int SIZE_MAX = 50;

    /** 跨迭代复用同一内存库，用序号保证 userId / 邮箱 / 邀请码全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(6_100_000L);

    @LocalServerPort
    private int port;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthApiContractPropertyTest.class).prepareTestInstance(this);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删两张成长表与四张事实源表。均无外键，删除顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM budgets");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM invite_relations");
    }

    // ---------------------------------- 生成器 ----------------------------------

    /** 逐页取完时用的每页条数：全部合法（1–50），覆盖单条、跨页与上界。 */
    @Provide
    Arbitrary<Integer> walkSizes() {
        return Arbitraries.of(1, 2, 7, 20, 50);
    }

    /**
     * {@code page} 原始取值：覆盖合法（含缺省）、越界、不可解析与溢出。{@code null} 表示不带该参数。
     */
    @Provide
    Arbitrary<String> pageParams() {
        return Arbitraries.of("-1", "0", "1", "50", "100000", "100001",
                "abc", "", " ", "1e3", "99999999999999999999", null);
    }

    /**
     * {@code size} 原始取值：覆盖合法（含缺省）、越界、不可解析。{@code null} 表示不带该参数。
     */
    @Provide
    Arbitrary<String> sizeParams() {
        return Arbitraries.of("0", "1", "20", "50", "51", "-5", "abc", " ", "1e2", null);
    }

    /** 无效令牌形态：0 缺失 / 1 验签失败 / 2 已过期 / 3 令牌用户已不存在 / 4 空 Bearer。 */
    @Provide
    Arbitrary<Integer> invalidTokenShapes() {
        return Arbitraries.integers().between(0, 4);
    }

    /** 账本头形态：0 不带 / 1 不可访问 / 2 另一个取值——成长数据与账本无关，三者响应应逐字段相等。 */
    @Provide
    Arbitrary<Integer> ledgerHeaderModes() {
        return Arbitraries.integers().between(0, 2);
    }

    // ---------------------------------- Property 16 ----------------------------------

    /**
     * Feature: growth-level-system, Property 16: 两个接口的字段集、分页与越权防护
     *
     * <p>对每个生成组合，先用 A、B 两个真实用户各预置一批成长事件（B 的规模刻意与 A 不同，使越权一旦
     * 成功即被字段级相等断言抓到），再依次断言：无效令牌一律 401 且优先于分页参数错误、两表不变；
     * 经验明细的字段集恰好相等、逐页不重不漏、非法参数 400 且响应无列表项与计数、伪造入参与账本头不改
     * 变响应、且明细请求不触发结算（两表逐行不变）；成长概览的字段集恰好相等、伪造入参与账本头不改变
     * 响应；两个响应均不泄漏六个敏感字段。</p>
     *
     * <p>Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10, 10.11, 10.13, 10.15</p>
     */
    @Property(tries = 12)
    void property16_fieldSetsPaginationAndAccessControl(
            @ForAll @IntRange(min = 0, max = 60) int eventCountA,
            @ForAll("walkSizes") int walkSize,
            @ForAll("pageParams") String pageParam,
            @ForAll("sizeParams") String sizeParam,
            @ForAll("invalidTokenShapes") int invalidTokenShape,
            @ForAll("ledgerHeaderModes") int ledgerHeaderMode) {

        assertThat(port).as("嵌入式 Web 服务器端口应已注入").isGreaterThan(0);

        // A：eventCountA 条事件；B：刻意多 5 条（且总经验不同），作为「越权目标」。
        long idA = createUser("a");
        long idB = createUser("b");
        String tokenA = validToken(idA);
        long[] idsA = seedEvents(idA, eventCountA);            // 已按插入顺序、id 升序
        seedEvents(idB, eventCountA + 5);

        String forged = "userId=" + idB + "&targetUserId=" + idB + "&uid=" + idB
                + "&level=99&exp=999999";
        HttpHeaders ledger = withLedger(tokenA, ledgerHeaderMode);

        // ===== 1) 无效令牌一律 401 UNAUTHENTICATED，且优先于分页参数错误、不改动两表（需求 10.6、10.7）=====
        long geBefore = countRows("growth_events");
        long ugBefore = countRows("user_growth");

        // 先证明这组分页参数在有效令牌下确有区分度（否则「鉴权优先」断言是空的）：非法参数 → 400。
        if (!validParam(pageParam, PAGE_MIN, PAGE_MAX) || !validParam(sizeParam, SIZE_MIN, SIZE_MAX)) {
            ResponseEntity<Map> rejected = get(eventsPath(pageParam, sizeParam), bearer(tokenA));
            assertThat(rejected.getStatusCode()).as("有效令牌 + 非法分页 → 400").isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body(rejected)).containsEntry("code", "GROWTH_PAGE_PARAM_INVALID");
        }

        HttpHeaders invalid = invalidAuth(invalidTokenShape);
        for (String path : List.of("/api/growth", eventsPath(pageParam, sizeParam))) {
            assertUnauthenticated(get(path, invalid), "无效令牌形态 " + invalidTokenShape + " @ " + path);
        }
        // 鉴权优先于分页参数错误：确定非法的分页参数配无效令牌仍是 401（需求 10.7）。
        assertUnauthenticated(get("/api/growth/events?page=-1&size=999", invalid),
                "无效令牌 + 确定非法分页 → 仍 401");

        assertThat(countRows("growth_events")).as("鉴权失败不改动 growth_events").isEqualTo(geBefore);
        assertThat(countRows("user_growth")).as("鉴权失败不改动 user_growth").isEqualTo(ugBefore);

        // ===== 2) 经验明细：字段集、分页、非法参数、伪造入参、账本无关、零结算（需求 10.2–10.5、10.9–10.13）=====
        long geForABefore = countRowsFor("growth_events", idA);
        long ugForABefore = countRowsFor("user_growth", idA);
        assertThat(ugForABefore).as("尚未结算，A 无 user_growth 档案").isEqualTo(0L);

        // 2a) 基线（无分页参数、无伪造入参）：顶层与列表项字段集恰好相等，total == eventCountA。
        Map<String, Object> eventsBaseline = body(get("/api/growth/events", bearer(tokenA)));
        assertEventsShape(eventsBaseline, eventCountA);

        // 2b) 逐页不重不漏：以 walkSize 取完全部页（需求 10.4、10.5）。
        assertPaginationCoversAllExactlyOnce(tokenA, walkSize, idsA);

        // 2c) 页码越界（合法范围内的大页码）：空列表 + 真实 total（需求 10.10）。
        Map<String, Object> outOfRange = body(get("/api/growth/events?page=100000&size=" + walkSize, bearer(tokenA)));
        assertThat(items(outOfRange)).as("越界页返回空列表").isEmpty();
        assertThat(totalOf(outOfRange)).as("越界页 total 仍为真实总数").isEqualTo((long) eventCountA);

        // 2d) 生成的分页参数：非法 → 400 且响应无列表项与计数；合法 → 200 且字段集恰好相等（需求 10.9）。
        boolean paramsValid = validParam(pageParam, PAGE_MIN, PAGE_MAX)
                && validParam(sizeParam, SIZE_MIN, SIZE_MAX);
        ResponseEntity<Map> generated = get(eventsPath(pageParam, sizeParam), bearer(tokenA));
        if (paramsValid) {
            assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertEventsShape(body(generated), eventCountA);
        } else {
            assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            Map<String, Object> err = body(generated);
            assertThat(err).containsEntry("code", "GROWTH_PAGE_PARAM_INVALID");
            assertThat(err).as("非法分页响应不含任何列表项与计数值")
                    .doesNotContainKey("items").doesNotContainKey("total");
        }

        // 2e) 伪造入参无法越权：附加指向 B 的伪造入参，响应恒等于基线（需求 10.8）。
        assertThat(body(get("/api/growth/events?" + forged, bearer(tokenA))))
                .as("经验明细：伪造入参被忽略").isEqualTo(eventsBaseline);

        // 2f) 与会话账本无关：带/不带 X-Ledger-Id 响应逐字段相等（需求 10.12）。
        assertThat(body(get("/api/growth/events", ledger)))
                .as("经验明细：X-Ledger-Id 不影响响应").isEqualTo(eventsBaseline);

        // 2g) 不泄漏敏感字段（需求 10.13）。
        assertNoSensitiveFields(rawGet("/api/growth/events", bearer(tokenA)), "经验明细");

        // 2h) 明细接口零结算：两表针对 A 的行逐量不变，尤其不为 A 建 user_growth 档案（需求 10.11）。
        assertThat(countRowsFor("growth_events", idA)).as("明细不新增/删除 A 的成长事件").isEqualTo(geForABefore);
        assertThat(countRowsFor("user_growth", idA)).as("明细不触发结算：A 仍无档案").isEqualTo(0L);

        // ===== 3) 成长概览：字段集恰好相等、伪造入参与账本头不改变响应、不泄漏敏感字段（需求 10.1、10.8、10.12、10.13）=====
        // 概览是写入型 GET：首次调用触发结算写档案，后续 10 秒内被节流、返回持久化取值 —— 故基线与其后各调用应相等。
        Map<String, Object> overviewBaseline = body(get("/api/growth", bearer(tokenA)));
        assertOverviewShape(overviewBaseline);

        assertThat(body(get("/api/growth?" + forged, bearer(tokenA))))
                .as("成长概览：伪造入参被忽略").isEqualTo(overviewBaseline);
        assertThat(body(get("/api/growth", ledger)))
                .as("成长概览：X-Ledger-Id 不影响响应").isEqualTo(overviewBaseline);
        assertNoSensitiveFields(rawGet("/api/growth", bearer(tokenA)), "成长概览");
    }

    // ---------------------------------- 形状断言 ----------------------------------

    /** 成长概览：顶层键集合恰好 15 项、每枚徽章键集合恰好 6 项（需求 10.1、10.13）。 */
    @SuppressWarnings("unchecked")
    private void assertOverviewShape(Map<String, Object> overview) {
        assertThat(overview.keySet()).as("成长概览顶层字段集恰好等于 15 项").isEqualTo(OVERVIEW_KEYS);
        Object badges = overview.get("badges");
        assertThat(badges).as("badges 为列表").isInstanceOf(List.class);
        for (Object badge : (List<Object>) badges) {
            assertThat(badge).isInstanceOf(Map.class);
            assertThat(((Map<String, Object>) badge).keySet())
                    .as("每枚徽章字段集恰好等于 6 项").isEqualTo(BADGE_KEYS);
        }
    }

    /** 经验明细：顶层键集合恰好 {items,total}、每个列表项键集合恰好 5 项、total 等于期望、单页条数受限。 */
    private void assertEventsShape(Map<String, Object> events, long expectedTotal) {
        assertThat(events.keySet()).as("经验明细顶层字段集恰好为 {items, total}").isEqualTo(EVENTS_TOP_KEYS);
        assertThat(totalOf(events)).as("total 等于该用户成长事件总数").isEqualTo(expectedTotal);
        for (Map<String, Object> item : items(events)) {
            assertThat(item.keySet()).as("每个列表项字段集恰好等于 5 项").isEqualTo(EVENT_ITEM_KEYS);
        }
    }

    /**
     * 逐页不重不漏（需求 10.4、10.5、10.10）：以固定 {@code size} 从 0 页起翻到空页，断言各页条数之和
     * 等于 {@code total}、并集等于全集且互不重复、单页条数 ≤ {@code size}、拼接后的 id 序列严格递减
     * 且恰为全序列（{@code id} 倒序）的稳定切片。
     */
    private void assertPaginationCoversAllExactlyOnce(String token, int size, long[] allIdsAsc) {
        // 全序列 = 全部 id 的倒序（与接口约定的 id DESC 一致）。
        List<Long> expectedDesc = new ArrayList<>(allIdsAsc.length);
        for (int i = allIdsAsc.length - 1; i >= 0; i--) {
            expectedDesc.add(allIdsAsc[i]);
        }

        List<Long> collected = new ArrayList<>();
        long total = -1L;
        int page = 0;
        // 安全上界：最多 (total/size)+2 页，避免任何意外死循环。
        int maxPages = allIdsAsc.length / size + 2;
        while (page <= maxPages) {
            Map<String, Object> resp = body(get("/api/growth/events?page=" + page + "&size=" + size, bearer(token)));
            long t = totalOf(resp);
            if (total < 0) {
                total = t;
            } else {
                assertThat(t).as("total 不受分页影响").isEqualTo(total);
            }
            List<Map<String, Object>> pageItems = items(resp);
            assertThat(pageItems.size()).as("单页条数 ≤ 生效 size").isLessThanOrEqualTo(size);
            if (pageItems.isEmpty()) {
                break;
            }
            for (Map<String, Object> item : pageItems) {
                collected.add(((Number) item.get("id")).longValue());
            }
            page++;
        }

        assertThat(total).as("total 等于全集大小").isEqualTo((long) allIdsAsc.length);
        // 各页条数之和 == total。
        assertThat(collected.size()).as("各页条数之和等于 total").isEqualTo(allIdsAsc.length);
        // 互不重复。
        assertThat(new HashSet<>(collected)).as("各页项互不重复").hasSize(allIdsAsc.length);
        // 并集 == 全集。
        assertThat(new HashSet<>(collected)).as("各页项并集等于全集")
                .isEqualTo(new HashSet<>(expectedDesc));
        // 稳定切片：拼接后恰为全序列（id 倒序）。
        assertThat(collected).as("翻页拼接结果恰为 id 倒序的稳定切片").isEqualTo(expectedDesc);
    }

    /** 断言响应为 401 且统一错误体 {@code code=UNAUTHENTICATED}，正文为 JSON（需求 10.6）。 */
    private void assertUnauthenticated(ResponseEntity<Map> response, String shape) {
        assertThat(response.getStatusCode()).as(shape).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(response)).as(shape).containsEntry("code", "UNAUTHENTICATED");
    }

    /** 断言 JSON 文本不含被排除的六个字段键与取值（需求 10.13）。 */
    private void assertNoSensitiveFields(String rawJson, String label) {
        assertThat(rawJson).as(label + " / 200 且有响应体").isNotBlank();
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(rawJson).as(label + " / 不含 " + forbidden).doesNotContain(forbidden);
        }
    }

    // ---------------------------------- 分页参数合法性预判 ----------------------------------

    /** 复刻 {@code GrowthQueryService.parseInRange}：null/空白取缺省（合法），否则须能解析且落在闭区间内。 */
    private static boolean validParam(String raw, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return value >= min && value <= max;
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String eventsPath(String pageParam, String sizeParam) {
        StringBuilder sb = new StringBuilder("/api/growth/events");
        List<String> params = new ArrayList<>();
        if (pageParam != null) {
            params.add("page=" + urlEncode(pageParam));
        }
        if (sizeParam != null) {
            params.add("size=" + urlEncode(sizeParam));
        }
        if (!params.isEmpty()) {
            sb.append('?').append(String.join("&", params));
        }
        return sb.toString();
    }

    private static String urlEncode(String raw) {
        return java.net.URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    /** 取原始 JSON 文本用于「不泄漏字段」断言（Map 解析会丢掉键名文本的原样性）。 */
    private String rawGet(String path, HttpHeaders headers) {
        ResponseEntity<String> response =
                rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** 账本头形态：0 不带 / 1 不可访问的取值 / 2 另一个取值——成长与账本无关，三者应等价。 */
    private HttpHeaders withLedger(String token, int mode) {
        HttpHeaders headers = bearer(token);
        switch (mode) {
            case 1 -> headers.set("X-Ledger-Id", "987654321");
            case 2 -> headers.set("X-Ledger-Id", "1");
            default -> { /* 不带 X-Ledger-Id */ }
        }
        return headers;
    }

    /** 无效令牌形态：0 缺失 / 1 验签失败 / 2 已过期 / 3 令牌用户已不存在 / 4 空 Bearer。 */
    private HttpHeaders invalidAuth(int shape) {
        return switch (shape) {
            case 0 -> new HttpHeaders();                                                // 缺失
            case 1 -> bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)));           // 验签失败
            case 2 -> bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)));            // 已过期
            case 3 -> bearer(token(nonexistentUserId(), jwtSecret, Duration.ofHours(1))); // 用户已不存在
            default -> blankBearer();                                                   // 空 Bearer
        };
    }

    private HttpHeaders blankBearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer ");
        return headers;
    }

    private String validToken(long userId) {
        return token(userId, jwtSecret, Duration.ofHours(1));
    }

    /** 自行签发令牌：可指定用户 id、密钥（制造验签失败）与有效期（负值即已过期），对齐任务 6.5 写法。 */
    private String token(long userId, String secret, Duration ttl) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date issuedAt = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", "user")
                .issuedAt(issuedAt)
                .expiration(new Date(issuedAt.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 一个签名合法、未过期但不指向任何已建用户的 id（过滤链放行、由控制器 requireExistingUserId 兜住）。 */
    private long nonexistentUserId() {
        return SEQ.getAndIncrement() + 900_000_000L;
    }

    // ---------------------------------- 数据准备辅助 ----------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> events) {
        return (List<Map<String, Object>>) events.get("items");
    }

    private long totalOf(Map<String, Object> events) {
        return ((Number) events.get("total")).longValue();
    }

    /** 建一个真实用户，返回其 id。邮箱/邀请码带 P16 命名空间并接序号，避免与并存测试的唯一约束相撞。 */
    private long createUser(String tag) {
        long seq = SEQ.getAndIncrement();
        LocalDateTime now = LocalDateTime.now();
        User u = new User();
        u.setEmail("p16-" + tag + "-" + seq + "@example.com");
        u.setNickname("p16-" + tag + "-" + seq);
        u.setInviteCode(inviteCodeOf(seq));
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u).getId();
    }

    /** 8 位邀请码：固定前缀 {@code P6} + 6 位 36 进制序号，本类命名空间专属。 */
    private static String inviteCodeOf(long seq) {
        String base36 = Long.toString(seq % 2_176_782_336L, 36); // 36^6，保证 ≤ 6 位
        return ("P6" + "000000" + base36).substring(("P6" + "000000" + base36).length() - 8);
    }

    /**
     * 直接经 JDBC 预置 {@code count} 条成长事件，返回其 id（升序，即插入顺序）。
     *
     * <p>刻意用非 {@code DAILY_RECORD} 的合法类型 {@code STREAK}：概览触发的全量重算只按日期解析
     * {@code DAILY_RECORD} 键，用其它类型可避免给合成事件编造日期键，同时经验合计仍取 {@code SUM(exp_amount)}。
     * {@code (user_id, event_key)} 唯一，故每条键都带序号，保证互不重复。</p>
     */
    private long[] seedEvents(long userId, int count) {
        long[] ids = new long[count];
        LocalDateTime base = LocalDateTime.of(2025, 6, 15, 8, 0, 0);
        for (int i = 0; i < count; i++) {
            long seq = SEQ.getAndIncrement();
            String eventKey = "STREAK:P16-" + userId + "-" + seq + "-" + i;
            jdbcTemplate.update(
                    "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    userId, "STREAK", eventKey, 5, base.plusSeconds(i));
            ids[i] = jdbcTemplate.queryForObject(
                    "SELECT id FROM growth_events WHERE user_id = ? AND event_key = ?",
                    Long.class, userId, eventKey);
        }
        return ids;
    }

    private long countRows(String table) {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return c == null ? 0L : c;
    }

    private long countRowsFor(String table, long userId) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
        return c == null ? 0L : c;
    }
}
