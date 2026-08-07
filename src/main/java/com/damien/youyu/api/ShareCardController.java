package com.damien.youyu.api;

import java.time.Clock;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.ShareCardResponse;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.ShareCardQuery;
import com.damien.youyu.service.ShareCardService;
import com.damien.youyu.service.ShareCardType;

/**
 * 分享卡片接口 {@code /api/share-cards}（Share_Card_System，关联需求 1、2、9、10、11、12、13）。
 *
 * <p>单个只读端点 {@code GET /api/share-cards}，按 {@code type} 返回该卡片的数据包
 * （{@link ShareCardResponse}）或不可用标识。纯只读派生、不触发结算、不落库、<strong>不新增任何错误码</strong>
 * （复用既有 {@code UNAUTHENTICATED} / {@code LEDGER_NOT_ACCESSIBLE} / {@code REPORT_PARAM_INVALID}）、不改动任何
 * 既有端点（需求 10.1、10.9、13.1、13.3）。</p>
 *
 * <p><strong>选择新增独立控制器而非扩展 {@code ReportController}</strong>：分享卡片同时涉及账本相关与账本无关
 * 两种语义，且账本无关卡片必须<strong>不读取 {@code X-Ledger-Id}</strong>——而 {@code ReportController} 的每个
 * 端点都无条件 {@code currentLedger.requireLedgerId()}，语义不符。独立控制器可按卡片类型分派账本解析，边界更
 * 清晰（需求 1.7、10.4）。</p>
 *
 * <h2>固定错误优先级顺序（需求 10.6 与 1.7）</h2>
 *
 * <p>需求 10.6 要求单请求多错误时按「鉴权（{@code UNAUTHENTICATED}）→ 账本（{@code LEDGER_NOT_ACCESSIBLE}）→
 * 参数（{@code REPORT_PARAM_INVALID}）」返回最高优先级；需求 1.7 又要求账本无关卡片<strong>绝不因
 * {@code X-Ledger-Id} 缺失或不可访问被拒绝</strong>。二者只有一种自洽实现：{@code cardType} 是决定账本语义的
 * 路由判别式，必须<strong>先于</strong>账本解析被识别。故本控制器固定顺序为：</p>
 * <ol>
 *   <li><strong>鉴权</strong>：{@link #requireExistingUserId()}（{@code currentUser.requireUserId()} + {@code users}
 *       存在校验）→ {@code UNAUTHENTICATED}（最高优先级，与 {@code GrowthController}/{@code StreakController} 同构，
 *       需求 10.3）。</li>
 *   <li><strong>{@code cardType} 路由校验</strong>：{@link ShareCardType#parse(String)}，非 6 种取值之一 →
 *       {@code REPORT_PARAM_INVALID}（此时卡片账本语义未定义，账本不可访问条件无从评估，故不进入账本解析，
 *       需求 10.5）。</li>
 *   <li><strong>账本解析（仅账本相关卡片）</strong>：{@code MONTHLY_SUMMARY}/{@code ANNUAL_BILL}/
 *       {@code BUDGET_ACHIEVED} 调用 {@code currentLedger.requireLedgerId()} → 不可访问抛
 *       {@code LEDGER_NOT_ACCESSIBLE}、无头回退默认账本；账本无关卡片跳过此步、<strong>完全不读取
 *       {@code X-Ledger-Id}</strong>（需求 1.7、10.4）。</li>
 *   <li><strong>周期/标识参数校验</strong>：{@link ShareCardQuery#of} 解析当前 {@code type} 相关的可选参数、
 *       忽略无关参数，账本相关卡片的 {@code month}（{@code YYYY-MM}）/ {@code year}（{@code YYYY}）非法 →
 *       {@code REPORT_PARAM_INVALID}（在账本之后，满足需求 10.6 的账本 → 参数次序，需求 4.7、5.7、7.6、10.7）。</li>
 * </ol>
 *
 * <p><strong>不接受任何指定他人身份的入参</strong>：方法签名无目标用户入参，请求中任何用于指定目标用户身份的
 * 查询参数、路径参数、请求体字段与自定义请求头一律被忽略，且<strong>不因携带此类字段而返回错误</strong>
 * （需求 10.7）。数据归属只认令牌用户 id 与（账本相关卡片的）当前账本。</p>
 */
