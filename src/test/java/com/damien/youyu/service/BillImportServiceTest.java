package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.BillImportRequest;
import com.damien.youyu.api.dto.BillImportRequest.BillEntry;
import com.damien.youyu.api.dto.BillImportResponse;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;

/**
 * {@link BillImportService} 单元测试。H2 + 真实 Repository。
 *
 * <p>覆盖：批量导入 + 余额一次性更新、按 externalId 去重（已入库/同批内）、金额非法跳过、
 * 分类默认兜底、越权账户隔离。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BillImportServiceTest {

    private static final long USER = 1L;
    private static final long OTHER = 2L;

    private static final Clock FIXED = Clock.fixed(
            LocalDateTime.of(2026, 7, 1, 12, 0).toInstant(ZoneOffset.ofHours(8)),
            ZoneId.of("Asia/Shanghai"));

    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private TransactionTagRepository transactionTagRepository;
    @Autowired
    private com.damien.youyu.repository.AccountLedgerRepository accountLedgerRepository;

    private BillImportService service() {
        return new BillImportService(transactionRepository, accountRepository, categoryRepository,
                projectRepository, tagRepository, transactionTagRepository,
                new LedgerAccountResolver(accountRepository, accountLedgerRepository), FIXED);
    }

    @Test
    void import_insertsAndUpdatesBalanceOnce() {
        Account acc = account(USER, "支付宝", "0.00");
        Category food = category(USER, CategoryKind.EXPENSE, "餐饮");
        Category salary = category(USER, CategoryKind.INCOME, "工资");

        BillImportResponse r = service().importBills(USER, USER, new BillImportRequest(
                acc.getId(), food.getId(), salary.getId(), null, null,
                List.of(
                        entry("expense", "38.00", "alipay:1", food.getId()),
                        entry("expense", "12.50", "alipay:2", null),      // 用默认支出分类
                        entry("income", "5000.00", "alipay:3", salary.getId()))));

        assertThat(r.imported()).isEqualTo(3);
        assertThat(r.skippedDuplicate()).isZero();
        assertThat(r.skippedInvalid()).isZero();
        // 余额：+5000 - 38 - 12.50 = 4949.50
        Account after = accountRepository.findById(acc.getId()).orElseThrow();
        assertThat(after.getCurrentBalance()).isEqualByComparingTo("4949.50");
        assertThat(transactionRepository.count()).isEqualTo(3);
    }

    @Test
    void import_skipsDuplicatesAcrossCallsAndWithinBatch() {
        Account acc = account(USER, "支付宝", "0.00");
        Category food = category(USER, CategoryKind.EXPENSE, "餐饮");
        Category salary = category(USER, CategoryKind.INCOME, "工资");

        // 首次导入 alipay:1。
        service().importBills(USER, USER, new BillImportRequest(acc.getId(), food.getId(), salary.getId(),
                null, null, List.of(entry("expense", "38.00", "alipay:1", null))));

        // 再次导入：alipay:1 已存在（跳过），alipay:9 同批出现两次（一入一跳）。
        BillImportResponse r = service().importBills(USER, USER, new BillImportRequest(
                acc.getId(), food.getId(), salary.getId(), null, null,
                List.of(
                        entry("expense", "38.00", "alipay:1", null),
                        entry("expense", "5.00", "alipay:9", null),
                        entry("expense", "5.00", "alipay:9", null))));

        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.skippedDuplicate()).isEqualTo(2);
        assertThat(transactionRepository.count()).isEqualTo(2); // 38 + 5
    }

    @Test
    void import_skipsInvalidAmount() {
        Account acc = account(USER, "支付宝", "0.00");
        Category food = category(USER, CategoryKind.EXPENSE, "餐饮");

        BillImportResponse r = service().importBills(USER, USER, new BillImportRequest(
                acc.getId(), food.getId(), null, null, null,
                List.of(
                        entry("expense", "0.00", "a:1", null),    // ≤0 非法
                        entry("expense", "1.234", "a:2", null),   // 超两位小数
                        entry("expense", "10.00", "a:3", null))));

        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.skippedInvalid()).isEqualTo(2);
    }

    @Test
    void import_skipsInvalidWhenNoCategoryAvailable() {
        Account acc = account(USER, "支付宝", "0.00");
        // 无默认收入分类，且条目无分类 → 收入行无兜底，跳过为非法。
        Category food = category(USER, CategoryKind.EXPENSE, "餐饮");

        BillImportResponse r = service().importBills(USER, USER, new BillImportRequest(
                acc.getId(), food.getId(), null, null, null,
                List.of(
                        entry("income", "100.00", "a:1", null),
                        entry("expense", "20.00", "a:2", null))));

        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.skippedInvalid()).isEqualTo(1);
    }

    @Test
    void import_otherUsersAccount_returnsNotFound() {
        Account acc = account(OTHER, "别人支付宝", "0.00");
        Category food = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().importBills(USER, USER, new BillImportRequest(
                acc.getId(), food.getId(), null, null, null,
                List.of(entry("expense", "10.00", "a:1", null)))), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void import_emptyEntries_rejected() {
        Account acc = account(USER, "支付宝", "0.00");
        ApiException ex = catchThrowableOfType(() -> service().importBills(USER, USER, new BillImportRequest(
                acc.getId(), null, null, null, null, List.of())), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("IMPORT_INVALID");
    }

    // ---------------- 夹具 ----------------

    private BillEntry entry(String type, String amount, String extId, Long categoryId) {
        return new BillEntry(type, new BigDecimal(amount),
                LocalDateTime.of(2026, 7, 1, 10, 0), "对方 · 商品", extId, categoryId);
    }

    private Account account(long ledgerId, String name, String balance) {
        Account a = new Account();
        a.setUserId(ledgerId);
        a.setName(name);
        a.setType(AccountType.ALIPAY);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setCreatedAt(LocalDateTime.now(FIXED));
        a.setUpdatedAt(LocalDateTime.now(FIXED));
        Account saved = accountRepository.save(a);
        // 纳入账本 ledgerId，使其可用于该账本导入记账。
        com.damien.youyu.domain.AccountLedger al = new com.damien.youyu.domain.AccountLedger();
        al.setAccountId(saved.getId());
        al.setLedgerId(ledgerId);
        al.setVisibleToOthers(true);
        al.setShowBalance(true);
        al.setCreatedAt(LocalDateTime.now(FIXED));
        accountLedgerRepository.save(al);
        return saved;
    }

    private Category category(long ledgerId, CategoryKind kind, String name) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(LocalDateTime.now(FIXED));
        c.setUpdatedAt(LocalDateTime.now(FIXED));
        return categoryRepository.save(c);
    }
}
