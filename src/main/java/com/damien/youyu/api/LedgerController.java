package com.damien.youyu.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.LedgerCreateRequest;
import com.damien.youyu.api.dto.LedgerResponse;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.LedgerService;

/**
 * 账本管理接口。账本按会话用户隔离；业务数据按账本隔离（阶段二落地）。
 *
 * <ul>
 *   <li>GET  {@code /api/ledgers} 列出本人账本（无则自动创建默认账本）。</li>
 *   <li>POST {@code /api/ledgers} 新建账本。</li>
 *   <li>PUT  {@code /api/ledgers/{id}} 重命名账本。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ledgers")
public class LedgerController {

    private final LedgerService ledgerService;
    private final CurrentUser currentUser;

    public LedgerController(LedgerService ledgerService, CurrentUser currentUser) {
        this.ledgerService = ledgerService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ResponseEntity<List<LedgerResponse>> list() {
        Long userId = currentUser.requireUserId();
        List<LedgerResponse> body = ledgerService.list(userId).stream()
                .map(LedgerResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<LedgerResponse> create(@RequestBody LedgerCreateRequest req) {
        Long userId = currentUser.requireUserId();
        Ledger ledger = ledgerService.create(userId, req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(LedgerResponse.from(ledger));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LedgerResponse> rename(
            @PathVariable Long id, @RequestBody LedgerCreateRequest req) {
        Long userId = currentUser.requireUserId();
        Ledger ledger = ledgerService.rename(userId, id, req.name());
        return ResponseEntity.ok(LedgerResponse.from(ledger));
    }
}
