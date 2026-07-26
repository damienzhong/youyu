package com.damien.youyu.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.LoanCreateRequest;
import com.damien.youyu.api.dto.LoanListResponse;
import com.damien.youyu.api.dto.LoanResponse;
import com.damien.youyu.api.dto.LoanUpdateRequest;
import com.damien.youyu.domain.Loan;
import com.damien.youyu.domain.LoanDirection;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.LoanService;

/**
 * 借贷往来接口。
 *
 * <p>身份由 Spring Security 统一鉴权，所有读写按会话 userId 隔离（越权返回 404，需求 2.2-2.4）。
 * 借贷为独立台账，不影响账户余额与净资产。</p>
 *
 * <ul>
 *   <li>GET {@code /api/loans} 列表 + 待还/待收汇总。</li>
 *   <li>POST {@code /api/loans} 新建（201）。</li>
 *   <li>PUT {@code /api/loans/{id}} 修改。</li>
 *   <li>POST {@code /api/loans/{id}/settle?settled=true|false} 切换结清状态。</li>
 *   <li>DELETE {@code /api/loans/{id}} 删除（204）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService loanService;
    private final CurrentUser currentUser;

    public LoanController(LoanService loanService, CurrentUser currentUser) {
        this.loanService = loanService;
        this.currentUser = currentUser;
    }

    /** 列出本人借贷并附待还/待收汇总。 */
    @GetMapping
    public ResponseEntity<LoanListResponse> list() {
        Long userId = currentUser.requireUserId();
        List<LoanResponse> loans = loanService.list(userId).stream()
                .map(LoanResponse::from)
                .toList();
        LoanListResponse body = new LoanListResponse(
                loanService.outstanding(userId, LoanDirection.BORROW),
                loanService.outstanding(userId, LoanDirection.LEND),
                loans);
        return ResponseEntity.ok(body);
    }

    /** 新建借贷：成功返回 201。 */
    @PostMapping
    public ResponseEntity<LoanResponse> create(@RequestBody LoanCreateRequest req) {
        Long userId = currentUser.requireUserId();
        Loan loan = loanService.create(
                userId, req.direction(), req.counterparty(),
                req.amount(), req.occurredAt(), req.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(LoanResponse.from(loan));
    }

    /** 修改借贷。 */
    @PutMapping("/{id}")
    public ResponseEntity<LoanResponse> update(
            @PathVariable Long id, @RequestBody LoanUpdateRequest req) {
        Long userId = currentUser.requireUserId();
        Loan loan = loanService.update(
                userId, id, req.direction(), req.counterparty(),
                req.amount(), req.occurredAt(), req.note());
        return ResponseEntity.ok(LoanResponse.from(loan));
    }

    /** 切换结清状态（settled 缺省 true）。 */
    @PostMapping("/{id}/settle")
    public ResponseEntity<LoanResponse> settle(
            @PathVariable Long id,
            @RequestParam(name = "settled", defaultValue = "true") boolean settled) {
        Long userId = currentUser.requireUserId();
        Loan loan = loanService.setSettled(userId, id, settled);
        return ResponseEntity.ok(LoanResponse.from(loan));
    }

    /** 删除借贷：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long userId = currentUser.requireUserId();
        loanService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
