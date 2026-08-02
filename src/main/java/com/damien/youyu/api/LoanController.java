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
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.AccountLoanEntryResponse;
import com.damien.youyu.api.dto.LoanCreateRequest;
import com.damien.youyu.api.dto.LoanDetailResponse;
import com.damien.youyu.api.dto.LoanListResponse;
import com.damien.youyu.api.dto.LoanRepaymentRequest;
import com.damien.youyu.api.dto.LoanRepaymentResponse;
import com.damien.youyu.api.dto.LoanResponse;
import com.damien.youyu.api.dto.LoanUpdateRequest;
import com.damien.youyu.domain.Loan;
import com.damien.youyu.domain.LoanDirection;
import com.damien.youyu.domain.LoanRepayment;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.LoanService;

/**
 * 借贷往来接口。借贷为用户级实体（独立于账本），按会话用户隔离（越权 404），并联动关联账户余额。
 *
 * <ul>
 *   <li>GET {@code /api/loans} 列表 + 待还/待收汇总。</li>
 *   <li>GET {@code /api/loans/{id}} 详情（含收款/还款明细）。</li>
 *   <li>POST {@code /api/loans} 新建（201）。</li>
 *   <li>PUT {@code /api/loans/{id}} 修改。</li>
 *   <li>DELETE {@code /api/loans/{id}} 删除（204）。</li>
 *   <li>POST {@code /api/loans/{id}/repayments} 新增收款/还款（201）。</li>
 *   <li>DELETE {@code /api/loans/{id}/repayments/{repaymentId}} 删除收款/还款（204）。</li>
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

    /** 某账户的借贷流水投影（供账户流水合并展示；借贷为用户级，不进账本流水）。 */
    @GetMapping("/account-entries")
    public ResponseEntity<List<AccountLoanEntryResponse>> accountEntries(
            @org.springframework.web.bind.annotation.RequestParam Long accountId) {
        Long userId = currentUser.requireUserId();
        return ResponseEntity.ok(loanService.accountEntries(userId, accountId));
    }

    /** 借贷详情（含收款/还款明细）。 */
    @GetMapping("/{id}")
    public ResponseEntity<LoanDetailResponse> detail(@PathVariable Long id) {
        Long userId = currentUser.requireUserId();
        Loan loan = loanService.get(userId, id);
        List<LoanRepaymentResponse> repayments = loanService.repayments(userId, id).stream()
                .map(LoanRepaymentResponse::from)
                .toList();
        return ResponseEntity.ok(new LoanDetailResponse(LoanResponse.from(loan), repayments));
    }

    /** 新建借贷：成功返回 201。 */
    @PostMapping
    public ResponseEntity<LoanResponse> create(@RequestBody LoanCreateRequest req) {
        Long userId = currentUser.requireUserId();
        Loan loan = loanService.create(
                userId, req.direction(), req.counterparty(),
                req.amount(), req.accountId(), req.occurredAt(), req.dueDate(),
                req.includeInTotal() == null || req.includeInTotal(), req.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(LoanResponse.from(loan));
    }

    /** 修改借贷。 */
    @PutMapping("/{id}")
    public ResponseEntity<LoanResponse> update(
            @PathVariable Long id, @RequestBody LoanUpdateRequest req) {
        Long userId = currentUser.requireUserId();
        Loan loan = loanService.update(
                userId, id, req.direction(), req.counterparty(),
                req.amount(), req.accountId(), req.occurredAt(), req.dueDate(),
                req.includeInTotal() == null || req.includeInTotal(), req.note());
        return ResponseEntity.ok(LoanResponse.from(loan));
    }

    /** 删除借贷：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long userId = currentUser.requireUserId();
        loanService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** 新增收款/还款：成功返回 201。 */
    @PostMapping("/{id}/repayments")
    public ResponseEntity<LoanRepaymentResponse> addRepayment(
            @PathVariable Long id, @RequestBody LoanRepaymentRequest req) {
        Long userId = currentUser.requireUserId();
        LoanRepayment r = loanService.addRepayment(
                userId, id, req.amount(), req.accountId(), req.occurredAt(), req.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(LoanRepaymentResponse.from(r));
    }

    /** 删除收款/还款：成功返回 204。 */
    @DeleteMapping("/{id}/repayments/{repaymentId}")
    public ResponseEntity<Void> deleteRepayment(
            @PathVariable Long id, @PathVariable Long repaymentId) {
        Long userId = currentUser.requireUserId();
        loanService.deleteRepayment(userId, repaymentId);
        return ResponseEntity.noContent().build();
    }
}
