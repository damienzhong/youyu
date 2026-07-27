package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;

/**
 * AuthService 的示例与边界单元测试（关联需求 1.1-1.10、9.2）。
 *
 * <p>使用 H2 + 真实 {@link UserRepository} 与真实 {@link BCryptPasswordEncoder}，不使用任何桩，
 * 以固定 {@link Clock} 做确定性时间断言。属性测试（Property 22/23/24/25）在任务 3.3 中实现。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuthServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z"); // = 2025-06-01T12:30 +08:00

    @Autowired
    private UserRepository userRepository;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private AuthService serviceAt(Instant instant) {
        return new AuthService(userRepository, encoder, Clock.fixed(instant, ZONE), null, 5, 15);
    }

    // ---------------- 注册 ----------------

    @Test
    void register_success_initializesPlanRoleAndHashesPassword() {
        AuthService service = serviceAt(T0);

        User user = service.register("alice", "password123");

        assertThat(user.getId()).isNotNull();
        assertThat(user.getUsername()).isEqualTo("alice");
        // 需求 1.8：存储的是哈希而非明文，且能通过校验
        assertThat(user.getPasswordHash()).isNotEqualTo("password123");
        assertThat(encoder.matches("password123", user.getPasswordHash())).isTrue();
        // 需求 1.9/1.10
        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(user.getRole()).isEqualTo(Role.USER);
        // 需求 9.2：plan_expires_at = plan_started_at + 精确 365 天
        LocalDateTime expectedStart = LocalDateTime.ofInstant(T0, ZONE);
        assertThat(user.getPlanStartedAt()).isEqualTo(expectedStart);
        assertThat(user.getPlanExpiresAt()).isEqualTo(expectedStart.plusDays(365));
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void register_trimsUsername() {
        AuthService service = serviceAt(T0);

        User user = service.register("  bob  ", "password123");

        assertThat(user.getUsername()).isEqualTo("bob");
    }

    @Test
    void register_duplicateUsername_rejectedWithUsernameTaken() {
        AuthService service = serviceAt(T0);
        service.register("carol", "password123");

        ApiException ex = catchThrowableOfType(
                () -> service.register("carol", "anotherpass"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("USERNAME_TAKEN");
        assertThat(userRepository.findByUsername("carol")).isPresent();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void register_weakPassword_tooShortOrTooLong_rejected() {
        AuthService service = serviceAt(T0);

        ApiException tooShort = catchThrowableOfType(
                () -> service.register("dave", "short12"), ApiException.class); // 7 chars
        assertThat(tooShort.getCode()).isEqualTo("PASSWORD_WEAK");

        String tooLong = "a".repeat(65);
        ApiException tooLongEx = catchThrowableOfType(
                () -> service.register("dave", tooLong), ApiException.class);
        assertThat(tooLongEx.getCode()).isEqualTo("PASSWORD_WEAK");

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void register_missingFields_rejectedWithFieldRequired() {
        AuthService service = serviceAt(T0);

        ApiException blankUsername = catchThrowableOfType(
                () -> service.register("   ", "password123"), ApiException.class);
        assertThat(blankUsername.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(blankUsername.getField()).isEqualTo("username");

        ApiException emptyPassword = catchThrowableOfType(
                () -> service.register("erin", ""), ApiException.class);
        assertThat(emptyPassword.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(emptyPassword.getField()).isEqualTo("password");

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void register_usernameTooLong_rejected() {
        AuthService service = serviceAt(T0);

        ApiException ex = catchThrowableOfType(
                () -> service.register("u".repeat(65), "password123"), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("USERNAME_INVALID");
        assertThat(userRepository.count()).isZero();
    }

    // ---------------- 登录 ----------------

    @Test
    void login_success_returnsUserAndResetsCounters() {
        AuthService service = serviceAt(T0);
        service.register("frank", "password123");

        User user = service.login("frank", "password123");

        assertThat(user.getUsername()).isEqualTo("frank");
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void login_wrongPassword_rejectedWithBadCredentialsAndCountsFailure() {
        AuthService service = serviceAt(T0);
        service.register("grace", "password123");

        ApiException ex = catchThrowableOfType(
                () -> service.login("grace", "wrongpass1"), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("BAD_CREDENTIALS");
        assertThat(userRepository.findByUsername("grace").orElseThrow().getFailedLoginCount()).isEqualTo(1);
    }

    @Test
    void login_unknownUser_rejectedWithBadCredentials() {
        AuthService service = serviceAt(T0);

        ApiException ex = catchThrowableOfType(
                () -> service.login("nobody", "password123"), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    void login_fiveFailures_locksAccountAndRejectsCorrectCredentials() {
        AuthService service = serviceAt(T0);
        service.register("heidi", "password123");

        // 连续 5 次失败
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login("heidi", "wrongpass1"))
                    .isInstanceOf(ApiException.class);
        }

        User locked = userRepository.findByUsername("heidi").orElseThrow();
        assertThat(locked.getFailedLoginCount()).isEqualTo(5);
        assertThat(locked.getLockedUntil()).isNotNull();

        // 锁定期内即使凭证正确也被拒绝（需求 1.7）
        ApiException ex = catchThrowableOfType(
                () -> service.login("heidi", "password123"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void login_fourFailuresThenCorrect_doesNotLock() {
        AuthService service = serviceAt(T0);
        service.register("ivan", "password123");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.login("ivan", "wrongpass1"))
                    .isInstanceOf(ApiException.class);
        }

        User user = service.login("ivan", "password123");
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void login_afterLockWindowExpires_correctCredentialsSucceed() {
        // 在 T0 触发锁定
        AuthService atLock = serviceAt(T0);
        atLock.register("judy", "password123");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> atLock.login("judy", "wrongpass1"))
                    .isInstanceOf(ApiException.class);
        }

        // 16 分钟后（锁定 15 分钟已过），正确凭证可登录
        AuthService afterExpiry = serviceAt(T0.plusSeconds(16 * 60));
        User user = afterExpiry.login("judy", "password123");

        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void register_samePasswordDifferentUsers_producesDifferentHashes() {
        AuthService service = serviceAt(T0);

        User u1 = service.register("kate", "password123");
        User u2 = service.register("leo", "password123");

        // 加盐体现：相同明文不同用户哈希不同（需求 1.8 / Property 24）
        assertThat(u1.getPasswordHash()).isNotEqualTo(u2.getPasswordHash());
    }
}
