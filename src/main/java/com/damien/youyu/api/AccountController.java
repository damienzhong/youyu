package com.damien.youyu.api;

import java.util.List;
import java.util.Map;

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

import com.damien.youyu.api.dto.AccountCreateRequest;
import com.damien.youyu.api.dto.AccountResponse;
import com.damien.youyu.api.dto.AccountUpdateRequest;
import com.damien.youyu.api.dto.AccountVisibilityRequest;
import com.damien.youyu.api.dto.AccountVisibilityResponse;
import com.damien.youyu.api.dto.TransactionResponse;
import com.damien.youyu.api.dto.TransferOwnershipRequest;
import com.damien.youyu.api.dto.TransferRequest;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.AccountService;
import com.damien.youyu.service.LedgerAccountResolver;
import com.damien.youyu.service.TransactionService;

/**
 * 账户管理接口（关联需求 1、3、4、6、9）。
 *
 * <p>账户是独立于账本的一等实体，始终归属当前用户（owner）。账户在哪些账本可用、是否对协作成员
 * 可见/显示余额由 {@code account_ledger} 表达。越权访问返回 404。</p>
 *
 * <ul>
 *   <li>POST {@code /api/accounts} 创建账户并纳入当前账本（201）。</li>
 *   <li>GET {@code /api/accounts} 列出本人全部账户（管理视图，余额可见）。</li>
 *   <li>GET {@code /api/accounts/selectable} 列出当前账本可选账户（记账用，含余额可见性）。</li>
 *   <li>PUT {@code /api/accounts/{id}} 修改名称/类型等（保留余额）。</li>
 *   <li>DELETE {@code /api/accounts/{id}} 删除（无关联交易才允许，204）。</li>
 *   <li>PUT {@code /api/accounts/{id}/visibility} 纳入账本 / 更新可见性。</li>
 *   <li>DELETE {@code /api/accounts/{id}/ledgers/{ledgerId}} 取消账户在某账本的参与。</li>
 *   <li>POST {@code /api/accounts/transfer} 账户间转账（脱离账本）。</li>
 *   <li>POST {@code /api/accounts/{id}/transfer-ownership} 转交账户。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final TransactionService transactionService;
    private final LedgerAccountResolver accountResolver;
    private final CurrentUser currentUser;
    private final CurrentLedger currentLedger;

    public AccountController(AccountService accountService, TransactionService transactionService,
            LedgerAccountResolver accountResolver, CurrentUser currentUser, CurrentLedger currentLedger) {
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.accountResolver = accountResolver;
        this.currentUser = currentUser;
        this.currentLedger = currentLedger;
    }

    /** 创建账户：成功返回 201 与账户信息。默认纳入当前账本，便于立即记账。 */
    @PostMapping
    public ResponseEntity<AccountResponse> create(@RequestBody AccountCreateRequest req) {
        Long userId = currentUser.requireUserId();
        Long ledgerId = currentLedger.requireLedgerId();
        Account account = accountService.create(
                userId, req.name(), req.type(), req.initialBalance(), req.sortOrder(),
                req.includeInTotal() == null || req.includeInTotal(),
                req.hidden() != null && req.hidden(),
                req.note(),
                req.creditLimit(),
                req.billDay(), req.repayDay(), req.repayReminder() != null && req.repayReminder(),
                ledgerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    /** 列出本人全部账户（管理视图，余额可见），按 sort_order、id 升序。 */
    @GetMapping
    public ResponseEntity<List<AccountResponse>> list() {
        Long userId = currentUser.requireUserId();
        List<AccountResponse> body = accountService.list(userId).stream()
                .map(AccountResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** 列出当前账本可选账户（记账用）：本人纳入的 + 他人暴露的，附余额可见性脱敏。 */
    @GetMapping("/selectable")
    public ResponseEntity<List<AccountResponse>> selectable() {
        Long userId = currentUser.requireUserId();
        Long ledgerId = currentLedger.requireLedgerId();
        List<AccountResponse> body = accountResolver.selectableAccounts(userId, ledgerId).stream()
                .map(a -> AccountResponse.from(a, accountResolver.canSeeBalance(userId, ledgerId, a)))
                .toList();
        return ResponseEntity.ok(body);
    }

    /** 快速记账默认账户：上一笔在此账本记账用的账户（仍可选则用之），否则可选集第一个；无则 204。 */
    @GetMapping("/default")
    public ResponseEntity<AccountResponse> defaultForEntry() {
        Long userId = currentUser.requireUserId();
        Long ledgerId = currentLedger.requireLedgerId();
        Account account = transactionService.defaultAccountForEntry(userId, ledgerId);
        if (account == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(
                AccountResponse.from(account, accountResolver.canSeeBalance(userId, ledgerId, account)));
    }

    /**
     * 账户明细：账户 owner 见全部流水（跨账本 + 转账）；协作账本内其他成员仅见该账户在当前账本内的流水。
     */
    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<TransactionResponse>> transactions(@PathVariable Long id) {
        Long userId = currentUser.requireUserId();
        Long ledgerId = currentLedger.requireLedgerId();
        List<TransactionResponse> body = transactionService.listAccountDetail(userId, ledgerId, id).stream()
                .map(TransactionResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** 修改账户名称/类型（保留余额）。 */
    @PutMapping("/{id}")
    public ResponseEntity<AccountResponse> update(
            @PathVariable Long id, @RequestBody AccountUpdateRequest req) {
        Long userId = currentUser.requireUserId();
        Account account = accountService.update(
                userId, id, req.name(), req.type(),
                req.includeInTotal() == null || req.includeInTotal(),
                req.hidden() != null && req.hidden(),
                req.note(),
                req.creditLimit(),
                req.billDay(), req.repayDay(), req.repayReminder() != null && req.repayReminder());
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    /** 删除账户（无关联交易才允许）：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accountService.delete(currentUser.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 读取账户在当前账本的可见性状态（owner 视角）。 */
    @GetMapping("/{id}/visibility")
    public ResponseEntity<AccountVisibilityResponse> getVisibility(@PathVariable Long id) {
        Long userId = currentUser.requireUserId();
        Long ledgerId = currentLedger.requireLedgerId();
        AccountVisibilityResponse body = accountService.visibilityOf(userId, id, ledgerId)
                .map(al -> new AccountVisibilityResponse(
                        ledgerId, true, al.isVisibleToOthers(), al.isShowBalance()))
                .orElseGet(() -> new AccountVisibilityResponse(ledgerId, false, true, true));
        return ResponseEntity.ok(body);
    }

    /** 纳入账本 / 更新账户在某账本的可见性。ledgerId 缺省取当前账本。 */
    @PutMapping("/{id}/visibility")
    public ResponseEntity<Void> setVisibility(
            @PathVariable Long id, @RequestBody AccountVisibilityRequest req) {
        Long userId = currentUser.requireUserId();
        Long ledgerId = req.ledgerId() != null ? req.ledgerId() : currentLedger.requireLedgerId();
        boolean visibleToOthers = req.visibleToOthers() == null || req.visibleToOthers();
        boolean showBalance = req.showBalance() == null || req.showBalance();
        accountService.attachToLedger(userId, id, ledgerId, visibleToOthers, showBalance);
        return ResponseEntity.noContent().build();
    }

    /** 取消账户在某账本的参与（未来不可选，历史保留）。返回是否已有历史流水以供前端提示。 */
    @DeleteMapping("/{id}/ledgers/{ledgerId}")
    public ResponseEntity<Map<String, Boolean>> detach(
            @PathVariable Long id, @PathVariable Long ledgerId) {
        boolean hasHistory = accountService.detachFromLedger(currentUser.requireUserId(), id, ledgerId);
        return ResponseEntity.ok(Map.of("hasHistory", hasHistory));
    }

    /** 账户间转账（脱离账本，源/目标须为本人账户）：成功返回 201。 */
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(@RequestBody TransferRequest req) {
        Long userId = currentUser.requireUserId();
        Transaction tx = transactionService.transfer(
                userId, req.sourceAccountId(), req.destinationAccountId(),
                req.amount(), req.occurredAt(), req.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(tx));
    }

    /** 转交账户给另一用户：成功返回 200。 */
    @PostMapping("/{id}/transfer-ownership")
    public ResponseEntity<AccountResponse> transferOwnership(
            @PathVariable Long id, @RequestBody TransferOwnershipRequest req) {
        Account account = accountService.transferOwnership(
                currentUser.requireUserId(), id, req.newOwnerUserId());
        return ResponseEntity.ok(AccountResponse.from(account));
    }
}