@RestController
@RequestMapping("/api/share-cards")
public class ShareCardController {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final CurrentLedger currentLedger;
    private final ShareCardService shareCardService;
    private final Clock clock;

    public ShareCardController(
            CurrentUser currentUser,
            UserRepository userRepository,
            CurrentLedger currentLedger,
            ShareCardService shareCardService,
            Clock clock) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.currentLedger = currentLedger;
        this.shareCardService = shareCardService;
        this.clock = clock;
    }

    /**
     * 分享卡片数据包（需令牌）。按 {@code type} 返回该卡片的数据包或不可用标识（需求 10.2）。
     *
     * <p>固定错误优先级：鉴权 → {@code cardType} 路由 → 账本（仅账本相关卡片）→ 周期参数（见类级 Javadoc，
     * 需求 10.6、1.7）。返回 {@code ResponseEntity.ok(...)}；卡片可用/不可用两态均以 200 承载于响应体，唯有
     * 鉴权/账本/参数错误以对应错误码短路（需求 10.8）。</p>
     *
     * @param type      卡片类型（必填）：{@code STREAK_MILESTONE} / {@code MONTHLY_SUMMARY} / {@code ANNUAL_BILL} /
     *                  {@code ACHIEVEMENT_BADGE} / {@code BUDGET_ACHIEVED} / {@code LEVEL_UP}（区分大小写）
     * @param month     目标月 {@code YYYY-MM}（{@code MONTHLY_SUMMARY} / {@code BUDGET_ACHIEVED} 可选，缺省当前自然月）
     * @param year      目标年 {@code YYYY}（{@code ANNUAL_BILL} 可选，缺省当前自然年）
     * @param code      成就编码（{@code ACHIEVEMENT_BADGE} 可选，缺省取最近解锁）
     * @param milestone 里程碑天数（{@code STREAK_MILESTONE} 可选，缺省取已达成最高里程碑）
     * @return 该卡片的数据包（可用/不可用两态，200）
     */
    @GetMapping
    public ResponseEntity<ShareCardResponse> card(
            @RequestParam("type") String type,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "year", required = false) String year,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "milestone", required = false) String milestone) {

        // ① 鉴权（最高优先级）：令牌用户仍存在（需求 10.3、10.6）。
        Long userId = requireExistingUserId();

        // ② cardType 路由校验：非 6 种 → REPORT_PARAM_INVALID（需求 10.5）。
        ShareCardType cardType = ShareCardType.parse(type);

        // ③ 账本解析：仅账本相关卡片；账本无关卡片完全不读取 X-Ledger-Id（需求 1.7、10.4）。
        Long ledgerId = cardType.isLedgerScoped() ? currentLedger.requireLedgerId() : null;

        // ④ 周期/标识参数校验（在账本之后，满足需求 10.6 的账本 → 参数次序，需求 4.7、5.7、7.6、10.7）。
        ShareCardQuery query = ShareCardQuery.of(cardType, month, year, code, milestone, clock);

        return ResponseEntity.ok(shareCardService.card(userId, ledgerId, query));
    }

    /**
     * 取当前会话用户 id，并确认该用户在 {@code users} 表中仍存在（需求 10.3、10.6）。
     *
     * <p>与 {@code GrowthController}/{@code StreakController} 同构：把「令牌合法但用户已注销」也归入
     * {@code UNAUTHENTICATED}，补上过滤链只验签不查库留下的缺口，且该校验先于 {@code cardType} 路由、
     * 账本解析与参数校验，单次请求内至多执行 1 次。</p>
     */
    private Long requireExistingUserId() {
        Long userId = currentUser.requireUserId();
        userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);
        return userId;
    }
}
