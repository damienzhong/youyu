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

import com.damien.youyu.api.dto.BalanceAdjustRequest;
import com.damien.youyu.api.dto.BatchIdsRequest;
import com.damien.youyu.api.dto.FilteredTransactionsResponse;
import com.damien.youyu.api.dto.TransactionCreateRequest;
import com.damien.youyu.api.dto.TransactionResponse;
import com.damien.youyu.api.dto.TransactionUpdateRequest;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.AccountScope;
import com.damien.youyu.service.LedgerService;
import com.damien.youyu.service.MerchantService;
import com.damien.youyu.service.ProjectService;
import com.damien.youyu.service.TagService;
import com.damien.youyu.service.TransactionSearchService;
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
    private final LedgerService ledgerService;
    private final ProjectService projectService;
    private final MerchantService merchantService;
    private final TagService tagService;
    private final TransactionSearchService searchService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public TransactionController(TransactionService transactionService,
            LedgerService ledgerService, ProjectService projectService,
            MerchantService merchantService, TagService tagService,
            TransactionSearchService searchService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.transactionService = transactionService;
        this.ledgerService = ledgerService;
        this.projectService = projectService;
        this.merchantService = merchantService;
        this.tagService = tagService;
        this.searchService = searchService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 创建交易：成功返回 201 与交易信息。 */
    @PostMapping
    public ResponseEntity<TransactionResponse> create(@RequestBody TransactionCreateRequest req) {
        Ledger ledger = currentLedger.requireLedger();
        Long userId = currentUser.requireUserId();
        AccountScope scope = AccountScope.forLedger(userId, ledger);
        // 协作代记：仅协作账本、且指定记账人为账本成员时生效，否则以会话用户为记账人。
        Long createdBy = null;
        if (req.createdBy() != null
                && "COLLABORATIVE".equals(ledger.getType())
                && ledgerService.isMember(ledger.getId(), req.createdBy())) {
            createdBy = req.createdBy();
        }
        // 校验所属项目/商家/标签归属本账本（不存在则 404）；null 表示无。
        projectService.requireInLedgerOrNull(ledger.getId(), req.projectId());
        merchantService.requireInLedgerOrNull(ledger.getId(), req.merchantId());
        List<Long> tagIds = tagService.validateTagIds(ledger.getId(), req.tagIds());
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
                req.note(),
                createdBy,
                req.projectId(),
                req.merchantId());
        tagService.setTransactionTags(tx.getId(), tagIds);
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(tx, tagIds));
    }

    /**
     * 余额调整：把账户当前余额校准到目标值，用一笔补差流水落地。
     * 有差额返回 201 与补差流水；无差额返回 204。
     */
    @PostMapping("/adjust")
    public ResponseEntity<TransactionResponse> adjust(@RequestBody BalanceAdjustRequest req) {
        Ledger ledger = currentLedger.requireLedger();
        AccountScope scope = AccountScope.forLedger(currentUser.requireUserId(), ledger);
        Transaction tx = transactionService.adjustBalance(
                scope, ledger.getId(), req.accountId(), req.balance(), req.occurredAt(), req.note());
        if (tx == null) {
            return ResponseEntity.noContent().build();
        }
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
            return ResponseEntity.ok(withTags(transactionService.listByRange(ledgerId, from, to)));
        }

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize);
        return ResponseEntity.ok(withTags(transactionService.list(ledgerId, pageable).getContent()));
    }

    private YearMonth parseMonth(String raw) {
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.reportParamInvalid("month", "月份格式应为 YYYY-MM");
        }
    }

    /** 批量为一组交易附上标签 id 列表，构建响应（避免逐条查询标签）。 */
    private List<TransactionResponse> withTags(List<Transaction> txs) {
        if (txs.isEmpty()) {
            return List.of();
        }
        java.util.Map<Long, List<Long>> tagMap = tagService.tagIdsMap(
                txs.stream().map(Transaction::getId).toList());
        return txs.stream()
                .map(tx -> TransactionResponse.from(tx, tagMap.getOrDefault(tx.getId(), List.of())))
                .toList();
    }

    /**
     * 按项目/商家/标签过滤本人交易并附支出/收入汇总（三者恰传其一）。
     * 用于项目/商家/标签的明细与统计视图。
     */
    @GetMapping("/filter")
    public ResponseEntity<FilteredTransactionsResponse> filter(
            @RequestParam(name = "projectId", required = false) Long projectId,
            @RequestParam(name = "merchantId", required = false) Long merchantId,
            @RequestParam(name = "tagId", required = false) Long tagId) {
        Long ledgerId = currentLedger.requireLedgerId();
        List<Transaction> txs;
        if (projectId != null) {
            txs = transactionService.listByProject(ledgerId, projectId);
        } else if (merchantId != null) {
            txs = transactionService.listByMerchant(ledgerId, merchantId);
        } else if (tagId != null) {
            txs = transactionService.listByTag(ledgerId, tagId);
        } else {
            throw ApiException.reportParamInvalid("filter", "需指定 projectId / merchantId / tagId 之一");
        }
        java.math.BigDecimal expense = java.math.BigDecimal.ZERO;
        java.math.BigDecimal income = java.math.BigDecimal.ZERO;
        for (Transaction t : txs) {
            if (t.getType() == com.damien.youyu.domain.TransactionType.EXPENSE) {
                expense = expense.add(t.getAmount());
            } else if (t.getType() == com.damien.youyu.domain.TransactionType.INCOME) {
                income = income.add(t.getAmount());
            }
        }
        FilteredTransactionsResponse body = new FilteredTransactionsResponse(
                expense, income, txs.size(), withTags(txs));
        return ResponseEntity.ok(body);
    }

    /** 关键词搜索本人流水（跨月，命中备注/分类/商家/标签/金额），按时间倒序，附标签。 */
    @GetMapping("/search")
    public ResponseEntity<List<TransactionResponse>> search(@RequestParam(name = "q") String q) {
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(withTags(searchService.search(ledgerId, q)));
    }

    /** 单条读取本人交易（校验归属）。 */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> get(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        Transaction tx = transactionService.get(ledgerId, id);
        return ResponseEntity.ok(TransactionResponse.from(tx, tagService.tagIdsOf(id)));
    }

    /** 修改交易：回滚原影响后应用新影响，成功返回 200 与最新交易信息（需求 4.6、4.7）。 */
    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponse> update(
            @PathVariable Long id, @RequestBody TransactionUpdateRequest req) {
        Ledger ledger = currentLedger.requireLedger();
        AccountScope scope = AccountScope.forLedger(currentUser.requireUserId(), ledger);
        // 校验所属项目/商家/标签归属本账本（不存在则 404）；null 表示无。
        projectService.requireInLedgerOrNull(ledger.getId(), req.projectId());
        merchantService.requireInLedgerOrNull(ledger.getId(), req.merchantId());
        List<Long> tagIds = tagService.validateTagIds(ledger.getId(), req.tagIds());
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
                req.note(),
                req.projectId(),
                req.merchantId());
        tagService.setTransactionTags(tx.getId(), tagIds);
        return ResponseEntity.ok(TransactionResponse.from(tx, tagIds));
    }

    /** 删除交易：回滚原影响后删除，成功返回 204（需求 4.6、4.7）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Ledger ledger = currentLedger.requireLedger();
        AccountScope scope = AccountScope.forLedger(currentUser.requireUserId(), ledger);
        // 软删除（移入回收站）：保留标签关联以便恢复，不在此清除。
        transactionService.delete(scope, ledger.getId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 批量软删除（移入回收站）：返回实际删除笔数。忽略不存在/已删除的 id。 */
    @PostMapping("/batch-delete")
    public ResponseEntity<java.util.Map<String, Integer>> batchDelete(@RequestBody BatchIdsRequest req) {
        Ledger ledger = currentLedger.requireLedger();
        AccountScope scope = AccountScope.forLedger(currentUser.requireUserId(), ledger);
        int deleted = 0;
        if (req != null && req.ids() != null) {
            for (Long id : req.ids()) {
                if (id == null) {
                    continue;
                }
                try {
                    transactionService.delete(scope, ledger.getId(), id);
                    deleted++;
                } catch (ApiException ex) {
                    // 不存在/越权的 id 跳过，不影响其余。
                }
            }
        }
        return ResponseEntity.ok(java.util.Map.of("deleted", deleted));
    }

    /** 回收站列表（已软删除的流水），按删除时间倒序，附标签。 */
    @GetMapping("/recycle")
    public ResponseEntity<List<TransactionResponse>> recycle() {
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(withTags(transactionService.listDeleted(ledgerId)));
    }

    /** 从回收站恢复一笔流水（重新应用余额影响）。 */
    @PostMapping("/{id}/restore")
    public ResponseEntity<TransactionResponse> restore(@PathVariable Long id) {
        Ledger ledger = currentLedger.requireLedger();
        AccountScope scope = AccountScope.forLedger(currentUser.requireUserId(), ledger);
        Transaction tx = transactionService.restore(scope, ledger.getId(), id);
        return ResponseEntity.ok(TransactionResponse.from(tx, tagService.tagIdsOf(id)));
    }

    /** 彻底删除回收站中的一笔流水（物理删除，连带标签关联）。 */
    @DeleteMapping("/{id}/purge")
    public ResponseEntity<Void> purge(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        transactionService.purge(ledgerId, id);
        tagService.clearTransactionTags(id);
        return ResponseEntity.noContent().build();
    }
}
