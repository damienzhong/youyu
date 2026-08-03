package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;

/**
 * 单元测试：{@link AuthService#emailLogin(String, String, String)} 的登录/注册合一行为（需求 2）。
 *
 * <p>使用测试替身（Mockito）隔离验证码校验与持久化：验证码校验结果由
 * {@link VerificationCodeService#verifyConsume} 控制，用户仓储的查/建以 {@link UserRepository}
 * 桩表达。覆盖三条核心路径：新邮箱建号、老邮箱登录、验证码无效拒绝。</p>
 *
 * <p>时间以固定 {@link Clock} 注入以获得确定性断言（plan 起止时间）。</p>
 */
class AuthEmailLoginTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 2025-06-01T12:30 +08:00
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZONE);

    private UserRepository userRepository;
    private VerificationCodeService verificationCodeService;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        verificationCodeService = mock(VerificationCodeService.class);
        // emailLogin 不使用 weChatClient，传 null 即可。
        InviteBindingService inviteBindingService = mock(InviteBindingService.class);
        when(inviteBindingService.bindOnRegister(any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(InviteBindResult.ofUnbound(UnboundReason.NO_CODE));
        service = new AuthService(userRepository, Clock.fixed(T0, ZONE), null,
                verificationCodeService, new InviteCodeGenerator(), inviteBindingService);
        // save 回填 id 并原样返回，模拟 JPA 持久化语义。
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(1L);
            }
            return u;
        });
    }

    @Test
    void newEmail_createsUserWithDefaultNicknamePlanAndRole() {
        String email = "alice@example.com";
        when(verificationCodeService.verifyConsume(email, EmailCodePurpose.LOGIN, "123456"))
                .thenReturn(true);
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        User created = service.emailLogin(email, "123456", null).user();

        assertThat(created.getId()).isNotNull();
        assertThat(created.getEmail()).isEqualTo(email);
        assertThat(created.getWxOpenid()).isNull();
        // 昵称缺省取邮箱 @ 前缀。
        assertThat(created.getNickname()).isEqualTo("alice");
        assertThat(created.getPlan()).isEqualTo(Plan.FREE);
        assertThat(created.getRole()).isEqualTo(Role.USER);
        assertThat(created.getPlanStartedAt()).isEqualTo(NOW);
        assertThat(created.getPlanExpiresAt()).isEqualTo(NOW.plusDays(365));
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        assertThat(created.getUpdatedAt()).isEqualTo(NOW);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void existingEmail_logsInWithoutCreatingNewUser() {
        String email = "bob@example.com";
        User existing = new User();
        existing.setId(42L);
        existing.setEmail(email);
        existing.setNickname("bobby");
        existing.setPlan(Plan.PRO);
        existing.setRole(Role.USER);
        when(verificationCodeService.verifyConsume(email, EmailCodePurpose.LOGIN, "654321"))
                .thenReturn(true);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existing));

        User loggedIn = service.emailLogin(email, "654321", null).user();

        assertThat(loggedIn).isSameAs(existing);
        assertThat(loggedIn.getId()).isEqualTo(42L);
        // 已存在账号：不重复创建（不写库）。
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void invalidCode_rejectedWithCodeInvalidAndNoSideEffects() {
        String email = "carol@example.com";
        when(verificationCodeService.verifyConsume(email, EmailCodePurpose.LOGIN, "000000"))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(
                () -> service.emailLogin(email, "000000", null), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("CODE_INVALID");
        // 校验失败零副作用：不查库、不建号。
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void email_isTrimmedBeforeVerifyAndLookup() {
        String raw = "  dave@example.com  ";
        String trimmed = "dave@example.com";
        when(verificationCodeService.verifyConsume(eq(trimmed), eq(EmailCodePurpose.LOGIN), any()))
                .thenReturn(true);
        when(userRepository.findByEmail(trimmed)).thenReturn(Optional.empty());

        User created = service.emailLogin(raw, "111111", null).user();

        assertThat(created.getEmail()).isEqualTo(trimmed);
        assertThat(created.getNickname()).isEqualTo("dave");
        verify(verificationCodeService).verifyConsume(trimmed, EmailCodePurpose.LOGIN, "111111");
    }
}
