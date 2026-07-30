package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;

/**
 * 单元测试：{@link AuthService#updateNickname(Long, String)}（需求 4.4）。
 * 覆盖：成功修改（去空白）、空/超长拒绝（NICKNAME_INVALID）、会话用户不存在（UNAUTHENTICATED）。
 */
class AuthUpdateNicknameTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long USER_ID = 7L;

    private UserRepository userRepository;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AuthService(userRepository, Clock.fixed(T0, ZONE), null, null);
    }

    private User user() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail("u@example.com");
        u.setNickname("old");
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        return u;
    }

    @Test
    void updateNickname_success_trimsAndSaves() {
        User u = user();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        User result = service.updateNickname(USER_ID, "  新昵称  ");

        assertThat(result.getNickname()).isEqualTo("新昵称");
        verify(userRepository).save(u);
    }

    @Test
    void updateNickname_blank_rejected() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));

        ApiException ex = catchThrowableOfType(
                () -> service.updateNickname(USER_ID, "   "), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("NICKNAME_INVALID");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updateNickname_tooLong_rejected() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));

        ApiException ex = catchThrowableOfType(
                () -> service.updateNickname(USER_ID, "n".repeat(65)), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NICKNAME_INVALID");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updateNickname_missingUser_unauthenticated() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> service.updateNickname(USER_ID, "x"), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
    }
}
