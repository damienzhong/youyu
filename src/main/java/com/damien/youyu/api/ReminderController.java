package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.GrantResponse;
import com.damien.youyu.service.ReminderCreateRequest;
import com.damien.youyu.service.ReminderGrantRequest;
import com.damien.youyu.service.ReminderItem;
import com.damien.youyu.service.ReminderListResponse;
import com.damien.youyu.service.ReminderService;
import com.damien.youyu.service.ReminderUpdateRequest;

/**
 * 自定义提醒接口 {@code /api/reminders}（关联需求 1、5、7、8）。
 *
 * <p>五个端点，均需令牌，均与会话账本无关（不要求 {@code X-Ledger-Id}，需求 8.5）：</p>
 * <ul>
 *   <li>{@code GET /api/reminders}：本人提醒列表 + 剩余订阅次数（需求 5.7、7.1、7.2）。</li>
 *   <li>{@code POST /api/reminders}：创建提醒，返回 4 字段（需求 1）。</li>
 *   <li>{@code PUT /api/reminders/{reminderId}}：部分更新提醒（需求 7.3、7.4、7.8）。</li>
 *   <li>{@code DELETE /api/reminders/{reminderId}}：删除提醒，返回 204 无体（需求 7.6）。</li>
 *   <li>{@code POST /api/reminders/quota:grant}：上报订阅授权（需求 5.1、5.2）。</li>
 * </ul>
 *
 * <p>本控制器只做<strong>「令牌用户仍存在」的校验与 DTO 转发，不含任何业务判定</strong>：
 * 校验优先级、时间/频率解析、去重、上限、额度累加、归属统一 {@code NOT_FOUND} 全在
 * {@link ReminderService}（链路 A）。定时触发与发送属链路 B，不经本控制器。</p>
 *
 * <p><strong>每个端点为什么要自己查一次 {@code users}</strong>（需求 8.1、8.2）：需求把「有效令牌」
 * 定义为「签名有效、未过期，<em>且其标识的用户在 users 表中仍存在</em>」。过滤链
 * （{@link com.damien.youyu.security.JwtAuthenticationFilter}）只验签与验有效期、<strong>不查库</strong>，
 * 所以「令牌合法但用户已注销」这个缺口只能在这里补。因此每个端点第一步都是
 * {@link #requireExistingUserId()}，且该校验<strong>先于</strong>任何字段校验——需求 8.2 明确要求
 * {@code UNAUTHENTICATED} 优先于任何字段与业务校验。</p>
 *
 * <p><strong>请求体的 {@code frequency} / {@code remindTime} / {@code grantedCount} 一律以原文
 * （{@code String}）接收</strong>（需求 8.1 之实现取舍）：交给框架做类型转换会把「取值非法」提前变成
 * {@code REQUEST_BODY_INVALID}（另一错误码、另一套字段集），既绕过上面的鉴权校验，也让服务层无法
 * 精确返回本域的五个错误码。故解析与校验全部落在服务层，见各请求 DTO 的 Javadoc。</p>
 *
 * <p><strong>数据归属只认令牌用户 id</strong>（需求 8.3、8.4）：五个端点的方法签名与服务层调用只透传
 * 令牌用户 id 与请求体业务字段，任何用于指定目标用户身份的查询参数、路径参数、请求体字段与自定义
 * 请求头一律被忽略，且<strong>不因携带此类字段而返回错误码</strong>。</p>
 *
 * <p><strong>{@code SecurityConfig} 无需为本控制器补规则</strong>：{@code /api/reminders/**} 落在
 * {@code anyRequest().authenticated()} 之下，提醒接口无公开端点。</p>
 */
