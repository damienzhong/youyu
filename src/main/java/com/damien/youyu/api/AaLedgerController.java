package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.AaOverviewResponse;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.aa.AaLedgerService;

/**
 * AA 账本账本级视图接口（关联需求 2.1、4.4、5.1、7.1、7.2、8.1、9.4）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权，当前用户由 {@link CurrentUser} 解析。账本由<b>路径参数</b>
 * {@code ledgerId} 指定（与 design.md「GET /api/aa/{ledgerId}/overview」一致，风格对齐
 * {@link AaSettlementController} 的结算视图）；成员校验与越权 NOT_FOUND（需求 9.4）、归档账本仍可查看
 * （需求 8.3）下沉到 {@link AaLedgerService}，本控制器仅负责请求 / 响应装配。</p>
 *
 * <ul>
 *   <li>GET {@code /api/aa/{ledgerId}/overview}：首页 hero 三口径（账户已支出 / 我的消费 / 待收回）
 *       + 每人净额 + 流水（派生、只读）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/aa")
public class AaLedgerController {

    private final AaLedgerService aaLedgerService;
    private final CurrentUser currentUser;

    public AaLedgerController(AaLedgerService aaLedgerService, CurrentUser currentUser) {
        this.aaLedgerService = aaLedgerService;
        this.currentUser = currentUser;
    }

    /** 概览视图：当前用户三口径 + 成员净额 + 流水（派生，不落库）。 */
    @GetMapping("/{ledgerId}/overview")
    public ResponseEntity<AaOverviewResponse> overview(@PathVariable Long ledgerId) {
        Long userId = currentUser.requireUserId();
        return ResponseEntity.ok(aaLedgerService.overview(userId, ledgerId));
    }
}
