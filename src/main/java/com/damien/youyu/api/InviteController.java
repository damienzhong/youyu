package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.InviteInfoResponse;
import com.damien.youyu.api.dto.InviteQrCodeResponse;
import com.damien.youyu.api.dto.InviteeListResponse;
import com.damien.youyu.api.dto.InviterBriefResponse;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.InviteQrCodeService;
import com.damien.youyu.service.InviteService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 邀请接口 {@code /api/invite}（关联需求 8.1～8.4）。
 *
 * <p>四个端点，前三个需令牌、最后一个公开：</p>
 * <ul>
 *   <li>{@code GET /api/invite}：邀请码 + 邀请链接 + 已邀请人数（需求 1.10、2.1）。</li>
 *   <li>{@code GET /api/invite/qrcode}：小程序码 PNG 的 base64（需求 3.1）。</li>
 *   <li>{@code GET /api/invite/invitees}：被邀请人列表分页（需求 7）。</li>
 *   <li>{@code GET /api/invite/inviter}：<strong>公开</strong>查询邀请人昵称（需求 4.2、8.4）。</li>
 * </ul>
 *
 * <p>本控制器只做参数解析与 DTO 组装，<strong>不含任何业务判定</strong>：邀请码惰性补齐、统计口径、
 * 分页参数校验、限流、缓存与微信调用一律在服务层。公开/受保护的边界由 {@code SecurityConfig} 落地。</p>
 *
 * <p><strong>三个受保护端点为什么要自己查一次 {@code users}</strong>（需求 8.1、8.2）：需求把「有效
 * 令牌」定义为「签名有效、未过期，<em>且其标识的用户在 users 表中仍存在</em>」。过滤链
 * （{@link com.damien.youyu.security.JwtAuthenticationFilter}）只验签与验有效期、<strong>不查库</strong>，
 * 所以「令牌合法但用户已注销」这一情形过滤链管不到，会带着一个指向不存在用户的 userId 进到这里。
 * 因此每个受保护端点的第一件事就是 {@code findById(...).orElseThrow(unauthenticated)}，
 * 且该校验<strong>先于</strong>任何字段校验与限流判定——需求 8.2 明确要求 {@code UNAUTHENTICATED}
 * 优先于分页参数错误与限流错误，把它写在参数校验之后就会让「已注销用户 + 非法 page」返回 400。
 * 这也是把 {@code page} / {@code size} 声明为 {@code String} 而非 {@code Integer} 的原因之一：
 * 交给框架做类型转换，非数字取值会在进入方法体之前就抛出转换异常（另一个错误码、另一套字段集），
 * 既绕过了上述鉴权校验，也违背需求 7.9 的「不可解析与越界同为 {@code INVITE_PAGE_PARAM_INVALID}」。</p>
 *
 * <p><strong>公开端点为什么不碰 {@link CurrentUser}</strong>（需求 8.4）：携带无效或已过期令牌访问
 * {@code /api/invite/inviter} 必须忽略该令牌、按匿名请求正常返回 200，绝不能返回
 * {@code UNAUTHENTICATED}。所以该方法既不取 principal、也不查用户，只从查询参数取邀请码、
 * 从 {@link ClientIpResolver} 取限流键。</p>
 */
@RestController
@RequestMapping("/api/invite")
public class InviteController {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final InviteService inviteService;
    private final InviteQrCodeService inviteQrCodeService;

    public InviteController(
            CurrentUser currentUser,
            UserRepository userRepository,
            InviteService inviteService,
            InviteQrCodeService inviteQrCodeService) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.inviteService = inviteService;
        this.inviteQrCodeService = inviteQrCodeService;
    }

    /**
     * 邀请信息（需令牌）：邀请码（为空时服务层惰性补齐）、邀请链接与已邀请人数，字段是且仅是三个。
     *
     * <p>数据归属只认令牌用户 id，不接受任何指定目标用户的入参（需求 8.3）。</p>
     */
    @GetMapping
    public ResponseEntity<InviteInfoResponse> inviteInfo() {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(InviteInfoResponse.from(inviteService.getInviteInfo(userId)));
    }

    /**
     * 邀请二维码（需令牌）：返回不含 {@code data:image/png;base64,} 前缀的 base64（需求 3.1）。
     *
     * <p>缓存命中判定、24 小时未命中额度与微信调用全在 {@link InviteQrCodeService} 内，
     * 本方法不参与其中任何判定。</p>
     */
    @GetMapping("/qrcode")
    public ResponseEntity<InviteQrCodeResponse> qrCode() {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(InviteQrCodeResponse.of(inviteQrCodeService.getQrCodeBase64(userId)));
    }

    /**
     * 被邀请人列表（需令牌）：分页 + 两个口径不同的计数（需求 7）。
     *
     * <p>{@code page} / {@code size} 以<strong>原文字符串</strong>接收后交给服务层解析：见类级
     * Javadoc 中关于「不能让框架做类型转换」的说明。缺失时传 {@code null}，由服务层取缺省值
     * （{@code page}=0、{@code size}=20）。</p>
     */
    @GetMapping("/invitees")
    public ResponseEntity<InviteeListResponse> invitees(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size) {
        Long userId = requireExistingUserId();
        return ResponseEntity.ok(InviteeListResponse.from(inviteService.listInvitees(userId, page, size)));
    }

    /**
     * 邀请人展示信息（<strong>公开</strong>，无需令牌）：成功响应有且仅有 {@code nickname} 一个字段。
     *
     * <p>{@code code} 声明为可选：缺失时以 {@code null} 交给服务层，与「格式非法」「查不到」一样
     * 收敛为同一个 {@code NOT_FOUND}（需求 8.9），避免「缺参数」变成另一套可区分的响应。</p>
     *
     * <p>限流键取 {@code X-Forwarded-For} <strong>末位</strong>（需求 8.6，理由见
     * {@link ClientIpResolver}）；限流判定本身在服务层，且先于规整与查库。</p>
     */
    @GetMapping("/inviter")
    public ResponseEntity<InviterBriefResponse> inviterBrief(
            @RequestParam(name = "code", required = false) String code,
            HttpServletRequest request) {
        String clientIp = ClientIpResolver.resolveClientIp(request);
        return ResponseEntity.ok(InviterBriefResponse.of(inviteService.findInviterNickname(code, clientIp)));
    }

    /**
     * 取当前会话用户 id，并确认该用户在 {@code users} 表中仍存在（需求 8.1、8.2）。
     *
     * <p>三个受保护端点的第一步都是本方法：它把「令牌合法但用户已注销」也归入
     * {@code UNAUTHENTICATED}，补上过滤链不查库留下的缺口。</p>
     */
    private Long requireExistingUserId() {
        Long userId = currentUser.requireUserId();
        userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);
        return userId;
    }
}
