package com.damien.youyu.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.Merchant;
import com.damien.youyu.domain.Tag;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 明细搜索服务：跨月按关键词/金额搜索本账本流水。
 *
 * <p>关键词匹配范围：备注（模糊）、分类名、商家名、标签名（后三者先按名解析出 id，再取其流水）；
 * 若关键词可解析为金额，则并入金额精确匹配。多来源结果按交易 id 去重、按时间倒序，最多返回 {@value #MAX}
 * 条。所有查询按会话 {@code ledgerId} 隔离（需求 2.3）。</p>
 */
@Service
public class TransactionSearchService {

    /** 搜索结果上限（避免超大返回）。 */
    static final int MAX = 200;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;
    private final TagRepository tagRepository;

    public TransactionSearchService(
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            MerchantRepository merchantRepository,
            TagRepository tagRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.tagRepository = tagRepository;
    }

    /** 关键词搜索；空白关键词返回空列表。 */
    @Transactional(readOnly = true)
    public List<Transaction> search(Long ledgerId, String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        Map<Long, Transaction> merged = new LinkedHashMap<>();

        // 备注模糊。
        for (Transaction t : transactionRepository.findByLedgerIdAndNoteContainingIgnoreCase(ledgerId, q)) {
            merged.putIfAbsent(t.getId(), t);
        }

        // 金额精确（关键词是合法金额时）。
        BigDecimal amount = tryAmount(q);
        if (amount != null) {
            for (Transaction t : transactionRepository.findByLedgerIdAndAmount(ledgerId, amount)) {
                merged.putIfAbsent(t.getId(), t);
            }
        }

        // 分类名匹配 → 分类 id → 流水。
        List<Long> catIds = new ArrayList<>();
        for (Category c : categoryRepository.findByLedgerId(ledgerId)) {
            if (containsIgnoreCase(c.getName(), q)) {
                catIds.add(c.getId());
            }
        }
        if (!catIds.isEmpty()) {
            for (Transaction t : transactionRepository.findByLedgerIdAndCategoryIdIn(ledgerId, catIds)) {
                merged.putIfAbsent(t.getId(), t);
            }
        }

        // 商家名匹配 → 商家 id → 流水。
        List<Long> merIds = new ArrayList<>();
        for (Merchant m : merchantRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId)) {
            if (containsIgnoreCase(m.getName(), q)) {
                merIds.add(m.getId());
            }
        }
        if (!merIds.isEmpty()) {
            for (Transaction t : transactionRepository.findByLedgerIdAndMerchantIdIn(ledgerId, merIds)) {
                merged.putIfAbsent(t.getId(), t);
            }
        }

        // 标签名匹配 → 标签 id → 流水（经关联表）。
        for (Tag tag : tagRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId)) {
            if (containsIgnoreCase(tag.getName(), q)) {
                for (Transaction t : transactionRepository.findByLedgerIdAndTagId(ledgerId, tag.getId())) {
                    merged.putIfAbsent(t.getId(), t);
                }
            }
        }

        List<Transaction> result = new ArrayList<>(merged.values());
        result.sort(Comparator
                .comparing(Transaction::getOccurredAt).reversed()
                .thenComparing(Comparator.comparing(Transaction::getId).reversed()));
        return result.size() > MAX ? result.subList(0, MAX) : result;
    }

    private static boolean containsIgnoreCase(String s, String q) {
        return s != null && s.toLowerCase().contains(q.toLowerCase());
    }

    /** 尝试把关键词解析为两位小数金额；非法返回 null。 */
    private static BigDecimal tryAmount(String q) {
        try {
            BigDecimal v = new BigDecimal(q.replace(",", "").replace("¥", "").trim());
            if (v.signum() <= 0) {
                return null;
            }
            return v.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
