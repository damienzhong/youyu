package com.damien.youyu.api;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

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

import com.damien.youyu.api.dto.BudgetAmountRequest;
import com.damien.youyu.api.dto.BudgetOverviewResponse;
import com.damien.youyu.api.dto.CategoryBudgetRequest;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.service.BudgetService;

/**
 * 预算接口：月度总预算 + 分类预算 + 预算健康（前瞻）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权，本控制器从 {@link CurrentLedger} 读取会话用户主键，
 * 所有读写按该 ledgerId 隔离（需求 2.3/2.4）。月份参数 {@code month} 为 {@code YYYY-MM}，缺省取
 * {@code Asia/Shanghai} 当前自然月。</p>
 *
 * <ul>
 *   <li>GET    {@code /api/budgets?month=YYYY-MM} 预算总览。</li>
 *   <li>PUT    {@code /api/budgets?month=YYYY-MM} 设置月度总预算 {amount}。</li>
 *   <li>POST   {@code /api/budgets/categories?month=YYYY-MM} 设置分类预算 {categoryId, amount}。</li>
 *   <li>DELETE {@code /api/budgets/categories/{categoryId}?month=YYYY-MM} 删除分类预算。</li>
 *   <li>POST   {@code /api/budgets/copy-previous?month=YYYY-MM} 沿用上月预算。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgetService;
    private final CurrentLedger currentLedger;
    private final Clock clock;

    public BudgetController(BudgetService budgetService, CurrentLedger currentLedger, Clock clock) {
        this.budgetService = budgetService;
        this.currentLedger = currentLedger;
        this.clock = clock;
    }

    @GetMapping
    public ResponseEntity<BudgetOverviewResponse> overview(
            @RequestParam(name = "month", required = false) String month) {
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(budgetService.overview(ledgerId, resolveMonth(month)));
    }

    @PutMapping
    public ResponseEntity<BudgetOverviewResponse> setTotal(
            @RequestParam(name = "month", required = false) String month,
            @RequestBody BudgetAmountRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(budgetService.setTotalBudget(ledgerId, resolveMonth(month), req.amount()));
    }

    @PostMapping("/categories")
    public ResponseEntity<BudgetOverviewResponse> setCategory(
            @RequestParam(name = "month", required = false) String month,
            @RequestBody CategoryBudgetRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(
                budgetService.setCategoryBudget(ledgerId, resolveMonth(month), req.categoryId(), req.amount()));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<BudgetOverviewResponse> deleteCategory(
            @PathVariable Long categoryId,
            @RequestParam(name = "month", required = false) String month) {
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(budgetService.deleteCategoryBudget(ledgerId, resolveMonth(month), categoryId));
    }

    @PostMapping("/copy-previous")
    public ResponseEntity<BudgetOverviewResponse> copyPrevious(
            @RequestParam(name = "month", required = false) String month) {
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(budgetService.copyFromPreviousMonth(ledgerId, resolveMonth(month)));
    }

    private YearMonth resolveMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.now(clock);
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.budgetMonthInvalid();
        }
    }
}
