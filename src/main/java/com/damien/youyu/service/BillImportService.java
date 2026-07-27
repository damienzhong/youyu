package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.BillImportRequest;
import com.damien.youyu.api.dto.BillImportRequest.BillEntry;
import com.damien.youyu.api.dto.BillImportResponse;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 账单批量导入服务：把前端解析归一化后的支付宝/微信账单落库为支出/收入流水（关联导入需求）。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>全部流水记入用户选定的单一目标账户；同一事务内一次性更新该账户余额（收入 +、支出 −）。</li>
 *   <li>去重：账单 {@code externalId}（形如 "alipay:订单号"）唯一——已入库或同批次重复的自动跳过，
 *       计入 {@code skippedDuplicate}（依赖唯一索引 (user_id, external_id) 兜底）。</li>
 *   <li>分类：优先用前端匹配到的分类（须属于本人且类别与收/支一致），否则用对应默认分类；
 *       仍无有效分类则该笔计入 {@code skippedInvalid}。</li>
 *   <li>金额非法（≤0、超上限、超两位小数）或类型非法的行计入 {@code skippedInvalid}，不影响其余。</li>
 * </ul>
 *
 * <p>按会话 {@code userId} 隔离：目标账户/分类不属于当前用户即 {@code NOT_FOUND}。金额一律 {@link BigDecimal}。</p>
 */
@Service
public class BillImportService {

    static final BigDecimal AMOUNT_MIN = new BigDecimal("0.01");
    static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999999999.99");
    static final int NOTE_MAX = 200;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final Clock clock;

    public BillImportService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.clock = clock;
    }

    /**
     * 批量导入账单流水。
     *
     * @throws ApiException IMPORT_INVALID（缺目标账户/无有效条目）、NOT_FOUND（账户/默认分类不属于当前用户）
     */
    @Transactional
    public BillImportResponse importBills(Long userId, BillImportRequest req) {
        if (req == null || req.accountId() == null) {
            throw ApiException.importInvalid("请选择导入目标账户");
        }
        List<BillEntry> entries = req.entries() == null ? List.of() : req.entries();
        if (entries.isEmpty()) {
            throw ApiException.importInvalid("没有可导入的账单条目");
        }

        // 目标账户加锁（导入结束一次性更新余额）。
        Account account = accountRepository.findForUpdateByIdAndUserId(req.accountId(), userId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));

        Category defExpense = resolveDefault(userId, req.defaultExpenseCategoryId(), CategoryKind.EXPENSE);
        Category defIncome = resolveDefault(userId, req.defaultIncomeCategoryId(), CategoryKind.INCOME);

        // 去重：先查该用户已入库的 externalId，同批内再去重。
        Set<String> incomingIds = new HashSet<>();
        for (BillEntry e : entries) {
            String id = trimToNull(e.externalId());
            if (id != null) {
                incomingIds.add(id);
            }
        }
        Set<String> existing = incomingIds.isEmpty()
                ? Set.of()
                : new HashSet<>(transactionRepository.findExistingExternalIds(userId, incomingIds));
        Set<String> seen = new HashSet<>();

        LocalDateTime now = LocalDateTime.now(clock);
        int imported = 0;
        int skippedDuplicate = 0;
        int skippedInvalid = 0;
        BigDecimal delta = BigDecimal.ZERO;
        List<Transaction> toSave = new ArrayList<>();

        for (BillEntry e : entries) {
            TransactionType type = parseType(e.type());
            if (type == null) {
                skippedInvalid++;
                continue;
            }
            BigDecimal amount = normalizeAmount(e.amount());
            if (amount == null) {
                skippedInvalid++;
                continue;
            }

            String extId = trimToNull(e.externalId());
            if (extId != null && (existing.contains(extId) || !seen.add(extId))) {
                skippedDuplicate++;
                continue;
            }

            CategoryKind kind = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
            Category category = resolveCategory(userId, e.categoryId(), kind,
                    type == TransactionType.INCOME ? defIncome : defExpense);
            if (category == null) {
                skippedInvalid++;
                continue;
            }

            Transaction tx = new Transaction();
            tx.setUserId(userId);
            tx.setType(type);
            tx.setAmount(amount);
            tx.setAccountId(account.getId());
            tx.setCategoryId(category.getId());
            tx.setOccurredAt(e.occurredAt() == null ? now : e.occurredAt());
            tx.setNote(truncateNote(e.note()));
            tx.setExternalId(extId);
            tx.setCreatedAt(now);
            tx.setUpdatedAt(now);
            toSave.add(tx);

            delta = delta.add(type == TransactionType.INCOME ? amount : amount.negate());
            imported++;
        }

        if (!toSave.isEmpty()) {
            account.setCurrentBalance(account.getCurrentBalance().add(delta));
            account.setUpdatedAt(now);
            accountRepository.save(account);
            transactionRepository.saveAll(toSave);
        }

        return new BillImportResponse(imported, skippedDuplicate, skippedInvalid);
    }

    /** 默认分类：为空则返回 null（届时无兜底的行将被跳过）；提供则须属于本人且类别匹配。 */
    private Category resolveDefault(Long userId, Long categoryId, CategoryKind kind) {
        if (categoryId == null) {
            return null;
        }
        Category c = categoryRepository.findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> ApiException.notFound("默认分类不存在"));
        if (c.getKind() != kind) {
            throw ApiException.importInvalid("默认分类的收支类别不匹配");
        }
        return c;
    }

    /** 逐笔分类：优先用前端匹配分类（属于本人且类别一致），否则用默认分类。 */
    private Category resolveCategory(Long userId, Long categoryId, CategoryKind kind, Category fallback) {
        if (categoryId != null) {
            Category c = categoryRepository.findByIdAndUserId(categoryId, userId).orElse(null);
            if (c != null && c.getKind() == kind) {
                return c;
            }
        }
        return fallback;
    }

    private static TransactionType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        if ("expense".equals(v)) {
            return TransactionType.EXPENSE;
        }
        if ("income".equals(v)) {
            return TransactionType.INCOME;
        }
        return null; // 中性/转账不在导入范围
    }

    /** 归一化金额；非法（空、≤0、超上限、超两位小数）返回 null。 */
    private static BigDecimal normalizeAmount(BigDecimal raw) {
        if (raw == null) {
            return null;
        }
        BigDecimal v;
        try {
            v = raw.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            return null;
        }
        if (v.compareTo(AMOUNT_MIN) < 0 || v.compareTo(AMOUNT_MAX) > 0) {
            return null;
        }
        return v;
    }

    private static String truncateNote(String note) {
        if (note == null) {
            return null;
        }
        String t = note.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > NOTE_MAX ? t.substring(0, NOTE_MAX) : t;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
