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
 * 成长接口的鉴权与越权集成测试（任务 6.5，需求 10.6、10.7、10.8、10.12、10.13）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)，经真实 HTTP、真实 Spring Security 过滤链、真实 JWT
 * 与 H2 持久化层，覆盖四件事：</p>
 *
 * <ol>
 *   <li><b>两个受保护端点 × 5 种令牌形态一律 401 {@code UNAUTHENTICATED}</b>（需求 10.6、10.7）：
 *       缺失 / 验签失败 / 已过期 / <b>令牌用户已注销</b> / 空 Bearer。第四种是这个测试存在的主要理由：
 *       {@link com.damien.youyu.security.JwtAuthenticationFilter} 只验签与验有效期、<b>不查库</b>，
 *       所以「令牌合法但用户已注销」过滤链管不到，只能由 {@link GrowthController#requireExistingUserId()}
 *       兜住。删掉那一步校验，本类「令牌用户已注销」的几个断言就会变成 200。</li>
 *   <li><b>{@code UNAUTHENTICATED} 优先于非法分页参数</b>（需求 10.7）：同一组非法 {@code page} /
 *       {@code size} 在有效令牌下确实返回 400 {@code GROWTH_PAGE_PARAM_INVALID}（否则本断言是空的），
 *       但在上述 5 种令牌形态下一律 401。</li>
 *   <li><b>伪造入参无法越权</b>（需求 10.8）：以 A 的令牌附加 {@code userId} / {@code targetUserId} /
 *       {@code uid}（以及 {@code level} / {@code exp}，取值全部指向 B）请求成长概览与经验明细，响应与
 *       不带这些入参时<b>逐字段相等</b>，且只含 A 的数据。B 的成长数据刻意与 A 不同（B 记满 10 笔命中
 *       {@code RECORD_10} 徽章，A 只记 1 笔），若越权成功，逐字段相等断言必然失败。</li>
 *   <li><b>与会话账本无关 + 不泄漏敏感字段</b>（需求 10.12、10.13）：不带 {@code X-Ledger-Id} 与带一个
 *       不可访问的 {@code X-Ledger-Id} 时两个端点响应<b>逐字段相等</b>；两个端点序列化后的 JSON 文本一律
 *       不出现 {@code email} / {@code wx_openid} / {@code wx_unionid} / {@code invite_code} / {@code plan}
 *       / {@code role} 六个键与取值。</li>
 * </ol>
 *
 * <p>成长数据经真实链路生成：直接落一笔（或多笔）有效记账交易到 {@link TransactionRepository}，再以
 * {@code GET /api/growth} 触发一次同步结算写档案与事件（成长概览是本项目唯一的写入型 GET）。使用独立
 * 命名的内存库，避免污染其它共享内存库的切片测试。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-growthsec-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class GrowthApiSecurityIntegrationTest {

    /** 两个受保护端点（需求 10.6）。 */
    private static final List<String> PROTECTED_PATHS = List.of("/api/growth", "/api/growth/events");

    /** 与 {@code app.jwt.secret} 不同的密钥，用于制造验签失败的令牌（长度满足 HS256 要求）。 */
    private static final String FOREIGN_SECRET =
            "foreign-secret-key-only-for-growth-security-test-do-not-use";

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

    // ============ 1) 两个受保护端点 × 5 种令牌形态 → 401 UNAUTHENTICATED（需求 10.6、10.7）============

    @Test
    void protectedEndpoints_underAllFiveTokenShapes_returnUnauthenticated() {
        long deletedUserId = registerThenDeleteAccount("growth_sec_deleted@example.com");

        for (String path : PROTECTED_PATHS) {
            // 形态 1：完全没有 Authorization 头。
            assertUnauthenticated(get(path, noAuth()), path + " / 缺失令牌");
            // 形态 2：结构完整但用别的密钥签名 → 验签失败。
            assertUnauthenticated(get(path, bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)))),
                    path + " / 验签失败");
            // 形态 3：本系统密钥签名但已过期。
            assertUnauthenticated(get(path, bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)))),
                    path + " / 已过期");
            // 形态 4：签名有效、未过期，但令牌用户已注销 —— 过滤链不查库，管不到这一情形。
            assertUnauthenticated(get(path, bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1)))),
                    path + " / 令牌用户已注销");
            // 形态 5：空 Bearer（Bearer 后无令牌）。
            assertUnauthenticated(get(path, blankBearer()), path + " / 空 Bearer");
        }
    }

    @Test
    void unauthenticated_takesPrecedenceOverInvalidPageParams() {
        String invalidPaging = "/api/growth/events?page=-1&size=999";
        long deletedUserId = registerThenDeleteAccount("growth_sec_deleted_paging@example.com");

        // 先证明这组分页参数确实非法：有效令牌下返回 400 GROWTH_PAGE_PARAM_INVALID。
        String validToken = registerAndLogin("growth_sec_paging@example.com");
        ResponseEntity<Map> rejected = get(invalidPaging, bearer(validToken));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(rejected)).containsEntry("code", "GROWTH_PAGE_PARAM_INVALID");

        // 5 种令牌形态下，鉴权错误一律压过分页参数错误（需求 10.7）。
        assertUnauthenticated(get(invalidPaging, noAuth()), "非法分页 / 缺失令牌");
        assertUnauthenticated(get(invalidPaging, bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)))),
                "非法分页 / 验签失败");
        assertUnauthenticated(get(invalidPaging, bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)))),
                "非法分页 / 已过期");
        assertUnauthenticated(get(invalidPaging, bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1)))),
                "非法分页 / 令牌用户已注销");
        assertUnauthenticated(get(invalidPaging, blankBearer()), "非法分页 / 空 Bearer");
    }

    // ==================== 2) 伪造入参无法越权读取他人数据（需求 10.8）====================

    @Test
    void forgedTargetUserParams_areIgnored_andOnlyOwnDataIsReadable() {
        // A：1 笔有效记账（level 2、totalRecordCount 1）；B：10 笔（额外命中 RECORD_10 徽章）。数据刻意不同。
        String tokenA = registerAndLogin("growth_scope_a@example.com");
        String tokenB = registerAndLogin("growth_scope_b@example.com");
        long idA = userIdOf("growth_scope_a@example.com");
        long idB = userIdOf("growth_scope_b@example.com");
        seedValidRecords(idA, 90_001L, 1);
        seedValidRecords(idB, 90_002L, 10);

        // 触发结算（成长概览是写入型 GET）：各自跑一次即写好档案与事件。
        Map<String, Object> infoBaseline = body(get("/api/growth", bearer(tokenA)));
        Map<String, Object> listBaseline = body(get("/api/growth/events", bearer(tokenA)));
        Map<String, Object> infoOfB = body(get("/api/growth", bearer(tokenB)));

        // A 与 B 的真实数据确实不同：否则下面的逐字段相等断言是空的。
        assertThat(infoBaseline).containsEntry("totalRecordCount", 1);
        assertThat(infoOfB).containsEntry("totalRecordCount", 10);
        assertThat(infoBaseline).isNotEqualTo(infoOfB);

        String forged = "userId=" + idB
                + "&targetUserId=" + idB
                + "&uid=" + idB
                + "&level=99"
                + "&exp=999999";

        // 带上全套伪造入参：响应与基线逐字段相等（Map 相等即整棵 JSON 结构相等）。
        assertThat(body(get("/api/growth?" + forged, bearer(tokenA)))).isEqualTo(infoBaseline);
        assertThat(body(get("/api/growth/events?" + forged, bearer(tokenA)))).isEqualTo(listBaseline);
        // 合法分页参数与伪造入参共存时同样只读到 A 的数据。
        assertThat(body(get("/api/growth/events?page=0&size=20&" + forged, bearer(tokenA))))
                .isEqualTo(listBaseline);

        // 反向确认：B 的令牌附加指向 A 的伪造入参，读到的仍是 B 自己的数据。
        Map<String, Object> infoOfBForged = body(get("/api/growth?userId=" + idA
                + "&targetUserId=" + idA + "&uid=" + idA, bearer(tokenB)));
        assertThat(infoOfBForged).isEqualTo(infoOfB);
    }

    // ============== 3) 与会话账本无关 + 不泄漏敏感字段（需求 10.12、10.13）==============

    @Test
    void ledgerHeader_doesNotAffectResponse_andSensitiveFieldsNeverLeak() {
        String token = registerAndLogin("growth_noleak@example.com");
        long userId = userIdOf("growth_noleak@example.com");
        seedValidRecords(userId, 90_003L, 3);

        // 不带 X-Ledger-Id 的基线。
        Map<String, Object> overviewNoLedger = body(get("/api/growth", bearer(token)));
        Map<String, Object> eventsNoLedger = body(get("/api/growth/events", bearer(token)));

        // 带一个不可访问的 X-Ledger-Id：成长数据与账本无关，响应逐字段相等（需求 10.12）。
        HttpHeaders withBogusLedger = bearer(token);
        withBogusLedger.set("X-Ledger-Id", "987654321");
        assertThat(body(get("/api/growth", withBogusLedger))).isEqualTo(overviewNoLedger);
        assertThat(body(get("/api/growth/events", withBogusLedger))).isEqualTo(eventsNoLedger);

        // 序列化后的 JSON 文本一律不出现被排除的六个键与取值（需求 10.13）。
        assertNoSensitiveFields(rawGet("/api/growth", bearer(token)), "成长概览");
        assertNoSensitiveFields(rawGet("/api/growth/events", bearer(token)), "经验明细");
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言响应为 401 且统一错误体 {@code code=UNAUTHENTICATED}，正文为 JSON。 */
    private void assertUnauthenticated(ResponseEntity<Map> response, String shape) {
        assertThat(response.getStatusCode()).as(shape).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).as(shape)
                .isNotNull()
                .satisfies(ct -> assertThat(ct.includes(MediaType.APPLICATION_JSON)).isTrue());
        assertThat(body(response)).as(shape).containsEntry("code", "UNAUTHENTICATED");
    }

    /** 断言 JSON 文本不含被排除的六个字段键与取值（需求 10.13）。 */
    private void assertNoSensitiveFields(String rawJson, String label) {
        assertThat(rawJson).as(label + " / 200 且有响应体").isNotBlank();
        for (String forbidden : List.of("email", "wx_openid", "wx_unionid", "invite_code", "plan", "role")) {
            assertThat(rawJson).as(label + " / 不含 " + forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
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
     * {@code type ∈ {expense}}、{@code ledger_id} 非 NULL），记账日均为当天（{@code created_at} = now）。
     * 直接经仓储落库，不重复覆盖记账链路——本类验的是成长接口的鉴权与数据范围。
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
        ResponseEntity<Map> login = emailLoginWithFreshCode(email);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /**
     * 建号 → 二次验证注销 → 返回该已注销用户的 id。
     *
     * <p>用于制造「签名有效、未过期，但令牌用户已不存在」这一过滤链管不到的令牌形态（需求 10.7）。</p>
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

    /** 以「新鲜」LOGIN 验证码执行 email-login（清历史码以规避 60s 发码冷却）。 */
    private ResponseEntity<Map> emailLoginWithFreshCode(String email) {
        verificationCodeRepository.deleteByEmail(email);

        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String code = latestCode(email, EmailCodePurpose.LOGIN);
        return rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
    }

    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }
}
