package com.damien.youyu.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.TransactionTemplateCreateRequest;
import com.damien.youyu.api.dto.TransactionTemplateResponse;
import com.damien.youyu.domain.TransactionTemplate;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.TransactionTemplateService;

/**
 * 记账模板接口。
 *
 * <p>身份由 Spring Security 统一鉴权，所有读写按会话 ledgerId 隔离（越权返回 404，需求 2.2-2.4）。
 * 模板保存常用记账形态，记一笔时套用预填，本身不产生流水。</p>
 *
 * <ul>
 *   <li>GET {@code /api/templates} 列出本账本模板。</li>
 *   <li>POST {@code /api/templates} 新建（201）。</li>
 *   <li>DELETE {@code /api/templates/{id}} 删除（204）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/templates")
public class TransactionTemplateController {

    private final TransactionTemplateService templateService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public TransactionTemplateController(TransactionTemplateService templateService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.templateService = templateService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 列出本账本模板。 */
    @GetMapping
    public ResponseEntity<List<TransactionTemplateResponse>> list() {
        Long ledgerId = currentLedger.requireLedgerId();
        List<TransactionTemplateResponse> body = templateService.list(ledgerId).stream()
                .map(TransactionTemplateResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** 新建模板：成功返回 201。 */
    @PostMapping
    public ResponseEntity<TransactionTemplateResponse> create(
            @RequestBody TransactionTemplateCreateRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        TransactionTemplate t = templateService.create(
                userId, ledgerId, req.name(), req.type(), req.amount(),
                req.accountId(), req.categoryId(),
                req.sourceAccountId(), req.destinationAccountId(), req.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionTemplateResponse.from(t));
    }

    /** 删除模板：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        templateService.delete(ledgerId, id);
        return ResponseEntity.noContent().build();
    }
}
