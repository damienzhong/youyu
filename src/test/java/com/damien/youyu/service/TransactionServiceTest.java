package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link TransactionService} 的示例与边界单元测试（账户与账本解耦后）。
 *
 * <p>收支记账通过账本可选集校验账户（账户须已纳入该账本）；转账为脱离账本的账户间动作，
 * 走 {@link TransactionService#transfer}，源/目标须为本人账户。使用 H2 + 真实 Repository，
 * 以固定 {@link Clock} 做确定性时间。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long USER = 1L;
    private static final long OTHER_USER = 2L;

    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    private TransactionService service() {
        Clock clock = Clock.fixed(T0, ZONE);
        return new TransactionService(transactionRepository, accountRepository, categoryRepository,
                new LedgerAccountResolver(accountRepository, accountLedgerRepository), clock,
                new GrowthSettlementTrigger(null, clock));
    }

    /** 创建账户并纳入 ledgerId=userId 的账本，使其可用于该账本记账。 */
    private Account account(long userId, String name, String balance) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Account a = new Account();
        a.setUserId(userId);
        a.setName(name);
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        Account saved = accountRepository.save(a);
        AccountLedger al = new AccountLedger();
        al.setAccountId(saved.getId());
        al.setLedgerId(userId);
        al.setVisibleToOthers(true);
        al.setShowBalance(true);
        al.setCreatedAt(now);
        accountLedgerRepository.save(al);
        return saved;
    }

    private Category category(long ledgerId, CategoryKind kind, String name) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    // ---------------- 支出 / 收入 ----------------

    @Test
    void createExpense_decreasesAccountBalance() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        Transaction tx = service().create(USER, USER, "expense", new BigDecimal("23.50"),
                acc.getId(), cat.getId(), null, "午餐");

        assertThat(tx.getId()).isNotNull();
        assertThat(tx.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(tx.getLedgerId()).isEqualTo(USER);
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("76.50");
    }

    @Test
    void createIncome_increasesAccountBalance() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.INCOME, "工资");

        service().create(USER, USER, "income", new BigDecimal("2500.00"),
                acc.getId(), cat.getId(), null, null);

        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("2600.00");
    }

    @Test
    void createTransferType_viaRecordingPath_rejected() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "transfer",
                new BigDecimal("10.00"), acc.getId(), cat.getId(), null, null), ApiException.class);

        // 转账不走记账路径。
        assertThat(ex.getCode()).isEqualTo("TRANSACTION_TYPE_INVALID");
    }

    // ---------------- 转账（脱离账本，账户间动作） ----------------

    @Test
    void transfer_movesBalanceBetweenAccounts_ledgerless() {
        Account src = account(USER, "银行卡", "1000.00");
        Account dst = account(USER, "信用卡", "-500.00");

        Transaction tx = service().transfer(USER, src.getId(), dst.getId(),
                new BigDecimal("500.00"), null, "还信用卡");

        assertThat(tx.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(tx.getLedgerId()).isNull();
        assertThat(tx.getAccountId()).isNull();
        assertThat(tx.getSourceAccountId()).isEqualTo(src.getId());
        assertThat(tx.getDestinationAccountId()).isEqualTo(dst.getId());
        assertThat(balanceOf(src.getId())).isEqualByComparingTo("500.00");
        assertThat(balanceOf(dst.getId())).isEqualByComparingTo("0.00");
        // 转账不改变账户余额之和。
        assertThat(balanceOf(src.getId()).add(balanceOf(dst.getId()))).isEqualByComparingTo("500.00");
    }

    @Test
    void transfer_sameSourceAndDestination_rejectedWithZeroSideEffect() {
        Account acc = account(USER, "银行卡", "1000.00");

        ApiException ex = catchThrowableOfType(() -> service().transfer(USER, acc.getId(), acc.getId(),
                new BigDecimal("100.00"), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("TRANSFER_SAME_ACCOUNT");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("1000.00");
    }

    @Test
    void transfer_missingDestination_rejectedWithFieldRequired() {
        Account src = account(USER, "银行卡", "1000.00");

        ApiException ex = catchThrowableOfType(() -> service().transfer(USER, src.getId(), null,
                new BigDecimal("100.00"), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("destinationAccountId");
        assertThat(balanceOf(src.getId())).isEqualByComparingTo("1000.00");
    }

    @Test
    void transfer_nonexistentSource_rejectedWithNotFoundAndNoSideEffect() {
        Account dst = account(USER, "信用卡", "-500.00");

        ApiException ex = catchThrowableOfType(() -> service().transfer(USER, 9999L, dst.getId(),
                new BigDecimal("100.00"), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(dst.getId())).isEqualByComparingTo("-500.00");
    }

    @Test
    void transfer_otherUsersAccount_rejectedWithNotFound() {
        Account mine = account(USER, "现金", "100.00");
        Account other = account(OTHER_USER, "别人的", "100.00");

        ApiException ex = catchThrowableOfType(() -> service().transfer(USER, mine.getId(), other.getId(),
                new BigDecimal("10.00"), null, null), ApiException.class);

        // 跨用户转账不支持：目标非本人账户视为不存在。
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(mine.getId())).isEqualByComparingTo("100.00");
    }

    // ---------------- 金额校验（零副作用） ----------------

    @Test
    void createExpense_amountBelowMin_rejectedWithoutSideEffect() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "expense",
                new BigDecimal("0.00"), acc.getId(), cat.getId(), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findByLedgerId(USER)).isEmpty();
    }

    @Test
    void createExpense_amountTooManyDecimals_rejected() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "expense",
                new BigDecimal("1.234"), acc.getId(), cat.getId(), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void createExpense_amountAboveMax_rejected() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "expense",
                new BigDecimal("10000000000000000.00"), acc.getId(), cat.getId(), null, null),
                ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
    }

    // ---------------- 必填字段（零副作用） ----------------

    @Test
    void createExpense_missingAmount_rejectedWithFieldRequired() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "expense",
                null, acc.getId(), cat.getId(), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("amount");
        assertThat(transactionRepository.findByLedgerId(USER)).isEmpty();
    }

    @Test
    void createExpense_missingAccount_rejectedWithFieldRequired() {
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "expense",
                new BigDecimal("10.00"), null, cat.getId(), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("accountId");
    }

    @Test
    void createExpense_missingCategory_rejectedWithFieldRequired() {
        Account acc = account(USER, "现金", "100.00");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "expense",
                new BigDecimal("10.00"), acc.getId(), null, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("categoryId");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }

    // ---------------- 账户/分类存在性（零副作用） ----------------

    @Test
    void createExpense_nonexistentAccount_rejectedWithNotFound() {
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "expense",
                new BigDecimal("10.00"), 9999L, cat.getId(), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(transactionRepository.findByLedgerId(USER)).isEmpty();
    }

    @Test
    void createExpense_accountNotInLedger_rejectedWithNotFound() {
        // 他人账户未纳入本账本 → 记账时不可用。
        Account other = account(OTHER_USER, "别人的", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "expense",
                new BigDecimal("10.00"), other.getId(), cat.getId(), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(other.getId())).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findByLedgerId(USER)).isEmpty();
    }

    @Test
    void createExpense_nonexistentCategory_rejectedWithNotFound() {
        Account acc = account(USER, "现金", "100.00");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, USER, "expense",
                new BigDecimal("10.00"), acc.getId(), 9999L, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }

    // ---------------- 读取 ----------------

    @Test
    void get_otherUsersTransaction_returnsNotFound() {
        Account acc = account(OTHER_USER, "别人的", "100.00");
        Category cat = category(OTHER_USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(OTHER_USER, OTHER_USER, "expense", new BigDecimal("10.00"),
                acc.getId(), cat.getId(), null, null);

        ApiException ex = catchThrowableOfType(
                () -> service().get(USER, tx.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 修改（回滚原影响 + 应用新影响） ----------------

    @Test
    void updateExpense_changeAmount_adjustsBalance() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, USER, "expense", new BigDecimal("30.00"),
                acc.getId(), cat.getId(), null, "午餐");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");

        Transaction updated = service().update(USER, USER, tx.getId(), "expense", new BigDecimal("50.00"),
                acc.getId(), cat.getId(), null, "午餐+咖啡");

        assertThat(updated.getAmount()).isEqualByComparingTo("50.00");
        assertThat(updated.getNote()).isEqualTo("午餐+咖啡");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("50.00");
    }

    @Test
    void updateExpense_changeAccount_movesEffectToNewAccount() {
        Account from = account(USER, "现金", "100.00");
        Account to = account(USER, "银行卡", "200.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, USER, "expense", new BigDecimal("40.00"),
                from.getId(), cat.getId(), null, null);
        assertThat(balanceOf(from.getId())).isEqualByComparingTo("60.00");

        service().update(USER, USER, tx.getId(), "expense", new BigDecimal("40.00"),
                to.getId(), cat.getId(), null, null);

        assertThat(balanceOf(from.getId())).isEqualByComparingTo("100.00");
        assertThat(balanceOf(to.getId())).isEqualByComparingTo("160.00");
    }

    @Test
    void update_toTransferType_rejected() {
        Account acc = account(USER, "现金", "100.00");
        Account dst = account(USER, "银行卡", "200.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, USER, "expense", new BigDecimal("30.00"),
                acc.getId(), cat.getId(), null, null);

        ApiException ex = catchThrowableOfType(() -> service().update(USER, USER, tx.getId(), "transfer",
                new BigDecimal("25.00"), acc.getId(), dst.getId(), null, "转到银行卡"), ApiException.class);

        // 收支交易不能改为转账（转账为独立的账户间动作）。
        assertThat(ex.getCode()).isEqualTo("TRANSACTION_TYPE_INVALID");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");
    }

    @Test
    void update_nonexistentTransaction_rejectedWithNotFoundAndNoBalanceChange() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().update(USER, USER, 9999L, "expense",
                new BigDecimal("10.00"), acc.getId(), cat.getId(), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void update_toNonexistentAccount_rejectedWithNotFoundAndNoBalanceChange() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, USER, "expense", new BigDecimal("30.00"),
                acc.getId(), cat.getId(), null, null);
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");

        ApiException ex = catchThrowableOfType(() -> service().update(USER, USER, tx.getId(), "expense",
                new BigDecimal("30.00"), 9999L, cat.getId(), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");
    }

    // ---------------- 删除（回滚原影响） ----------------

    @Test
    void deleteExpense_rollsBackBalanceAndMovesToRecycleBin() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, USER, "expense", new BigDecimal("30.00"),
                acc.getId(), cat.getId(), null, null);
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");

        service().delete(USER, USER, tx.getId());

        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findByIdAndLedgerId(tx.getId(), USER)).isEmpty();
    }

    @Test
    void delete_nonexistentTransaction_rejectedWithNotFound() {
        Account acc = account(USER, "现金", "100.00");

        ApiException ex = catchThrowableOfType(
                () -> service().delete(USER, USER, 9999L), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }
}
