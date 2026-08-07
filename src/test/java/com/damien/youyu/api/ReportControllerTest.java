package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 智能月报聚合接口 {@code GET /api/reports/monthly-digest} 的<b>控制器契约与安全边界</b>集成测试
 * （任务 4，需求 1.2、9.2、9.3、9.4）。
 *
 * <p>照抄 {@link GrowthApiSecurityIntegrationTest} / {@link ReminderApiSecurityIntegrationTest} 的
 * {@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate} + {@code Jwts} 手工签发范式，
 * 使用<b>独立命名</b>的内存库。经真实 HTTP、真实 Spring Security 过滤链、真实 JWT、真实 {@code CurrentLedger}
 * 账本解析与 H2 持久化层，覆盖四件事：</p>
 *
 * <ol>
 *   <li><b>缺省 {@code month} 取当前自然月</b>（需求 1.2）：有效令牌、不带 {@code month} 参数请求，
 *       返回 200 且 {@code month} 恰为 {@code Asia/Shanghai} 当前自然月、{@code monthStatus} 为
 *       {@code partial}（当前月未完结），并携带九个模块字段。</li>
 *   <li><b>无 / 坏令牌 → 401 {@code UNAUTHENTICATED}，响应不含任何月报字段</b>（需求 9.2）：缺失 /
 *       验签失败 / 已过期 / 空 Bearer 四种形态一律 401，响应体不出现 {@code month} /
 *       {@code income} 等月报数据键。</li>
 *   <li><b>越权 {@code X-Ledger-Id} → {@code LEDGER_NOT_ACCESSIBLE}</b>（需求 9.3）：令牌有效但
 *       {@code X-Ledger-Id} 指向当前用户无权访问的账本，返回既有的账本不可访问错误，且不含任何月报字段。</li>
 *   <li><b>非法 {@code month} → {@code REPORT_PARAM_INVALID}（field=month）</b>（需求 9.4）：
 *       {@code 2024-13}、{@code abc} 等非 {@code YYYY-MM} 取值一律 400、错误码
 *       {@code REPORT_PARAM_INVALID}、出错字段为 {@code month}，且不含任何月报字段。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-report-digest-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class ReportControllerTest {

    private static final String DIGEST_PATH = "/api/reports/monthly-digest";

    /** AI 趣味分析只读接口路径（需求 10）。 */
    private static final String AI_INSIGHTS_PATH = "/api/reports/ai-insights";

    /** 响应绝不应出现的 AI 趣味分析数据键（未认证 / 账本不可访问 / 参数非法时，需求 10.2、10.3、10.4、10.8）。 */
    private static final List<String> AI_INSIGHT_DATA_KEYS = List.of(
            "month", "monthStatus", "isFallback", "fallbackText", "insights");

    /** 业务时区（UTC+8），与后端注入的 {@code Clock} 一致（{@code TimeConfig}）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 与 {@code app.jwt.secret} 不同的密钥，用于制造验签失败的令牌（长度满足 HS256 要求）。 */
    private static final String FOREIGN_SECRET =
            "foreign-secret-key-only-for-report-digest-test-do-not-use";

    /** 响应绝不应出现的月报数据键（未认证 / 账本不可访问 / 参数非法时，需求 9.2、9.3、9.4）。 */
    private static final List<String> DIGEST_DATA_KEYS = List.of(
            "month", "monthStatus", "income", "expense", "netBalance",
            "trend", "categoryRanking", "budget", "largestExpense", "mostFrugalWeek");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    // ============ 1) 缺省 month 取当前自然月（需求 1.2）============

    @Test
    void missingMonthParam_defaultsToCurrentMonth_andReturnsDigest() {
        String token = registerAndLogin("report_digest_default@example.com");

        ResponseEntity<String> response = get(DIGEST_PATH, bearer(token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = parse(response);
        // 缺省取 Asia/Shanghai 当前自然月（需求 1.2）。
        assertThat(body).containsEntry("month", YearMonth.now(ZONE).toString());
        // 当前自然月未完结 → 进行中（需求 1.3）。
        assertThat(body).containsEntry("monthStatus", "partial");
        // 九个模块字段齐备（需求 1.1、9.1）。
        assertThat(body.keySet()).containsAll(DIGEST_DATA_KEYS);
    }

    // ============ 2) 无 / 坏令牌 → 401 UNAUTHENTICATED，响应不含任何月报字段（需求 9.2）============

    @Test
    void missingOrBadToken_returnsUnauthenticated_withoutDigestFields() {
        // 形态 1：完全没有 Authorization 头。
        assertUnauthenticated(get(DIGEST_PATH, noAuth()), "缺失令牌");
        // 形态 2：结构完整但用别的密钥签名 → 验签失败。
        assertUnauthenticated(get(DIGEST_PATH, bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)))),
                "验签失败");
        // 形态 3：本系统密钥签名但已过期。
        assertUnauthenticated(get(DIGEST_PATH, bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)))),
                "已过期");
        // 形态 4：空 Bearer（Bearer 后无令牌，畸形）。
        assertUnauthenticated(get(DIGEST_PATH, blankBearer()), "空 Bearer");
    }

    // ============ 3) 越权 X-Ledger-Id → LEDGER_NOT_ACCESSIBLE（需求 9.3）============

    @Test
    void inaccessibleLedgerHeader_returnsLedgerNotAccessible_withoutDigestFields() {
        String token = registerAndLogin("report_digest_ledger@example.com");

        HttpHeaders headers = bearer(token);
        // 当前用户无权访问的账本 id（不存在 / 非其成员）。
        headers.set("X-Ledger-Id", "987654321");
        ResponseEntity<String> response = get(DIGEST_PATH, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = parse(response);
        assertThat(body).containsEntry("code", "LEDGER_NOT_ACCESSIBLE");
        assertNoDigestFields(body, "越权账本");
    }

    // ============ 4) 非法 month → REPORT_PARAM_INVALID（field=month）（需求 9.4）============

    @Test
    void invalidMonthParam_returnsReportParamInvalid_withoutDigestFields() {
        String token = registerAndLogin("report_digest_month@example.com");

        for (String badMonth : List.of("2024-13", "abc", "2024/01", "202401")) {
            ResponseEntity<String> response = get(DIGEST_PATH + "?month=" + badMonth, bearer(token));

            assertThat(response.getStatusCode()).as("month=" + badMonth).isEqualTo(HttpStatus.BAD_REQUEST);
            Map<String, Object> body = parse(response);
            assertThat(body).as("month=" + badMonth).containsEntry("code", "REPORT_PARAM_INVALID");
            assertThat(body).as("month=" + badMonth).containsEntry("field", "month");
            assertNoDigestFields(body, "非法 month=" + badMonth);
        }
    }

    // ============ 5) AI 趣味分析：缺省 month 取当前自然月（需求 1.2）============

    @Test
    void aiInsights_missingMonthParam_defaultsToCurrentMonth() {
        String token = registerAndLogin("report_ai_default@example.com");

        ResponseEntity<String> response = get(AI_INSIGHTS_PATH, bearer(token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = parse(response);
        // 缺省取 Asia/Shanghai 当前自然月（需求 1.2）。
        assertThat(body).containsEntry("month", YearMonth.now(ZONE).toString());
        // 当前自然月未完结 → 进行中（需求 1.3）。
        assertThat(body).containsEntry("monthStatus", "partial");
    }

    // ============ 6) AI 趣味分析：无 / 坏令牌 → 401 UNAUTHENTICATED，响应不含任何洞察字段（需求 10.2）============

    @Test
    void aiInsights_missingOrBadToken_returnsUnauthenticated_withoutInsightFields() {
        assertAiUnauthenticated(get(AI_INSIGHTS_PATH, noAuth()), "缺失令牌");
        assertAiUnauthenticated(get(AI_INSIGHTS_PATH, bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)))),
                "验签失败");
        assertAiUnauthenticated(get(AI_INSIGHTS_PATH, bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)))),
                "已过期");
        assertAiUnauthenticated(get(AI_INSIGHTS_PATH, blankBearer()), "空 Bearer");
    }

    // ============ 7) AI 趣味分析：越权 X-Ledger-Id → LEDGER_NOT_ACCESSIBLE（需求 10.3）============

    @Test
    void aiInsights_inaccessibleLedgerHeader_returnsLedgerNotAccessible_withoutInsightFields() {
        String token = registerAndLogin("report_ai_ledger@example.com");

        HttpHeaders headers = bearer(token);
        headers.set("X-Ledger-Id", "987654321");
        ResponseEntity<String> response = get(AI_INSIGHTS_PATH, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = parse(response);
        assertThat(body).containsEntry("code", "LEDGER_NOT_ACCESSIBLE");
        assertNoAiInsightFields(body, "越权账本");
    }

    // ============ 8) AI 趣味分析：非法 month → REPORT_PARAM_INVALID（field=month）（需求 10.4）============

    @Test
    void aiInsights_invalidMonthParam_returnsReportParamInvalid_withoutInsightFields() {
        String token = registerAndLogin("report_ai_month@example.com");

        for (String badMonth : List.of("2024-13", "abc", "2024/01", "202401")) {
            ResponseEntity<String> response = get(AI_INSIGHTS_PATH + "?month=" + badMonth, bearer(token));

            assertThat(response.getStatusCode()).as("month=" + badMonth).isEqualTo(HttpStatus.BAD_REQUEST);
            Map<String, Object> body = parse(response);
            assertThat(body).as("month=" + badMonth).containsEntry("code", "REPORT_PARAM_INVALID");
            assertThat(body).as("month=" + badMonth).containsEntry("field", "month");
            assertNoAiInsightFields(body, "非法 month=" + badMonth);
        }
    }

    // ============ 9) AI 趣味分析：缺 X-Ledger-Id → 取默认账本（需求 10.7）============

    @Test
    void aiInsights_noLedgerHeader_usesDefaultLedger() {
        String token = registerAndLogin("report_ai_default_ledger@example.com");

        // 有效令牌、不带 X-Ledger-Id 头 → 应回落到默认账本并成功返回（而非账本不可访问）。
        ResponseEntity<String> response = get(AI_INSIGHTS_PATH, bearer(token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = parse(response);
        assertThat(body).as("默认账本请求应返回洞察响应结构").containsKey("isFallback");
        assertThat(body).doesNotContainKey("code");
    }

    // ============ 10) AI 趣味分析：多类错误按「鉴权 → 账本 → 参数」优先级（需求 10.8）============

    @Test
    void aiInsights_multipleErrors_followPriorityAuthThenLedgerThenParam() {
        // 同时坏令牌 + 越权账本 + 非法参数 → 鉴权最高优先级 → 401 UNAUTHENTICATED。
        HttpHeaders badAll = bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)));
        badAll.set("X-Ledger-Id", "987654321");
        ResponseEntity<String> authWins = get(AI_INSIGHTS_PATH + "?month=2024-13", badAll);
        assertThat(authWins.getStatusCode()).as("鉴权优先").isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> authBody = parse(authWins);
        assertThat(authBody).containsEntry("code", "UNAUTHENTICATED");
        assertNoAiInsightFields(authBody, "鉴权+账本+参数同时错误");

        // 有效令牌 + 越权账本 + 非法参数 → 账本优先于参数 → LEDGER_NOT_ACCESSIBLE。
        String token = registerAndLogin("report_ai_priority@example.com");
        HttpHeaders badLedgerAndParam = bearer(token);
        badLedgerAndParam.set("X-Ledger-Id", "987654321");
        ResponseEntity<String> ledgerWins = get(AI_INSIGHTS_PATH + "?month=2024-13", badLedgerAndParam);
        assertThat(ledgerWins.getStatusCode()).as("账本优先于参数").isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> ledgerBody = parse(ledgerWins);
        assertThat(ledgerBody).containsEntry("code", "LEDGER_NOT_ACCESSIBLE");
        assertNoAiInsightFields(ledgerBody, "账本+参数同时错误");
    }

    // ============ 11) AI 趣味分析：返回类型为「≤N 条洞察」或「兜底文案」（需求 10.1）============

    @Test
    void aiInsights_returnsAtMostNInsightsOrFallback() {
        String token = registerAndLogin("report_ai_shape@example.com");

        ResponseEntity<String> response = get(AI_INSIGHTS_PATH, bearer(token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = parse(response);
        assertThat(body).containsKey("isFallback");
        Object fallback = body.get("isFallback");
        assertThat(fallback).isInstanceOf(Boolean.class);
        if (Boolean.TRUE.equals(fallback)) {
            // 兜底态：一条非空鼓励文案、insights 为空列表。
            assertThat(body.get("fallbackText")).isInstanceOf(String.class);
            assertThat((String) body.get("fallbackText")).isNotBlank();
            assertThat(body.get("insights")).isInstanceOf(List.class);
            assertThat((List<?>) body.get("insights")).isEmpty();
        } else {
            // 非兜底态：insights 为 1..N（默认 5）条。
            assertThat(body.get("insights")).isInstanceOf(List.class);
            assertThat((List<?>) body.get("insights")).hasSizeBetween(1, 5);
        }
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言 AI 趣味分析响应为 401、统一错误体 {@code code=UNAUTHENTICATED}，且不含任何洞察数据键（需求 10.2）。 */
    private void assertAiUnauthenticated(ResponseEntity<String> response, String shape) {
        assertThat(response.getStatusCode()).as(shape).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = parse(response);
        assertThat(body).as(shape).containsEntry("code", "UNAUTHENTICATED");
        assertNoAiInsightFields(body, shape);
    }

    /** 断言错误体不含任何 AI 趣味分析数据键（需求 10.2、10.3、10.4、10.8）。 */
    private void assertNoAiInsightFields(Map<String, Object> body, String shape) {
        for (String dataKey : AI_INSIGHT_DATA_KEYS) {
            assertThat(body).as(shape + " / 不含洞察数据键 " + dataKey).doesNotContainKey(dataKey);
        }
    }


    /** 断言响应为 401、统一错误体 {@code code=UNAUTHENTICATED}，且不含任何月报数据键（需求 9.2）。 */
    private void assertUnauthenticated(ResponseEntity<String> response, String shape) {
        assertThat(response.getStatusCode()).as(shape).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = parse(response);
        assertThat(body).as(shape).containsEntry("code", "UNAUTHENTICATED");
        assertNoDigestFields(body, shape);
    }

    /** 断言错误体不含任何月报数据键（需求 9.2、9.3、9.4）。 */
    private void assertNoDigestFields(Map<String, Object> body, String shape) {
        for (String dataKey : DIGEST_DATA_KEYS) {
            assertThat(body).as(shape + " / 不含月报数据键 " + dataKey).doesNotContainKey(dataKey);
        }
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private HttpHeaders noAuth() {
        return new HttpHeaders();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** 空 Bearer：{@code Authorization: Bearer } 后无令牌（畸形，必须 401）。 */
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

    /** 把响应体原始 JSON 解析为 Map；空体返回空 Map。 */
    private Map<String, Object> parse(ResponseEntity<String> response) {
        String raw = response.getBody();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON 对象: " + raw, e);
        }
    }

    // ---------------------------------- 数据准备辅助 ----------------------------------

    /** 邮箱验证码登录/注册合一，返回 JWT。 */
    private String registerAndLogin(String email) {
        verificationCodeRepository.deleteByEmail(email);

        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String code = latestCode(email, EmailCodePurpose.LOGIN);
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) login.getBody();
        assertThat(body).isNotNull();
        String token = (String) body.get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }
}
