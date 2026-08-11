package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.BudgetReminderGrantRequest;
import com.damien.youyu.service.BudgetReminderPreferenceRequest;
import com.damien.youyu.service.BudgetReminderService;
import com.damien.youyu.service.BudgetReminderStatus;
import com.damien.youyu.service.GrantResponse;

/**
 * 预算提醒接口 {@code /api/budget-reminders}（关联需求 1、6、7）。
 *
 * <p>三个端点，均需令牌，均与会话账本无关（不要求 {@code X-Ledger-Id}，需求 7.4）：</p>
 * <ul>
 *   <li>{@code GET /api/budget-reminders}：本人预算提醒状态 {@code {enabled, remainingQuota}}（需求 1.1、1.2）。</li>
 *   <li>{@code PUT /api/budget-reminders/preference}：更新偏好，返回最新状态（需求 1.3、1.4、1.5）。</li>
 *   <li>{@code POST /api/budget-reminders/quota:grant}：上报订阅授权，返回增加后的剩余订阅次数（需求 6.1~6.4）。</li>
 * </ul>
 *
 * <p>镜像 {@code ReminderController} 的三条取舍：</p>
 * <ol>
 *   <li><b>鉴权先于字段校验</b>（需求 7.2）：每端点首步 {@link #requireExistingUserId()} 把「令牌合法但
 *       用户已注销」也归入 {@code UNAUTHENTICATED}，补上过滤链不查库留下的缺口，且先于任何字段校验。</li>
 *   <li><b>请求体字段以原文（{@code String}）接收</b>：交服务层解析，以精确返回本域错误码
 *       {@code BUDGET_REMINDER_PREF_INVALID} / {@code BUDGET_REMINDER_GRANT_INVALID}，而非让框架类型
 *       转换抢先抛出 {@code REQUEST_BODY_INVALID}。请求体声明为可选（{@code required = false}），
 *       整体缺失与字段缺失得同一结果。</li>
 *   <li><b>数据归属只认令牌用户 id</b>（需求 7.3）：忽略任何指定目标用户身份的查询参数 / 路径参数 /
 *       请求体字段 / 自定义头，且不因携带此类字段而返回错误码。</li>
 * </ol>
 *
 * <p>{@code SecurityConfig} 无需为本控制器补规则：{@code /api/budget-reminders/**} 落在
 * {@code anyRequest().authenticated()} 之下，无公开端点。</p>
 */
@RestController
@RequestMapping("/api/budget-reminders")
public class BudgetReminderController {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final BudgetReminderService budgetReminderService;

    public BudgetReminderController(
            CurrentUser currentUser,
            UserRepository userRepository,
            BudgetReminderService budgetReminderService) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.budgetReminderService = budgetReminderService;
    }

    /** 本人预算提醒状态（需令牌）：{@code {enabled, remainingQuota}}；无记录缺省 {@code {true, 0}}（需求 1.1、1.2）。 */
    @GetMapping
    public ResponseEntity<BudgetReminderStatus> status() {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(budgetReminderService.getStatus(userId));
    }

    /**
     * 更新预算提醒偏好（需令牌）：接收 {@code enabled}（原文），更新后返回最新 {@code {enabled, remainingQuota}}。
     * {@code enabled} 缺失或不可解析为布尔由服务层收敛为 {@code BUDGET_REMINDER_PREF_INVALID}（需求 1.3~1.5）。
     */
    @PutMapping("/preference")
    public ResponseEntity<BudgetReminderStatus> updatePreference(
            @RequestBody(required = false) BudgetReminderPreferenceRequest request) {
        Long userId = requireExistingUserId();
        String enabled = request == null ? null : request.enabled();
        return ResponseEntity.ok(budgetReminderService.updatePreference(userId, enabled));
    }

    /**
     * 上报预算提醒订阅授权（需令牌）：接收 {@code grantedCount}（原文），原子上限累加后返回增加后的剩余订阅次数
     * （需求 6.1~6.4）。{@code grantedCount} 缺失 / 不可解析 / 越界由服务层收敛为 {@code BUDGET_REMINDER_GRANT_INVALID}。
     */
    @PostMapping("/quota:grant")
    public ResponseEntity<GrantResponse> grantQuota(
            @RequestBody(required = false) BudgetReminderGrantRequest request) {
        Long userId = requireExistingUserId();
        String grantedCount = request == null ? null : request.grantedCount();
        int remaining = budgetReminderService.grantQuota(userId, grantedCount);
        return ResponseEntity.ok(new GrantResponse(remaining));
    }

    /**
     * 取当前会话用户 id，并确认该用户在 {@code users} 表中仍存在（需求 7.1、7.2）。
     *
     * <p>三个端点的第一步都是本方法：把「令牌合法但用户已注销」归入 {@code UNAUTHENTICATED}，
     * 且先于任何字段校验，单次请求内至多执行 1 次。</p>
     */
    private Long requireExistingUserId() {
        Long userId = currentUser.requireUserId();
        userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);
        return userId;
    }
}
