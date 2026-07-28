package com.damien.youyu.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 数据导入服务：从 {@link ExportService} 产出的 JSON 文档还原数据到当前用户（关联需求 8.5）。
 *
 * <p>用途为<b>往返一致性</b>与迁移：将导出的 JSON 导入到一个空账户后，应还原出与导出前
 * 记录数分别相等、且除系统生成标识符（自增 ID）外各业务字段值逐一相等的
 * Account / Category / Transaction 集合（需求 8.5，属性 18 见任务 8.3）。</p>
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li><b>重建引用关系</b>：导出使用业务引用键（{@code ref}/{@code parentRef}/{@code accountRef}/
 *       {@code categoryRef}/{@code sourceAccountRef}/{@code destinationAccountRef}）而非自增 ID。
 *       导入时先创建账户与分类并建立 {@code ref → 新实体} 映射，再据此把交易的引用键解析为新库主键。</li>
 *   <li><b>分类父子重建</b>：分两趟处理——先创建父分类（{@code parentRef} 为 null），再创建子分类
 *       并将其 {@code parentId} 指向已创建父分类的新主键（层级最多两级）。</li>
 *   <li><b>余额一致</b>：账户创建时 {@code current_balance = initial_balance}；随后按与正常记账相同的
 *       余额更新语义逐笔应用交易增量（支出 −amount、收入 +amount、转账源 −amount 且目标 +amount），
 *       使还原后的 {@code current_balance} 与导出前一致，并满足余额守恒不变式（可经
 *       {@link AccountService#recomputeBalance} 校验）。</li>
 *   <li><b>强制会话 user_id</b>：所有还原实体的 {@code user_id} 一律取会话用户，忽略文档中任何归属信息
 *       （需求 2.2）。</li>
 *   <li><b>业务字段精确保留</b>：金额一律 {@link BigDecimal} 且缩放至两位小数；{@code occurredAt} 以
 *       {@code Asia/Shanghai}（UTC+8）偏移解析回本地时间；{@code note}、{@code type}、{@code kind}、
 *       {@code sortOrder} 原样保留。</li>
 *   <li><b>原子性</b>：整个导入在单个事务内完成，任一步失败即整体回滚，不产生任何部分数据
 *       （失败抛出 {@link ApiException#importFailed()} / {@link ApiException#importInvalid(String)}）。</li>
 * </ul>
 */
@Service
public class ImportService {

    /** 业务时区（UTC+8），与导出保持一致。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 带偏移的 ISO-8601 时间格式（如 {@code 2025-06-01T12:30:00+08:00}）。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public ImportService(
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            TransactionRepository transactionRepository,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.clock = clock;
    }

    /** 导入结果汇总：还原的账户/分类/交易记录数。 */
    public record ImportResult(int accounts, int categories, int transactions) {
    }

    /**
     * 从导出 JSON 还原数据到当前用户（需求 8.5）。整个过程在单个事务内执行，失败即回滚。
     *
     * @param ledgerId 会话用户主键（强制覆盖所有还原实体的 user_id）
     * @param in     导出 JSON 文档输入流
     * @return 还原的各类记录数
     * @throws ApiException IMPORT_INVALID（文档结构/引用键/字段值非法）；IMPORT_FAILED（解析失败）
     */
    @Transactional
    public ImportResult importJson(Long userId, Long ledgerId, InputStream in) {
        JsonNode root;
        try {
            root = mapper.readTree(in);
        } catch (IOException e) {
            throw ApiException.importFailed();
        }
        if (root == null || !root.isObject()) {
            throw ApiException.importInvalid("导入文档必须为 JSON 对象");
        }

        LocalDateTime now = LocalDateTime.now(clock);

        Map<String, Account> accountByRef = restoreAccounts(userId, root.path("accounts"), now);
        Map<String, Category> categoryByRef = restoreCategories(ledgerId, root.path("categories"), now);
        int txCount = restoreTransactions(
                ledgerId, root.path("transactions"), accountByRef, categoryByRef, now);

        // 逐笔交易已在内存中累加余额增量，统一持久化更新后的 current_balance。
        accountRepository.saveAll(accountByRef.values());

        return new ImportResult(accountByRef.size(), categoryByRef.size(), txCount);
    }

    // ---------------- 账户还原 ----------------

    private Map<String, Account> restoreAccounts(Long userId, JsonNode accounts, LocalDateTime now) {
        Map<String, Account> byRef = new HashMap<>();
        if (accounts.isMissingNode() || accounts.isNull()) {
            return byRef;
        }
        if (!accounts.isArray()) {
            throw ApiException.importInvalid("accounts 必须为数组");
        }
        for (JsonNode node : accounts) {
            String ref = requireText(node, "ref", "账户");
            BigDecimal initial = money(requireText(node, "initialBalance", "账户"));

            Account account = new Account();
            // 账户为用户级：归属会话用户，ledger_id 为空。
            account.setUserId(userId);
            account.setLedgerId(null);
            account.setName(requireText(node, "name", "账户"));
            account.setType(parseAccountType(requireText(node, "type", "账户")));
            account.setInitialBalance(initial);
            // 需求 8.5：current_balance 初始等于 initial_balance，随后由交易增量还原。
            account.setCurrentBalance(initial);
            account.setSortOrder(node.path("sortOrder").asInt(0));
            account.setCreatedAt(now);
            account.setUpdatedAt(now);
            accountRepository.save(account);

            if (byRef.putIfAbsent(ref, account) != null) {
                throw ApiException.importInvalid("账户引用键重复: " + ref);
            }
        }
        return byRef;
    }

    // ---------------- 分类还原（两趟：父分类 → 子分类） ----------------

    private Map<String, Category> restoreCategories(Long ledgerId, JsonNode categories, LocalDateTime now) {
        Map<String, Category> byRef = new HashMap<>();
        if (categories.isMissingNode() || categories.isNull()) {
            return byRef;
        }
        if (!categories.isArray()) {
            throw ApiException.importInvalid("categories 必须为数组");
        }

        // 第一趟：父分类（parentRef 为 null 或缺省）。
        for (JsonNode node : categories) {
            if (hasRef(node, "parentRef")) {
                continue;
            }
            String ref = requireText(node, "ref", "分类");
            Category parent = newCategory(ledgerId, node, null, now);
            categoryRepository.save(parent);
            if (byRef.putIfAbsent(ref, parent) != null) {
                throw ApiException.importInvalid("分类引用键重复: " + ref);
            }
        }

        // 第二趟：子分类，parentId 指向已还原父分类的新主键。
        for (JsonNode node : categories) {
            if (!hasRef(node, "parentRef")) {
                continue;
            }
            String ref = requireText(node, "ref", "分类");
            String parentRef = node.get("parentRef").asText();
            Category parent = byRef.get(parentRef);
            if (parent == null) {
                throw ApiException.importInvalid("子分类引用了不存在的父分类: " + parentRef);
            }
            Category child = newCategory(ledgerId, node, parent.getId(), now);
            categoryRepository.save(child);
            if (byRef.putIfAbsent(ref, child) != null) {
                throw ApiException.importInvalid("分类引用键重复: " + ref);
            }
        }
        return byRef;
    }

    private Category newCategory(Long ledgerId, JsonNode node, Long parentId, LocalDateTime now) {
        Category category = new Category();
        category.setLedgerId(ledgerId);
        category.setParentId(parentId);
        category.setKind(parseCategoryKind(requireText(node, "kind", "分类")));
        category.setName(requireText(node, "name", "分类"));
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        return category;
    }

    // ---------------- 交易还原（解析引用键 + 累加余额增量） ----------------

    private int restoreTransactions(
            Long ledgerId, JsonNode transactions,
            Map<String, Account> accountByRef, Map<String, Category> categoryByRef,
            LocalDateTime now) {
        if (transactions.isMissingNode() || transactions.isNull()) {
            return 0;
        }
        if (!transactions.isArray()) {
            throw ApiException.importInvalid("transactions 必须为数组");
        }

        int count = 0;
        for (JsonNode node : transactions) {
            TransactionType type = parseTransactionType(requireText(node, "type", "交易"));
            BigDecimal amount = money(requireText(node, "amount", "交易"));

            Transaction tx = new Transaction();
            tx.setLedgerId(ledgerId);
            tx.setType(type);
            tx.setAmount(amount);
            tx.setOccurredAt(parseTs(requireText(node, "occurredAt", "交易")));
            tx.setNote(hasRef(node, "note") ? node.get("note").asText() : null);
            tx.setCreatedAt(now);
            tx.setUpdatedAt(now);

            if (type == TransactionType.TRANSFER) {
                Account source = requireAccount(accountByRef, node, "sourceAccountRef");
                Account destination = requireAccount(accountByRef, node, "destinationAccountRef");
                tx.setSourceAccountId(source.getId());
                tx.setDestinationAccountId(destination.getId());
                // 需求 8.5：转账源 −amount、目标 +amount，与正常记账语义一致。
                source.setCurrentBalance(source.getCurrentBalance().subtract(amount));
                destination.setCurrentBalance(destination.getCurrentBalance().add(amount));
            } else {
                Account account = requireAccount(accountByRef, node, "accountRef");
                Category category = requireCategory(categoryByRef, node);
                tx.setAccountId(account.getId());
                tx.setCategoryId(category.getId());
                // 需求 8.5：支出 −amount、收入 +amount。
                if (type == TransactionType.EXPENSE) {
                    account.setCurrentBalance(account.getCurrentBalance().subtract(amount));
                } else {
                    account.setCurrentBalance(account.getCurrentBalance().add(amount));
                }
            }
            transactionRepository.save(tx);
            count++;
        }
        return count;
    }

    // ---------------- 解析工具 ----------------

    private Account requireAccount(Map<String, Account> byRef, JsonNode node, String field) {
        String ref = requireText(node, field, "交易");
        Account account = byRef.get(ref);
        if (account == null) {
            throw ApiException.importInvalid("交易引用了不存在的账户: " + ref);
        }
        return account;
    }

    private Category requireCategory(Map<String, Category> byRef, JsonNode node) {
        String ref = requireText(node, "categoryRef", "交易");
        Category category = byRef.get(ref);
        if (category == null) {
            throw ApiException.importInvalid("交易引用了不存在的分类: " + ref);
        }
        return category;
    }

    /** 读取必需的非空文本字段，缺失即视为文档非法。 */
    private static String requireText(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw ApiException.importInvalid(context + "缺少必要字段: " + field);
        }
        return value.asText();
    }

    /** 判断字段存在且为非 null 值（用于可选的 parentRef / note）。 */
    private static boolean hasRef(JsonNode node, String field) {
        return node.hasNonNull(field);
    }

    /** 金额解析：字符串 → 缩放至两位小数的 {@link BigDecimal}（DECIMAL(18,2)）。 */
    private static BigDecimal money(String raw) {
        try {
            return new BigDecimal(raw).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw ApiException.importInvalid("金额格式非法: " + raw);
        }
    }

    /** 时间解析：带 UTC+8 偏移的 ISO-8601 → 本地时间（导出的逆操作）。 */
    private static LocalDateTime parseTs(String raw) {
        try {
            return OffsetDateTime.parse(raw, TS).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (RuntimeException e) {
            throw ApiException.importInvalid("时间格式非法: " + raw);
        }
    }

    private static AccountType parseAccountType(String raw) {
        try {
            return AccountType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.importInvalid("不支持的账户类型: " + raw);
        }
    }

    private static CategoryKind parseCategoryKind(String raw) {
        try {
            return CategoryKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.importInvalid("不支持的分类种类: " + raw);
        }
    }

    private static TransactionType parseTransactionType(String raw) {
        try {
            return TransactionType.fromCode(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.importInvalid("不支持的交易类型: " + raw);
        }
    }
}
