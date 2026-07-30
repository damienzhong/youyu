package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;
import com.damien.youyu.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ExportService} 的示例与边界单元测试（关联需求 8.1-8.4、8.6、8.7）。
 *
 * <p>使用 H2 + 真实 Repository，不使用任何桩，以固定 {@link Clock} 做确定性时间。覆盖：
 * 非空 JSON 导出的引用键与记录数正确、空数据导出结构有效且记录数为 0、导出隔离仅含本人数据、
 * CSV 带 UTF-8 BOM 且中文以 UTF-8 正确编码。属性测试（Property 18/19）在任务 8.3 中实现。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExportServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long USER = 1L;
    private static final long OTHER_USER = 2L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private com.damien.youyu.repository.AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private TransactionTagRepository transactionTagRepository;
    @Autowired
    private UserRepository userRepository;

    private ExportService service() {
        return new ExportService(accountRepository, accountLedgerRepository, categoryRepository,
                transactionRepository, projectRepository, merchantRepository, tagRepository,
                transactionTagRepository, userRepository, Clock.fixed(T0, ZONE));
    }

    // ---------------- JSON 导出：引用键与记录数（需求 8.2、8.5 基础） ----------------

    @Test
    void writeJson_nonEmpty_hasBusinessRefsAndCorrectRecordCounts() throws Exception {
        Account cash = account(USER, "现金", AccountType.CASH, "100.00", 0);
        Account card = account(USER, "工资卡", AccountType.BANK_CARD, "0.00", 1);
        Category food = category(USER, CategoryKind.EXPENSE, "餐饮", null);
        Category takeout = category(USER, CategoryKind.EXPENSE, "外卖", food.getId());

        expense(USER, "23.50", cash.getId(), takeout.getId(), "午餐");
        income(USER, "2500.00", card.getId(), food.getId(), null); // 收入分类复用父分类
        transfer(USER, "500.00", card.getId(), cash.getId(), "取现");

        JsonNode root = MAPPER.readTree(json(USER));

        // 结构有效：schemaVersion 与 exportedAt 存在。
        assertThat(root.get("schemaVersion").asInt()).isEqualTo(ExportService.SCHEMA_VERSION);
        assertThat(root.get("exportedAt").asText()).contains("+08:00");

        // 记录数正确。
        JsonNode accounts = root.get("accounts");
        JsonNode categories = root.get("categories");
        JsonNode transactions = root.get("transactions");
        assertThat(accounts).hasSize(2);
        assertThat(categories).hasSize(2);
        assertThat(transactions).hasSize(3);

        // 账户使用业务引用键（a1/a2）而非自增 id，金额为两位小数字符串。
        assertThat(accounts.get(0).get("ref").asText()).isEqualTo("a1");
        assertThat(accounts.get(0).get("name").asText()).isEqualTo("现金");
        assertThat(accounts.get(0).get("type").asText()).isEqualTo("CASH");
        assertThat(accounts.get(0).get("initialBalance").asText()).isEqualTo("100.00");
        assertThat(accounts.get(0).has("id")).isFalse();

        // 子分类的 parentRef 指向父分类的 ref；父分类 parentRef 为 null。
        String foodRef = categories.get(0).get("ref").asText();
        assertThat(categories.get(0).get("parentRef").isNull()).isTrue();
        assertThat(categories.get(1).get("name").asText()).isEqualTo("外卖");
        assertThat(categories.get(1).get("parentRef").asText()).isEqualTo(foodRef);

        // 支出交易引用键自洽：accountRef/categoryRef 指向已导出的 ref。
        JsonNode expenseTx = findTx(transactions, "expense");
        assertThat(expenseTx.get("amount").asText()).isEqualTo("23.50");
        assertThat(expenseTx.get("accountRef").asText()).isEqualTo("a1"); // 现金
        assertThat(expenseTx.get("categoryRef").asText())
                .isEqualTo(categories.get(1).get("ref").asText()); // 外卖
        assertThat(expenseTx.get("occurredAt").asText()).contains("+08:00");
        assertThat(expenseTx.get("note").asText()).isEqualTo("午餐");

        // 转账交易使用 source/destination 引用键，无 accountRef/categoryRef。
        JsonNode transferTx = findTx(transactions, "transfer");
        assertThat(transferTx.get("amount").asText()).isEqualTo("500.00");
        assertThat(transferTx.get("sourceAccountRef").asText()).isEqualTo("a2"); // 工资卡
        assertThat(transferTx.get("destinationAccountRef").asText()).isEqualTo("a1"); // 现金
        assertThat(transferTx.has("accountRef")).isFalse();
        assertThat(transferTx.has("categoryRef")).isFalse();
    }

    // ---------------- 空数据导出（需求 8.7） ----------------

    @Test
    void writeJson_emptyData_producesValidStructureWithZeroRecords() throws Exception {
        JsonNode root = MAPPER.readTree(json(USER));

        assertThat(root.get("schemaVersion").asInt()).isEqualTo(ExportService.SCHEMA_VERSION);
        assertThat(root.get("accounts")).isEmpty();
        assertThat(root.get("categories")).isEmpty();
        assertThat(root.get("transactions")).isEmpty();
    }

    @Test
    void writeCsv_emptyData_producesValidStructureWithHeadersOnly() {
        byte[] bytes = csv(USER);
        // 去掉 BOM 后解码。
        String content = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);

        // 三段表头均存在、无数据行。
        assertThat(content).contains("# accounts");
        assertThat(content).contains("ref,name,type,initialBalance,sortOrder");
        assertThat(content).contains("# categories");
        assertThat(content).contains("ref,kind,name,parentRef");
        assertThat(content).contains("# transactions");
        assertThat(content).contains(
                "type,amount,accountRef,categoryRef,sourceAccountRef,destinationAccountRef,occurredAt,note,project,merchant,tags,recorder");
    }

    // ---------------- 导出隔离（需求 8.4） ----------------

    @Test
    void writeJson_isolation_containsOnlyRequestingUsersData() throws Exception {
        Account mine = account(USER, "我的现金", AccountType.CASH, "10.00", 0);
        Category mineCat = category(USER, CategoryKind.EXPENSE, "我的餐饮", null);
        expense(USER, "5.00", mine.getId(), mineCat.getId(), "我的午餐");

        // 另一用户的数据不应出现在本人导出中。
        Account other = account(OTHER_USER, "别人的卡", AccountType.BANK_CARD, "9999.00", 0);
        Category otherCat = category(OTHER_USER, CategoryKind.INCOME, "别人的工资", null);
        income(OTHER_USER, "8888.00", other.getId(), otherCat.getId(), "别人的收入");

        JsonNode root = MAPPER.readTree(json(USER));

        assertThat(root.get("accounts")).hasSize(1);
        assertThat(root.get("accounts").get(0).get("name").asText()).isEqualTo("我的现金");
        assertThat(root.get("categories")).hasSize(1);
        assertThat(root.get("categories").get(0).get("name").asText()).isEqualTo("我的餐饮");
        assertThat(root.get("transactions")).hasSize(1);
        assertThat(root.get("transactions").get(0).get("note").asText()).isEqualTo("我的午餐");

        // 全文不含他人任何字段值。
        String raw = json(USER);
        assertThat(raw).doesNotContain("别人的卡", "别人的工资", "别人的收入", "8888.00");
    }

    // ---------------- CSV BOM 与 UTF-8（需求 8.1） ----------------

    @Test
    void writeCsv_hasUtf8BomAndEncodesChineseCorrectly() {
        Account cash = account(USER, "现金", AccountType.CASH, "100.00", 0);
        Category food = category(USER, CategoryKind.EXPENSE, "餐饮", null);
        expense(USER, "23.50", cash.getId(), food.getId(), "午餐, 加饮料"); // 含逗号，触发 CSV 引号转义

        byte[] bytes = csv(USER);

        // 需求 8.1：以 UTF-8 BOM（EF BB BF）开头。
        assertThat(bytes.length).isGreaterThan(3);
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);

        String content = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        // 中文以 UTF-8 正确编码、可读；引用键自洽。
        assertThat(content).contains("a1,现金,CASH,100.00,0");
        assertThat(content).contains("c1,EXPENSE,餐饮,");
        // 含逗号的备注被双引号包裹。
        assertThat(content).contains("\"午餐, 加饮料\"");
        // 交易行使用引用键，金额两位小数。
        assertThat(content).contains("expense,23.50,a1,c1,,,");
    }

    // ---------------- 测试数据构造 ----------------

    private String json(long ledgerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service().writeJson(ledgerId, ledgerId, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private byte[] csv(long ledgerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service().writeCsv(ledgerId, ledgerId, out);
        return out.toByteArray();
    }

    private static JsonNode findTx(JsonNode transactions, String type) {
        for (JsonNode t : transactions) {
            if (type.equals(t.get("type").asText())) {
                return t;
            }
        }
        throw new AssertionError("未找到类型为 " + type + " 的交易");
    }

    private Account account(long ledgerId, String name, AccountType type, String initial, int sortOrder) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Account a = new Account();
        a.setUserId(ledgerId);
        a.setName(name);
        a.setType(type);
        a.setInitialBalance(new BigDecimal(initial));
        a.setCurrentBalance(new BigDecimal(initial));
        a.setSortOrder(sortOrder);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        Account saved = accountRepository.save(a);
        // 纳入账本，使其出现在该账本导出中。
        com.damien.youyu.domain.AccountLedger al = new com.damien.youyu.domain.AccountLedger();
        al.setAccountId(saved.getId());
        al.setLedgerId(ledgerId);
        al.setVisibleToOthers(true);
        al.setShowBalance(true);
        al.setCreatedAt(now);
        accountLedgerRepository.save(al);
        return saved;
    }

    private Category category(long ledgerId, CategoryKind kind, String name, Long parentId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(name);
        c.setParentId(parentId);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private void expense(long ledgerId, String amount, Long accountId, Long categoryId, String note) {
        save(ledgerId, TransactionType.EXPENSE, amount, accountId, categoryId, null, null, note);
    }

    private void income(long ledgerId, String amount, Long accountId, Long categoryId, String note) {
        save(ledgerId, TransactionType.INCOME, amount, accountId, categoryId, null, null, note);
    }

    private void transfer(long ledgerId, String amount, Long sourceId, Long destId, String note) {
        save(ledgerId, TransactionType.TRANSFER, amount, null, null, sourceId, destId, note);
    }

    private void save(long ledgerId, TransactionType type, String amount, Long accountId,
            Long categoryId, Long sourceId, Long destId, String note) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setAccountId(accountId);
        t.setCategoryId(categoryId);
        t.setSourceAccountId(sourceId);
        t.setDestinationAccountId(destId);
        t.setOccurredAt(now);
        t.setNote(note);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        transactionRepository.save(t);
    }
}
