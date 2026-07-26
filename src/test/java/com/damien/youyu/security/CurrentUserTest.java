package com.damien.youyu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.damien.youyu.error.ApiException;

/**
 * CurrentUser 从 SecurityContext 读取会话用户的单元测试（任务 3.2）。
 */
class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId, String role) {
        var principal = new CurrentUserPrincipal(userId, role);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void requireUserId_returnsUserIdFromContext() {
        authenticateAs(77L, "user");

        assertThat(currentUser.requireUserId()).isEqualTo(77L);
        assertThat(currentUser.role()).contains("user");
        assertThat(currentUser.userId()).contains(77L);
        assertThat(currentUser.isAuthenticated()).isTrue();
    }

    @Test
    void requireUserId_withoutAuthentication_throwsUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThat(currentUser.isAuthenticated()).isFalse();
        assertThat(currentUser.userId()).isEmpty();
        ApiException ex = catchThrowableOfType(currentUser::requireUserId, ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
    }
}
