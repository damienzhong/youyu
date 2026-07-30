package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.damien.youyu.api.dto.UserSummaryResponse;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.security.CurrentUserPrincipal;
import com.damien.youyu.support.InMemoryUserRepository;

import java.util.List;

/**
 * MeController 的示例单元测试（关联需求 9.1、9.4、9.5）。
 *
 * <p>验证 GET /api/me 返回当前会话用户的 id/username/plan/role/planStartedAt/planExpiresAt 等只读字段。
 * 通过在 {@code SecurityContext} 中放入 {@link CurrentUserPrincipal} 模拟已鉴权会话，
 * 使用真实的 {@link CurrentUser} 与 {@link InMemoryUserRepository}，不使用 mock。</p>
 */
class MeControllerTest {

    private final InMemoryUserRepository repository = new InMemoryUserRepository();
    private final CurrentUser currentUser = new CurrentUser();
    // bind/unbind/delete 端点测试属于任务 9.3；此处仅覆盖 GET /me，AuthService/AccountDeletionService 不参与，置 null。
    private final MeController controller = new MeController(currentUser, repository, null, null);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId, String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                new CurrentUserPrincipal(userId, role), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private User seed(Plan plan, Role role) {
        User u = new User();
        u.setEmail("alice@example.com");
        u.setNickname("alice");
        u.setPlan(plan);
        u.setRole(role);
        LocalDateTime t = LocalDateTime.of(2025, 6, 1, 12, 30);
        u.setPlanStartedAt(t);
        u.setPlanExpiresAt(t.plusDays(365));
        u.setCreatedAt(t);
        u.setUpdatedAt(t);
        return repository.save(u);
    }

    @Test
    void me_returnsCurrentUserFields() {
        User user = seed(Plan.FREE, Role.USER);
        authenticateAs(user.getId(), "user");

        ResponseEntity<UserSummaryResponse> response = controller.me();
        UserSummaryResponse body = response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(user.getId());
        assertThat(body.nickname()).isEqualTo("alice");
        assertThat(body.email()).isEqualTo("alice@example.com");
        assertThat(body.hasEmail()).isTrue();
        assertThat(body.hasWechat()).isFalse();
        assertThat(body.plan()).isEqualTo("free");
        assertThat(body.role()).isEqualTo("user");
        assertThat(body.planStartedAt()).isEqualTo(user.getPlanStartedAt());
        assertThat(body.planExpiresAt()).isEqualTo(user.getPlanStartedAt().plusDays(365));
    }

    @Test
    void me_reflectsStoredPlanAndRoleWithoutGating() {
        // plan=lifetime、role=admin 也仅作展示，不触发任何门控行为。
        User user = seed(Plan.LIFETIME, Role.ADMIN);
        authenticateAs(user.getId(), "admin");

        UserSummaryResponse body = controller.me().getBody();

        assertThat(body).isNotNull();
        assertThat(body.plan()).isEqualTo("lifetime");
        assertThat(body.role()).isEqualTo("admin");
    }

    @Test
    void me_withoutAuthentication_rejectedAsUnauthenticated() {
        ApiException ex = catchThrowableOfType(controller::me, ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
    }
}
