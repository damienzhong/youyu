package com.damien.youyu.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.AaSettlementCreateRequest;
import com.damien.youyu.api.dto.AaSettlementRecordResponse;
import com.damien.youyu.api.dto.AaSettlementResponse;
import com.damien.youyu.domain.AaSettlement;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.aa.AaSettlementService;

/**
 * AA 账本净额 / 清算视图接口（关联需求 5.2、5.4、5.5）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权，当前用户由 {@link CurrentUser} 解析。账本由<b>路径参数</b>
 * {@code ledgerId} 指定（与 design.md「GET /api/aa/{ledgerId}/settlement」一致）；成员校验与越权
 * NOT_FOUND（需求 9.4）下沉到 {@link AaSettlementService}，本控制器仅负责请求 / 响应装配。</p>
 *
 * <ul>
 *   <li>GET {@code /api/aa/{ledgerId}/settlement}：每人净额（应收正 / 应付负）+ 最少转账建议（派生、只读）。</li>
 *   <li>POST {@code /api/aa/settlements}：结清一条涉及本人的转账（本人侧账户增减 + 落结算 + 展示流水，
 *       201）；账本按请求头 {@code X-Ledger-Id} 隔离（{@link CurrentLedger}），只读账本拒写
 *       {@code AA_LEDGER_ARCHIVED}、金额 / 对象非法 {@code AA_SETTLEMENT_INVALID}（需求 6.1-6.4、6.6、9.5）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/aa")
public class AaSettlementController {

    private final AaSettlementService aaSettlementService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public AaSettlementController(AaSettlementService aaSettlementService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.aaSettlementService = aaSettlementService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 结算视图：返回每人净额与建议转账（派生，不落库）。 */
    @GetMapping("/{ledgerId}/settlement")
    public ResponseEntity<AaSettlementResponse> settlement(@PathVariable Long ledgerId) {
        Long userId = currentUser.requireUserId();
        return ResponseEntity.ok(aaSettlementService.settlement(userId, ledgerId));
    }

    /**
     * 结清一条涉及本人的转账：本人侧账户增减 + 落 {@code aa_settlements} + 生成展示流水，成功返回 201
     * 与该结算记录（需求 6.1-6.4、6.6）。账本按 {@code X-Ledger-Id} 隔离；校验与账户操作下沉服务层。
     */
    @PostMapping("/settlements")
    public ResponseEntity<AaSettlementRecordResponse> settle(@RequestBody AaSettlementCreateRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        AaSettlement settlement = aaSettlementService.settle(
                userId, ledgerId, req.toUserId(), req.fromUserId(), req.amount(), req.myAccountId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AaSettlementRecordResponse.from(settlement));
    }

    /**
     * 撤销一条结算：软撤销 + 回滚本人侧账户增减 + 作废展示流水，成功返回 200 与该结算记录（需求 6.5）。
     * 账本按 {@code X-Ledger-Id} 隔离；只读账本拒写 {@code AA_LEDGER_ARCHIVED}、非结清人无权
     * {@code LEDGER_FORBIDDEN}、已撤销 {@code AA_SETTLEMENT_INVALID}，校验与账户操作下沉服务层。
     */
    @PostMapping("/settlements/{id}/revert")
    public ResponseEntity<AaSettlementRecordResponse> revert(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        AaSettlement settlement = aaSettlementService.revert(userId, ledgerId, id);
        return ResponseEntity.ok(AaSettlementRecordResponse.from(settlement));
    }
}
