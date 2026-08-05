package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

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
 * 连续记账接口的鉴权与越权集成测试（任务 7.1，需求 6.8、6.9、6.10、6.11、6.15、6.16、6.17、6.12）。
 *
 * <p>照抄 {@link GrowthApiSecurityIntegrationTest} 的 {@code TestRestTemplate} + {@code Jwts} 手工
 * 签发范式，使用<b>独立命名</b>的内存库。全栈 {@code @SpringBootTest}(RANDOM_PORT)，经真实 HTTP、
 * 真实 Spring Security 过滤链、真实 JWT 与 H2 持久化层，覆盖五件事：</p>
 *
 * <ol>
 *   <li><b>两个受保护端点 × 5 种令牌形态一律 401 {@code UNAUTHENTICATED}</b>（需求 6.8）：
 *       缺失 / 验签失败 / 已过期 / <b>令牌用户已注销</b> / 空 Bearer，且响应不含
 *       {@code currentStreakDays} / {@code maxStreakDays} / {@code items} 三项中的任何一项。
 *       第四种是这个测试存在的主要理由：{@link com.damien.youyu.security.JwtAuthenticationFilter}
 *       只验签与验有效期、<b>不查库</b>，「令牌合法但用户已注销」只能由
 *       {@link StreakController#requireExistingUserId()} 兜住（需求 6.9）。</li>
 *   <li><b>{@code UNAUTHENTICATED} 优先于非法分页参数</b>（需求 6.8）：同一组非法 {@code page} 在
 *       有效令牌下确实返回 400 {@code STREAK_PAGE_PARAM_INVALID}（否则本断言是空的），但在已注销
 *       用户令牌下返回 401 而非 400。</li>
 *   <li><b>伪造入参无法越权</b>（需求 6.10、6.16）：以 A 的令牌附加 {@code userId} / {@code targetUserId}
 *       / {@code uid}（取值全部指向 B）以及请求体字段与自定义头请求两个端点，响应与不带这些入参时
 *       <b>逐字段相等</b>，且只含 A 的数据。B 的段数据刻意与 A 不同（B 记满 3 天连续、A 只记 1 天），
 *       若越权成功，逐字段相等断言必然失败。</li>
 *   <li><b>与会话账本无关</b>（需求 6.11）：不带 {@code X-Ledger-Id} 与带任意 {@code X-Ledger-Id}
 *       时两个端点响应<b>逐字段相等</b>。</li>
 *   <li><b>数据范围硬性限定本人 + 越界页码降级</b>（需求 6.15、6.17）：A 的令牌读不到 B 的任何段；
 *       越界页码返回空列表 + 真实 {@code total}，不报错。</li>
 * </ol>
 *
 * <p>连续记账数据经真实链路生成：直接落有效记账交易到 {@link TransactionRepository}，再以
 * {@code GET /api/streak} 触发一次同步结算写档案、事件与段行。跨日连续段通过把 {@code created_at}
 * 落在不同自然日实现。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-streaksec-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class StreakApiSecurityIntegrationTest {

    /** 两个受保护端点（需求 6.8）。 */
    private static final List<String> PROTECTED_PATHS = List.of("/api/streak", "/api/streak/segments");

    /** 与 {@code app.jwt.secret} 不同的密钥，用于制造验签失败的令牌（长度满足 HS256 要求）。 */
    private static final String FOREIGN_SECRET =
            "foreign-secret-key-only-for-streak-security-test-do-not-use";

    /** 响应绝不应出现的连续记账数据键（未认证时，需求 6.8）。 */
    private static final List<String> STREAK_DATA_KEYS =
            List.of("currentStreakDays", "maxStreakDays", "items");

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

    // ============ 1) 两个受保护端点 × 5 种令牌形态 → 401 UNAUTHENTICATED（需求 6.8、6.9）============

    @Test
    void protectedEndpoints_underAllFiveTokenShapes_returnUnauthenticated() {
        long deletedUserId = registerThenDeleteAccount("streak_sec_deleted@example.com");

        for (String path : PROTECTED_PATHS) {
            // 形态 1：完全没有 Authorization 头。
            assertUnauthenticated(get(path, noAuth()), path + " / 缺失令牌");
            // 形态 2：结构完整但用别的密钥签名 → 验签失败（畸形/签名错）。
            assertUnauthenticated(get(path, bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)))),
                    path + " / 验签失败");
            // 形态 3：本系统密钥签名但已过期。
            assertUnauthenticated(get(path, bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)))),
                    path + " / 已过期");
            // 形态 4：签名有效、未过期，但令牌用户已注销 —— 过滤链不查库，管不到这一情形（需求 6.9）。
            assertUnauthenticated(get(path, bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1)))),
                    path + " / 令牌用户已注销");
            // 形态 5：空 Bearer（Bearer 后无令牌，畸形）。
            assertUnauthenticated(get(path, blankBearer()), path + " / 空 Bearer");
        }
    }

    @Test
    void unauthenticated_takesPrecedenceOverInvalidPageParams() {
        String invalidPaging = "/api/streak/segments?page=-1&size=999";
        long deletedUserId = registerThenDeleteAccount("streak_sec_deleted_paging@example.com");

        // 先证明这组分页参数确实非法：有效令牌下返回 400 STREAK_PAGE_PARAM_INVALID。
        String validToken = registerAndLogin("streak_sec_paging@example.com");
        ResponseEntity<Map> rejected = get(invalidPaging, bearer(validToken));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(rejected)).containsEntry("code", "STREAK_PAGE_PARAM_INVALID");

        // 5 种令牌形态下，鉴权错误一律压过分页参数错误（需求 6.8）。已注销用户 + 非法 page → 401 而非 400。
        assertUnauthenticated(get(invalidPaging, noAuth()), "非法分页 / 缺失令牌");
        assertUnauthenticated(get(invalidPaging, bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)))),
                "非法分页 / 验签失败");
        assertUnauthenticated(get(invalidPaging, bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)))),
                "非法分页 / 已过期");
        assertUnauthenticated(get(invalidPaging, bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1)))),
                "非法分页 / 令牌用户已注销");
        assertUnauthenticated(get(invalidPaging, blankBearer()), "非法分页 / 空 Bearer");
    }

    // ==================== 2) 伪造入参无法越权读取他人数据（需求 6.10、6.16）====================

    @Test
    void forgedTargetUserParams_areIgnored_andOnlyOwnDataIsReadable() {
        // A：1 天有效记账（当前连续 1 天、1 段）；B：连续 3 天（当前连续 3 天、1 段，days=3）。数据刻意不同。
        String tokenA = registerAndLogin("streak_scope_a@example.com");
        String tokenB = registerAndLogin("streak_scope_b@example.com");
        long idA = userIdOf("streak_scope_a@example.com");
        long idB = userIdOf("streak_scope_b@example.com");
        seedRecordsOn(idA, 93_001L, todayOffsets(1));      // 仅今天
        seedRecordsOn(idB, 93_002L, todayOffsets(3));      // 今天、昨天、前天

        // 触发结算（概览是写入型 GET）：各自跑一次即写好档案、事件与段行。
        Map<String, Object> overviewBaseline = body(get("/api/streak", bearer(tokenA)));
        Map<String, Object> segmentsBaseline = body(get("/api/streak/segments", bearer(tokenA)));
        Map<String, Object> overviewOfB = body(get("/api/streak", bearer(tokenB)));

        // A 与 B 的真实数据确实不同：否则下面的逐字段相等断言是空的。
        assertThat(((Number) overviewBaseline.get("currentStreakDays")).intValue()).isEqualTo(1);
        assertThat(((Number) overviewOfB.get("currentStreakDays")).intValue()).isEqualTo(3);
        assertThat(overviewBaseline).isNotEqualTo(overviewOfB);

        String forged = "userId=" + idB
                + "&targetUserId=" + idB
                + "&uid=" + idB;

        // 带上全套伪造查询参数：响应与基线逐字段相等（Map 相等即整棵 JSON 结构相等），且不报错（需求 6.10、6.16）。
        assertThat(body(get("/api/streak?" + forged, bearer(tokenA)))).isEqualTo(overviewBaseline);
        assertThat(body(get("/api/streak/segments?" + forged, bearer(tokenA)))).isEqualTo(segmentsBaseline);
        // 合法分页参数与伪造入参共存时同样只读到 A 的数据。
        assertThat(body(get("/api/streak/segments?page=0&size=20&" + forged, bearer(tokenA))))
                .isEqualTo(segmentsBaseline);

        // 携带自定义头 + 请求体字段（GET 带体经 exchange）：一律忽略，与不带时逐字段相等且不报错（需求 6.10、6.16）。
        HttpHeaders forgedHeaders = bearer(tokenA);
        forgedHeaders.set("X-User-Id", String.valueOf(idB));
        forgedHeaders.set("X-Target-User-Id", String.valueOf(idB));
        assertThat(body(getWithBody("/api/streak", forgedHeaders, Map.of("userId", idB))))
                .isEqualTo(overviewBaseline);
        assertThat(body(getWithBody("/api/streak/segments", forgedHeaders, Map.of("userId", idB))))
                .isEqualTo(segmentsBaseline);

        // 反向确认：B 的令牌附加指向 A 的伪造入参，读到的仍是 B 自己的数据（需求 6.15）。
        Map<String, Object> overviewOfBForged = body(get("/api/streak?userId=" + idA
                + "&targetUserId=" + idA + "&uid=" + idA, bearer(tokenB)));
        assertThat(overviewOfBForged).isEqualTo(overviewOfB);
    }

    // ============== 3) 与会话账本无关（需求 6.11）==============

    @Test
    void ledgerHeader_doesNotAffectResponse() {
        String token = registerAndLogin("streak_noledger@example.com");
        long userId = userIdOf("streak_noledger@example.com");
        seedRecordsOn(userId, 93_003L, todayOffsets(2));

        // 不带 X-Ledger-Id 的基线。
        Map<String, Object> overviewNoLedger = body(get("/api/streak", bearer(token)));
        Map<String, Object> segmentsNoLedger = body(get("/api/streak/segments", bearer(token)));

        // 带任意 X-Ledger-Id：连续记账数据与账本无关，响应逐字段相等（需求 6.11）。
        HttpHeaders withLedger = bearer(token);
        withLedger.set("X-Ledger-Id", "987654321");
        assertThat(body(get("/api/streak", withLedger))).isEqualTo(overviewNoLedger);
        assertThat(body(get("/api/streak/segments", withLedger))).isEqualTo(segmentsNoLedger);
    }

    // ============== 4) 数据范围限定本人 + 越界页码降级（需求 6.15、6.17）==============

    @Test
    void userA_cannotReadUserBSegments_andOutOfRangePageReturnsEmptyWithRealTotal() {
        String tokenA = registerAndLogin("streak_isolation_a@example.com");
        String tokenB = registerAndLogin("streak_isolation_b@example.com");
        long idA = userIdOf("streak_isolation_a@example.com");
        long idB = userIdOf("streak_isolation_b@example.com");
        seedRecordsOn(idA, 93_004L, todayOffsets(2));      // A：连续 2 天 → 1 段
        seedRecordsOn(idB, 93_005L, new int[] {0, 2, 4});  // B：三个不相邻日 → 3 段

        // 段行由结算写入，而 /segments 端点刻意不触发结算（需求 6.6）。先各请求一次概览触发结算建段。
        get("/api/streak", bearer(tokenA));
        get("/api/streak", bearer(tokenB));

        Map<String, Object> segmentsA = body(get("/api/streak/segments", bearer(tokenA)));
        Map<String, Object> segmentsB = body(get("/api/streak/segments", bearer(tokenB)));

        // A 只看到自己的 1 段，B 看到自己的 3 段：数据范围硬性限定本人（需求 6.15）。
        assertThat(((Number) segmentsA.get("total")).longValue()).isEqualTo(1L);
        assertThat(itemsOf(segmentsA)).hasSize(1);
        assertThat(((Number) segmentsB.get("total")).longValue()).isEqualTo(3L);
        assertThat(itemsOf(segmentsB)).hasSize(3);

        // 越界页码：空列表 + 真实总条数，不报错（需求 6.17）。
        ResponseEntity<Map> outOfRange = get("/api/streak/segments?page=100&size=20", bearer(tokenB));
        assertThat(outOfRange.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> outBody = body(outOfRange);
        assertThat(itemsOf(outBody)).isEmpty();
        assertThat(((Number) outBody.get("total")).longValue()).isEqualTo(3L);
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言响应为 401、统一错误体 {@code code=UNAUTHENTICATED}，且不含任何连续记账数据键（需求 6.8）。 */
    private void assertUnauthenticated(ResponseEntity<Map> response, String shape) {
        assertThat(response.getStatusCode()).as(shape).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).as(shape)
                .isNotNull()
                .satisfies(ct -> assertThat(ct.includes(MediaType.APPLICATION_JSON)).isTrue());
        Map<String, Object> body = body(response);
        assertThat(body).as(shape).containsEntry("code", "UNAUTHENTICATED");
        for (String dataKey : STREAK_DATA_KEYS) {
            assertThat(body).as(shape + " / 不含连续记账数据键 " + dataKey).doesNotContainKey(dataKey);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    /** GET 携带请求体：验证请求体中的身份字段被忽略（需求 6.10、6.16）。 */
    private ResponseEntity<Map> getWithBody(String path, HttpHeaders headers, Map<String, Object> body) {
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.addAll(headers);
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(body, jsonHeaders), Map.class);
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

    /** 生成「今天起向前 n 个连续自然日」的偏移数组：{0, 1, ..., n-1}（0 = 今天）。 */
    private int[] todayOffsets(int n) {
        int[] offsets = new int[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = i;
        }
        return offsets;
    }

    /**
     * 按 {@code dayOffsets} 逐个自然日各落一笔「有效记账交易」，{@code created_at} = 当天减去该偏移天数
     * （成长体系的记账日历取自 {@code created_at} 的自然日）。用于构造跨日连续段与不相邻段。
     */
    private void seedRecordsOn(long userId, long ledgerId, int[] dayOffsets) {
        LocalDateTime now = LocalDateTime.now();
        for (int offset : dayOffsets) {
            LocalDateTime at = now.minusDays(offset);
            Transaction tx = new Transaction();
            tx.setUserId(userId);
            tx.setLedgerId(ledgerId);
            tx.setCreatedBy(userId);
            tx.setType(TransactionType.EXPENSE);
            tx.setAmount(new BigDecimal("12.34"));
            tx.setAccountId(ledgerId);
            tx.setCategoryId(ledgerId);
            tx.setOccurredAt(at);
            tx.setCreatedAt(at);
            tx.setUpdatedAt(at);
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
     * <p>用于制造「签名有效、未过期，但令牌用户已不存在」这一过滤链管不到的令牌形态（需求 6.9）。</p>
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
