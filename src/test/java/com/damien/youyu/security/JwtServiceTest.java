package com.damien.youyu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;

import io.jsonwebtoken.JwtException;

/**
 * JwtService 令牌签发与解析的往返测试。
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-key-only-for-unit-tests-do-not-use-in-prod";

    private User userWith(Long id, Role role) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setPlan(Plan.FREE);
        u.setRole(role);
        return u;
    }

    @Test
    void generateThenParse_roundTripsUserIdAndRole() {
        JwtService service = new JwtService(SECRET, 3_600_000L);
        User user = userWith(42L, Role.USER);

        String token = service.generateToken(user);

        assertThat(token).isNotBlank();
        assertThat(service.extractUserId(token)).isEqualTo(42L);
        assertThat(service.extractRole(token)).isEqualTo("user");
    }

    @Test
    void parse_withDifferentSecret_isRejected() {
        JwtService issuer = new JwtService(SECRET, 3_600_000L);
        String token = issuer.generateToken(userWith(7L, Role.ADMIN));

        JwtService verifierWithOtherSecret =
                new JwtService("another-different-secret-key-with-enough-length-1234567890", 3_600_000L);

        assertThatThrownBy(() -> verifierWithOtherSecret.parseClaims(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parse_expiredToken_isRejected() {
        // 负有效期使令牌立即过期
        JwtService service = new JwtService(SECRET, -1_000L);
        String token = service.generateToken(userWith(1L, Role.USER));

        assertThatThrownBy(() -> service.parseClaims(token))
                .isInstanceOf(JwtException.class);
    }
}
