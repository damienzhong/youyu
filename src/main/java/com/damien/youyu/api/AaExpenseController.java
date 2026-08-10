package com.damien.youyu.api;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.AaExpenseRequest;
import com.damien.youyu.api.dto.AaExpenseResponse;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.aa.AaExpenseService;

/**
 * AA 账本记账接口（关联需求 3.1、3.6、9.1、9.4、9.5）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权；当前账本由 {@link CurrentLedger} 依请求头 {@code X-Ledger-Id}
 * 解析（越权 / 不存在返回 NOT_FOUND，需求 9.4），当前用户由 {@link CurrentUser} 解析。所有分摊 / 账户 /
 * 只读 / 成员校验一律下沉到 {@link AaExpenseService}：非成员越权返回 NOT_FOUND、只读账本写操作返回
 * {@code AA_LEDGER_ARCHIVED}（需求 9.5），本控制器仅负责请求 / 响应装配，异常经
 * {@link com.damien.youyu.error.GlobalExceptionHandler} 统一成错误体。</p>
 *
 * <ul>
 *   <li>POST {@code /api/aa/expenses} 创建 AA 支出（201）。</li>
 *   <li>PUT {@code /api/aa/expenses/{id}} 编辑（回滚旧效果后按新参数重建，200）。</li>
 *   <li>DELETE {@code /api/aa/expenses/{id}} 删除（未涉结算才可删，回滚账户与分摊，204）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/aa/expenses")
public class AaExpenseController {

    private final AaExpenseService aaExpenseService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public AaExpenseController(AaExpenseService aaExpenseService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.aaExpenseService = aaExpenseService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 创建 AA 支出：成功返回 201 与该笔（含分摊明细）。 */
    @PostMapping
    public ResponseEntity<AaExpenseResponse> create(@RequestBody AaExpenseRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        Transaction tx = aaExpenseService.create(
                userId,
                ledgerId,
                req.amount(),
                req.categoryId(),
                req.payerUserId(),
                req.payerAccountId(),
                req.occurredAt(),
                req.note(),
                req.splitMode(),
                req.participants(),
                toCustomShares(req.customShares()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AaExpenseResponse.from(tx, aaExpenseService.splitsOf(tx.getId())));
    }

    /** 编辑 AA 支出：回滚旧效果后按新参数重建，成功返回 200 与最新该笔（含分摊明细，需求 9.2a、9.3）。 */
    @PutMapping("/{id}")
    public ResponseEntity<AaExpenseResponse> update(
            @PathVariable Long id, @RequestBody AaExpenseRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        Transaction tx = aaExpenseService.update(
                userId,
                ledgerId,
                id,
                req.amount(),
                req.categoryId(),
                req.payerUserId(),
                req.payerAccountId(),
                req.occurredAt(),
                req.note(),
                req.splitMode(),
                req.participants(),
                toCustomShares(req.customShares()));
        return ResponseEntity.ok(AaExpenseResponse.from(tx, aaExpenseService.splitsOf(tx.getId())));
    }

    /** 删除 AA 支出：回滚付款账户与分摊，成功返回 204（需求 9.2a、9.3）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        aaExpenseService.delete(userId, ledgerId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 把请求体的自定义分摊列表转为服务层所需的 {@code userId → amount} 映射（保序）。
     * 为空返回 {@code null}（均分场景无需自定义明细）；具体校验（每人一条、Σ=总额）由服务层完成。
     */
    private static Map<Long, BigDecimal> toCustomShares(List<AaExpenseRequest.CustomShare> shares) {
        if (shares == null || shares.isEmpty()) {
            return null;
        }
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (AaExpenseRequest.CustomShare share : shares) {
            if (share != null) {
                out.put(share.userId(), share.amount());
            }
        }
        return out;
    }
}