@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final ReminderService reminderService;

    public ReminderController(
            CurrentUser currentUser,
            UserRepository userRepository,
            ReminderService reminderService) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.reminderService = reminderService;
    }

    /**
     * 本人提醒列表 + 剩余订阅次数（需令牌）：仅本人提醒，按 {@code created_at} 升序，每项 4 字段；
     * 无提醒时为空列表；剩余订阅次数无记录时为 0（需求 5.7、7.1、7.2）。
     */
    @GetMapping
    public ResponseEntity<ReminderListResponse> list() {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(reminderService.list(userId));
    }

    /**
     * 创建提醒（需令牌）：{@code frequency} 与 {@code remindTime} 必填、{@code enabled} 可选（缺省真）；
     * 返回新建提醒的 4 字段。校验优先级与解析全在服务层（需求 1）。
     *
     * <p>请求体声明为可选（{@code required = false}）：整个体缺失与体内字段缺失应得同一结果，
     * 故此处把 {@code null} 原样交给服务层，由 {@link ReminderService#create} 统一收敛为
     * {@code REMINDER_FREQUENCY_INVALID}（频率优先级最高），而非让框架抛
     * {@code HttpMessageNotReadableException} 得到另一个错误码并跳过鉴权校验。</p>
     */
    @PostMapping
    public ResponseEntity<ReminderItem> create(
            @RequestBody(required = false) ReminderCreateRequest request) {
        Long userId = requireExistingUserId();
        String frequency = request == null ? null : request.frequency();
        String remindTime = request == null ? null : request.remindTime();
        Boolean enabled = request == null ? null : request.enabled();
        return ResponseEntity.ok(reminderService.create(userId, frequency, remindTime, enabled));
    }

    /**
     * 更新提醒（需令牌）：三字段均可选，只保存提交字段、未提交字段保持原值；返回更新后的 4 字段。
     * 目标不存在或不属于本人 → 统一 {@code NOT_FOUND}（需求 7.3、7.4、7.5、7.8、8.8）。
     */
    @PutMapping("/{reminderId}")
    public ResponseEntity<ReminderItem> update(
            @PathVariable Long reminderId,
            @RequestBody(required = false) ReminderUpdateRequest request) {
        Long userId = requireExistingUserId();
        String frequency = request == null ? null : request.frequency();
        String remindTime = request == null ? null : request.remindTime();
        Boolean enabled = request == null ? null : request.enabled();
        return ResponseEntity.ok(reminderService.update(userId, reminderId, frequency, remindTime, enabled));
    }

    /**
     * 删除提醒（需令牌）：删除本人该提醒配置行，返回 204 无体；不删除历史发送记录（需求 7.6）。
     * 目标不存在或不属于本人 → 统一 {@code NOT_FOUND}（需求 7.5、8.8）。
     */
    @DeleteMapping("/{reminderId}")
    public ResponseEntity<Void> delete(@PathVariable Long reminderId) {
        Long userId = requireExistingUserId();
        reminderService.delete(userId, reminderId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 上报订阅授权（需令牌）：接收 {@code grantedCount}（原文），原子上限累加后返回增加后的剩余订阅次数
     * （需求 5.1、5.2、5.3、5.4）。{@code grantedCount} 缺失 / 不可解析 / 越界由服务层收敛为
     * {@code REMINDER_GRANT_INVALID}。
     */
    @PostMapping("/quota:grant")
    public ResponseEntity<GrantResponse> grantQuota(
            @RequestBody(required = false) ReminderGrantRequest request) {
        Long userId = requireExistingUserId();
        String grantedCount = request == null ? null : request.grantedCount();
        int remaining = reminderService.grantQuota(userId, grantedCount);
        return ResponseEntity.ok(new GrantResponse(remaining));
    }

    /**
     * 取当前会话用户 id，并确认该用户在 {@code users} 表中仍存在（需求 8.1、8.2）。
     *
     * <p>五个端点的第一步都是本方法：它把「令牌合法但用户已注销」也归入 {@code UNAUTHENTICATED}，
     * 补上过滤链不查库留下的缺口，且该校验先于任何字段与业务校验，单次请求内至多执行 1 次。</p>
     */
    private Long requireExistingUserId() {
        Long userId = currentUser.requireUserId();
        userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);
        return userId;
    }
}
