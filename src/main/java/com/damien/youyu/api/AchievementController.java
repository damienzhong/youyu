package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.AchievementAckRequest;
import com.damien.youyu.service.AchievementAckResponse;
import com.damien.youyu.service.AchievementListResponse;
import com.damien.youyu.service.AchievementQueryService;
import com.damien.youyu.service.PendingAchievementResponse;

/**
 * 成就接口 {@code /api/achievements}（关联需求 6）。
 *
 * <p>三个端点，均需令牌：</p>
 * <ul>
 *   <li>{@code GET /api/achievements}：成就清单——16 项成就视图、已解锁成就数与成就总数；
 *       返回前触发一次结算（复用概览侧 10 秒节流，需求 6.1～6.7）。</li>
 *   <li>{@code GET /api/achievements/pending}：待播报成就，<strong>只读、不触发结算</strong>（需求 5.4、5.5）。</li>
 *   <li>{@code POST /api/achievements/notices/ack}：推进播报游标（需求 5.6～5.13）。</li>
 * </ul>
 *
 * <p>本控制器只做<strong>「令牌用户仍存在」的校验与 DTO 转发，不含任何业务判定</strong>：结算触发与
 * 降级、八个统计口径的求值、当前值钳制、待播报截断与总条数、{@code lastEventId} 的解析与上界校验
 * 一律在 {@link AchievementQueryService}。</p>
 *
 * <p><strong>三个端点为什么要自己查一次 {@code users}</strong>（需求 6.8、6.9）：需求把「有效令牌」
 * 定义为「签名有效、未过期，<em>且其标识的用户在 users 表中仍存在</em>」。过滤链
 * （{@link com.damien.youyu.security.JwtAuthenticationFilter}）只验签与验有效期、<strong>不查库</strong>，
 * 所以「令牌合法但用户已注销」这个缺口过滤链管不到——它会带着一个指向不存在用户的 userId 进到这里，
 * <strong>只能在这里补</strong>。因此每个端点的第一件事就是 {@link #requireExistingUserId()}，且该校验
 * <strong>先于</strong>结算、入参校验与任何聚合查询，单次请求内至多执行 1 次（需求 6.9）。
 * 需求 6.8 明确要求 {@code UNAUTHENTICATED} 优先于任何字段校验：把它写在入参校验之后，
 * 「已注销用户 + {@code lastEventId} 为 {@code "abc"}」就会返回 400 而非 401。</p>
 *
 * <p><strong>数据归属只认令牌用户 id</strong>（需求 6.10、6.17）：三个端点的方法签名里没有任何目标用户
 * 参数，因此请求携带的查询参数、路径参数、请求体字段与自定义请求头中任何用于指定他人身份的取值
 * 一律被忽略；同时<strong>不因携带此类字段而返回错误码</strong>——多余的入参既不参与判定，也不触发校验。</p>
 *
 * <p><strong>三个端点都与会话账本无关</strong>：成就数据不按账本过滤，故<strong>不要求也不检查</strong>
 * {@code X-Ledger-Id} 头，缺失或取值不可访问都不影响结果（需求 6.11）。</p>
 *
 * <p><strong>{@code SecurityConfig} 明确不改动</strong>：{@code /api/achievements/**} 落在
 * {@code anyRequest().authenticated()} 之下，成就接口无公开端点，不存在 invite-system 那种
 * 「permitAll 必须写在前面」的顺序陷阱，无需为它补任何一条多余规则。</p>
 *
 * <p><strong>成就清单是写入型 GET</strong>（内含结算，与成长概览同构），因此刻意<strong>不加任何
 * HTTP 缓存头</strong>：缓存会让「记完账立刻打开成就页看到新解锁」失效，也会掩盖结算的降级返回。</p>
 */
@RestController
@RequestMapping("/api/achievements")
public class AchievementController {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final AchievementQueryService achievementQueryService;

    public AchievementController(
            CurrentUser currentUser,
            UserRepository userRepository,
            AchievementQueryService achievementQueryService) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.achievementQueryService = achievementQueryService;
    }

    /**
     * 成就清单（需令牌）：顶层恰好 3 项，成就视图列表恒 16 项、成就总数恒 16（需求 6.1、6.2）。
     *
     * <p>返回前由服务层触发一次结算（复用概览侧节流器），结算失败或被节流时字段集与成功时完全相同
     * （需求 6.6、6.7）。本方法只在<strong>结算之前</strong>确认令牌用户仍存在，随后原样返回服务层
     * 组装的响应，不加任何缓存头。</p>
     */
    @GetMapping
    public ResponseEntity<AchievementListResponse> achievements() {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(achievementQueryService.getAchievements(userId));
    }

    /**
     * 待播报成就（需令牌）：顶层恰好 2 项，项数 ≤10、总条数为<strong>截断前</strong>的全部条数（需求 5.4、5.5）。
     *
     * <p><strong>只读</strong>：不触发结算、不推进游标、不向 {@code growth_events} 与 {@code user_growth}
     * 写任何语句（需求 5.14、5.16）。</p>
     */
    @GetMapping("/pending")
    public ResponseEntity<PendingAchievementResponse> pending() {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(achievementQueryService.getPending(userId));
    }

    /**
     * 推进播报游标（需令牌）：返回推进后的游标取值，顶层恰好 1 项（需求 5.6、5.7）。
     *
     * <p>{@code lastEventId} 以<strong>原文字符串</strong>接收后交给服务层解析与校验：理由见
     * {@link AchievementAckRequest} 的 Javadoc（不能让 Jackson 替我们做类型转换，否则
     * {@code "abc"} 会在进入方法体之前变成另一个错误码，既绕过上面的鉴权校验也违背需求 5.12）。</p>
     *
     * <p>请求体声明为<strong>可选</strong>（{@code required = false}）：整个体缺失与体内
     * {@code lastEventId} 缺失应得同一个结果，故此处把 {@code null} 原样交给服务层，由它统一收敛为
     * {@code ACHIEVEMENT_ACK_PARAM_INVALID}（需求 5.12）；若让框架在方法体之前抛
     * {@code HttpMessageNotReadableException}，就会得到另一个错误码、另一套字段集，并且跳过
     * 「令牌用户仍存在」校验。</p>
     */
    @PostMapping("/notices/ack")
    public ResponseEntity<AchievementAckResponse> ackNotices(
            @RequestBody(required = false) AchievementAckRequest request) {
        Long userId = requireExistingUserId();
        String rawLastEventId = request == null ? null : request.lastEventId();
        return ResponseEntity.ok(achievementQueryService.ack(userId, rawLastEventId));
    }

    /**
     * 取当前会话用户 id，并确认该用户在 {@code users} 表中仍存在（需求 6.8、6.9）。
     *
     * <p>三个端点的第一步都是本方法：它把「令牌合法但用户已注销」也归入 {@code UNAUTHENTICATED}，
     * 补上过滤链不查库留下的缺口，且该校验先于结算、入参校验与任何聚合查询，单次请求内只执行一次。</p>
     */
    private Long requireExistingUserId() {
        Long userId = currentUser.requireUserId();
        userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);
        return userId;
    }
}
