package com.damien.youyu.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.CategoryClassifyRequest;
import com.damien.youyu.api.dto.CategoryClassifyResponse;
import com.damien.youyu.api.dto.CategoryRuleSaveRequest;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.CategorySuggestService;

/**
 * 自动分类接口。身份由 Spring Security 统一鉴权，按会话 ledgerId 隔离。
 *
 * <ul>
 *   <li>POST {@code /api/imports/bills/classify} 批量推荐分类（只读），结果与请求顺序一一对应。</li>
 *   <li>PUT {@code /api/category-rules} 批量保存「以后都这样记」的商户 → 分类规则，返回 {@code saved} 条数。</li>
 * </ul>
 */
@RestController
public class CategorySuggestController {

    private final CategorySuggestService service;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public CategorySuggestController(CategorySuggestService service,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.service = service;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 批量推荐分类。 */
    @PostMapping("/api/imports/bills/classify")
    public ResponseEntity<CategoryClassifyResponse> classify(@RequestBody CategoryClassifyRequest req) {
        currentUser.requireUserId();
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(service.classify(ledgerId, req));
    }

    /** 批量保存分类规则。 */
    @PutMapping("/api/category-rules")
    public ResponseEntity<Map<String, Integer>> saveRules(@RequestBody CategoryRuleSaveRequest req) {
        Long userId = currentUser.requireUserId();
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(Map.of("saved", service.saveRules(userId, ledgerId, req)));
    }
}
