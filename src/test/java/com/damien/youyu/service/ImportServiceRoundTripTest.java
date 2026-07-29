package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

/**
 * {@link ImportService} 的往返一致性示例单元测试（关联需求 8.5）。
 *
 * <p>用 H2 + 真实 Repository、固定 {@link Clock}，不使用任何桩。核心场景：为用户 A 构造包含
 * 两级分类、支出/收入/转账三类交易的样本数据，用 {@link ExportService} 导出为 JSON，再用
 * {@link ImportService} 导入到<b>空账户</b>用户 B，随后断言：</p>
 * <ul>
 *   <li>账户/分类/交易记录数与 A 一致（{@link ImportService.ImportResult}）。</li>
 *   <li>账户业务字段（name/type/initialBalance/sortOrder）逐一相等，仅系统 id 不同。</li>
 *   <li>分类父子引用被正确重建（子分类指向对应父分类）。</li>
 *   <li>交易业务字段（type/amount/引用目标名称/occurredAt/note）逐一相等。</li>
 *   <li>还原后每个账户的 {@code current_balance} 与由初始余额+全量流水重算的结果一致
 *       （{@link AccountService#recomputeBalance}），且与 A 对应账户的 current_balance 相等。</li>
 *   <li>再次导出 B 的 JSON 与 A 的导出逐字节相等（引用键、结构、记录顺序完全一致）。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ImportServiceRoundTripTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZONE);
    private static final long USER_A = 1L;
    private static final long USER_B = 2L;

    @Autowired
    private AccountRepository accountRepository;
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
        return new ExportService(accountRepository, categoryRepository, transactionRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository,
                userRepository, CLOCK);
    }

    private ImportService importService() {
        return new ImportService(accountRepository, categoryRepository, transactionRepository, CLOCK);
    }

    private AccountService accountService() {
        return new AccountService(accountRepository, transactionRepository, CLOCK);
    }

    @Test
    void exportThenImportIntoEmptyUser_preservesAllRecordsAndBalances() {
        // ---- 为用户 A 构造样本数据（两级分类 + 三类交易）----
        Account cash = account(USER_A, "现金", AccountType.CASH, "100.00", 0);
        Account card = account(USER_A, "工资卡", AccountType.BANK_CARD, "0.00", 1);
        Category food = category(USER_A, CategoryKind.EXPENSE, "餐饮", null);
        Category takeout = category(USER_A, CategoryKind.EXPENSE, "外卖", food.getId());
        Category salary = category(USER_A, CategoryKind.INCOME, "工资", null);

        expense(USER_A, "23.50", cash.getId(), takeout.getId(), "午餐");
        income(USER_A, "2500.00", card.getId(), salary.getId(), null);
        transfer(USER_A, "500.00", card.getId(), cash.getId(), "取现");

        // A 记账后的余额（随流水事务性更新的期望值）。
        // 现金：100 - 23.50(支出) + 500(转入) = 576.50
        // 工资卡：0 + 2500(收入) - 500(转出) = 2000.00
        setCurrentBalance(cash.getId(), "576.50");
        setCurrentBalance(card.getId(), "2000.00");

        byte[] exportA = exportJson(USER_A);

        // ---- 导入到空账户用户 B ----
        assertThat(accountRepository.countByUserId(USER_B)).isZero();
        ImportService.ImportResult result = importService()
                .importJson(USER_B, USER_B, new ByteArrayInputStream(exportA));

        // 记录数与 A 一致。
        assertThat(result.accounts()).isEqualTo(2);
        assertThat(result.categories()).isEqualTo(3);
        assertThat(result.transactions()).isEqualTo(3);
        assertThat(accountRepository.countByUserId(USER_B)).isEqualTo(2);
        assertThat(categoryRepository.countByLedgerId(USER_B)).isEqualTo(3);
        assertThat(transactionRepository.findByLedgerId(USER_B)).hasSize(3);

        // ---- 账户业务字段逐一相等（按 name 配对，忽略 id/ledgerId）----
        Map<String, Account> accountsA = byName(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_A));
        Map<String, Account> accountsB = byName(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_B));
        assertThat(accountsB.keySet()).isEqualTo(accountsA.keySet());
        for (String name : accountsA.keySet()) {
            Account a = accountsA.get(name);
            Account b = accountsB.get(name);
            assertThat(b.getType()).isEqualTo(a.getType());
            assertThat(b.getInitialBalance()).isEqualByComparingTo(a.getInitialBalance());
            assertThat(b.getSortOrder()).isEqualTo(a.getSortOrder());
            // current_balance 与 A 一致。
            assertThat(b.getCurrentBalance()).isEqualByComparingTo(a.getCurrentBalance());
            // user_id 强制为会话用户 B（需求 2.2）。
            assertThat(b.getUserId()).isEqualTo(USER_B);
        }

        // ---- 分类父子引用被正确重建 ----
        List<Category> catsB = categoryRepository.findByLedgerId(USER_B);
        Map<Long, String> idToNameB = catsB.stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        Category takeoutB = catsB.stream()
                .filter(c -> c.getName().equals("外卖")).findFirst().orElseThrow();
        Category foodB = catsB.stream()
                .filter(c -> c.getName().equals("餐饮")).findFirst().orElseThrow();
        Category salaryB = catsB.stream()
                .filter(c -> c.getName().equals("工资")).findFirst().orElseThrow();
        // 子分类「外卖」的 parentId 指向重建后的「餐饮」，父分类无 parent。
        assertThat(takeoutB.getParentId()).isEqualTo(foodB.getId());
        assertThat(idToNameB.get(takeoutB.getParentId())).isEqualTo("餐饮");
        assertThat(foodB.getParentId()).isNull();
        assertThat(salaryB.getParentId()).isNull();
        assertThat(takeoutB.getKind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(salaryB.getKind()).isEqualTo(CategoryKind.INCOME);
        assertThat(catsB).allMatch(c -> c.getLedgerId().equals(USER_B));

        // ---- 交易业务字段逐一相等（归一化为 [type|amount|引用名|occurredAt|note] 集合）----
        assertThat(normalizeTransactions(USER_B))
                .containsExactlyInAnyOrderElementsOf(normalizeTransactions(USER_A));

        // ---- 还原后 current_balance 与重算结果一致（余额守恒不变式，需求 4.13）----
        AccountService accountService = accountService();
        for (Account b : accountRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_B)) {
            BigDecimal recomputed = accountService.recomputeBalance(USER_B, b.getId());
            assertThat(b.getCurrentBalance()).isEqualByComparingTo(recomputed);
        }

        // ---- 再次导出 B 与 A 的导出逐字节相等（结构/引用键/顺序完全一致）----
        byte[] exportB = exportJson(USER_B);
        assertThat(new String(exportB, StandardCharsets.UTF_8))
                .isEqualTo(new String(exportA, StandardCharsets.UTF_8));
    }

    // ---------------- 归一化与配对辅助 ----------------

    /** 将某用户全部交易归一化为可比较的业务字段串（用引用目标的名称替代自增 id）。 */
    private List<String> normalizeTransactions(long ledgerId) {
        Map<Long, String> accountName = accountRepository.findByUserIdOrderBySortOrderAscIdAsc(ledgerId).stream()
                .collect(Collectors.toMap(Account::getId, Account::getName));
        Map<Long, String> categoryName = categoryRepository.findByLedgerId(ledgerId).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        List<String> result = new ArrayList<>();
        for (Transaction t : transactionRepository.findByLedgerId(ledgerId)) {
            String refs;
            if (t.getType() == TransactionType.TRANSFER) {
                refs = "src=" + accountName.get(t.getSourceAccountId())
                        + ",dst=" + accountName.get(t.getDestinationAccountId());
            } else {
                refs = "acc=" + accountName.get(t.getAccountId())
                        + ",cat=" + categoryName.get(t.getCategoryId());
            }
            result.add(String.join("|",
                    t.getType().getCode(),
                    t.getAmount().setScale(2).toPlainString(),
                    refs,
                    t.getOccurredAt().toString(),
                    t.getNote() == null ? "" : t.getNote()));
        }
        return result;
    }

    private static Map<String, Account> byName(List<Account> accounts) {
        Map<String, Account> map = new HashMap<>();
        for (Account a : accounts) {
            map.put(a.getName(), a);
        }
        return map;
    }

    private byte[] exportJson(long ledgerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService().writeJson(ledgerId, ledgerId, out);
        return out.toByteArray();
    }

    // ---------------- 测试数据构造 ----------------

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
        return accountRepository.save(a);
    }

    private void setCurrentBalance(Long accountId, String balance) {
        Account a = accountRepository.findById(accountId).orElseThrow();
        a.setCurrentBalance(new BigDecimal(balance));
        accountRepository.save(a);
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
