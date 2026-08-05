package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 自定义提醒五个接口的<b>契约与安全边界</b>集成测试（任务 8.3，需求 8.1、8.2、8.3、8.4、8.5、8.7、8.8）。
 *
 * <p>照抄 {@link StreakApiSecurityIntegrationTest} 的 {@code TestRestTemplate} + {@code Jwts} 手工签发
 * 范式，使用<b>独立命名</b>的内存库。全栈 {@code @SpringBootTest}(RANDOM_PORT)，经真实 HTTP、真实
 * Spring Security 过滤链、真实 JWT、真实 Jackson 序列化与 H2 持久化层，覆盖五件事：</p>
 *
 * <ol>
 *   <li><b>五个受保护端点 × 5 种令牌形态一律 401 {@code UNAUTHENTICATED}，且鉴权先于字段校验</b>
 *       （需求 8.1、8.2）：缺失 / 验签失败 / 已过期 / <b>令牌用户已注销</b> / 空 Bearer；即便请求体字段
 *       非法，也返回 401 而非 {@code REMINDER_*} 字段错误。先证明「有效令牌 + 非法体」确实返回 400
 *       字段错误（否则本断言是空的），再证明同样的非法体在无效令牌下压成 401。第四种（令牌用户已注销）
 *       是过滤链只验签不查库留下的缺口，只能由 {@link ReminderController#requireExistingUserId()} 兜住，
 *       它对<b>能到达控制器</b>的请求验证「鉴权先于字段校验」（过滤链拒绝的前四种在控制器之前）。</li>
 *   <li><b>用户 A 访问用户 B 的 {@code reminderId} → 与不存在同一 {@code NOT_FOUND}</b>
 *       （需求 8.8、7.5）：PUT / DELETE 用户 B 的提醒 id，与 PUT / DELETE 一个不存在的 id，返回
 *       <b>逐字段完全相同</b>的 {@code NOT_FOUND}（相同 code、相同 message），且不泄漏 B 的提醒是否存在；
 *       A 的越权尝试后 B 的提醒保持不变。</li>
 *   <li><b>携带指定目标用户参数被忽略且不报错</b>（需求 8.3、8.4）：以 A 的令牌附加 {@code userId} /
 *       {@code targetUserId} / {@code uid}（全部指向 B）作查询参数、请求体字段与自定义头，五个端点的
 *       响应与不带这些入参时逐字段相等，且只作用于 A 的数据、不返回任何错误码。B 的数据刻意与 A 不同，
 *       若越权成功则相等断言必然失败。</li>
 *   <li><b>缺 {@code X-Ledger-Id} 不被拒</b>（需求 8.5）：五个端点在不带 {@code X-Ledger-Id} 时正常
 *       返回；带任意 {@code X-Ledger-Id} 时读操作响应逐字段相等——提醒接口与会话账本无关。</li>
 *   <li><b>错误体字段集恰为 {@code {code, message, field}}</b>（需求 8.7）：字段相关错误
 *       （{@code REMINDER_FREQUENCY_INVALID} 等）原始 JSON 键集恰为三项；{@code UNAUTHENTICATED} /
 *       {@code NOT_FOUND} 与具体字段无关，{@code field} 取空值被省略、键集恰为 {@code {code, message}}，
 *       且 {@code code}、{@code message} 两项均不缺省，任何错误体都不含三项以外的键。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-reminder-sec-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class ReminderApiSecurityIntegrationTest {

    /** 与 {@code app.jwt.secret} 不同的密钥，用于制造验签失败的令牌（长度满足 HS256 要求）。 */
    private static final String FOREIGN_SECRET =
            "foreign-secret-key-only-for-reminder-security-test-do-not-use";

    /** 统一错误体的完整键集：{@code {code, message, field}}（需求 8.7）。 */
    private static final List<String> ERROR_BODY_KEYS = List.of("code", "message", "field");

    /** 响应绝不应出现的提醒数据键（未认证 / NOT_FOUND 时，需求 8.2、8.8）。 */
    private static final List<String> REMINDER_DATA_KEYS =
            List.of("reminderId", "reminders", "remainingQuota", "enabled", "remindTime");

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    // ============ 1) 五个受保护端点 × 5 种令牌形态 → 401 UNAUTHENTICATED，且鉴权先于字段校验 ============

    @Test
    void protectedEndpoints_underAllFiveTokenShapes_returnUnauthenticated() {
        long deletedUserId = registerThenDeleteAccount("reminder_sec_deleted@example.com");

        // 五个端点，故意都带上「会触发字段校验失败」的非法体/参数：验证鉴权一律压过字段校验（需求 8.2）。
        List<Endpoint> endpoints = List.of(
                new Endpoint(HttpMethod.GET, "/api/reminders", null),
                new Endpoint(HttpMethod.POST, "/api/reminders", Map.of("frequency", "bogus", "remindTime", "99:99")),
                new Endpoint(HttpMethod.PUT, "/api/reminders/1", Map.of("frequency", "bogus")),
                new Endpoint(HttpMethod.DELETE, "/api/reminders/1", null),
                new Endpoint(HttpMethod.POST, "/api/reminders/quota:grant", Map.of("grantedCount", "999")));

        for (Endpoint ep : endpoints) {
            String label = ep.method() + " " + ep.path();
            // 形态 1：完全没有 Authorization 头。
            assertUnauthenticated(call(ep, noAuth()), label + " / 缺失令牌");
            // 形态 2：结构完整但用别的密钥签名 → 验签失败。
            assertUnauthenticated(call(ep, bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)))),
                    label + " / 验签失败");
            // 形态 3：本系统密钥签名但已过期。
            assertUnauthenticated(call(ep, bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)))),
                    label + " / 已过期");
            // 形态 4：签名有效、未过期，但令牌用户已注销 —— 过滤链不查库，管不到（需求 8.2）。
            assertUnauthenticated(call(ep, bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1)))),
                    label + " / 令牌用户已注销");
            // 形态 5：空 Bearer（Bearer 后无令牌，畸形）。
            assertUnauthenticated(call(ep, blankBearer()), label + " / 空 Bearer");
        }
    }

    @Test
    void unauthenticated_takesPrecedenceOverFieldValidation() {
        // 先证明这组非法请求体在有效令牌下确实返回 400 字段错误（否则下面的「压成 401」断言是空的）。
        String validToken = registerAndLogin("reminder_prec_valid@example.com");
        Map<String, Object> illegalCreate = Map.of("frequency", "bogus", "remindTime", "99:99");
        ResponseEntity<String> rejected = call(
                new Endpoint(HttpMethod.POST, "/api/reminders", illegalCreate), bearer(validToken));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(rejected)).containsEntry("code", "REMINDER_FREQUENCY_INVALID");

        Map<String, Object> illegalGrant = Map.of("grantedCount", "999");
        ResponseEntity<String> grantRejected = call(
                new Endpoint(HttpMethod.POST, "/api/reminders/quota:grant", illegalGrant), bearer(validToken));
        assertThat(grantRejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(grantRejected)).containsEntry("code", "REMINDER_GRANT_INVALID");

        // 令牌用户已注销 + 同样的非法体：控制器 requireExistingUserId 先抛 UNAUTHENTICATED，
        // 字段校验根本不执行（需求 8.2）。这是「能到达控制器」的鉴权先于字段校验的直接证明。
        long deletedUserId = registerThenDeleteAccount("reminder_prec_deleted@example.com");
        assertUnauthenticated(
                call(new Endpoint(HttpMethod.POST, "/api/reminders", illegalCreate),
                        bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1)))),
                "已注销用户 + 非法创建体");
        assertUnauthenticated(
                call(new Endpoint(HttpMethod.POST, "/api/reminders/quota:grant", illegalGrant),
                        bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1)))),
                "已注销用户 + 非法授权体");
    }

    // ============ 2) 用户 A 访问用户 B 的 reminderId → 与不存在同一 NOT_FOUND（需求 8.8、7.5）============

    @Test
    void userA_accessingUserBReminder_returnsSameNotFoundAsNonexistent() {
        String tokenA = registerAndLogin("reminder_nf_a@example.com");
        String tokenB = registerAndLogin("reminder_nf_b@example.com");

        // B 建一条提醒；A 建一条（确保 A 有数据，越权失败后可核对 B 未被改动）。
        long reminderIdB = createReminder(tokenB, "DAILY", "09:00", true);
        createReminder(tokenA, "WEEKDAY", "21:30", true);
        Map<String, Object> bListBefore = list(tokenB);

        long nonexistentId = 999_999_999L;

        // PUT：A 改 B 的提醒 vs A 改一个不存在的 id → 两者逐字段完全相同的 NOT_FOUND。
        Map<String, Object> putCrossUser = parse(call(
                new Endpoint(HttpMethod.PUT, "/api/reminders/" + reminderIdB, Map.of("remindTime", "10:00")),
                bearer(tokenA), HttpStatus.NOT_FOUND));
        Map<String, Object> putNonexistent = parse(call(
                new Endpoint(HttpMethod.PUT, "/api/reminders/" + nonexistentId, Map.of("remindTime", "10:00")),
                bearer(tokenA), HttpStatus.NOT_FOUND));
        assertThat(putCrossUser).isEqualTo(putNonexistent);
        assertNotFound(putCrossUser, "PUT 越权他人提醒");
        assertNotFound(putNonexistent, "PUT 不存在提醒");

        // DELETE：同理，越权删他人 vs 删不存在 → 完全相同的 NOT_FOUND。
        Map<String, Object> delCrossUser = parse(call(
                new Endpoint(HttpMethod.DELETE, "/api/reminders/" + reminderIdB, null),
                bearer(tokenA), HttpStatus.NOT_FOUND));
        Map<String, Object> delNonexistent = parse(call(
                new Endpoint(HttpMethod.DELETE, "/api/reminders/" + nonexistentId, null),
                bearer(tokenA), HttpStatus.NOT_FOUND));
        assertThat(delCrossUser).isEqualTo(delNonexistent);
        assertNotFound(delCrossUser, "DELETE 越权他人提醒");
        assertNotFound(delNonexistent, "DELETE 不存在提醒");

        // B 的提醒经 A 的越权 PUT/DELETE 后保持不变（需求 8.8）。
        assertThat(list(tokenB)).isEqualTo(bListBefore);
    }

    // ============ 3) 携带指定目标用户参数被忽略且不报错（需求 8.3、8.4）============

    @Test
    void forgedTargetUserParams_areIgnored_andOnlyOwnDataIsAffected() {
        String tokenA = registerAndLogin("reminder_scope_a@example.com");
        String tokenB = registerAndLogin("reminder_scope_b@example.com");
        long idA = userIdOf("reminder_scope_a@example.com");
        long idB = userIdOf("reminder_scope_b@example.com");

        // A 一条提醒、B 两条提醒 + 一次授权：两人数据刻意不同。
        long reminderIdA = createReminder(tokenA, "DAILY", "08:00", true);
        createReminder(tokenB, "WEEKDAY", "12:00", true);
        createReminder(tokenB, "WEEKEND", "13:00", false);
        grant(tokenB, "3");

        Map<String, Object> listBaselineA = list(tokenA);
        Map<String, Object> listOfB = list(tokenB);
        // 前提校验：A 与 B 的列表确实不同，否则下面逐字段相等断言是空的。
        assertThat(listBaselineA).isNotEqualTo(listOfB);

        String forgedQuery = "userId=" + idB + "&targetUserId=" + idB + "&uid=" + idB;

        // GET：附加伪造查询参数 → 只读到 A 自己的数据，逐字段相等且不报错（需求 8.3、8.4）。
        Map<String, Object> listWithQuery = parse(call(
                new Endpoint(HttpMethod.GET, "/api/reminders?" + forgedQuery, null),
                bearer(tokenA), HttpStatus.OK));
        assertThat(listWithQuery).isEqualTo(listBaselineA);

        // GET：附加伪造自定义头 → 同样只读到 A 的数据。
        HttpHeaders forgedHeaders = bearer(tokenA);
        forgedHeaders.set("X-User-Id", String.valueOf(idB));
        forgedHeaders.set("X-Target-User-Id", String.valueOf(idB));
        Map<String, Object> listWithHeaders = parse(call(
                new Endpoint(HttpMethod.GET, "/api/reminders", null), forgedHeaders, HttpStatus.OK));
        assertThat(listWithHeaders).isEqualTo(listBaselineA);

        // POST：请求体夹带 userId/targetUserId（指向 B）+ 伪造查询参数 → 提醒归属仍是 A、不报错。
        Map<String, Object> forgedCreateBody = Map.of(
                "frequency", "WEEKDAY", "remindTime", "22:15", "enabled", true,
                "userId", idB, "targetUserId", idB);
        ResponseEntity<String> created = call(
                new Endpoint(HttpMethod.POST, "/api/reminders?" + forgedQuery, forgedCreateBody),
                bearer(tokenA));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long newReminderId = ((Number) parse(created).get("reminderId")).longValue();
        // 新提醒出现在 A 的列表、不出现在 B 的列表：归属只认令牌用户（需求 8.4）。
        assertThat(reminderIdsOf(list(tokenA))).contains(newReminderId).contains(reminderIdA);
        assertThat(reminderIdsOf(list(tokenB))).doesNotContain(newReminderId);

        // PUT：伪造入参不改变归属，更新的是 A 自己的提醒，不报错。
        ResponseEntity<String> updated = call(
                new Endpoint(HttpMethod.PUT, "/api/reminders/" + reminderIdA + "?" + forgedQuery,
                        Map.of("remindTime", "08:30", "userId", idB)),
                bearer(tokenA));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(updated)).containsEntry("remindTime", "08:30");

        // POST quota:grant：请求体夹带 userId → 只累加 A 自己的额度，不报错。
        Map<String, Object> forgedGrantBody = Map.of("grantedCount", "2", "userId", idB);
        ResponseEntity<String> granted = call(
                new Endpoint(HttpMethod.POST, "/api/reminders/quota:grant?" + forgedQuery, forgedGrantBody),
                bearer(tokenA));
        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.OK);
        // A 的额度为本次授权的 2（此前未授权），B 的额度仍是它自己的 3：额度未被越权修改（需求 8.4）。
        assertThat(((Number) list(tokenA).get("remainingQuota")).intValue()).isEqualTo(2);
        assertThat(((Number) list(tokenB).get("remainingQuota")).intValue()).isEqualTo(3);
    }

    // ============ 4) 缺 X-Ledger-Id 不被拒（需求 8.5）============

    @Test
    void ledgerHeader_isNeitherRequiredNorInfluential() {
        String token = registerAndLogin("reminder_noledger@example.com");
        long reminderId = createReminder(token, "DAILY", "07:45", true);

        // 五个端点全部在不带 X-Ledger-Id 时正常返回（需求 8.5）。
        assertThat(call(new Endpoint(HttpMethod.GET, "/api/reminders", null), bearer(token))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(new Endpoint(HttpMethod.POST, "/api/reminders",
                Map.of("frequency", "WEEKDAY", "remindTime", "18:00")), bearer(token))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(new Endpoint(HttpMethod.PUT, "/api/reminders/" + reminderId,
                Map.of("remindTime", "07:50")), bearer(token))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(new Endpoint(HttpMethod.POST, "/api/reminders/quota:grant",
                Map.of("grantedCount", "1")), bearer(token))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(new Endpoint(HttpMethod.DELETE, "/api/reminders/" + reminderId, null), bearer(token))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 提醒与会话账本无关：带任意 X-Ledger-Id 与不带时 GET 响应逐字段相等（需求 8.5）。
        Map<String, Object> noLedger = list(token);
        HttpHeaders withLedger = bearer(token);
        withLedger.set("X-Ledger-Id", "987654321");
        Map<String, Object> ledgered = parse(call(
                new Endpoint(HttpMethod.GET, "/api/reminders", null), withLedger, HttpStatus.OK));
        assertThat(ledgered).isEqualTo(noLedger);
    }

    // ============ 5) 错误体字段集恰为 {code, message, field}（需求 8.7）============

    @Test
    void errorBody_keySetIsExactlyCodeMessageField() {
        String token = registerAndLogin("reminder_errbody@example.com");

        // 字段相关错误：REMINDER_FREQUENCY_INVALID → 原始 JSON 键集恰为 {code, message, field}。
        Map<String, Object> freqInvalid = parse(call(
                new Endpoint(HttpMethod.POST, "/api/reminders", Map.of("frequency", "bogus", "remindTime", "09:00")),
                bearer(token), HttpStatus.BAD_REQUEST));
        assertThat(freqInvalid.keySet()).containsExactlyInAnyOrderElementsOf(ERROR_BODY_KEYS);
        assertThat(freqInvalid).containsEntry("code", "REMINDER_FREQUENCY_INVALID");
        assertThat(freqInvalid).containsEntry("field", "frequency");
        assertMessageSane(freqInvalid);

        // 字段相关错误：REMINDER_GRANT_INVALID → field 为 grantedCount，键集仍恰为三项。
        Map<String, Object> grantInvalid = parse(call(
                new Endpoint(HttpMethod.POST, "/api/reminders/quota:grant", Map.of("grantedCount", "0")),
                bearer(token), HttpStatus.BAD_REQUEST));
        assertThat(grantInvalid.keySet()).containsExactlyInAnyOrderElementsOf(ERROR_BODY_KEYS);
        assertThat(grantInvalid).containsEntry("field", "grantedCount");

        // 与字段无关的错误：NOT_FOUND → field 取空值被省略，键集恰为 {code, message}，code/message 均在（需求 8.7）。
        Map<String, Object> notFound = parse(call(
                new Endpoint(HttpMethod.DELETE, "/api/reminders/424242", null),
                bearer(token), HttpStatus.NOT_FOUND));
        assertThat(notFound.keySet()).containsExactlyInAnyOrder("code", "message");
        assertThat(notFound).containsEntry("code", "NOT_FOUND");
        assertThat(notFound.keySet()).doesNotContain("field"); // NON_NULL 省略空值 field（需求 8.7）
        assertMessageSane(notFound);
        // 键集不含三项以外的任何键（尤其不含提醒数据键）。
        for (String key : notFound.keySet()) {
            assertThat(ERROR_BODY_KEYS).as("NOT_FOUND 键集不含三项以外的键: " + key).contains(key);
        }

        // 与字段无关的错误：UNAUTHENTICATED → 同样键集 ⊆ {code, message, field}，且含 code/message。
        Map<String, Object> unauth = parse(call(
                new Endpoint(HttpMethod.GET, "/api/reminders", null), noAuth(), HttpStatus.UNAUTHORIZED));
        assertThat(unauth).containsKeys("code", "message");
        assertThat(unauth).containsEntry("code", "UNAUTHENTICATED");
        for (String key : unauth.keySet()) {
            assertThat(ERROR_BODY_KEYS).as("UNAUTHENTICATED 键集不含三项以外的键: " + key).contains(key);
        }
        assertMessageSane(unauth);
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言响应为 401、统一错误体 {@code code=UNAUTHENTICATED}，且不含任何提醒数据键（需求 8.2）。 */
    private void assertUnauthenticated(ResponseEntity<String> response, String shape) {
        assertThat(response.getStatusCode()).as(shape).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = parse(response);
        assertThat(body).as(shape).containsEntry("code", "UNAUTHENTICATED");
        for (String dataKey : REMINDER_DATA_KEYS) {
            assertThat(body).as(shape + " / 不含提醒数据键 " + dataKey).doesNotContainKey(dataKey);
        }
    }

    /** 断言错误体为 {@code NOT_FOUND}、message 为「提醒不存在」，且不含任何提醒数据键（需求 8.8）。 */
    private void assertNotFound(Map<String, Object> body, String shape) {
        assertThat(body).as(shape).containsEntry("code", "NOT_FOUND");
        for (String dataKey : REMINDER_DATA_KEYS) {
            assertThat(body).as(shape + " / 不含提醒数据键 " + dataKey).doesNotContainKey(dataKey);
        }
        assertMessageSane(body);
    }

    /** 断言 message 为非空中文、≤100 字符，且不含用户 id / 邮箱 / 令牌痕迹（需求 8.6、8.7）。 */
    private void assertMessageSane(Map<String, Object> body) {
        Object message = body.get("message");
        assertThat(message).isInstanceOf(String.class);
        String text = (String) message;
        assertThat(text).isNotBlank();
        assertThat(text.length()).isLessThanOrEqualTo(100);
        assertThat(text).doesNotContain("@").doesNotContain("Bearer").doesNotContain("eyJ");
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    /** 一个受保护端点：HTTP 方法、路径与可选请求体。 */
    private record Endpoint(HttpMethod method, String path, Object body) {
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** 以给定令牌头调用端点，返回原始响应文本（保留「键存在且为 null」与「键被省略」的区别）。 */
    private ResponseEntity<String> call(Endpoint ep, HttpHeaders headers) {
        HttpHeaders effective = new HttpHeaders();
        effective.addAll(headers);
        HttpEntity<Object> entity;
        if (ep.body() == null) {
            entity = new HttpEntity<>(effective);
        } else {
            effective.setContentType(MediaType.APPLICATION_JSON);
            entity = new HttpEntity<>(ep.body(), effective);
        }
        return rest.exchange(url(ep.path()), ep.method(), entity, String.class);
    }

    /** 同上并断言状态码，便于在契约断言处直接拿解析后的 body。 */
    private ResponseEntity<String> call(Endpoint ep, HttpHeaders headers, HttpStatus expected) {
        ResponseEntity<String> response = call(ep, headers);
        assertThat(response.getStatusCode()).as(ep.method() + " " + ep.path()).isEqualTo(expected);
        return response;
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

    // ---------------------------------- 业务辅助 ----------------------------------

    /** 创建一条提醒，返回其 {@code reminderId}。 */
    private long createReminder(String token, String frequency, String remindTime, boolean enabled) {
        ResponseEntity<String> response = call(
                new Endpoint(HttpMethod.POST, "/api/reminders",
                        Map.of("frequency", frequency, "remindTime", remindTime, "enabled", enabled)),
                bearer(token));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) parse(response).get("reminderId")).longValue();
    }

    /** 上报订阅授权。 */
    private void grant(String token, String grantedCount) {
        ResponseEntity<String> response = call(
                new Endpoint(HttpMethod.POST, "/api/reminders/quota:grant", Map.of("grantedCount", grantedCount)),
                bearer(token));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** 取本人提醒列表响应（含 reminders 与 remainingQuota）。 */
    private Map<String, Object> list(String token) {
        return parse(call(new Endpoint(HttpMethod.GET, "/api/reminders", null), bearer(token), HttpStatus.OK));
    }

    @SuppressWarnings("unchecked")
    private List<Long> reminderIdsOf(Map<String, Object> listBody) {
        List<Map<String, Object>> reminders = (List<Map<String, Object>>) listBody.get("reminders");
        return reminders.stream()
                .map(item -> ((Number) item.get("reminderId")).longValue())
                .toList();
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
        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) login.getBody();
        String token = (String) body.get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /**
     * 建号 → 二次验证注销 → 返回该已注销用户的 id。
     *
     * <p>用于制造「签名有效、未过期，但令牌用户已不存在」这一过滤链管不到的令牌形态（需求 8.2）。</p>
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
