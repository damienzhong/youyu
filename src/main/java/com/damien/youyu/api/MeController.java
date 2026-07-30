package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.BindEmailRequest;
import com.damien.youyu.api.dto.BindWechatRequest;
import com.damien.youyu.api.dto.DeleteAccountRequest;
import com.damien.youyu.api.dto.UnbindRequest;
import com.damien.youyu.api.dto.UpdateNicknameRequest;
import com.damien.youyu.api.dto.UserSummaryResponse;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.AccountDeletionService;
import com.damien.youyu.service.AuthService;

/**
 * 当前用户信息与身份管理接口 {@code /api/me}（关联需求 5、6、7、8、9）。
 *
 * <p>身份由 Spring Security 过滤链（{@link com.damien.youyu.security.JwtAuthenticationFilter}）统一鉴权，
 * 本控制器不再自行解析 Authorization 头，而是从 {@link CurrentUser} 读取当前会话用户主键，
 * 再据此加载用户信息。未认证请求会在进入本控制器前被过滤链以 401 拒绝（需求 9.2）。</p>
 *
 * <p>提供的端点：</p>
 * <ul>
 *   <li>{@code GET /api/me}：返回当前用户摘要（需求 9.1）。</li>
 *   <li>{@code POST /api/me/bind-email}：绑定邮箱身份（需求 5）。</li>
 *   <li>{@code POST /api/me/bind-wechat}：绑定微信身份（需求 6）。</li>
 *   <li>{@code POST /api/me/unbind}：解绑指定身份，保底至少一种登录方式（需求 7）。</li>
 *   <li>{@code POST /api/me/delete}：二次验证 + 协作牵连检查 + 级联硬删注销（需求 8）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AccountDeletionService accountDeletionService;

    public MeController(
            CurrentUser currentUser,
            UserRepository userRepository,
            AuthService authService,
            AccountDeletionService accountDeletionService) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.authService = authService;
        this.accountDeletionService = accountDeletionService;
    }

    @GetMapping
    public ResponseEntity<UserSummaryResponse> me() {
        Long userId = currentUser.requireUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(ApiException::unauthenticated);
        return ResponseEntity.ok(UserSummaryResponse.from(user));
    }

    /** 修改昵称（需求 4.4）：仅展示名，长度 1-64。 */
    @PostMapping("/nickname")
    public ResponseEntity<UserSummaryResponse> updateNickname(@RequestBody UpdateNicknameRequest request) {
        Long userId = currentUser.requireUserId();
        User user = authService.updateNickname(userId, request.nickname());
        return ResponseEntity.ok(UserSummaryResponse.from(user));
    }

    /** 绑定邮箱身份（需求 5）：单次消费 BIND 验证码 → 冲突检查 → 写 email。 */
    @PostMapping("/bind-email")
    public ResponseEntity<UserSummaryResponse> bindEmail(@RequestBody BindEmailRequest request) {
        Long userId = currentUser.requireUserId();
        User user = authService.bindEmail(userId, request.email(), request.code());
        return ResponseEntity.ok(UserSummaryResponse.from(user));
    }

    /** 绑定微信身份（需求 6）：换取 openid → 冲突检查 → 写 wx_openid。 */
    @PostMapping("/bind-wechat")
    public ResponseEntity<UserSummaryResponse> bindWechat(@RequestBody BindWechatRequest request) {
        Long userId = currentUser.requireUserId();
        User user = authService.bindWechat(userId, request.code());
        return ResponseEntity.ok(UserSummaryResponse.from(user));
    }

    /** 解绑指定身份（需求 7）：保底至少一种登录方式。 */
    @PostMapping("/unbind")
    public ResponseEntity<UserSummaryResponse> unbind(@RequestBody UnbindRequest request) {
        Long userId = currentUser.requireUserId();
        User user = authService.unbind(userId, request.type());
        return ResponseEntity.ok(UserSummaryResponse.from(user));
    }

    /**
     * 注销账号（需求 8）：协作牵连检查 → 二次验证 → 单事务级联硬删，成功返回 204。
     *
     * <p>邮箱用户提交 {@code code}（DELETE 用途），纯微信用户提交 {@code wxCode} 重新授权。</p>
     */
    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@RequestBody DeleteAccountRequest request) {
        Long userId = currentUser.requireUserId();
        accountDeletionService.requireDeletable(userId);
        accountDeletionService.verifySecondFactor(userId, request.code(), request.wxCode());
        accountDeletionService.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }
}
