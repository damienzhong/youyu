package com.damien.youyu.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.LoginRequest;
import com.damien.youyu.api.dto.LoginResponse;
import com.damien.youyu.api.dto.RegisterRequest;
import com.damien.youyu.api.dto.UserSummaryResponse;
import com.damien.youyu.api.dto.WxLoginRequest;
import com.damien.youyu.domain.User;
import com.damien.youyu.security.JwtService;
import com.damien.youyu.service.AuthService;

/**
 * 鉴权接口：注册、登录、注销与当前用户信息。
 *
 * <p>关联需求：1.1-1.10、9.2。除本控制器下的注册/登录外，其余业务接口需携带有效令牌
 * （完整过滤链见任务 3.2）。当前 GET /api/me 直接解析 Authorization 头，作为最小可用实现，
 * 待 3.2 引入 SecurityContext 后可平滑替换。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    /** 注册：成功返回 201 与用户摘要。 */
    @PostMapping("/register")
    public ResponseEntity<UserSummaryResponse> register(@RequestBody RegisterRequest req) {
        User user = authService.register(req.username(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserSummaryResponse.from(user));
    }

    /** 登录：成功返回令牌与用户摘要。 */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
        User user = authService.login(req.username(), req.password());
        String token = jwtService.generateToken(user);
        return ResponseEntity.ok(LoginResponse.of(token, UserSummaryResponse.from(user)));
    }

    /**
     * 微信小程序登录：用一次性 code 换取 openid，找到或创建用户后返回令牌与用户摘要。
     * 返回结构与账号密码登录一致，前端拿到 token 后的处理无需区分登录方式。
     */
    @PostMapping("/wx-login")
    public ResponseEntity<LoginResponse> wxLogin(@RequestBody WxLoginRequest req) {
        User user = authService.wxLogin(req.code());
        String token = jwtService.generateToken(user);
        return ResponseEntity.ok(LoginResponse.of(token, UserSummaryResponse.from(user)));
    }

    /**
     * 注销：无状态令牌由客户端丢弃即可，服务端无需维护会话状态。
     * 返回 204，语义上表示"已注销"。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }
}
