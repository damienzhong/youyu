package com.damien.youyu.api;

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

import com.damien.youyu.api.dto.CategoryCreateRequest;
import com.damien.youyu.api.dto.CategoryListResponse;
import com.damien.youyu.api.dto.CategoryResponse;
import com.damien.youyu.api.dto.CategoryUpdateRequest;
import com.damien.youyu.domain.Category;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.service.CategoryService;

/**
 * 两级分类管理接口（关联需求 5.1-5.9）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权，本控制器从 {@link CurrentLedger} 读取当前会话用户主键，
 * 所有读写均按该 ledgerId 隔离（写入强制覆盖 user_id、访问他人分类返回 404，需求 2.2-2.4）。</p>
 *
 * <ul>
 *   <li>POST {@code /api/categories} 创建父/子分类（指定 kind、可选 parentId，201）。</li>
 *   <li>GET {@code /api/categories} 列出本人分类（按 kind 分组、含层级）。</li>
 *   <li>PUT {@code /api/categories/{id}} 重命名（保留关联）。</li>
 *   <li>DELETE {@code /api/categories/{id}} 删除（无引用、无子分类才允许，204）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final CurrentLedger currentLedger;

    public CategoryController(CategoryService categoryService, CurrentLedger currentLedger) {
        this.categoryService = categoryService;
        this.currentLedger = currentLedger;
    }

    /** 创建父/子分类：成功返回 201 与分类信息。 */
    @PostMapping
    public ResponseEntity<CategoryResponse> create(@RequestBody CategoryCreateRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Category category = categoryService.create(
                ledgerId, req.kind(), req.name(), req.parentId(), req.icon(), req.iconColor());
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(category));
    }

    /** 列出本人分类，按 kind 分组并含两级层级（需求 5.6）。 */
    @GetMapping
    public ResponseEntity<CategoryListResponse> list() {
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(CategoryListResponse.from(categoryService.list(ledgerId)));
    }

    /** 给当前账本补齐默认分类（仅当为空时），供新手引导使用。幂等。 */
    @PostMapping("/seed-defaults")
    public ResponseEntity<CategoryListResponse> seedDefaults() {
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(CategoryListResponse.from(categoryService.seedDefaultsIfEmpty(ledgerId)));
    }

    /** 重命名分类（保留关联，需求 5.4）。 */
    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> rename(
            @PathVariable Long id, @RequestBody CategoryUpdateRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Category category = categoryService.update(ledgerId, id, req.name(), req.icon(), req.iconColor());
        return ResponseEntity.ok(CategoryResponse.from(category));
    }

    /** 删除分类（无引用、无子分类才允许）：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        categoryService.delete(ledgerId, id);
        return ResponseEntity.noContent().build();
    }
}
