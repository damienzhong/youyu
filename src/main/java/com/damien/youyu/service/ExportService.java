package com.damien.youyu.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.Merchant;
import com.damien.youyu.domain.Project;
import com.damien.youyu.domain.Tag;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionTag;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;
import com.damien.youyu.repository.UserRepository;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/**
 * 数据导出服务：将当前用户的全部 Account/Category/Transaction 导出为 CSV 或 JSON（关联需求 8.1-8.4、8.6、8.7）。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>导出该用户<b>全部</b>账户、分类与交易，一律以 UTF-8 编码；CSV 额外写入 UTF-8 BOM，
 *       以便 Excel 正确识别中文（需求 8.1）。</li>
 *   <li><b>流式写出</b>：直接写入调用方提供的 {@link OutputStream}（配合 Controller 的
 *       {@code StreamingResponseBody}），交易记录以流式游标逐条写出，避免将全量数据载入内存
 *       （需求 8.1、8.2；10 万条量级见任务 8.3）。</li>
 *   <li>JSON 采用<b>业务引用键</b>（{@code ref}/{@code parentRef}/{@code accountRef}/
 *       {@code categoryRef}/{@code sourceAccountRef}/{@code destinationAccountRef}）而非数据库自增 ID，
 *       以便导入到空账户时重建引用关系（需求 8.5，导入见任务 8.2）。CSV 亦复用同一套引用键以保持自洽。</li>
 *   <li>空数据生成结构有效、业务记录数为 0 的文件（需求 8.7）。</li>
 *   <li>仅包含请求用户本人的数据（需求 8.4）；对任何 plan 免费、无门控（需求 8.3）。</li>
 *   <li>导出失败抛出 {@link ApiException#exportFailed()}；读操作在只读事务内完成，不改动既有数据（需求 8.6）。</li>
 * </ul>
 *
 * <p>金额一律以 {@link BigDecimal}（DECIMAL(18,2)）序列化为两位小数的字符串；时间以
 * {@code Asia/Shanghai}（UTC+8）偏移的 ISO-8601 字符串输出（如 {@code 2025-06-01T12:30:00+08:00}）。</p>
 */
@Service
public class ExportService {

    /** 导出文档 schema 版本（需求 8.5 往返一致性的版本基线）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 业务时区（UTC+8）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 带偏移的 ISO-8601 时间格式（如 {@code 2025-06-01T12:30:00+08:00}）。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** UTF-8 BOM 字节序列（EF BB BF），供 CSV 前置写入以便 Excel 识别（需求 8.1）。 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** CSV 行分隔（CRLF，兼容 Excel）。 */
    private static final String CRLF = "\r\n";

    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final ProjectRepository projectRepository;
    private final MerchantRepository merchantRepository;
    private final TagRepository tagRepository;
    private final TransactionTagRepository transactionTagRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final JsonFactory jsonFactory = new JsonFactory();

