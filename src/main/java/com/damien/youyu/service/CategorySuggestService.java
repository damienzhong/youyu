package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.CategoryClassifyRequest;
import com.damien.youyu.api.dto.CategoryClassifyResponse;
import com.damien.youyu.api.dto.CategoryRuleSaveRequest;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.CategoryRule;
import com.damien.youyu.domain.Merchant;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.CategoryRuleRepository;
import com.damien.youyu.repository.ClassifyHistoryRow;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 自动分类服务：给一批流水推荐分类，并保存用户确认的「商户 → 分类」规则。
 *
 * <p>每笔按以下顺序取第一个命中的分类，且结果一律是当前账本里<b>仍存在、收支种类一致</b>的分类：</p>
 * <ol>
 *   <li>RULE：用户在预览里改过并勾了「以后都这样记」的规则（{@code category_rules}）。</li>
 *   <li>HABIT：该账本近 {@value #HISTORY_DAYS} 天里同一商户最常用的分类（并列取最近一次）。
 *       商户取自流水的商家，或账单导入备注「交易对方 · 商品」的交易对方。只学用户亲手定过分类的流水
 *       （手动记账，或导入后又改过），导入时默认兜底、没人碰过的不算。</li>
 *   <li>MERCHANT：内置商户库（美团外卖 → 外卖、滴滴 → 打车……），落到用户已有分类上。</li>
 *   <li>KEYWORD：用户分类名出现在账单文本里（沿用原前端匹配口径）。</li>
 *   <li>NONE：都没命中，返回 null，由调用方用默认分类兜底。</li>
 * </ol>
 *
 * <p>只读推荐不写库；规则保存按会话 ledgerId 隔离。金额不参与分类。</p>
 */
@Service
public class CategorySuggestService {

    /** 历史习惯的回看天数。 */
    static final int HISTORY_DAYS = 365;

    /** 单次分类 / 保存规则的条数上限，防止超大请求。 */
    static final int MAX_ITEMS = 5000;
    static final int MAX_RULES = 500;

    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository ruleRepository;
    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;
    private final Clock clock;

    public CategorySuggestService(
            CategoryRepository categoryRepository,
            CategoryRuleRepository ruleRepository,
            TransactionRepository transactionRepository,
            MerchantRepository merchantRepository,
            Clock clock) {
        this.categoryRepository = categoryRepository;
        this.ruleRepository = ruleRepository;
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
        this.clock = clock;
    }

    /** 统计用：某商户某分类出现的次数与最近一次时间。 */
    private static final class Tally {
        int count;
        LocalDateTime last;
    }

    /** 批量推荐分类，结果顺序与 {@code items} 一致。 */
    @Transactional(readOnly = true)
    public CategoryClassifyResponse classify(Long ledgerId, CategoryClassifyRequest req) {
        List<CategoryClassifyRequest.Item> items = req == null || req.items() == null ? List.of() : req.items();
        if (items.size() > MAX_ITEMS) {
            throw ApiException.importInvalid("单次最多分类 " + MAX_ITEMS + " 笔");
        }
        if (items.isEmpty()) {
            return new CategoryClassifyResponse(List.of());
        }

        List<Category> categories = categoryRepository.findByLedgerId(ledgerId);
        Map<Long, Category> byId = new HashMap<>();
        for (Category c : categories) {
            byId.put(c.getId(), c);
        }

        Map<String, Long> rules = new HashMap<>();
        for (CategoryRule r : ruleRepository.findByLedgerId(ledgerId)) {
            if (isUsable(byId.get(r.getCategoryId()), r.getKind())) {
                rules.put(r.getKind() + "|" + r.getMerchantKey(), r.getCategoryId());
            }
        }

        Map<String, Long> habits = buildHabits(ledgerId, byId);

        List<CategoryClassifyResponse.Result> out = new ArrayList<>(items.size());
        for (CategoryClassifyRequest.Item it : items) {
            out.add(classifyOne(it, categories, rules, habits));
        }
        return new CategoryClassifyResponse(out);
    }

    private CategoryClassifyResponse.Result classifyOne(CategoryClassifyRequest.Item it, List<Category> categories,
            Map<String, Long> rules, Map<String, Long> habits) {
        CategoryKind kind = parseKind(it == null ? null : it.type());
        if (kind == null) {
            return new CategoryClassifyResponse.Result(null, "NONE");
        }
        String key = CategoryClassifier.merchantKey(it.counterparty());
        if (key != null) {
            Long id = rules.get(kind + "|" + key);
            if (id != null) {
                return new CategoryClassifyResponse.Result(id, "RULE");
            }
            id = habits.get(kind + "|" + key);
            if (id != null) {
                return new CategoryClassifyResponse.Result(id, "HABIT");
            }
        }
        String text = join(it.counterparty(), it.text());
        Category c = CategoryClassifier.matchLibrary(text, kind, categories);
        if (c != null) {
            return new CategoryClassifyResponse.Result(c.getId(), "MERCHANT");
        }
        c = CategoryClassifier.matchByCategoryName(text, kind, categories);
        if (c != null) {
            return new CategoryClassifyResponse.Result(c.getId(), "KEYWORD");
        }
        return new CategoryClassifyResponse.Result(null, "NONE");
    }

    /** 历史习惯：(种类|商户键) → 最常用分类（并列取最近一次）。 */
    private Map<String, Long> buildHabits(Long ledgerId, Map<Long, Category> byId) {
        Map<Long, String> merchantNames = new HashMap<>();
        for (Merchant m : merchantRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId)) {
            merchantNames.put(m.getId(), m.getName());
        }
        LocalDateTime from = LocalDateTime.now(clock).minusDays(HISTORY_DAYS);
        Map<String, Map<Long, Tally>> tallies = new LinkedHashMap<>();
        for (ClassifyHistoryRow row : transactionRepository.findClassifyHistoryRows(ledgerId, from)) {
            CategoryKind kind = row.getType() == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
            if (!isUsable(byId.get(row.getCategoryId()), kind)) {
                continue;
            }
            String raw = row.getMerchantId() != null && merchantNames.containsKey(row.getMerchantId())
                    ? merchantNames.get(row.getMerchantId())
                    : CategoryClassifier.counterpartyFromNote(row.getNote());
            String key = CategoryClassifier.merchantKey(raw);
            if (key == null) {
                continue;
            }
            Tally t = tallies.computeIfAbsent(kind + "|" + key, k -> new HashMap<>())
                    .computeIfAbsent(row.getCategoryId(), k -> new Tally());
            t.count++;
            if (t.last == null || (row.getOccurredAt() != null && row.getOccurredAt().isAfter(t.last))) {
                t.last = row.getOccurredAt();
            }
        }
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Map<Long, Tally>> e : tallies.entrySet()) {
            Long best = null;
            Tally bestT = null;
            for (Map.Entry<Long, Tally> c : e.getValue().entrySet()) {
                Tally t = c.getValue();
                if (bestT == null || t.count > bestT.count
                        || (t.count == bestT.count && t.last != null
                                && (bestT.last == null || t.last.isAfter(bestT.last)))) {
                    best = c.getKey();
                    bestT = t;
                }
            }
            out.put(e.getKey(), best);
        }
        return out;
    }

    /**
     * 保存「以后都这样记」的规则：同一账本、同一收支、同一商户覆盖旧规则。
     *
     * @return 实际保存的条数（商户为空的条目忽略）
     * @throws ApiException IMPORT_INVALID（条数超限 / 收支类型非法 / 分类种类不符）、NOT_FOUND（分类不属于当前账本）
     */
    @Transactional
    public int saveRules(Long userId, Long ledgerId, CategoryRuleSaveRequest req) {
        List<CategoryRuleSaveRequest.Rule> rules = req == null || req.rules() == null ? List.of() : req.rules();
        if (rules.size() > MAX_RULES) {
            throw ApiException.importInvalid("单次最多保存 " + MAX_RULES + " 条规则");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int saved = 0;
        for (CategoryRuleSaveRequest.Rule r : rules) {
            if (r == null) {
                continue;
            }
            CategoryKind kind = parseKind(r.type());
            if (kind == null) {
                throw ApiException.importInvalid("规则的收支类型非法");
            }
            String key = CategoryClassifier.merchantKey(r.counterparty());
            if (key == null || r.categoryId() == null) {
                continue;
            }
            Category c = categoryRepository.findByIdAndLedgerId(r.categoryId(), ledgerId)
                    .orElseThrow(() -> ApiException.notFound("分类不存在"));
            if (c.getKind() != kind) {
                throw ApiException.importInvalid("规则的分类与收支类型不匹配");
            }
            CategoryRule rule = ruleRepository.findByLedgerIdAndKindAndMerchantKey(ledgerId, kind, key)
                    .orElseGet(() -> {
                        CategoryRule n = new CategoryRule();
                        n.setLedgerId(ledgerId);
                        n.setKind(kind);
                        n.setMerchantKey(key);
                        n.setCreatedAt(now);
                        return n;
                    });
            rule.setUserId(userId);
            rule.setCategoryId(c.getId());
            rule.setUpdatedAt(now);
            ruleRepository.save(rule);
            saved++;
        }
        return saved;
    }

    private static boolean isUsable(Category c, CategoryKind kind) {
        return c != null && c.getKind() == kind;
    }

    private static CategoryKind parseKind(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.trim().toLowerCase()) {
            case "expense" -> CategoryKind.EXPENSE;
            case "income" -> CategoryKind.INCOME;
            default -> null;
        };
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null ? "" : b;
        }
        return b == null || b.isBlank() ? a : a + " " + b;
    }
}
