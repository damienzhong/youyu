package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;

/**
 * {@link AuthService#unbind(Long, String)} 的示例/边界单元测试（关联需求 7）。
 *
 * <p>不接真实 DB：{@link UserRepository} 用 Mockito 测试替身（{@code findById} 返回既有用户、
 * {@code save} 回显入参），{@link WeChatClient} 与 {@link VerificationCodeService} 在解绑路径
 * 上不参与，置空即可。覆盖：解绑邮箱（微信在场）、解绑微信（邮箱在场）、解绑唯一身份被拒
 * （LAST_LOGIN_METHOD 且零副作用）、type 缺失/非法（FIELD_REQUIRED）、会话用户缺失
 * （UNAUTHENTICATED）。</p>
 */
class AuthServiceUnbindTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long USER_ID = 42L;

    private UserRepository userRepository;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AuthService(userRepository, Clock.fixed(T0, ZONE), null, null, null, null);
    }

    private User userWith(String email, String openid, String unionid) {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail(email);
        u.setWxOpenid(openid);
        u.setWxUnionid(unionid);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        return u;
    }

    @Test
    void unbindEmail_whenWechatPresent_clearsEmailOnly() {
        User user = userWith("alice@example.com", "openid-1", "unionid-1");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        User result = service.unbind(USER_ID, "email");

        assertThat(result.getEmail()).isNull();
        // 微信身份不受影响（需求 7.1）。
        assertThat(result.getWxOpenid()).isEqualTo("openid-1");
        assertThat(result.getWxUnionid()).isEqualTo("unionid-1");
        verify(userRepository).save(user);
    }

    @Test
    void unbindWechat_whenEmailPresent_clearsOpenidAndUnionid() {
        User user = userWith("bob@example.com", "openid-2", "unionid-2");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        User result = service.unbind(USER_ID, "wechat");

        // 微信解绑同时清空 openid 与 unionid（需求 7.1/7.3）。
        assertThat(result.getWxOpenid()).isNull();
        assertThat(result.getWxUnionid()).isNull();
        assertThat(result.getEmail()).isEqualTo("bob@example.com");
        verify(userRepository).save(user);
    }

    @Test
    void unbindType_isCaseInsensitive() {
        User user = userWith("carol@example.com", "openid-3", null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        User result = service.unbind(USER_ID, "  EMAIL  ");

        assertThat(result.getEmail()).isNull();
        assertThat(result.getWxOpenid()).isEqualTo("openid-3");
    }

    @Test
    void unbindOnlyIdentity_email_rejectedWithLastLoginMethodAndNoChange() {
        User user = userWith("solo@example.com", null, null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ApiException ex = catchThrowableOfType(
                () -> service.unbind(USER_ID, "email"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("LAST_LOGIN_METHOD");
        // 零副作用：身份保持不变、不落盘（需求 7.2）。
        assertThat(user.getEmail()).isEqualTo("solo@example.com");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void unbindOnlyIdentity_wechat_rejectedWithLastLoginMethodAndNoChange() {
        User user = userWith(null, "openid-solo", "unionid-solo");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ApiException ex = catchThrowableOfType(
                () -> service.unbind(USER_ID, "wechat"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("LAST_LOGIN_METHOD");
        assertThat(user.getWxOpenid()).isEqualTo("openid-solo");
        assertThat(user.getWxUnionid()).isEqualTo("unionid-solo");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void unbind_missingType_rejectedWithFieldRequired() {
        User user = userWith("dave@example.com", "openid-4", null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ApiException blank = catchThrowableOfType(
                () -> service.unbind(USER_ID, "   "), ApiException.class);
        assertThat(blank.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(blank.getField()).isEqualTo("type");

        ApiException nullType = catchThrowableOfType(
                () -> service.unbind(USER_ID, null), ApiException.class);
        assertThat(nullType.getCode()).isEqualTo("FIELD_REQUIRED");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void unbind_invalidType_rejectedWithFieldRequired() {
        User user = userWith("erin@example.com", "openid-5", null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ApiException ex = catchThrowableOfType(
                () -> service.unbind(USER_ID, "phone"), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("type");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void unbind_missingUser_rejectedWithUnauthenticated() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> service.unbind(USER_ID, "email"), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
        verify(userRepository, never()).save(any(User.class));
    }
}