    public ExportService(
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            TransactionRepository transactionRepository,
            ProjectRepository projectRepository,
            MerchantRepository merchantRepository,
            TagRepository tagRepository,
            TransactionTagRepository transactionTagRepository,
            UserRepository userRepository,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.projectRepository = projectRepository;
        this.merchantRepository = merchantRepository;
        this.tagRepository = tagRepository;
        this.transactionTagRepository = transactionTagRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * 将该用户全部数据以 JSON 流式写入 {@code out}（需求 8.2、8.4、8.7）。
     *
     * <p>先加载有界的账户与分类以建立引用键映射，再以只读游标流式写出交易，逐条序列化。
     * 空数据时 accounts/categories/transactions 三个数组均为空，文档结构仍有效（需求 8.7）。</p>
     */
    @Transactional(readOnly = true)
    public void writeJson(Long userId, Long ledgerId, OutputStream out) {
        writeJson(AccountScope.independent(userId), ledgerId, out);
    }

    @Transactional(readOnly = true)
    public void writeJson(AccountScope scope, Long ledgerId, OutputStream out) {
        List<Account> accounts = scopedAccounts(scope);
        List<Category> categories = orderedCategories(ledgerId);
        Map<Long, String> accountRef = accountRefs(accounts);
        Map<Long, String> categoryRef = categoryRefs(categories);

        try (JsonGenerator g = jsonFactory.createGenerator(out, JsonEncoding.UTF8)) {
            g.writeStartObject();
            g.writeNumberField("schemaVersion", SCHEMA_VERSION);
            g.writeStringField("exportedAt", nowTs());

            g.writeArrayFieldStart("accounts");
            for (Account a : accounts) {
                g.writeStartObject();
                g.writeStringField("ref", accountRef.get(a.getId()));
                g.writeStringField("name", a.getName());
                g.writeStringField("type", a.getType().name());
                g.writeStringField("initialBalance", money(a.getInitialBalance()));
                g.writeNumberField("sortOrder", a.getSortOrder());
                g.writeEndObject();
            }
            g.writeEndArray();

            g.writeArrayFieldStart("categories");
            for (Category c : categories) {
                g.writeStartObject();
                g.writeStringField("ref", categoryRef.get(c.getId()));
                g.writeStringField("kind", c.getKind().name());
                g.writeStringField("name", c.getName());
                if (c.getParentId() != null) {
                    g.writeStringField("parentRef", categoryRef.get(c.getParentId()));
                } else {
                    g.writeNullField("parentRef");
                }
                g.writeEndObject();
            }
            g.writeEndArray();

            g.writeArrayFieldStart("transactions");
            try (Stream<Transaction> stream = transactionRepository.streamByLedgerIdOrderById(ledgerId)) {
                Iterator<Transaction> it = stream.iterator();
                while (it.hasNext()) {
                    writeTransactionJson(g, it.next(), accountRef, categoryRef);
                }
            }
            g.writeEndArray();

            g.writeEndObject();
            g.flush();
        } catch (IOException e) {
            // 需求 8.6：导出失败不提供部分文件（读操作在只读事务内，未改动既有数据）。
            throw ApiException.exportFailed();
        }
    }

    /**
     * 将该用户全部数据以 CSV（UTF-8 带 BOM）流式写入 {@code out}（需求 8.1、8.4、8.7）。
     *
     * <p>采用三段布局：{@code # accounts}、{@code # categories}、{@code # transactions}，
     * 各段首行为表头。三段之间以空行分隔。引用键与 JSON 一致，保证 CSV 自洽可再导入。</p>
     */
    @Transactional(readOnly = true)
    public void writeCsv(Long userId, Long ledgerId, OutputStream out) {
        writeCsv(AccountScope.independent(userId), ledgerId, out);
    }

    @Transactional(readOnly = true)
    public void writeCsv(AccountScope scope, Long ledgerId, OutputStream out) {
        List<Account> accounts = scopedAccounts(scope);
        List<Category> categories = orderedCategories(ledgerId);
        Map<Long, String> accountRef = accountRefs(accounts);
        Map<Long, String> categoryRef = categoryRefs(categories);

        try {
            // 需求 8.1：UTF-8 BOM 前置，Excel 正确识别中文。
            out.write(UTF8_BOM);
            Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

            w.write("# accounts" + CRLF);
            w.write(csvRow("ref", "name", "type", "initialBalance", "sortOrder"));
            for (Account a : accounts) {
                w.write(csvRow(
                        accountRef.get(a.getId()),
                        a.getName(),
                        a.getType().name(),
                        money(a.getInitialBalance()),
                        String.valueOf(a.getSortOrder())));
            }
            w.write(CRLF);

            w.write("# categories" + CRLF);
            w.write(csvRow("ref", "kind", "name", "parentRef"));
            for (Category c : categories) {
                w.write(csvRow(
                        categoryRef.get(c.getId()),
                        c.getKind().name(),
                        c.getName(),
                        c.getParentId() == null ? "" : categoryRef.get(c.getParentId())));
            }
            w.write(CRLF);

            // 项目/商家/标签/记账人：以可读名称附于 CSV 末列（供人工查看；JSON 保持精简以护往返一致）。
            Map<Long, String> projectName = projectNameMap(ledgerId);
            Map<Long, String> merchantName = merchantNameMap(ledgerId);
            Map<Long, List<String>> tagsByTx = tagNamesByTransaction(ledgerId);
            Map<Long, String> recorderCache = new HashMap<>();

            w.write("# transactions" + CRLF);
            w.write(csvRow("type", "amount", "accountRef", "categoryRef",
                    "sourceAccountRef", "destinationAccountRef", "occurredAt", "note",
                    "project", "merchant", "tags", "recorder"));
            try (Stream<Transaction> stream = transactionRepository.streamByLedgerIdOrderById(ledgerId)) {
                Iterator<Transaction> it = stream.iterator();
                while (it.hasNext()) {
                    Transaction t = it.next();
                    boolean transfer = t.getType() == TransactionType.TRANSFER;
                    List<String> tagNames = tagsByTx.getOrDefault(t.getId(), List.of());
                    w.write(csvRow(
                            t.getType().getCode(),
                            money(t.getAmount()),
                            transfer ? "" : refOrEmpty(accountRef, t.getAccountId()),
                            transfer ? "" : refOrEmpty(categoryRef, t.getCategoryId()),
                            transfer ? refOrEmpty(accountRef, t.getSourceAccountId()) : "",
                            transfer ? refOrEmpty(accountRef, t.getDestinationAccountId()) : "",
                            ts(t.getOccurredAt()),
                            t.getNote() == null ? "" : t.getNote(),
                            t.getProjectId() == null ? "" : projectName.getOrDefault(t.getProjectId(), ""),
                            t.getMerchantId() == null ? "" : merchantName.getOrDefault(t.getMerchantId(), ""),
                            String.join(";", tagNames),
                            recorderName(t.getCreatedBy(), recorderCache)));
                }
            }
            // 仅刷新，不关闭底层流（由 StreamingResponseBody / 容器管理其生命周期）。
            w.flush();
        } catch (IOException e) {
            // 需求 8.6：导出失败不提供部分文件（读操作在只读事务内，未改动既有数据）。
            throw ApiException.exportFailed();
        }
    }

    // ---------------- 内部工具 ----------------

    private void writeTransactionJson(
            JsonGenerator g, Transaction t,
            Map<Long, String> accountRef, Map<Long, String> categoryRef) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", t.getType().getCode());
        g.writeStringField("amount", money(t.getAmount()));
        if (t.getType() == TransactionType.TRANSFER) {
            g.writeStringField("sourceAccountRef", refOrNull(accountRef, t.getSourceAccountId()));
            g.writeStringField("destinationAccountRef", refOrNull(accountRef, t.getDestinationAccountId()));
        } else {
            g.writeStringField("accountRef", refOrNull(accountRef, t.getAccountId()));
            g.writeStringField("categoryRef", refOrNull(categoryRef, t.getCategoryId()));
        }
        g.writeStringField("occurredAt", ts(t.getOccurredAt()));
        if (t.getNote() != null) {
            g.writeStringField("note", t.getNote());
        } else {
            g.writeNullField("note");
        }
        g.writeEndObject();
    }

