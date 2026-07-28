package com.damien.youyu.api;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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

import com.damien.youyu.api.dto.TransactionCreateRequest;
import com.damien.youyu.api.dto.TransactionResponse;
import com.damien.youyu.api.dto.TransactionUpdateRequest;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.AccountScope;
import com.damien.youyu.service.TransactionService;

/**
 * 交易记账接口（关联需求 4.1-4.5、4.8-4.11）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权，本控制器从 {@link CurrentLedger} 读取当前会话用户主键，
 * 所有读写均按该 ledgerId 隔离（写入强制覆盖 user_id、读取他人交易返回 404，需求 2.2-2.4）。</p>
 *
 * <ul>
 *   <li>POST {@code /api/transactions} 创建支出/收入/转账，事务性更新余额（201）。</li>
 *   <li>GET {@code /api/transactions} 分页列出本人交易（按时间倒序）。</li>
 *   <li>GET {@code /api/transactions/{id}} 单条读取（校验归属）。</li>
 *   <li>PUT {@code /api/transactions/{id}} 修改（回滚原影响后应用新影响，需求 4.6、4.7）。</li>
 *   <li>DELETE {@code /api/transactions/{id}} 删除（回滚原影响，需求 4.6、4.7）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final int MAX_PAGE_SIZE = 200;

    private final TransactionService transactionService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public TransactionController(TransactionService transactionService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.transactionService = transactionService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 创建交易：成功返回 201 与交易信息。 */
    @PostMapping
    public ResponseEntity<TransactionResponse> create(@RequestBody TransactionCreateRequest req) {
        Ledger ledger = currentLedger.requireLedger();
        AccountScope scope = AccountScope.forLedger(currentUser.requireUserId(), ledger);
        Transaction tx = transactionService.create(
                scope,
                ledger.getId(),
                req.type(),
                req.amount(),
                req.accountId(),
                req.categoryId(),
                req.sourceAccountId(),
                req.destinationAccountId(),
                req.occurredAt(),
                req.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(tx));
    }

    /**
     * 列出本人交易（按时间倒序）。
     * <ul>
     *   <li>指定 {@code month=YYYY-MM}：返回该自然月（Asia/Shanghai）全部交易（首页「当月流水」）。</li>
     *   <li>否则：按 {@code page}/{@code size} 分页返回。</li>
     * </ul>
     */
    @GetMapping
    public ResponseEntity<List<TransactionResponse>> list(
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Long ledgerId = currentLedger.requireLedgerId();

        if (month != null && !month.isBlank()) {
            YearMonth ym = parseMonth(month);
            LocalDateTime from = ym.atDay(1).atStartOfDay();
            LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();
            List<TransactionResponse> body = transactionService.listByRange(ledgerId, from, to).stream()
                    .map(TransactionResponse::from)
                    .toList();
            return ResponseEntity.ok(body);
        }

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize);
        List<TransactionResponse> body = transactionService.list(ledgerId, pageable).stream()
                .map(TransactionResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    private YearMonth parseMonth(String raw) {
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.reportParamInvalid("month", "月份格式应为 YYYY-MM");
        }
    }

    /** 单条读取本人交易（校验归属）。 */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> get(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        Transaction tx = transactionService.get(ledgerId, id);
        return ResponseEntity.ok(TransactionResponse.from(tx));
    }

    /** 修改交易：回滚原影响后应用新影响，成功返回 200 与最新交易信息（需求 4.6、4.7）。 */
    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponse> update(
            @PathVariable Long id, @RequestBody TransactionUpdateRequest req) {
        Ledger ledger = currentLedger.requireLedger();
        AccountScope scope = AccountScope.forLedger(currentUser.requireUserId(), ledger);
        Transaction tx = transactionService.update(
                scope,
                ledger.getId(),
                id,
                req.type(),
                req.amount(),
                req.accountId(),
                req.categoryId(),
                req.sourceAccountId(),
                req.destinationAccountId(),
                req.occurredAt(),
                req.note());
        return ResponseEntity.ok(TransactionResponse.from(tx));
    }

    /** 删除交易：回滚原影响后删除，成功返回 204（需求 4.6、4.7）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Ledger ledger = currentLedger.requireLedger();
        AccountScope scope = AccountScope.forLedger(currentUser.requireUserId(), ledger);
        transactionService.delete(scope, ledger.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
