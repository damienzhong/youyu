package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.GrowthEventPageResponse;
import com.damien.youyu.service.GrowthOverviewResponse;
import com.damien.youyu.service.GrowthQueryService;

/**
 * 成长接口 {@code /api/growth}（关联需求 10）。
 *
 * <p>两个端点，均需令牌：</p>
 * <ul>
 *   <li>{@code GET /api/growth}：成长概览——等级、经验、升级进度、累计统计与徽章墙（需求 10.1）。</li>
 *   <li>{@code GET /api/growth/events}：经验明细分页（需求 10.2～10.5）。</li>
 * </ul>
 *
 * <p>本控制器只做<strong>「令牌用户仍存在」的校验与 DTO 组装，不含任何业务判定</strong>：结算触发、
 * 累计聚合、连续天数校正、徽章组装、分页参数解析与校验一律在 {@link GrowthQueryService}。</p>
 *
 * <p><strong>两个端点为什么要自己查一次 {@code users}</strong>（需求 10.6、10.7）：需求把「有效令牌」
 * 定义为「签名有效、未过期，<em>且其标识的用户在 users 表中仍存在</em>」。过滤链
 * （{@link com.damien.youyu.security.JwtAuthenticationFilter}）只验签与验有效期、<strong>不查库</strong>，
 * 所以「令牌合法但用户已注销」这一情形过滤链管不到，会带着一个指向不存在用户的 userId 进到这里。
 * 因此每个端点的第一件事就是 {@link #requireExistingUserId()}，且该校验<strong>先于</strong>结算、
 * 分页参数校验与任何聚合查询——需求 10.7 明确要求 {@code UNAUTHENTICATED} 优先于分页参数错误，
 * 把它写在参数解析之后就会让「已注销用户 + 非法 page」返回 400 而非 401。</p>
 *
 * <p><strong>为什么把 {@code page} / {@code size} 声明为 {@code String} 而非 {@code Integer}</strong>
 * （需求 10.9）：交给框架做类型转换，非数字取值会在进入方法体<em>之前</em>抛出
 * {@code MethodArgumentTypeMismatchException} → {@code PARAM_INVALID}（另一个错误码、另一套字段集），
 * 既绕过上面「令牌用户仍存在」的校验，也违背需求 10.9 的「不可解析与越界同为
 * {@code GROWTH_PAGE_PARAM_INVALID}」。故以原文字符串接收后交由服务层自行解析。</p>
 *
 * <p><strong>概览是本项目唯一的写入型 GET</strong>（内含结算），因此刻意<strong>不加任何 HTTP 缓存头</strong>：
 * 缓存会让「记完账立刻打开成长页看到经验到账」失效，也会掩盖结算的降级返回。</p>
 *
 * <p><strong>{@code SecurityConfig} 明确不改动</strong>：{@code /api/growth/**} 落在
 * {@code anyRequest().authenticated()} 之下，成长接口无公开端点，不存在 invite-system 那种
 * 「permitAll 必须写在前面」的顺序陷阱，无需为它补任何一条多余规则。</p>
 *
 * <p><strong>两个端点都与会话账本无关</strong>：成长数据不按账本过滤，故<strong>不要求也不检查</strong>
 * {@code X-Ledger-Id} 头（需求 10.12）。</p>
 */
@RestController
@RequestMapping("/api/growth")
public class GrowthController {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final GrowthQueryService growthQueryService;

    public GrowthController(
            CurrentUser currentUser,
            UserRepository userRepository,
            GrowthQueryService growthQueryService) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.growthQueryService = growthQueryService;
    }

    /**
     * 成长概览（需令牌）：等级、经验、升级进度、累计统计与 9 枚徽章，字段集恰好 15 项（需求 10.1、10.13）。
     *
     * <p>数据归属只认令牌用户 id，不接受任何指定目标用户的入参（需求 10.8）。结算触发与降级返回全在
     * 服务层，本方法只在<strong>结算之前</strong>确认令牌用户仍存在，随后原样返回服务层组装的响应。</p>
     */
    @GetMapping
    public ResponseEntity<GrowthOverviewResponse> overview() {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(growthQueryService.getOverview(userId));
    }

    /**
     * 经验明细（需令牌）：按 {@code id} 倒序分页 + 不受分页影响的总条数（需求 10.2～10.5）。
     *
     * <p>{@code page} / {@code size} 以<strong>原文字符串</strong>接收后交给服务层解析：见类级
     * Javadoc 中关于「不能让框架做类型转换」的说明。缺失时传 {@code null}，由服务层取缺省值
     * （{@code page}=0、{@code size}=20）。本方法<strong>不触发结算</strong>（需求 10.11）。</p>
     */
    @GetMapping("/events")
    public ResponseEntity<GrowthEventPageResponse> events(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size) {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(growthQueryService.listEvents(userId, page, size));
    }

    /**
     * 取当前会话用户 id，并确认该用户在 {@code users} 表中仍存在（需求 10.6、10.7）。
     *
     * <p>两个端点的第一步都是本方法：它把「令牌合法但用户已注销」也归入 {@code UNAUTHENTICATED}，
     * 补上过滤链不查库留下的缺口，且该校验先于结算、分页参数校验与任何聚合查询。</p>
     */
    private Long requireExistingUserId() {
        Long userId = currentUser.requireUserId();
        userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);
        return userId;
    }
}