    /** 按作用域取账户：独立账本用户级、协作账本账本级。 */
    private List<Account> scopedAccounts(AccountScope scope) {
        return scope.isCollaborative()
                ? accountRepository.findByLedgerIdOrderBySortOrderAscIdAsc(scope.ledgerId())
                : accountRepository.findByUserIdAndLedgerIdIsNullOrderBySortOrderAscIdAsc(scope.userId());
    }

    /** 分类排序：父分类（parentId 为空）在前、再按 id 升序，保证 parentRef 先于其子分类出现（利于导入）。 */
    private List<Category> orderedCategories(Long ledgerId) {
        List<Category> list = new ArrayList<>(categoryRepository.findByLedgerId(ledgerId));
        list.sort(Comparator
                .comparing((Category c) -> c.getParentId() != null)
                .thenComparing(Category::getId));
        return list;
    }

    /** 项目 id→名称（本账本）。 */
    private Map<Long, String> projectNameMap(Long ledgerId) {
        Map<Long, String> map = new HashMap<>();
        for (Project p : projectRepository.findByLedgerIdOrderByArchivedAscSortOrderAscIdAsc(ledgerId)) {
            map.put(p.getId(), p.getName());
        }
        return map;
    }

    /** 商家 id→名称（本账本）。 */
    private Map<Long, String> merchantNameMap(Long ledgerId) {
        Map<Long, String> map = new HashMap<>();
        for (Merchant m : merchantRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId)) {
            map.put(m.getId(), m.getName());
        }
        return map;
    }

    /** 交易 id→标签名列表（预载本账本全部关联，避免 N+1）。 */
    private Map<Long, List<String>> tagNamesByTransaction(Long ledgerId) {
        Map<Long, String> tagName = new HashMap<>();
        for (Tag t : tagRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId)) {
            tagName.put(t.getId(), t.getName());
        }
        Map<Long, List<String>> byTx = new HashMap<>();
        for (TransactionTag tt : transactionTagRepository.findByLedgerId(ledgerId)) {
            String name = tagName.get(tt.getTagId());
            if (name != null) {
                byTx.computeIfAbsent(tt.getTransactionId(), k -> new ArrayList<>()).add(name);
            }
        }
        return byTx;
    }

    /** 记账人 id→显示名（缓存，减少查询）；未知返回空串。 */
    private String recorderName(Long userId, Map<Long, String> cache) {
        if (userId == null) {
            return "";
        }
        return cache.computeIfAbsent(userId, id ->
                userRepository.findById(id).map(User::getUsername).orElse("用户" + id));
    }

    /** 账户业务引用键：按列表顺序生成 a1、a2、……。 */
    private Map<Long, String> accountRefs(List<Account> accounts) {
        Map<Long, String> map = new HashMap<>();
        for (int i = 0; i < accounts.size(); i++) {
            map.put(accounts.get(i).getId(), "a" + (i + 1));
        }
        return map;
    }

    /** 分类业务引用键：按排序顺序生成 c1、c2、……。 */
    private Map<Long, String> categoryRefs(List<Category> categories) {
        Map<Long, String> map = new HashMap<>();
        for (int i = 0; i < categories.size(); i++) {
            map.put(categories.get(i).getId(), "c" + (i + 1));
        }
        return map;
    }

    private static String refOrNull(Map<Long, String> refs, Long id) {
        return id == null ? null : refs.get(id);
    }

    private static String refOrEmpty(Map<Long, String> refs, Long id) {
        String ref = refOrNull(refs, id);
        return ref == null ? "" : ref;
    }

    /** 金额序列化：DECIMAL(18,2) → 两位小数纯字符串（如 {@code "0.00"}、{@code "23.50"}）。 */
    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 交易/账户时间序列化为带 UTC+8 偏移的 ISO-8601 字符串。 */
    private static String ts(LocalDateTime when) {
        return when.atZone(ZONE).toOffsetDateTime().format(TS);
    }

    private String nowTs() {
        return LocalDateTime.now(clock).atZone(ZONE).toOffsetDateTime().format(TS);
    }

    /** 组装一行 CSV：逐字段转义后以逗号连接，行尾 CRLF。 */
    private static String csvRow(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvEscape(fields[i]));
        }
        sb.append(CRLF);
        return sb.toString();
    }

    /**
     * CSV 字段转义（RFC 4180）：含逗号、双引号或换行的字段用双引号包裹，内部双引号翻倍。
     */
    private static String csvEscape(String field) {
        if (field == null) {
            return "";
        }
        boolean needQuote = field.indexOf(',') >= 0
                || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0;
        if (!needQuote) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
