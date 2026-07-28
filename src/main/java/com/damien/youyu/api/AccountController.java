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

import com.damien.youyu.api.dto.AccountCreateRequest;
import com.damien.youyu.api.dto.AccountResponse;
import com.damien.youyu.api.dto.AccountUpdateRequest;
import com.damien.youyu.domain.Account;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.service.AccountService;

/**
 * 账户管理接口（关联需求 3.1-3.9）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权，本控制器从 {@link CurrentLedger} 读取当前会话用户主键，
 * 所有读写均按该 ledgerId 隔离（写入强制覆盖 user_id、读取/修改/删除他人账户返回 404，需求 2.2-2.4）。</p>
 *
 * <ul>
 *   <li>POST {@code /api/accounts} 创建账户（201）。</li>
 *   <li>GET {@code /api/accounts} 列出本人账户（按 sort_order）。</li>
 *   <li>PUT {@code /api/accounts/{id}} 修改名称/类型（保留余额）。</li>
 *   <li>DELETE {@code /api/accounts/{id}} 删除（无关联交易才允许，204）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final CurrentLedger currentLedger;

    public AccountController(AccountService accountService, CurrentLedger currentLedger) {
        this.accountService = accountService;
        this.currentLedger = currentLedger;
    }

    /** 创建账户：成功返回 201 与账户信息。 */
    @PostMapping
    public ResponseEntity<AccountResponse> create(@RequestBody AccountCreateRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Account account = accountService.create(
                ledgerId, req.name(), req.type(), req.initialBalance(), req.sortOrder(),
                req.includeInTotal() == null || req.includeInTotal(),
                req.hidden() != null && req.hidden(),
                req.note(),
                req.creditLimit());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    /** 列出本人账户（按 sort_order、id 升序），无账户返回空列表。 */
    @GetMapping
    public ResponseEntity<List<AccountResponse>> list() {
        Long ledgerId = currentLedger.requireLedgerId();
        List<AccountResponse> body = accountService.list(ledgerId).stream()
                .map(AccountResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** 修改账户名称/类型（保留余额）。 */
    @PutMapping("/{id}")
    public ResponseEntity<AccountResponse> update(
            @PathVariable Long id, @RequestBody AccountUpdateRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Account account = accountService.update(
                ledgerId, id, req.name(), req.type(),
                req.includeInTotal() == null || req.includeInTotal(),
                req.hidden() != null && req.hidden(),
                req.note(),
                req.creditLimit());
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    /** 删除账户（无关联交易才允许）：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        accountService.delete(ledgerId, id);
        return ResponseEntity.noContent().build();
    }
}
