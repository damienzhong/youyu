package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.UserSummaryResponse;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentUser;

/**
 * 当前用户信息接口 GET /api/me（关联需求 9.1）。
 *
 * <p>身份由 Spring Security 过滤链（{@link com.damien.youyu.security.JwtAuthenticationFilter}）统一鉴权，
 * 本控制器不再自行解析 Authorization 头，而是从 {@link CurrentUser} 读取当前会话用户主键，
 * 再据此加载用户信息。未认证请求会在进入本控制器前被过滤链以 401 拒绝（需求 2.5）。</p>
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;

    public MeController(CurrentUser currentUser, UserRepository userRepository) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<UserSummaryResponse> me() {
        Long userId = currentUser.requireUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(ApiException::unauthenticated);
        return ResponseEntity.ok(UserSummaryResponse.from(user));
    }
}
