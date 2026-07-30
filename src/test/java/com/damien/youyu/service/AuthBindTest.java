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
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

/**
 * 单元测试：{@link AuthService#bindEmail(Long, String, String)} 与
 * {@link AuthService#bindWechat(Long, String)} 的身份绑定与冲突检查（需求 5、6、7）。
 *
 * <p>使用测试替身（Mockito）隔离验证码校验、微信换取与持久化。覆盖每个方法的四条核心路径：
 * 成功绑定、当前账号已绑（IDENTITY_ALREADY_BOUND）、目标被他人占用（IDENTITY_TAKEN）、
 * 凭证无效（bindEmail 的 CODE_INVALID / bindWechat 的 WX_LOGIN_FAILED、WX_CODE_REQUIRED）。</p>
 *
 * <p>时间以固定 {@link Clock} 注入以获得确定性断言（updatedAt）。</p>
 */
class AuthBindTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 2025-06-01T12:30 +08:00
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZONE);

    private static final Long CURRENT_ID = 7L;

    private UserRepository userRepository;
    private VerificationCodeService verificationCodeService;
    private WeChatClient weChatClient;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        verificationCodeService = mock(VerificationCodeService.class);
        weChatClient = mock(WeChatClient.class);
        service = new AuthService(userRepository, Clock.fixed(T0, ZONE), weChatClient,
                verificationCodeService);
        // save 原样返回被保存对象，模拟 JPA 持久化语义。
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 构造一个"当前会话用户"：纯微信账号（无 email），供 bindEmail 成功路径复用。 */
    private User wechatOnlyUser() {
        User u = new User();
        u.setId(CURRENT_ID);
        u.setWxOpenid("openid-current");
        u.setNickname("current");
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(NOW.minusDays(10));
        u.setPlanExpiresAt(NOW.plusDays(355));
        u.setCreatedAt(NOW.minusDays(10));
        u.setUpdatedAt(NOW.minusDays(10));
        return u;
    }

    /** 构造一个"当前会话用户"：纯邮箱账号（无 wx_openid），供 bindWechat 成功路径复用。 */
    private User emailOnlyUser() {
        User u = new User();
        u.setId(CURRENT_ID);
        u.setEmail("me@example.com");
        u.setNickname("me");
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(NOW.minusDays(10));
        u.setPlanExpiresAt(NOW.plusDays(355));
        u.setCreatedAt(NOW.minusDays(10));
        u.setUpdatedAt(NOW.minusDays(10));
        return u;
    }

    // ---------------- bindEmail ----------------

    @Test
    void bindEmail_success_writesEmailAndBumpsUpdatedAt() {
        User current = wechatOnlyUser();
        String email = "new@example.com";
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));
        when(verificationCodeService.verifyConsume(email, EmailCodePurpose.BIND, "123456"))
                .thenReturn(true);
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        User result = service.bindEmail(CURRENT_ID, email, "123456");

        assertThat(result.getEmail()).isEqualTo(email);
        // 微信身份保持不变。
        assertThat(result.getWxOpenid()).isEqualTo("openid-current");
        assertThat(result.getUpdatedAt()).isEqualTo(NOW);
        verify(userRepository).save(current);
    }

    @Test
    void bindEmail_trimsEmailBeforeVerifyAndWrite() {
        User current = wechatOnlyUser();
        String trimmed = "spaced@example.com";
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));
        when(verificationCodeService.verifyConsume(trimmed, EmailCodePurpose.BIND, "111111"))
                .thenReturn(true);
        when(userRepository.findByEmail(trimmed)).thenReturn(Optional.empty());

        User result = service.bindEmail(CURRENT_ID, "  spaced@example.com  ", "111111");

        assertThat(result.getEmail()).isEqualTo(trimmed);
        verify(verificationCodeService).verifyConsume(trimmed, EmailCodePurpose.BIND, "111111");
    }

    @Test
    void bindEmail_alreadyBound_rejectedBeforeConsumingCode() {
        User current = wechatOnlyUser();
        current.setEmail("existing@example.com");
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));

        ApiException ex = catchThrowableOfType(
                () -> service.bindEmail(CURRENT_ID, "new@example.com", "123456"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("IDENTITY_ALREADY_BOUND");
        // 已绑检查先于消费验证码：不浪费用户的验证码，也不写库。
        verify(verificationCodeService, never())
                .verifyConsume(any(), any(EmailCodePurpose.class), any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void bindEmail_targetTakenByAnotherAccount_rejectedNoWrite() {
        User current = wechatOnlyUser();
        String email = "taken@example.com";
        User otherHolder = new User();
        otherHolder.setId(99L);
        otherHolder.setEmail(email);
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));
        when(verificationCodeService.verifyConsume(email, EmailCodePurpose.BIND, "123456"))
                .thenReturn(true);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(otherHolder));

        ApiException ex = catchThrowableOfType(
                () -> service.bindEmail(CURRENT_ID, email, "123456"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("IDENTITY_TAKEN");
        // 冲突时不修改任何账号。
        assertThat(current.getEmail()).isNull();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void bindEmail_invalidCode_rejectedWithCodeInvalid() {
        User current = wechatOnlyUser();
        String email = "new@example.com";
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));
        when(verificationCodeService.verifyConsume(email, EmailCodePurpose.BIND, "000000"))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(
                () -> service.bindEmail(CURRENT_ID, email, "000000"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("CODE_INVALID");
        // 验证码无效：不查占用、不写库。
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void bindEmail_missingUser_rejectedWithUnauthenticated() {
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> service.bindEmail(CURRENT_ID, "new@example.com", "123456"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
        verify(verificationCodeService, never())
                .verifyConsume(any(), any(EmailCodePurpose.class), any());
    }

    // ---------------- bindWechat ----------------

    @Test
    void bindWechat_success_writesOpenidAndUnionid() {
        User current = emailOnlyUser();
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));
        when(weChatClient.jscode2session("wxcode"))
                .thenReturn(new WxSession("openid-new", "unionid-new"));
        when(userRepository.findByWxOpenid("openid-new")).thenReturn(Optional.empty());

        User result = service.bindWechat(CURRENT_ID, "wxcode");

        assertThat(result.getWxOpenid()).isEqualTo("openid-new");
        assertThat(result.getWxUnionid()).isEqualTo("unionid-new");
        // 邮箱身份保持不变。
        assertThat(result.getEmail()).isEqualTo("me@example.com");
        assertThat(result.getUpdatedAt()).isEqualTo(NOW);
        verify(userRepository).save(current);
    }

    @Test
    void bindWechat_alreadyBound_rejectedBeforeExchange() {
        User current = emailOnlyUser();
        current.setWxOpenid("openid-existing");
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));

        ApiException ex = catchThrowableOfType(
                () -> service.bindWechat(CURRENT_ID, "wxcode"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("IDENTITY_ALREADY_BOUND");
        // 已绑检查先于换取 openid：不调用微信、不写库。
        verify(weChatClient, never()).jscode2session(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void bindWechat_targetTakenByAnotherAccount_rejectedNoWrite() {
        User current = emailOnlyUser();
        User otherHolder = new User();
        otherHolder.setId(99L);
        otherHolder.setWxOpenid("openid-taken");
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));
        when(weChatClient.jscode2session("wxcode"))
                .thenReturn(new WxSession("openid-taken", null));
        when(userRepository.findByWxOpenid("openid-taken")).thenReturn(Optional.of(otherHolder));

        ApiException ex = catchThrowableOfType(
                () -> service.bindWechat(CURRENT_ID, "wxcode"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("IDENTITY_TAKEN");
        // 冲突时不修改任何账号。
        assertThat(current.getWxOpenid()).isNull();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void bindWechat_missingCode_rejectedWithWxCodeRequired() {
        User current = emailOnlyUser();
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));

        ApiException ex = catchThrowableOfType(
                () -> service.bindWechat(CURRENT_ID, "   "), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("WX_CODE_REQUIRED");
        verify(weChatClient, never()).jscode2session(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void bindWechat_exchangeFailure_propagatesWxLoginFailed() {
        User current = emailOnlyUser();
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.of(current));
        when(weChatClient.jscode2session("badcode"))
                .thenThrow(ApiException.wxLoginFailed("微信登录失败"));

        ApiException ex = catchThrowableOfType(
                () -> service.bindWechat(CURRENT_ID, "badcode"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("WX_LOGIN_FAILED");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void bindWechat_missingUser_rejectedWithUnauthenticated() {
        when(userRepository.findById(CURRENT_ID)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> service.bindWechat(CURRENT_ID, "wxcode"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
        verify(weChatClient, never()).jscode2session(any());
    }
}
