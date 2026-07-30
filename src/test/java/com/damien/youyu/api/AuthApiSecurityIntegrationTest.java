package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.damien.youyu.repository.VerificationCodeRepository;

/**
 * API / 安全边界集成测试（任务 9.3，需求 9.2、9.3、9.4、2、8）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)，经真实 HTTP、真实 Spring Security 过滤链、JWT 与
 * H2 持久化层，验证鉴权端点的公开/受保护边界与两条关键身份语义：</p>
 *
 * <ol>
 *   <li><b>公开端点</b>：{@code /api/auth/send-code}、{@code /api/auth/email-login}、
 *       {@code /api/auth/wx-login} 无需令牌即可抵达（不被安全层以 401 UNAUTHENTICATED 拦截）；
 *       send-code 成功返回 204（需求 9.2）。</li>
 *   <li><b>受保护端点</b>：{@code /api/me} 及 {@code /api/me/bind-email|bind-wechat|unbind|delete}
 *       无令牌一律 401 且错误码 {@code UNAUTHENTICATED}；持有效令牌 GET /me 返回新摘要
 *       （nickname/email/hasEmail/hasWechat，需求 9.3）。</li>
 *   <li><b>登录/注册合一</b>：新邮箱 email-login 自动建号并返回 {token, user}（hasEmail=true）；
 *       同邮箱再次登录命中同一账号（同 id），不重复建号（需求 2）。</li>
 *   <li><b>注销后身份可复用</b>：注册 → 二次验证注销（DELETE 验证码）→ 同邮箱再次登录建立
 *       全新账号（id 不同），确认 email 已释放（需求 8.4）。</li>
 *   <li><b>废弃端点已移除</b>：{@code /api/auth/register}、{@code /api/auth/login} 不再是成功端点
 *       （非 2xx，需求 9.4）。</li>
 * </ol>
 *
 * <p>测试期未配置真实 SMTP，走日志降级发送器，验证码落库；测试从 {@link VerificationCodeRepository}
 * 读回验证码，沿用现有集成测试（EndToEndMainPathIntegrationTest 等）的既定套路。使用独立命名的内存库，
 * 避免污染其它共享内存库的切片测试。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-authsec-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试聚焦 API/安全边界与身份语义，并非发码防刷（冷却/IP 限流在 9.1/9.2 单独覆盖）。
        // 全部请求同源自 127.0.0.1，故放宽 IP 分钟/日限额，避免同源多次发码触发 CODE_RATE_LIMITED。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class AuthApiSecurityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ============================ 1) 公开端点：无令牌可达 ============================

    @Test
    void authEndpoints_arePublic_reachableWithoutToken() {
        String email = "public_reach@example.com";

        // send-code（无令牌）→ 204，未被安全层拦截。
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // email-login（无令牌）→ 携有效验证码可换取 JWT（200），未被安全层拦截。
        String code = latestCode(email, EmailCodePurpose.LOGIN);
        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("token")).isNotNull();

        // wx-login（无令牌）→ 抵达控制器/服务并给出业务校验错误（缺 code：WX_CODE_REQUIRED / 400），
        // 关键在于：不是安全层的 401 UNAUTHENTICATED，证明该端点为公开可达。
        ResponseEntity<Map> wx = rest.postForEntity(url("/api/auth/wx-login"),
                Map.of("code", ""), Map.class);
        assertThat(wx.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        @SuppressWarnings("unchecked")
        Map<String, Object> wxBody = wx.getBody();
        assertThat(wxBody).doesNotContainEntry("code", "UNAUTHENTICATED");
        assertThat(wxBody).containsEntry("code", "WX_CODE_REQUIRED");
    }

    // ======================= 2) 受保护端点：无令牌 401 UNAUTHENTICATED =======================

    @Test
    void meEndpoints_withoutToken_return401Unauthenticated() {
        assertUnauthenticated(rest.getForEntity(url("/api/me"), Map.class));

        assertUnauthenticated(rest.postForEntity(url("/api/me/bind-email"),
                Map.of("email", "x@example.com", "code", "000000"), Map.class));
        assertUnauthenticated(rest.postForEntity(url("/api/me/bind-wechat"),
                Map.of("code", "wxcode"), Map.class));
        assertUnauthenticated(rest.postForEntity(url("/api/me/unbind"),
                Map.of("type", "email"), Map.class));
        assertUnauthenticated(rest.postForEntity(url("/api/me/delete"),
                Map.of("code", "000000"), Map.class));
    }

    @Test
    void me_withValidToken_returnsSummaryShape() {
        String token = registerAndLogin("summary_user@example.com");

        ResponseEntity<Map> me = rest.exchange(url("/api/me"), HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = me.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("nickname", "summary_user");
        assertThat(body).containsEntry("email", "summary_user@example.com");
        assertThat(body).containsEntry("hasEmail", Boolean.TRUE);
        assertThat(body).containsEntry("hasWechat", Boolean.FALSE);
        assertThat(body).containsEntry("plan", "free");
        assertThat(body).containsEntry("role", "user");
    }

    // ============================ 3) 登录/注册合一 ============================

    @Test
    void emailLogin_unifiesRegisterAndLogin_sameEmailSameAccount() {
        String email = "unified@example.com";

        // 首次：新邮箱 → 自动建号，返回 {token, user}，hasEmail=true。
        ResponseEntity<Map> first = emailLoginWithFreshCode(email);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstUser = (Map<String, Object>) first.getBody().get("user");
        assertThat(firstUser).containsEntry("hasEmail", Boolean.TRUE);
        long firstId = ((Number) firstUser.get("id")).longValue();

        // 再次：同邮箱 → 命中同一账号（同 id），不重复建号。
        ResponseEntity<Map> second = emailLoginWithFreshCode(email);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> secondUser = (Map<?, ?>) second.getBody().get("user");
        long secondId = ((Number) secondUser.get("id")).longValue();

        assertThat(secondId).isEqualTo(firstId);
    }

    // ======================= 4) 注销后身份可复用（需求 8.4） =======================

    @Test
    void identityReusableAfterDeletion_sameEmailCreatesNewAccount() {
        String email = "reusable@example.com";

        // 注册并登录（全新独立账号，requireDeletable 通过）。
        ResponseEntity<Map> reg = emailLoginWithFreshCode(email);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) reg.getBody().get("token");
        long firstId = ((Number) ((Map<?, ?>) reg.getBody().get("user")).get("id")).longValue();

        // 二次验证注销：先发 DELETE 用途验证码并读回，再提交 /api/me/delete → 204。
        ResponseEntity<Void> sendDelete = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "DELETE"), Void.class);
        assertThat(sendDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String deleteCode = latestCode(email, EmailCodePurpose.DELETE);

        ResponseEntity<Void> delete = rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                new HttpEntity<>(Map.of("code", deleteCode), authJson(token)), Void.class);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 注销后同邮箱再次登录 → 建立全新账号（id 不同），确认 email 已释放。
        ResponseEntity<Map> again = emailLoginWithFreshCode(email);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        long newId = ((Number) ((Map<?, ?>) again.getBody().get("user")).get("id")).longValue();

        assertThat(newId).isNotEqualTo(firstId);
    }

    // ======================= 5) 废弃的密码端点已移除（需求 9.4） =======================

    @Test
    void removedPasswordEndpoints_areNotSuccessful() {
        ResponseEntity<Map> register = rest.postForEntity(url("/api/auth/register"),
                Map.of("username", "u", "password", "password123"), Map.class);
        assertThat(register.getStatusCode().is2xxSuccessful()).isFalse();

        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/login"),
                Map.of("username", "u", "password", "password123"), Map.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).isFalse();
    }

    // ---------------------------------- 辅助 ----------------------------------

    /** 断言响应为安全层 401 且统一错误体 code=UNAUTHENTICATED，正文为 JSON。 */
    @SuppressWarnings("unchecked")
    private void assertUnauthenticated(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.includes(MediaType.APPLICATION_JSON)).isTrue());
        assertThat((Map<String, Object>) response.getBody()).containsEntry("code", "UNAUTHENTICATED");
    }

    /**
     * 邮箱验证码登录/注册合一并换取 JWT（新邮箱自动建号）：清空该邮箱历史验证码以规避发码冷却，
     * 发码（日志降级落库）→ 读回 LOGIN 验证码 → email-login。
     */
    private String registerAndLogin(String email) {
        ResponseEntity<Map> login = emailLoginWithFreshCode(email);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /**
     * 以「新鲜」LOGIN 验证码执行 email-login，返回原始响应（含 {token, user}）。
     *
     * <p>为在同一测试内对同一邮箱多次登录（验证码单次消费 + 同 (email,purpose) 60s 发码冷却），
     * 先清除该邮箱的历史验证码记录（既释放冷却窗口，也避免读到旧码），再重新发码并读回当前有效码。</p>
     */
    private ResponseEntity<Map> emailLoginWithFreshCode(String email) {
        verificationCodeRepository.deleteByEmail(email);

        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String code = latestCode(email, EmailCodePurpose.LOGIN);
        return rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
    }

    /** 从验证码仓库读回该 (email, purpose) 下最新一条未消费验证码的明文码。 */
    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
