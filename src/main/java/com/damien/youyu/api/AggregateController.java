package com.damien.youyu.api;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.AccountResponse;
import com.damien.youyu.api.dto.CategoryListResponse;
import com.damien.youyu.api.dto.TransactionResponse;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.AggregateService;

/**
 * 「全部账本」聚合只读接口：跨当前用户所有账本汇总，供首页「全部」视图使用。
 *
 * <p>按会话用户（而非单一账本）聚合，故注入 {@link CurrentUser}。仅只读；写入请走具体账本接口。</p>
 *
 * <ul>
 *   <li>GET {@code /api/all/accounts} 全部账本的账户。</li>
 *   <li>GET {@code /api/all/transactions?month=YYYY-MM} 全部账本某自然月的交易。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/all")
public class AggregateController {

    private final AggregateService aggregateService;
    private final CurrentUser currentUser;

    public AggregateController(AggregateService aggregateService, CurrentUser currentUser) {
        this.aggregateService = aggregateService;
        this.currentUser = currentUser;
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<AccountResponse>> accounts() {
        Long userId = currentUser.requireUserId();
        List<AccountResponse> body = aggregateService.allAccounts(userId).stream()
                .map(AccountResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/categories")
    public ResponseEntity<CategoryListResponse> categories() {
        Long userId = currentUser.requireUserId();
        return ResponseEntity.ok(CategoryListResponse.from(aggregateService.allCategories(userId)));
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<TransactionResponse>> transactions(
            @RequestParam(name = "month") String month) {
        Long userId = currentUser.requireUserId();
        YearMonth ym = parseMonth(month);
        List<TransactionResponse> body = aggregateService.allTransactionsInMonth(userId, ym).stream()
                .map(TransactionResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    private YearMonth parseMonth(String raw) {
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException | NullPointerException ex) {
            throw ApiException.reportParamInvalid("month", "月份格式应为 YYYY-MM");
        }
    }
}
