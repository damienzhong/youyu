package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * {@link ExportService} 的集成测试（关联需求 8.1、8.2）。使用 {@code @DataJpaTest} + 真实 H2 与真实
 * Repository，不使用任何桩，覆盖 prework 标记为 INTEGRATION 的两项：
 *
 * <ol>
 *   <li><b>CSV/JSON 全量 UTF-8 导出</b>：构造含中文的账户/两级分类/三类交易全量数据，分别导出 CSV 与
 *       JSON，断言 CSV 以 UTF-8 BOM 开头、中文以 UTF-8 正确编码且可读，JSON 以 UTF-8 解析且中文完整保留、
 *       三类记录数正确（需求 8.1、8.2）。</li>
 *   <li><b>导出 30 秒上界</b>：需求 8.1/8.2 要求各类记录不超过 10 万条时于 30 秒内完成导出。为使 CI 构建
 *       在可接受时长内完成，此处按判断<b>缩放</b>数据规模至 {@value #PERF_TX_COUNT} 笔交易（约 2 万量级，
 *       仍足以体现流式写出的吞吐特征），并对<b>导出耗时</b>断言同一 30 秒上界。流式实现下该规模远低于上界；
 *       若未来退化为全量载入内存，此保护性用例可提前暴露风险。</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExportIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZONE);
    private static final LocalDateTime BASE = LocalDateTime.ofInstant(T0, ZONE);
    private static final long USER = 1L;

    /** 需求 8.1/8.2 的导出时间上界。 */
    private static final Duration EXPORT_UPPER_BOUND = Duration.ofSeconds(30);

    /**
     * 性能用例缩放后的交易规模（需求上界为 10 万条）。选取约 2 万量级以在体现流式吞吐的同时
     * 保证 CI 构建时长可控；对导出耗时仍断言 30 秒上界。
     */
    private static final int PERF_TX_COUNT = 20_000;

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

    private ExportService exportService() {
        return new ExportService(accountRepository, accountLedgerRepository, categoryRepository,
                transactionRepository, projectRepository, merchantRepository, tagRepository,
                transactionTagRepository, userRepository, CLOCK);
    }

    // ---------------- 集成测试 1：CSV/JSON 全量 UTF-8 导出 ----------------

    @Test
    void fullExport_csvAndJson_areUtf8WithChineseContentIntact() throws Exception {
        Account cash = account("现金钱包", AccountType.CASH, "1000.00", 0);
        Account card = account("招商银行卡", AccountType.BANK_CARD, "0.00", 1);
        Category food = category(CategoryKind.EXPENSE, "餐饮美食", null);
        Category takeout = category(CategoryKind.EXPENSE, "外卖订餐", food.getId());
        Category salary = category(CategoryKind.INCOME, "工资薪水", null);

        expense("38.80", cash.getId(), takeout.getId(), "午餐，麻辣烫");
        income("12500.00", card.getId(), salary.getId(), "六月工资");
        transfer("2000.00", card.getId(), cash.getId(), "取现金");

        // ---- JSON：UTF-8 解析、中文完整、记录数正确 ----
        byte[] jsonBytes = exportJson();
        JsonNode root = MAPPER.readTree(new String(jsonBytes, StandardCharsets.UTF_8));
        assertThat(root.get("accounts")).hasSize(2);
        assertThat(root.get("categories")).hasSize(3);
        assertThat(root.get("transactions")).hasSize(3);
        String jsonText = new String(jsonBytes, StandardCharsets.UTF_8);
        assertThat(jsonText).contains("现金钱包", "招商银行卡", "餐饮美食", "外卖订餐", "工资薪水");
        assertThat(jsonText).contains("午餐，麻辣烫", "六月工资", "取现金");

        // ---- CSV：UTF-8 BOM 开头、中文以 UTF-8 正确编码 ----
        byte[] csvBytes = exportCsv();
        assertThat(csvBytes.length).isGreaterThan(3);
        assertThat(csvBytes[0]).isEqualTo((byte) 0xEF);
        assertThat(csvBytes[1]).isEqualTo((byte) 0xBB);
        assertThat(csvBytes[2]).isEqualTo((byte) 0xBF);
        String csvText = new String(csvBytes, 3, csvBytes.length - 3, StandardCharsets.UTF_8);
        assertThat(csvText).contains("# accounts", "# categories", "# transactions");
        assertThat(csvText).contains("现金钱包", "招商银行卡", "餐饮美食", "外卖订餐", "工资薪水");
        // 含中文逗号「，」不触发 CSV 引号（仅 ASCII 逗号需转义），备注原样保留。
        assertThat(csvText).contains("午餐，麻辣烫", "六月工资", "取现金");
    }

    // ---------------- 集成测试 2：导出 30 秒上界（缩放规模） ----------------

    @Test
    void fullExport_atLargeScale_completesWithinUpperBound() {
        Account cash = account("现金", AccountType.CASH, "0.00", 0);
        Account card = account("银行卡", AccountType.BANK_CARD, "0.00", 1);
        Category food = category(CategoryKind.EXPENSE, "餐饮", null);
        Category salary = category(CategoryKind.INCOME, "工资", null);

        // 批量生成缩放后的交易规模。
        List<Transaction> batch = new ArrayList<>(PERF_TX_COUNT);
        for (int i = 0; i < PERF_TX_COUNT; i++) {
            Transaction t = new Transaction();
            t.setLedgerId(USER);
            LocalDateTime when = BASE.plusSeconds(i);
            int kind = i % 3;
            if (kind == 2) {
                t.setType(TransactionType.TRANSFER);
                t.setSourceAccountId(card.getId());
                t.setDestinationAccountId(cash.getId());
            } else if (kind == 1) {
                t.setType(TransactionType.INCOME);
                t.setAccountId(card.getId());
                t.setCategoryId(salary.getId());
            } else {
                t.setType(TransactionType.EXPENSE);
                t.setAccountId(cash.getId());
                t.setCategoryId(food.getId());
            }
            t.setAmount(new BigDecimal("12.34"));
            t.setOccurredAt(when);
            t.setNote(i % 5 == 0 ? "备注" + i : null);
            t.setCreatedAt(BASE);
            t.setUpdatedAt(BASE);
            batch.add(t);
        }
        transactionRepository.saveAll(batch);
        transactionRepository.flush();

        // 仅测量导出耗时（不含数据构造/插入），断言 30 秒上界（需求 8.1、8.2）。
        long jsonStart = System.nanoTime();
        byte[] json = exportJson();
        Duration jsonElapsed = Duration.ofNanos(System.nanoTime() - jsonStart);

        long csvStart = System.nanoTime();
        byte[] csv = exportCsv();
        Duration csvElapsed = Duration.ofNanos(System.nanoTime() - csvStart);

        assertThat(json.length).isGreaterThan(0);
        assertThat(csv.length).isGreaterThan(3);
        assertThat(jsonElapsed).as("JSON 导出耗时应在 30 秒内（实际 %d ms）", jsonElapsed.toMillis())
                .isLessThan(EXPORT_UPPER_BOUND);
        assertThat(csvElapsed).as("CSV 导出耗时应在 30 秒内（实际 %d ms）", csvElapsed.toMillis())
                .isLessThan(EXPORT_UPPER_BOUND);
    }

    // ---------------- 测试数据构造 ----------------

    private byte[] exportJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService().writeJson(USER, USER, out);
        return out.toByteArray();
    }

    private byte[] exportCsv() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService().writeCsv(USER, USER, out);
        return out.toByteArray();
    }

    private Account account(String name, AccountType type, String initial, int sortOrder) {
        Account a = new Account();
        a.setUserId(USER);
        a.setName(name);
        a.setType(type);
        a.setInitialBalance(new BigDecimal(initial));
        a.setCurrentBalance(new BigDecimal(initial));
        a.setSortOrder(sortOrder);
        a.setCreatedAt(BASE);
        a.setUpdatedAt(BASE);
        Account saved = accountRepository.save(a);
        com.damien.youyu.domain.AccountLedger al = new com.damien.youyu.domain.AccountLedger();
        al.setAccountId(saved.getId());
        al.setLedgerId(USER);
        al.setVisibleToOthers(true);
        al.setShowBalance(true);
        al.setCreatedAt(BASE);
        accountLedgerRepository.save(al);
        return saved;
    }

    private Category category(CategoryKind kind, String name, Long parentId) {
        Category c = new Category();
        c.setLedgerId(USER);
        c.setKind(kind);
        c.setName(name);
        c.setParentId(parentId);
        c.setCreatedAt(BASE);
        c.setUpdatedAt(BASE);
        return categoryRepository.save(c);
    }

    private void expense(String amount, Long accountId, Long categoryId, String note) {
        save(TransactionType.EXPENSE, amount, accountId, categoryId, null, null, note);
    }

    private void income(String amount, Long accountId, Long categoryId, String note) {
        save(TransactionType.INCOME, amount, accountId, categoryId, null, null, note);
    }

    private void transfer(String amount, Long sourceId, Long destId, String note) {
        save(TransactionType.TRANSFER, amount, null, null, sourceId, destId, note);
    }

    private void save(TransactionType type, String amount, Long accountId,
            Long categoryId, Long sourceId, Long destId, String note) {
        Transaction t = new Transaction();
        t.setLedgerId(USER);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setAccountId(accountId);
        t.setCategoryId(categoryId);
        t.setSourceAccountId(sourceId);
        t.setDestinationAccountId(destId);
        t.setOccurredAt(BASE);
        t.setNote(note);
        t.setCreatedAt(BASE);
        t.setUpdatedAt(BASE);
        transactionRepository.save(t);
    }
}
