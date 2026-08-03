package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.EmailLoginRequest;
import com.damien.youyu.api.dto.LoginResponse;
import com.damien.youyu.api.dto.SendCodeRequest;
import com.damien.youyu.api.dto.UserSummaryResponse;
import com.damien.youyu.api.dto.WxLoginRequest;
import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.security.JwtService;
import com.damien.youyu.service.AuthService;
import com.damien.youyu.service.InviteBindResult;
import com.damien.youyu.service.LoginOutcome;
import com.damien.youyu.service.VerificationCodeService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 鉴权接口（无密码）：发码、邮箱验证码登录/注册合一、微信一键登录与注销。
 *
 * <p>关联需求 2、3、9.2。本控制器下的三个端点
 * （{@code /send-code}、{@code /email-login}、{@code /wx-login}）为公开端点（无需令牌，
 * 见需求 9.2；公开/受保护边界由 {@code SecurityConfig} 落地，见任务 7.4）。密码注册/登录端点
 * （{@code /register}、{@code /login}）已移除（需求 9.4）。绑定/解绑/注销走 /api/me（任务 7.2）。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final VerificationCodeService verificationCodeService;

    public AuthController(
            AuthService authService,
            JwtService jwtService,
            VerificationCodeService verificationCodeService) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.verificationCodeService = verificationCodeService;
    }

    /**
     * 发送邮箱验证码（需求 1）。校验/冷却/限流/存表/发送均在
     * {@link VerificationCodeService#sendCode} 内完成；来源 IP 从请求解析用于限流。
     *
     * <p>成功返回 204（无正文），且不因邮箱是否已注册而返回可区分结果，避免邮箱枚举（需求 1.7）。</p>
     */
    @PostMapping("/send-code")
    public ResponseEntity<Void> sendCode(@RequestBody SendCodeRequest req, HttpServletRequest request) {
        EmailCodePurpose purpose = parsePurpose(req.purpose());
        String ip = resolveClientIp(request);
        verificationCodeService.sendCode(req.email(), purpose, ip);
        return ResponseEntity.noContent().build();
    }

    /**
     * 邮箱验证码登录/注册合一（需求 2）：验证码通过后按 email 查/建账号并签发 JWT，
     * 返回结构与微信登录一致（token + 用户摘要 + 本次邀请绑定结果）。
     *
     * <p>可选的 {@code inviteCode} 原样透传给服务层，控制器不做长度或格式校验（需求 5.1、5.6）。</p>
     */
    @PostMapping("/email-login")
    public ResponseEntity<LoginResponse> emailLogin(@RequestBody EmailLoginRequest req) {
        LoginOutcome outcome = authService.emailLogin(req.email(), req.code(), req.inviteCode());
        return ResponseEntity.ok(toLoginResponse(outcome));
    }

    /**
     * 微信小程序登录：用一次性 code 换取 openid，找到或创建用户后返回令牌与用户摘要（需求 3）。
     * 返回结构与邮箱登录一致，前端拿到 token 后的处理无需区分登录方式。
     */
    @PostMapping("/wx-login")
    public ResponseEntity<LoginResponse> wxLogin(@RequestBody WxLoginRequest req) {
        LoginOutcome outcome = authService.wxLogin(req.code(), req.inviteCode());
        return ResponseEntity.ok(toLoginResponse(outcome));
    }

    /**
     * 把登录结果拍成响应：签发令牌，并把 {@link LoginOutcome#inviteBind()} 映射为
     * {@code inviteBound} 与 {@code inviteUnboundReason}（需求 5.4）。
     *
     * <p>未绑定原因直接用枚举名称，客户端按名称判定；已绑定时为 {@code null}
     * （{@link InviteBindResult} 的构造已保证「已绑定 ⇒ 原因为空」）。</p>
     */
    private LoginResponse toLoginResponse(LoginOutcome outcome) {
        User user = outcome.user();
        String token = jwtService.generateToken(user);
        InviteBindResult bind = outcome.inviteBind();
        String reason = bind.reason() == null ? null : bind.reason().name();
        return LoginResponse.of(token, UserSummaryResponse.from(user), bind.bound(), reason);
    }

    /**
     * 注销：无状态令牌由客户端丢弃即可，服务端无需维护会话状态。
     * 返回 204，语义上表示“已注销”。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    /**
     * 将请求中的 purpose 字符串映射为 {@link EmailCodePurpose}（大小写不敏感）。
     * 为空/空白或非法取值一律以 {@code FIELD_REQUIRED(purpose)} 拒绝，避免向客户端暴露内部枚举细节。
     */
    private static EmailCodePurpose parsePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw ApiException.fieldRequired("purpose");
        }
        try {
            return EmailCodePurpose.valueOf(purpose.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.fieldRequired("purpose");
        }
    }

    /**
     * 解析客户端来源 IP，用于发码限流：优先取 {@code X-Forwarded-For} 的首段（最初的客户端），
     * 缺失时回退到 {@link HttpServletRequest#getRemoteAddr()}。
     *
     * <p>注意：这里取<strong>首位</strong>，而邀请系统的 {@link ClientIpResolver#resolveClientIp}
     * 取<strong>末位</strong>——首位由客户端自填可伪造，末位由 nginx {@code $proxy_add_x_forwarded_for}
     * 追加、客户端不可控。取末位才是正确取法；发码限流取首位是既有行为，改动它属于另一个 spec 的范围。
     * <strong>不要顺手把 {@link ClientIpResolver} 统一回首位</strong>，那会废掉邀请码枚举防护。</p>
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
