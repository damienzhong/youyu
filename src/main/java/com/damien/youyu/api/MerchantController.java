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

import com.damien.youyu.api.dto.MerchantResponse;
import com.damien.youyu.api.dto.MerchantSaveRequest;
import com.damien.youyu.domain.Merchant;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.MerchantService;

/**
 * 商家接口。
 *
 * <p>身份由 Spring Security 统一鉴权，所有读写按会话 ledgerId 隔离（越权返回 404，需求 2.2-2.4）。</p>
 *
 * <ul>
 *   <li>GET {@code /api/merchants} 列出本账本商家。</li>
 *   <li>POST {@code /api/merchants} 新建（同名幂等，201）。</li>
 *   <li>PUT {@code /api/merchants/{id}} 重命名。</li>
 *   <li>DELETE {@code /api/merchants/{id}} 删除（204）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private final MerchantService merchantService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public MerchantController(MerchantService merchantService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.merchantService = merchantService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 列出本账本商家。 */
    @GetMapping
    public ResponseEntity<List<MerchantResponse>> list() {
        Long ledgerId = currentLedger.requireLedgerId();
        List<MerchantResponse> body = merchantService.list(ledgerId).stream()
                .map(MerchantResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** 新建商家：成功返回 201。 */
    @PostMapping
    public ResponseEntity<MerchantResponse> create(@RequestBody MerchantSaveRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        Merchant m = merchantService.create(userId, ledgerId, req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(MerchantResponse.from(m));
    }

    /** 重命名商家。 */
    @PutMapping("/{id}")
    public ResponseEntity<MerchantResponse> update(
            @PathVariable Long id, @RequestBody MerchantSaveRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Merchant m = merchantService.rename(ledgerId, id, req.name());
        return ResponseEntity.ok(MerchantResponse.from(m));
    }

    /** 删除商家：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        merchantService.delete(ledgerId, id);
        return ResponseEntity.noContent().build();
    }
}
