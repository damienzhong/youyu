package com.damien.youyu.security;

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

/**
 * Spring Security 过滤链与 CurrentUser 上下文的集成测试（任务 3.2，需求 2.5、2.2/2.3）。
 *
 * <p>覆盖：未认证访问受保护端点返回 401 与统一错误体；持有有效令牌可通过过滤链并从会话上下文
 * 取到当前用户；无效令牌被拒绝；公开端点(注册/登录/健康检查)无需令牌。全流程通过真实 HTTP 与
 * H2 数据库执行，不使用任何桩。</p>
 *
 * <p>本测试为全栈 {@code @SpringBootTest}，会通过真实 HTTP 提交并提交（commit）用户数据。为避免污染
 * 其它使用共享内存库的切片测试（如 {@code @DataJpaTest}），此处使用独立命名的内存库。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-security-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class SecurityFilterChainIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String registerAndLogin(String username, String password) {
        // 注册（公开端点，无需令牌）
        ResponseEntity<Map> reg = restTemplate.postForEntity(
                url("/api/auth/register"),
                Map.of("username", username, "password", password),
                Map.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 登录换取令牌（公开端点）
        ResponseEntity<Map> login = restTemplate.postForEntity(
                url("/api/auth/login"),
                Map.of("username", username, "password", password),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    @Test
    void unauthenticatedRequestToProtectedEndpoint_returns401WithUnifiedErrorBody() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(url("/api/me"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.includes(MediaType.APPLICATION_JSON)).isTrue());
        assertThat(response.getBody()).containsEntry("code", "UNAUTHENTICATED");
    }

    @Test
    void validToken_passesFilterChainAndPopulatesCurrentUser() {
        String token = registerAndLogin("alice_sec", "password123");

        ResponseEntity<Map> response = restTemplate.exchange(
                url("/api/me"), HttpMethod.GET, bearer(token), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("username", "alice_sec");
        assertThat(response.getBody()).containsEntry("role", "user");
        assertThat(response.getBody()).containsEntry("plan", "free");
    }

    @Test
    void invalidToken_isRejectedWith401() {
        ResponseEntity<Map> response = restTemplate.exchange(
                url("/api/me"), HttpMethod.GET, bearer("not-a-valid-jwt"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "UNAUTHENTICATED");
    }

    @Test
    void healthEndpoint_isPublicWithoutToken() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(url("/api/health"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }
}
