package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.damien.youyu.domain.InviteRelation;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 邀请接口的鉴权与越权集成测试（任务 6.4，需求 8.1、8.2、8.3、8.4）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)，经真实 HTTP、真实 Spring Security 过滤链、真实 JWT
 * 与 H2 持久化层，覆盖三件事：</p>
 *
 * <ol>
 *   <li><b>三个受保护端点 × 5 种令牌形态一律 401 {@code UNAUTHENTICATED}</b>（需求 8.1、8.2）：
 *       缺失 / 验签失败 / 已过期 / <b>令牌用户已注销</b> / 空 Bearer。第四种是这个测试存在的主要理由：
 *       {@link com.damien.youyu.security.JwtAuthenticationFilter} 只验签与验有效期、<b>不查库</b>，
 *       所以「令牌合法但用户已注销」过滤链管不到，只能由 {@link InviteController} 每个受保护端点
 *       第一步的 {@code findById(...).orElseThrow(unauthenticated)} 兜住。删掉那一步校验，本类
 *       「令牌用户已注销」的几个断言就会变成 200。</li>
 *   <li><b>{@code UNAUTHENTICATED} 优先于非法分页参数</b>（需求 8.2）：同一组非法 {@code page} /
 *       {@code size} 在有效令牌下确实返回 400 {@code INVITE_PAGE_PARAM_INVALID}（否则本断言是空的），
 *       但在上述 5 种令牌形态下一律 401。</li>
 *   <li><b>伪造入参无法越权</b>（需求 8.3）：以 A 的令牌附加 {@code userId} / {@code inviterId} /
 *       {@code targetUserId} / {@code code} / {@code inviteCode}（取值全部指向 B）请求邀请信息与
 *       被邀请人列表，响应与不带这些入参时<b>逐字段相等</b>，且只含 A 的数据。</li>
 *   <li><b>公开端点带坏令牌仍 200</b>（需求 8.4）：{@code GET /api/invite/inviter} 携带验签失败 /
 *       已过期 / 用户已注销 / 空 Bearer 的令牌一律忽略该令牌按匿名请求处理，返回 200 且不含
 *       {@code UNAUTHENTICATED}。</li>
 * </ol>
 *
 * <p><b>刻意不测 {@code /api/invite/qrcode} 的成功路径</b>：测试 profile 下微信 appid/secret 为空、
 * {@code api-base-url} 指向不可路由的 {@code https://wechat.invalid}，该端点在有效令牌下必然以
 * {@code INVITE_QRCODE_FAILED} 结束。这对鉴权断言无妨（{@code UNAUTHENTICATED} 必须在抵达服务层
 * 之前就返回，正是本类要证明的），故数据归属断言只用邀请信息与被邀请人列表两个端点。</p>
 *
 * <p>邀请关系直接经 {@link InviteRelationRepository} 落库：本类验的是接口的鉴权与数据范围，
 * 不重复覆盖「注册时绑定」那条链路（另见登录绑定集成测试）。使用独立命名的内存库，避免污染其它
 * 共享内存库的切片测试。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-invitesec-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class InviteApiSecurityIntegrationTest {

    /** 三个受保护端点（需求 8.1）。 */
    private static final List<String> PROTECTED_PATHS =
            List.of("/api/invite", "/api/invite/qrcode", "/api/invite/invitees");

    /** 与 {@code app.jwt.secret} 不同的密钥，用于制造验签失败的令牌（长度满足 HS256 要求）。 */
    private static final String FOREIGN_SECRET =
            "foreign-secret-key-only-for-invite-security-test-do-not-use";

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
    private InviteRelationRepository inviteRelationRepository;

    // ============ 1) 三个受保护端点 × 5 种令牌形态 → 401 UNAUTHENTICATED（需求 8.1、8.2）============

    @Test
    void protectedEndpoints_underAllFiveTokenShapes_returnUnauthenticated() {
        long deletedUserId = registerThenDeleteAccount("invite_sec_deleted@example.com");

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
        String invalidPaging = "/api/invite/invitees?page=-1&size=999";
        long deletedUserId = registerThenDeleteAccount("invite_sec_deleted_paging@example.com");

        // 先证明这组分页参数确实非法：有效令牌下返回 400 INVITE_PAGE_PARAM_INVALID。
        String validToken = registerAndLogin("invite_sec_paging@example.com");
        ResponseEntity<Map> rejected = get(invalidPaging, bearer(validToken));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(rejected)).containsEntry("code", "INVITE_PAGE_PARAM_INVALID");

        // 5 种令牌形态下，鉴权错误一律压过分页参数错误（需求 8.2）。
        assertUnauthenticated(get(invalidPaging, noAuth()), "非法分页 / 缺失令牌");
        assertUnauthenticated(get(invalidPaging, bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)))),
                "非法分页 / 验签失败");
        assertUnauthenticated(get(invalidPaging, bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)))),
                "非法分页 / 已过期");
        assertUnauthenticated(get(invalidPaging, bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1)))),
                "非法分页 / 令牌用户已注销");
        assertUnauthenticated(get(invalidPaging, blankBearer()), "非法分页 / 空 Bearer");
    }

    // ==================== 2) 伪造入参无法越权读取他人数据（需求 8.3）====================

    @Test
    void forgedTargetUserParams_areIgnored_andOnlyOwnDataIsReadable() {
        // A：1 条邀请关系（被邀请人 C）；B：2 条（被邀请人 D、E）。两人数据刻意不同，避免断言空转。
        String tokenA = registerAndLogin("invite_scope_a@example.com");
        String tokenB = registerAndLogin("invite_scope_b@example.com");
        long idA = userIdOf("invite_scope_a@example.com");
        long idB = userIdOf("invite_scope_b@example.com");
        long idC = registerAndGetId("invite_scope_c@example.com");
        long idD = registerAndGetId("invite_scope_d@example.com");
        long idE = registerAndGetId("invite_scope_e@example.com");
        saveRelation(idA, idC);
        saveRelation(idB, idD);
        saveRelation(idB, idE);

        // 基线：A 的令牌，不带任何多余入参。
        Map<String, Object> infoBaseline = body(get("/api/invite", bearer(tokenA)));
        Map<String, Object> listBaseline = body(get("/api/invite/invitees", bearer(tokenA)));

        assertThat(infoBaseline).containsEntry("invitedCount", 1);
        assertThat(listBaseline).containsEntry("total", 1).containsEntry("invitedCount", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> baselineItems = (List<Map<String, Object>>) listBaseline.get("items");
        assertThat(baselineItems).hasSize(1);
        assertThat(baselineItems.get(0)).containsEntry("nickname", "invite_scope_c");

        // B 的真实数据与 A 不同：若越权成功，下面的逐字段相等断言必然失败。
        Map<String, Object> infoOfB = body(get("/api/invite", bearer(tokenB)));
        assertThat(infoOfB).containsEntry("invitedCount", 2);
        assertThat(infoOfB.get("inviteCode")).isNotEqualTo(infoBaseline.get("inviteCode"));

        String inviteCodeOfB = (String) infoOfB.get("inviteCode");
        String forged = "userId=" + idB
                + "&inviterId=" + idB
                + "&targetUserId=" + idB
                + "&code=" + inviteCodeOfB
                + "&inviteCode=" + inviteCodeOfB;

        // 带上全套伪造入参：响应与基线逐字段相等（Map 相等即整棵 JSON 结构相等）。
        assertThat(body(get("/api/invite?" + forged, bearer(tokenA)))).isEqualTo(infoBaseline);
        assertThat(body(get("/api/invite/invitees?" + forged, bearer(tokenA)))).isEqualTo(listBaseline);
        // 分页参数与伪造入参共存时同样只读到 A 的数据。
        assertThat(body(get("/api/invite/invitees?page=0&size=20&" + forged, bearer(tokenA))))
                .isEqualTo(listBaseline);

        // 反向确认：B 的令牌读到的仍是 B 自己的两条，且不含 A 的被邀请人。
        Map<String, Object> listOfB = body(get("/api/invite/invitees?" + "userId=" + idA
                + "&inviterId=" + idA + "&targetUserId=" + idA, bearer(tokenB)));
        assertThat(listOfB).containsEntry("total", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemsOfB = (List<Map<String, Object>>) listOfB.get("items");
        assertThat(itemsOfB).extracting(item -> item.get("nickname"))
                .containsExactlyInAnyOrder("invite_scope_d", "invite_scope_e");
    }

    // ============== 3) 公开端点携带无效/过期令牌仍 200（需求 8.4）==============

    @Test
    void publicInviterEndpoint_withInvalidOrExpiredToken_returns200() {
        String email = "invite_public_owner@example.com";
        registerAndLogin(email);
        String inviteCodeOfOwner = inviteCodeOf(email);
        String path = "/api/invite/inviter?code=" + inviteCodeOfOwner;
        long deletedUserId = registerThenDeleteAccount("invite_public_deleted@example.com");

        assertPublicOk(get(path, noAuth()), "无令牌");
        assertPublicOk(get(path, bearer(token(1L, FOREIGN_SECRET, Duration.ofHours(1)))), "验签失败");
        assertPublicOk(get(path, bearer(token(1L, jwtSecret, Duration.ofSeconds(-10)))), "已过期");
        assertPublicOk(get(path, bearer(token(deletedUserId, jwtSecret, Duration.ofHours(1)))),
                "令牌用户已注销");
        assertPublicOk(get(path, blankBearer()), "空 Bearer");
    }

    /** 公开端点断言：200、不是 401、响应体不含 UNAUTHENTICATED，且返回的正是邀请人昵称。 */
    private void assertPublicOk(ResponseEntity<Map> response, String shape) {
        assertThat(response.getStatusCode()).as("公开端点 / " + shape).isEqualTo(HttpStatus.OK);
        assertThat(body(response)).as("公开端点 / " + shape)
                .doesNotContainEntry("code", "UNAUTHENTICATED")
                .containsEntry("nickname", "invite_public_owner");
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

    /** 邮箱验证码登录/注册合一，返回 JWT。 */
    private String registerAndLogin(String email) {
        ResponseEntity<Map> login = emailLoginWithFreshCode(email);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 建号后返回其用户 id（用作被邀请人）。 */
    private long registerAndGetId(String email) {
        registerAndLogin(email);
        return userIdOf(email);
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

    private String inviteCodeOf(String email) {
        String inviteCode = userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getInviteCode();
        assertThat(inviteCode).as("建号时应写入邀请码").isNotBlank();
        return inviteCode;
    }

    /** 直接落一条 REGISTERED 邀请关系（本类不重复覆盖注册时绑定那条链路）。 */
    private void saveRelation(long inviterId, long inviteeId) {
        LocalDateTime now = LocalDateTime.now();
        InviteRelation relation = new InviteRelation();
        relation.setInviterId(inviterId);
        relation.setInviteeId(inviteeId);
        relation.setRegisterTime(now);
        relation.setStatus(InviteStatus.REGISTERED);
        relation.setCreatedAt(now);
        relation.setUpdatedAt(now);
        inviteRelationRepository.save(relation);
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
