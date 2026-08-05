package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.StreakOverviewResponse;
import com.damien.youyu.service.StreakQueryService;
import com.damien.youyu.service.StreakSegmentPageResponse;

/**
 * 连续记账接口 {@code /api/streak}（关联需求 6）。
 *
 * <p>两个端点，均需令牌：</p>
 * <ul>
 *   <li>{@code GET /api/streak}：连续记账概览——今日打卡、当前连续、历史最长、里程碑进度等，
 *       字段集恰好 14 项（需求 6.1）。</li>
 *   <li>{@code GET /api/streak/segments}：历史连续区间分页（需求 6.2～6.5）。</li>
 * </ul>
 *
 * <p>本控制器只做<strong>「令牌用户仍存在」的校验与 DTO 转发，不含任何业务判定</strong>：结算触发、
 * 段聚合、判定换算、里程碑换算、分页参数解析与校验一律在 {@link StreakQueryService}。</p>
 *
 * <p><strong>两个端点为什么要自己查一次 {@code users}</strong>（需求 6.8、6.9）：需求把「有效令牌」
 * 定义为「签名有效、未过期，<em>且其标识的用户在 users 表中仍存在</em>」。过滤链
 * （{@link com.damien.youyu.security.JwtAuthenticationFilter}）只验签与验有效期、<strong>不查库</strong>，
 * 所以「令牌合法但用户已注销」这一情形过滤链管不到，会带着一个指向不存在用户的 userId 进到这里。
 * 因此每个端点的第一件事就是 {@link #requireExistingUserId()}，且该校验<strong>先于</strong>结算、
 * 分页参数校验与任何聚合查询——需求 6.8 明确要求 {@code UNAUTHENTICATED} 优先于任何字段校验，
 * 把它写在参数解析之后就会让「已注销用户 + 非法 page」返回 400 而非 401。该校验在单次请求内至多执行 1 次。</p>
 *
 * <p><strong>为什么把 {@code page} / {@code size} 声明为 {@code String} 而非 {@code Integer}</strong>
 * （需求 6.12）：交给框架做类型转换，非数字取值会在进入方法体<em>之前</em>抛出
 * {@code MethodArgumentTypeMismatchException} → {@code PARAM_INVALID}（另一个错误码、另一套字段集），
 * 既绕过上面「令牌用户仍存在」的校验，也违背需求 6.12 的「不可解析与越界同为
 * {@code STREAK_PAGE_PARAM_INVALID}」。故以原文字符串接收后交由服务层自行解析。</p>
 *
 * <p><strong>概览是写入型 GET</strong>（内含结算），因此刻意<strong>不加任何 HTTP 缓存头</strong>：
 * 缓存会让「记完账立刻打开连续记账页看到今日已打卡」失效，也会掩盖结算的降级返回。</p>
 *
 * <p><strong>{@code SecurityConfig} 明确不改动</strong>：{@code /api/streak/**} 落在
 * {@code anyRequest().authenticated()} 之下，连续记账接口无公开端点，不存在 invite-system 那种
 * 「permitAll 必须写在前面」的顺序陷阱，无需为它补任何一条多余规则。</p>
 *
 * <p><strong>两个端点都与会话账本无关</strong>：连续记账数据不按账本过滤，故<strong>不要求也不检查</strong>
 * {@code X-Ledger-Id} 头（需求 6.11）。数据归属只认令牌用户 id，请求中任何用于指定目标用户身份的
 * 查询参数、路径参数、请求体字段与自定义请求头一律忽略，且<strong>不因携带此类字段而返回错误码</strong>
 * （需求 6.10、6.16）。</p>
 */
@RestController
@RequestMapping("/api/streak")
public class StreakController {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final StreakQueryService streakQueryService;

    public StreakController(
            CurrentUser currentUser,
            UserRepository userRepository,
            StreakQueryService streakQueryService) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.streakQueryService = streakQueryService;
    }

    /**
     * 连续记账概览（需令牌）：今日打卡、当前连续、历史最长、里程碑进度等，字段集恰好 14 项（需求 6.1）。
     *
     * <p>数据归属只认令牌用户 id，不接受任何指定目标用户的入参（需求 6.10、6.16）。结算触发与降级返回
     * 全在服务层，本方法只在<strong>结算之前</strong>确认令牌用户仍存在，随后原样返回服务层组装的响应。</p>
     */
    @GetMapping
    public ResponseEntity<StreakOverviewResponse> overview() {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(streakQueryService.getOverview(userId));
    }

    /**
     * 历史连续区间（需令牌）：按起始日倒序分页 + 不受分页影响的总条数（需求 6.2～6.5）。
     *
     * <p>{@code page} / {@code size} 以<strong>原文字符串</strong>接收后交给服务层解析：见类级
     * Javadoc 中关于「不能让框架做类型转换」的说明。缺失时传 {@code null}，由服务层取缺省值
     * （{@code page}=0、{@code size}=20）。本方法<strong>不触发结算</strong>（需求 6.6）。</p>
     */
    @GetMapping("/segments")
    public ResponseEntity<StreakSegmentPageResponse> segments(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size) {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(streakQueryService.listSegments(userId, page, size));
    }

    /**
     * 取当前会话用户 id，并确认该用户在 {@code users} 表中仍存在（需求 6.8、6.9）。
     *
     * <p>两个端点的第一步都是本方法：它把「令牌合法但用户已注销」也归入 {@code UNAUTHENTICATED}，
     * 补上过滤链不查库留下的缺口，且该校验先于结算、分页参数校验与任何聚合查询，单次请求内至多执行 1 次。</p>
     */
    private Long requireExistingUserId() {
        Long userId = currentUser.requireUserId();
        userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);
        return userId;
    }
}
