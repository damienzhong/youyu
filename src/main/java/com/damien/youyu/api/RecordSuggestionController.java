package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.RecordSuggestionResponse;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.service.RecordSuggestionService;

/**
 * 记账推荐查询接口（record-suggestion 需求 6.1-6.5）。
 *
 * <p>纯只读、纯派生：挂在既有交易路由族 {@code /api/transactions} 之下，避免新增顶层路径。
 * 身份由 Spring Security 统一鉴权（{@code /api/transactions/**} 已在 {@code authenticated()} 下），
 * 令牌缺失/过期/用户不存在时经 {@link CurrentLedger#requireLedgerId()} 内既有链路返回
 * {@code UNAUTHENTICATED}（需求 6.2）；{@code X-Ledger-Id} 越权时返回既有账本不可访问错误（需求 6.3）。</p>
 *
 * <p>数据归属只认令牌用户 + 解析出的账本，忽略任何请求入参指定的用户/账本（需求 6.4）。
 * 「全部账本聚合视图」由前端在聚合态不发起请求实现，后端始终按单账本工作。</p>
 *
 * <ul>
 *   <li>GET {@code /api/transactions/suggestions} 返回当前账本的候选列表（0 至 3 条，需求 6.1、6.6）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/transactions")
public class RecordSuggestionController {

    private final RecordSuggestionService suggestionService;
    private final CurrentLedger currentLedger;

    public RecordSuggestionController(RecordSuggestionService suggestionService,
            CurrentLedger currentLedger) {
        this.suggestionService = suggestionService;
        this.currentLedger = currentLedger;
    }

    /** 列出当前账本的记账推荐候选（按融合分降序及其决胜次序排列，条数为 0 或 [2, 3]）。 */
    @GetMapping("/suggestions")
    public ResponseEntity<RecordSuggestionResponse> suggestions() {
        // 无令牌→UNAUTHENTICATED；X-Ledger-Id 越权→既有账本不可访问错误（需求 6.2、6.3）
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(suggestionService.list(ledgerId));
    }
}
