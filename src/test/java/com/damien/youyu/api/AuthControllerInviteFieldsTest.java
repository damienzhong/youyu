package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.damien.youyu.domain.User;
import com.damien.youyu.security.JwtService;
import com.damien.youyu.service.AuthService;
import com.damien.youyu.service.InviteBindResult;
import com.damien.youyu.service.LoginOutcome;
import com.damien.youyu.service.UnboundReason;
import com.damien.youyu.service.VerificationCodeService;

/**
 * 登录端点的邀请字段契约（任务 8.2，需求 5.1、5.4）。
 *
 * <p>只验证控制器与 DTO 这一层：请求体的可选 {@code inviteCode} 原样透传给服务层（含超长取值，
 * 不在控制器被 400 拒绝），服务层返回的绑定结果被拍平为响应的 {@code inviteBound} /
 * {@code inviteUnboundReason}，且 {@code token} / {@code tokenType} / {@code user} 语义不变。
 * 绑定判定本身由 {@code InviteBindingService} 的测试覆盖。</p>
 */
class AuthControllerInviteFieldsTest {

    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        JwtService jwtService = mock(JwtService.class);
        when(jwtService.generateToken(any())).thenReturn("jwt-token");
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, jwtService,
                        mock(VerificationCodeService.class)))
                .build();
    }

    @Test
    void emailLoginPassesInviteCodeThroughAndReturnsUnboundReason() throws Exception {
        when(authService.emailLogin(any(), any(), any()))
                .thenReturn(new LoginOutcome(user(),
                        InviteBindResult.ofUnbound(UnboundReason.CODE_NOT_FOUND), true));

        mockMvc.perform(post("/api/auth/email-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"code\":\"123456\",\"inviteCode\":\" k7m2q9xt \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(7))
                .andExpect(jsonPath("$.inviteBound").value(false))
                .andExpect(jsonPath("$.inviteUnboundReason").value("CODE_NOT_FOUND"));

        // 控制器不做规整：原始取值（含首尾空白）原样交给服务层。
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(authService).emailLogin(any(), any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(" k7m2q9xt ");
    }

    @Test
    void emailLoginAcceptsOverlongInviteCodeWithoutRejectingRequest() throws Exception {
        when(authService.emailLogin(any(), any(), any()))
                .thenReturn(new LoginOutcome(user(),
                        InviteBindResult.ofUnbound(UnboundReason.CODE_NOT_FOUND), true));
        String overlong = "A".repeat(65);

        mockMvc.perform(post("/api/auth/email-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"code\":\"123456\",\"inviteCode\":\"" + overlong + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteUnboundReason").value("CODE_NOT_FOUND"));

        // 需求 5.6：超长取值不得让登录失败，长度判定留给服务层。
        verify(authService).emailLogin(any(), any(), org.mockito.ArgumentMatchers.eq(overlong));
    }

    @Test
    void wxLoginOmittingInviteCodeStillWorksAndBoundResultHasNullReason() throws Exception {
        when(authService.wxLogin(any(), any()))
                .thenReturn(new LoginOutcome(user(), InviteBindResult.ofBound(), true));

        // 老客户端的请求体（不含 inviteCode 字段）仍然可用。
        mockMvc.perform(post("/api/auth/wx-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.inviteBound").value(true))
                .andExpect(jsonPath("$.inviteUnboundReason").value(org.hamcrest.Matchers.nullValue()));

        verify(authService).wxLogin(org.mockito.ArgumentMatchers.eq("wx-code"),
                org.mockito.ArgumentMatchers.isNull());
    }

    private static User user() {
        User user = new User();
        user.setId(7L);
        user.setEmail("a@b.com");
        user.setNickname("小余");
        LocalDateTime now = LocalDateTime.of(2025, 1, 1, 0, 0);
        user.setPlanStartedAt(now);
        user.setPlanExpiresAt(now.plusDays(365));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }
}
