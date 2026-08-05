package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 成就三个接口的<b>鉴权与越权</b>集成测试（任务 6.2，需求 6.8、6.9、6.10、6.11、6.13、6.16、6.17、5.15）。
 *
 * <p>沿用 {@link GrowthApiSecurityIntegrationTest} 的范式：全栈 {@code @SpringBootTest}(RANDOM_PORT)
 * + {@link TestRestTemplate} + 手工签发 {@code Jwts} 令牌 + <b>独立命名</b>的内存库，经真实 HTTP、
 * 真实 Spring Security 过滤链、真实 JWT 与 H2 持久化层，覆盖四件事：</p>
 *
 * <ol>
 *   <li><b>三个端点 × 6 种令牌形态一律 401 {@code UNAUTHENTICATED}</b>（需求 6.8）：缺失 / 畸形
 *       （无法解析）/ 签名校验失败 / 已过期 / <b>令牌用户已注销</b> / 空 Bearer。第五种是本类存在的
 *       主要理由：{@link com.damien.youyu.security.JwtAuthenticationFilter} 只验签与验有效期、
 *       <b>不查库</b>，所以「令牌合法但用户已注销」过滤链管不到，只能由
 *       {@code AchievementController.requireExistingUserId()} 兜住（需求 6.9）。同时断言这些响应
 *       <b>不含成就视图列表、已解锁成就数、待播报成就项与游标取值四项中的任何一项</b>，
 *       且 {@code growth_events} 与 {@code achievement_notices} <b>两表的行数与全部列取值逐行不变</b>。</li>
 *   <li><b>{@code UNAUTHENTICATED} 优先于任何字段校验</b>（需求 6.8）：同一个非法
 *       {@code lastEventId}（{@code "abc"}）在有效令牌下确实返回 400
 *       {@code ACHIEVEMENT_ACK_PARAM_INVALID}（否则本断言是空的），但在<b>已注销用户</b>的令牌下
 *       返回 401 而非 400——把控制器里的存在性校验挪到入参校验之后，这条断言就会失败。</li>
 *   <li><b>伪造身份字段无法越权</b>（需求 6.10、6.16、6.17）：以 A 的令牌附加指向 B 的
 *       {@code userId} / {@code targetUserId} / {@code uid} 查询参数、请求体字段与自定义请求头，
 *       三个端点的响应与不携带时<b>逐项相等</b>且不返回错误；A 的响应里不出现 B 的任何成就事件 id；
 *       A 推进自己的游标不改变 B 的待播报结果。B 的成就数据刻意与 A 不同（B 记满 10 笔多命中
 *       {@code RECORD_10}），若越权成功，逐项相等断言必然失败。</li>
 *   <li><b>与会话账本无关</b>（需求 6.11）：不带 {@code X-Ledger-Id}、带一个不可访问的数字取值、
 *       带一个非数字取值三种情形下，三个端点的响应<b>逐项相等</b>且都不因该头被拒绝。</li>
 * </ol>
 *
 * <p>另外按需求 6.14、5.15 断言服务端耗时：成就清单、待播报与 ack 各自 ≤2000ms（经 localhost 回环，
 * 网络传输耗时可忽略，故直接以客户端往返耗时作上界——它恒不小于服务端处理耗时，断言只会更严）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-sec-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class AchievementApiSecurityIntegrationTest {

    /** 成就清单与待播报两个 GET 端点。 */
    private static final String LIST_PATH = "/api/achievements";
    private static final String PENDING_PATH = "/api/achievements/pending";
    private static final String ACK_PATH = "/api/achievements/notices/ack";

    /** 与 {@code app.jwt.secret} 不同的密钥，用于制造验签失败的令牌（长度满足 HS256 要求）。 */
    private static final String FOREIGN_SECRET =
            "foreign-secret-key-only-for-achievement-security-test-do-not-use";

    /** 鉴权失败时绝不能出现的四类成就数据键（需求 6.8）。 */
    private static final List<String> ACHIEVEMENT_DATA_KEYS =
            List.of("achievements", "unlockedCount", "items", "lastNotifiedEventId");

    /** 统一错误体的字段集上界，恰好 {@code {code, message, field}} 三项（需求 6.13）。 */
    private static final Set<String> ERROR_KEYS = Set.of("code", "message", "field");

    /** 服务端处理耗时上界（需求 6.14、5.15）。 */
    private static final long BUDGET_MS = 2000L;

    @LocalServerPort
    private int port;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ====== 1) 三个端点 × 6 种令牌形态 → 401 且两表不变（需求 6.8、6.9）======

    @Test
    void threeEndpoints_underAllTokenShapes_returnUnauthenticated_andLeaveBothTablesUnchanged() {
        // 先造一个「已注销用户」的令牌（注销会删该用户的成长与游标行，故必须在快照之前做完）。
        long deletedUserId = registerThenDeleteAccount("ach_sec_deleted@example.com");

        // 再造一个有真实数据的用户：解锁两枚成就并推进游标，使两张表都非空——
        // 否则「两表不变」的断言在空表上恒成立，等于什么都没验。
        String token = registerAndLogin("ach_sec_tables@example.com");
        long userId = userIdOf("ach_sec_tables@example.com");
        seedValidRecords(userId, 92_001L, 10);
        Map<String, Object> list = body(get(LIST_PATH, bearer(token)));
        assertThat(((Number) list.get("unlockedCount")).intValue()).isEqualTo(2);
        ResponseEntity<Map> ack = postAck(bearer(token), Map.of("lastEventId", "1"));
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> growthBefore = snapshotGrowthEvents();
        List<Map<String, Object>> noticesBefore = snapshotNotices();
        assertThat(growthBefore).as("growth_events 快照非空，否则「两表不变」断言是空的").isNotEmpty();
        assertThat(noticesBefore).as("achievement_notices 快照非空，否则「两表不变」断言是空的").isNotEmpty();

        Map<String, HttpHeaders> shapes = new LinkedHashMap<>();
        // 形态 1：完全没有 Authorization 头。
        shapes.put("缺失令牌", noAuth());
        // 形态 2：畸形，根本不是一个可解析的 JWT。
        shapes.put("畸形令牌", bearer("not-a-jwt.abc.def"));
        // 形态 3：结构完整但用别的密钥签名 → 验签失败。
        shapes.put("验签失败", bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1))));
        // 形态 4：本系统密钥签名但已过期。
        shapes.put("已过期", bearer(token(1L, jwtSecret, Duration.ofSeconds(-10))));
        // 形态 5：签名有效、未过期，但令牌用户已注销 —— 过滤链不查库，管不到这一情形。
        shapes.put("令牌用户已注销", bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1))));
        // 形态 6：空 Bearer（Bearer 后无令牌）。
        shapes.put("空 Bearer", blankBearer());

        for (Map.Entry<String, HttpHeaders> shape : shapes.entrySet()) {
            String label = shape.getKey();
            HttpHeaders headers = shape.getValue();
            assertUnauthenticated(get(LIST_PATH, headers), "成就清单 / " + label);
            assertUnauthenticated(get(PENDING_PATH, headers), "待播报成就 / " + label);
            assertUnauthenticated(postAck(headers, Map.of("lastEventId", "1")), "推进游标 / " + label);
        }

        // 鉴权失败的请求对两表零副作用：行数与全部列取值逐行相等（需求 6.8）。
        assertThat(snapshotGrowthEvents()).as("growth_events 行数与全部列取值不变").isEqualTo(growthBefore);
        assertThat(snapshotNotices()).as("achievement_notices 行数与全部列取值不变").isEqualTo(noticesBefore);
    }

    // ====== 2) UNAUTHENTICATED 优先于非法 lastEventId（需求 6.8）======

    @Test
    void unauthenticated_takesPrecedenceOverInvalidLastEventId() {
        // 先证明这个入参确实非法：有效令牌下返回 400 ACHIEVEMENT_ACK_PARAM_INVALID（需求 5.12）。
        String validToken = registerAndLogin("ach_sec_ackparam@example.com");
        ResponseEntity<Map> rejected = postAck(bearer(validToken), Map.of("lastEventId", "abc"));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> rejectedBody = body(rejected);
        assertThat(rejectedBody).containsEntry("code", "ACHIEVEMENT_ACK_PARAM_INVALID");
        assertThat(rejectedBody).containsEntry("field", "lastEventId");
        assertThat(rejectedBody.keySet()).containsExactlyInAnyOrderElementsOf(ERROR_KEYS);

        long deletedUserId = registerThenDeleteAccount("ach_sec_deleted_ack@example.com");
        List<Map<String, Object>> noticesBefore = snapshotNotices();

        // 已注销用户 + 非法 lastEventId：必须是 401 而非 400（存在性校验先于入参校验，需求 6.8、6.9）。
        assertUnauthenticated(
                postAck(bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1))),
                        Map.of("lastEventId", "abc")),
                "非法 lastEventId / 令牌用户已注销");
        // 其余令牌形态同样压过入参错误。
        assertUnauthenticated(postAck(noAuth(), Map.of("lastEventId", "abc")), "非法 lastEventId / 缺失令牌");
        assertUnauthenticated(postAck(bearer("not-a-jwt.abc.def"), Map.of("lastEventId", "abc")),
                "非法 lastEventId / 畸形令牌");
        assertUnauthenticated(postAck(bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1))),
                Map.of("lastEventId", "abc")), "非法 lastEventId / 验签失败");
        assertUnauthenticated(postAck(bearer(token(1L, jwtSecret, Duration.ofSeconds(-10))),
                Map.of("lastEventId", "abc")), "非法 lastEventId / 已过期");
        assertUnauthenticated(postAck(blankBearer(), Map.of("lastEventId", "abc")),
                "非法 lastEventId / 空 Bearer");

        // 非法请求与鉴权失败请求都不得改动游标表（需求 5.12、6.8）。
        assertThat(snapshotNotices()).as("achievement_notices 行数与全部列取值不变").isEqualTo(noticesBefore);
    }

    // ====== 3) 伪造身份字段无法越权（需求 6.10、6.16、6.17）======

    @Test
    void forgedIdentityFields_areIgnored_andOnlyOwnDataIsReadable() {
        // A：1 笔有效记账（只命中 FIRST_RECORD）；B：10 笔（多命中 RECORD_10）。数据刻意不同。
        String tokenA = registerAndLogin("ach_scope_a@example.com");
        String tokenB = registerAndLogin("ach_scope_b@example.com");
        long idA = userIdOf("ach_scope_a@example.com");
        long idB = userIdOf("ach_scope_b@example.com");
        seedValidRecords(idA, 92_002L, 1);
        seedValidRecords(idB, 92_003L, 10);

        // 各自触发一次结算（成就清单是写入型 GET），并顺带断言耗时上界（需求 6.14、5.15）。
        long startedAt = System.nanoTime();
        Map<String, Object> listBaseline = body(get(LIST_PATH, bearer(tokenA)));
        assertWithinBudget(startedAt, "成就清单");
        Map<String, Object> listOfB = body(get(LIST_PATH, bearer(tokenB)));

        startedAt = System.nanoTime();
        Map<String, Object> pendingBaseline = body(get(PENDING_PATH, bearer(tokenA)));
        assertWithinBudget(startedAt, "待播报成就");
        Map<String, Object> pendingOfB = body(get(PENDING_PATH, bearer(tokenB)));

        // A 与 B 的真实数据确实不同：否则下面的逐项相等断言是空的（需求 6.16）。
        assertThat(((Number) listBaseline.get("unlockedCount")).intValue()).isEqualTo(1);
        assertThat(((Number) listOfB.get("unlockedCount")).intValue()).isEqualTo(2);
        assertThat(listBaseline).isNotEqualTo(listOfB);
        assertThat(pendingBaseline).isNotEqualTo(pendingOfB);

        startedAt = System.nanoTime();
        ResponseEntity<Map> ackBaselineResponse = postAck(bearer(tokenA), Map.of("lastEventId", "0"));
        assertWithinBudget(startedAt, "推进播报游标");
        assertThat(ackBaselineResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> ackBaseline = body(ackBaselineResponse);

        String forgedQuery = "userId=" + idB + "&targetUserId=" + idB + "&uid=" + idB;

        // 3a) 伪造查询参数：响应与基线逐项相等且不报错（Map 相等即整棵 JSON 结构相等，需求 6.17）。
        assertThat(body(get(LIST_PATH + "?" + forgedQuery, bearer(tokenA)))).isEqualTo(listBaseline);
        assertThat(body(get(PENDING_PATH + "?" + forgedQuery, bearer(tokenA)))).isEqualTo(pendingBaseline);

        // 3b) 伪造自定义请求头：同样被忽略且不报错。
        HttpHeaders forgedHeaders = bearer(tokenA);
        forgedHeaders.set("X-User-Id", String.valueOf(idB));
        forgedHeaders.set("X-Target-User-Id", String.valueOf(idB));
        assertThat(body(get(LIST_PATH, forgedHeaders))).isEqualTo(listBaseline);
        assertThat(body(get(PENDING_PATH, forgedHeaders))).isEqualTo(pendingBaseline);

        // 3c) 伪造请求体字段：ack 的响应与只带 lastEventId 时逐项相等。
        ResponseEntity<Map> ackForged = postAck(bearer(tokenA), Map.of(
                "lastEventId", "0", "userId", String.valueOf(idB),
                "targetUserId", String.valueOf(idB), "uid", String.valueOf(idB)));
        assertThat(ackForged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(ackForged)).isEqualTo(ackBaseline);
        assertThat(body(postAck(forgedHeaders, Map.of("lastEventId", "0")))).isEqualTo(ackBaseline);

        // 3d) A 的响应里不出现 B 的任何成就事件 id（需求 6.16）。
        List<Long> eventIdsOfB = new ArrayList<>(eventIdsOf(listOfB, "achievements"));
        eventIdsOfB.addAll(eventIdsOf(pendingOfB, "items"));
        assertThat(eventIdsOfB).as("B 确实有成就事件 id 可供比对").isNotEmpty();
        assertThat(eventIdsOf(listBaseline, "achievements"))
                .as("A 的成就清单不含 B 的成就事件 id").doesNotContainAnyElementsOf(eventIdsOfB);
        assertThat(eventIdsOf(pendingBaseline, "items"))
                .as("A 的待播报列表不含 B 的成就事件 id").doesNotContainAnyElementsOf(eventIdsOfB);

        // 3e) A 推进自己的游标（推到自己的最大事件 id），B 的待播报结果与游标取值不受影响（需求 6.16）。
        long maxEventIdOfA = eventIdsOf(pendingBaseline, "items").stream()
                .mapToLong(Long::longValue).max().orElseThrow();
        ResponseEntity<Map> ackA = postAck(bearer(tokenA), Map.of("lastEventId", String.valueOf(maxEventIdOfA)));
        assertThat(ackA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) body(ackA).get("lastNotifiedEventId")).longValue()).isEqualTo(maxEventIdOfA);
        assertThat(body(get(PENDING_PATH, bearer(tokenB)))).isEqualTo(pendingOfB);

        // 3f) 反向确认：B 的令牌附加指向 A 的伪造入参，读到的仍是 B 自己的数据。
        String forgedTowardsA = "userId=" + idA + "&targetUserId=" + idA + "&uid=" + idA;
        assertThat(body(get(LIST_PATH + "?" + forgedTowardsA, bearer(tokenB)))).isEqualTo(listOfB);
        assertThat(body(get(PENDING_PATH + "?" + forgedTowardsA, bearer(tokenB)))).isEqualTo(pendingOfB);
    }

    // ====== 4) 与会话账本无关（需求 6.11）======

    @Test
    void ledgerHeader_doesNotAffectAnyOfTheThreeEndpoints() {
        String token = registerAndLogin("ach_sec_noledger@example.com");
        long userId = userIdOf("ach_sec_noledger@example.com");
        seedValidRecords(userId, 92_004L, 3);

        // 不带 X-Ledger-Id 的基线。
        Map<String, Object> listNoLedger = body(get(LIST_PATH, bearer(token)));
        Map<String, Object> pendingNoLedger = body(get(PENDING_PATH, bearer(token)));
        Map<String, Object> ackNoLedger = body(postAck(bearer(token), Map.of("lastEventId", "0")));

        // 带一个不可访问的数字取值，与带一个非数字取值：都不得被拒，结果逐项相等（需求 6.11）。
        for (String ledgerValue : List.of("987654321", "not-a-ledger-id")) {
            HttpHeaders withLedger = bearer(token);
            withLedger.set("X-Ledger-Id", ledgerValue);

            ResponseEntity<Map> list = get(LIST_PATH, withLedger);
            assertThat(list.getStatusCode()).as("成就清单 / X-Ledger-Id=" + ledgerValue)
                    .isEqualTo(HttpStatus.OK);
            assertThat(body(list)).isEqualTo(listNoLedger);

            ResponseEntity<Map> pending = get(PENDING_PATH, withLedger);
            assertThat(pending.getStatusCode()).as("待播报成就 / X-Ledger-Id=" + ledgerValue)
                    .isEqualTo(HttpStatus.OK);
            assertThat(body(pending)).isEqualTo(pendingNoLedger);

            ResponseEntity<Map> ack = postAck(withLedger, Map.of("lastEventId", "0"));
            assertThat(ack.getStatusCode()).as("推进游标 / X-Ledger-Id=" + ledgerValue)
                    .isEqualTo(HttpStatus.OK);
            assertThat(body(ack)).isEqualTo(ackNoLedger);
        }
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /**
     * 断言响应为 401、统一错误体 {@code code=UNAUTHENTICATED}，且<b>不含任何成就数据</b>（需求 6.8、6.13）。
     */
    private void assertUnauthenticated(ResponseEntity<Map> response, String shape) {
        assertThat(response.getStatusCode()).as(shape).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).as(shape)
                .isNotNull()
                .satisfies(ct -> assertThat(ct.includes(MediaType.APPLICATION_JSON)).isTrue());

        Map<String, Object> body = body(response);
        assertThat(body).as(shape).containsEntry("code", "UNAUTHENTICATED");
        // 统一错误体：字段集是 {code, message, field} 的子集（field 无归属时省略）。
        assertThat(ERROR_KEYS).as(shape + " / 错误体不含第 4 个字段").containsAll(body.keySet());
        for (String dataKey : ACHIEVEMENT_DATA_KEYS) {
            assertThat(body).as(shape + " / 不含成就数据 " + dataKey).doesNotContainKey(dataKey);
        }
    }

    /** 断言往返耗时不超过 2000ms（需求 6.14、5.15）。 */
    private void assertWithinBudget(long startedAtNanos, String label) {
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        assertThat(elapsedMs).as(label + " 的耗时（ms）").isLessThanOrEqualTo(BUDGET_MS);
    }

    /** 取某个响应里列表项的全部非空 {@code eventId}。 */
    @SuppressWarnings("unchecked")
    private List<Long> eventIdsOf(Map<String, Object> body, String listKey) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) body.get(listKey)) {
            Object eventId = item.get("eventId");
            if (eventId != null) {
                ids.add(((Number) eventId).longValue());
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    // ---------------------------------- 表快照辅助 ----------------------------------

    /** {@code growth_events} 全表快照：行数 + 每行全部列取值（{@code SELECT *} 不遗漏任何列）。 */
    private List<Map<String, Object>> snapshotGrowthEvents() {
        return jdbcTemplate.queryForList("SELECT * FROM growth_events ORDER BY id");
    }

    /** {@code achievement_notices} 全表快照：行数 + 每行全部列取值。 */
    private List<Map<String, Object>> snapshotNotices() {
        return jdbcTemplate.queryForList("SELECT * FROM achievement_notices ORDER BY user_id");
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> postAck(HttpHeaders headers, Map<String, Object> payload) {
        HttpHeaders withJson = new HttpHeaders();
        withJson.putAll(headers);
        withJson.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(ACK_PATH), HttpMethod.POST,
                new HttpEntity<>(payload, withJson), Map.class);
    }

    private HttpHeaders noAuth() {
        return new HttpHeaders();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /**
     * 空 Bearer：{@code Authorization: Bearer } 后无令牌。
     *
     * <p>若 HTTP 客户端裁掉了尾随空白，头值退化为 {@code "Bearer"}（不带空格），过滤链同样取不到
     * 令牌——两种情形都必须是 401 {@code UNAUTHENTICATED}，故本断言与是否裁空白无关。</p>
     */
    private HttpHeaders blankBearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer ");
        return headers;
    }

    /** 自行签发令牌：可指定用户 id、密钥（制造验签失败）与有效期（负值即已过期）。 */
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

    // ---------------------------------- 数据准备辅助 ----------------------------------

    /**
     * 落 {@code count} 笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type = expense}、{@code ledger_id} 非 NULL），记账日均为当天。直接经仓储落库，
     * 不重复覆盖记账链路——本类验的是成就接口的鉴权与数据范围。
     */
    private void seedValidRecords(long userId, long ledgerId, int count) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < count; i++) {
            Transaction tx = new Transaction();
            tx.setUserId(userId);
            tx.setLedgerId(ledgerId);
            tx.setCreatedBy(userId);
            tx.setType(TransactionType.EXPENSE);
            tx.setAmount(new BigDecimal("12.34"));
            tx.setAccountId(ledgerId);
            tx.setCategoryId(ledgerId);
            tx.setOccurredAt(now);
            tx.setCreatedAt(now);
            tx.setUpdatedAt(now);
            transactionRepository.save(tx);
        }
    }

    /** 邮箱验证码登录/注册合一，返回 JWT。 */
    private String registerAndLogin(String email) {
        verificationCodeRepository.deleteByEmail(email);

        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String code = latestCode(email, EmailCodePurpose.LOGIN);
        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /**
     * 建号 → 二次验证注销 → 返回该已注销用户的 id。
     *
     * <p>用于制造「签名有效、未过期，但令牌用户已不存在」这一过滤链管不到的令牌形态（需求 6.8、6.9）。</p>
     */
    private long registerThenDeleteAccount(String email) {
        String token = registerAndLogin(email);
        long userId = userIdOf(email);

        ResponseEntity<Void> sendDelete = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "DELETE"), Void.class);
        assertThat(sendDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String deleteCode = latestCode(email, EmailCodePurpose.DELETE);

        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> delete = rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                new HttpEntity<>(Map.of("code", deleteCode), headers), Void.class);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userRepository.findById(userId)).isEmpty();
        return userId;
    }

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }

    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }
}
