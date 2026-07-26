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
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link TransactionService} 的示例与边界单元测试（关联需求 4.1-4.5、4.8-4.11）。
 *
 * <p>使用 H2 + 真实 Repository，不使用任何桩，以固定 {@link Clock} 做确定性时间。
 * 守恒不变式与非法输入的属性测试（Property 1/2/3）在任务 6.4 中实现。</p>
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
    private CategoryRepository categoryRepository;

    private TransactionService service() {
        return new TransactionService(
                transactionRepository, accountRepository, categoryRepository, Clock.fixed(T0, ZONE));
    }

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
        return accountRepository.save(a);
    }

    private Category category(long userId, CategoryKind kind, String name) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setUserId(userId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findByIdAndUserId(accountId, USER).orElseThrow().getCurrentBalance();
    }

    // ---------------- 支出 / 收入 ----------------

    @Test
    void createExpense_decreasesAccountBalance() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        Transaction tx = service().create(USER, "expense", new BigDecimal("23.50"),
                acc.getId(), cat.getId(), null, null, null, "午餐");

        assertThat(tx.getId()).isNotNull();
        assertThat(tx.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(tx.getUserId()).isEqualTo(USER);
        // 需求 4.1：支出减少账户余额。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("76.50");
    }

    @Test
    void createIncome_increasesAccountBalance() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.INCOME, "工资");

        service().create(USER, "income", new BigDecimal("2500.00"),
                acc.getId(), cat.getId(), null, null, null, null);

        // 需求 4.2：收入增加账户余额。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("2600.00");
    }

    // ---------------- 转账 ----------------

    @Test
    void createTransfer_movesBalanceBetweenAccounts_singleRow() {
        Account src = account(USER, "银行卡", "1000.00");
        Account dst = account(USER, "信用卡", "-500.00");

        Transaction tx = service().create(USER, "transfer", new BigDecimal("500.00"),
                null, null, src.getId(), dst.getId(), null, "还信用卡");

        // 需求 4.3：单条建模，源减、目标增。
        assertThat(tx.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(tx.getAccountId()).isNull();
        assertThat(tx.getCategoryId()).isNull();
        assertThat(tx.getSourceAccountId()).isEqualTo(src.getId());
        assertThat(tx.getDestinationAccountId()).isEqualTo(dst.getId());
        assertThat(balanceOf(src.getId())).isEqualByComparingTo("500.00");
        assertThat(balanceOf(dst.getId())).isEqualByComparingTo("0.00");
        // 转账不改变账户余额之和。
        assertThat(balanceOf(src.getId()).add(balanceOf(dst.getId()))).isEqualByComparingTo("500.00");
    }

    @Test
    void createTransfer_sameSourceAndDestination_rejectedWithZeroSideEffect() {
        Account acc = account(USER, "银行卡", "1000.00");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "transfer",
                new BigDecimal("100.00"), null, null, acc.getId(), acc.getId(), null, null),
                ApiException.class);

        // 需求 4.5：源=目标被拒。
        assertThat(ex.getCode()).isEqualTo("TRANSFER_SAME_ACCOUNT");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("1000.00");
        assertThat(transactionRepository.findByUserId(USER)).isEmpty();
    }

    // ---------------- 金额校验（零副作用） ----------------

    @Test
    void createExpense_amountBelowMin_rejectedWithoutSideEffect() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "expense",
                new BigDecimal("0.00"), acc.getId(), cat.getId(), null, null, null, null),
                ApiException.class);

        // 需求 4.4：金额 < 0.01 非法。
        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findByUserId(USER)).isEmpty();
    }

    @Test
    void createExpense_amountTooManyDecimals_rejected() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "expense",
                new BigDecimal("1.234"), acc.getId(), cat.getId(), null, null, null, null),
                ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void createExpense_amountAboveMax_rejected() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "expense",
                new BigDecimal("10000000000000000.00"), acc.getId(), cat.getId(), null, null, null, null),
                ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
    }

    // ---------------- 必填字段（零副作用） ----------------

    @Test
    void createExpense_missingAmount_rejectedWithFieldRequired() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "expense",
                null, acc.getId(), cat.getId(), null, null, null, null), ApiException.class);

        // 需求 4.8：金额缺失属必填缺失。
        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("amount");
        assertThat(transactionRepository.findByUserId(USER)).isEmpty();
    }

    @Test
    void createExpense_missingAccount_rejectedWithFieldRequired() {
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "expense",
                new BigDecimal("10.00"), null, cat.getId(), null, null, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("accountId");
    }

    @Test
    void createExpense_missingCategory_rejectedWithFieldRequired() {
        Account acc = account(USER, "现金", "100.00");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "expense",
                new BigDecimal("10.00"), acc.getId(), null, null, null, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("categoryId");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void createTransfer_missingDestination_rejectedWithFieldRequired() {
        Account src = account(USER, "银行卡", "1000.00");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "transfer",
                new BigDecimal("100.00"), null, null, src.getId(), null, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("destinationAccountId");
        assertThat(balanceOf(src.getId())).isEqualByComparingTo("1000.00");
    }

    // ---------------- 账户/分类存在性（零副作用） ----------------

    @Test
    void createExpense_nonexistentAccount_rejectedWithNotFound() {
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "expense",
                new BigDecimal("10.00"), 9999L, cat.getId(), null, null, null, null), ApiException.class);

        // 需求 4.9：引用账户不存在。
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(transactionRepository.findByUserId(USER)).isEmpty();
    }

    @Test
    void createExpense_otherUsersAccount_rejectedWithNotFound() {
        Account other = account(OTHER_USER, "别人的", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "expense",
                new BigDecimal("10.00"), other.getId(), cat.getId(), null, null, null, null),
                ApiException.class);

        // 需求 2.4/4.9：他人账户视为不存在。
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        // 零副作用：他人账户余额不变，本人无任何交易落库。
        assertThat(accountRepository.findByIdAndUserId(other.getId(), OTHER_USER).orElseThrow()
                .getCurrentBalance()).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findByUserId(USER)).isEmpty();
    }

    @Test
    void createExpense_nonexistentCategory_rejectedWithNotFound() {
        Account acc = account(USER, "现金", "100.00");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "expense",
                new BigDecimal("10.00"), acc.getId(), 9999L, null, null, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        // 校验前置：账户余额不变。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void createTransfer_nonexistentSource_rejectedWithNotFoundAndNoSideEffect() {
        Account dst = account(USER, "信用卡", "-500.00");

        ApiException ex = catchThrowableOfType(() -> service().create(USER, "transfer",
                new BigDecimal("100.00"), null, null, 9999L, dst.getId(), null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        // 需求 4.10：中途失败零副作用，目标账户余额不变。
        assertThat(balanceOf(dst.getId())).isEqualByComparingTo("-500.00");
        assertThat(transactionRepository.findByUserId(USER)).isEmpty();
    }

    // ---------------- 读取 ----------------

    @Test
    void get_otherUsersTransaction_returnsNotFound() {
        Account acc = account(OTHER_USER, "别人的", "100.00");
        Category cat = category(OTHER_USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = new TransactionService(
                transactionRepository, accountRepository, categoryRepository, Clock.fixed(T0, ZONE))
                .create(OTHER_USER, "expense", new BigDecimal("10.00"),
                        acc.getId(), cat.getId(), null, null, null, null);

        ApiException ex = catchThrowableOfType(
                () -> service().get(USER, tx.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 修改（回滚原影响 + 应用新影响，需求 4.6、4.7） ----------------

    @Test
    void updateExpense_changeAmount_adjustsBalance() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, "expense", new BigDecimal("30.00"),
                acc.getId(), cat.getId(), null, null, null, "午餐");
        // 创建后：100 - 30 = 70。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");

        Transaction updated = service().update(USER, tx.getId(), "expense", new BigDecimal("50.00"),
                acc.getId(), cat.getId(), null, null, null, "午餐+咖啡");

        // 回滚 -30（+30）再应用 -50：100 - 50 = 50。
        assertThat(updated.getAmount()).isEqualByComparingTo("50.00");
        assertThat(updated.getNote()).isEqualTo("午餐+咖啡");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("50.00");
    }

    @Test
    void updateExpense_changeAccount_movesEffectToNewAccount() {
        Account from = account(USER, "现金", "100.00");
        Account to = account(USER, "银行卡", "200.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, "expense", new BigDecimal("40.00"),
                from.getId(), cat.getId(), null, null, null, null);
        assertThat(balanceOf(from.getId())).isEqualByComparingTo("60.00");

        service().update(USER, tx.getId(), "expense", new BigDecimal("40.00"),
                to.getId(), cat.getId(), null, null, null, null);

        // 原账户恢复：60 + 40 = 100；新账户扣减：200 - 40 = 160。
        assertThat(balanceOf(from.getId())).isEqualByComparingTo("100.00");
        assertThat(balanceOf(to.getId())).isEqualByComparingTo("160.00");
    }

    @Test
    void update_changeExpenseToTransfer_rollsBackOldAndAppliesNewShape() {
        Account acc = account(USER, "现金", "100.00");
        Account dst = account(USER, "银行卡", "200.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, "expense", new BigDecimal("30.00"),
                acc.getId(), cat.getId(), null, null, null, null);
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");

        Transaction updated = service().update(USER, tx.getId(), "transfer", new BigDecimal("25.00"),
                null, null, acc.getId(), dst.getId(), null, "转到银行卡");

        // 回滚支出 -30（现金 +30 → 100），再应用转账（现金 -25 → 75，银行卡 +25 → 225）。
        assertThat(updated.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(updated.getAccountId()).isNull();
        assertThat(updated.getCategoryId()).isNull();
        assertThat(updated.getSourceAccountId()).isEqualTo(acc.getId());
        assertThat(updated.getDestinationAccountId()).isEqualTo(dst.getId());
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("75.00");
        assertThat(balanceOf(dst.getId())).isEqualByComparingTo("225.00");
    }

    @Test
    void update_nonexistentTransaction_rejectedWithNotFoundAndNoBalanceChange() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().update(USER, 9999L, "expense",
                new BigDecimal("10.00"), acc.getId(), cat.getId(), null, null, null, null),
                ApiException.class);

        // 需求 4.7：目标交易不存在则拒绝且不改余额。
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void update_otherUsersTransaction_rejectedWithNotFound() {
        Account other = account(OTHER_USER, "别人的", "100.00");
        Category otherCat = category(OTHER_USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = new TransactionService(
                transactionRepository, accountRepository, categoryRepository, Clock.fixed(T0, ZONE))
                .create(OTHER_USER, "expense", new BigDecimal("10.00"),
                        other.getId(), otherCat.getId(), null, null, null, null);
        Account mine = account(USER, "现金", "100.00");
        Category myCat = category(USER, CategoryKind.EXPENSE, "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().update(USER, tx.getId(), "expense",
                new BigDecimal("20.00"), mine.getId(), myCat.getId(), null, null, null, null),
                ApiException.class);

        // 需求 2.4/4.7：他人交易视为不存在，拒绝且不改余额。
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(mine.getId())).isEqualByComparingTo("100.00");
        assertThat(accountRepository.findByIdAndUserId(other.getId(), OTHER_USER).orElseThrow()
                .getCurrentBalance()).isEqualByComparingTo("90.00");
    }

    @Test
    void update_toNonexistentAccount_rejectedWithNotFoundAndNoBalanceChange() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, "expense", new BigDecimal("30.00"),
                acc.getId(), cat.getId(), null, null, null, null);
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");

        ApiException ex = catchThrowableOfType(() -> service().update(USER, tx.getId(), "expense",
                new BigDecimal("30.00"), 9999L, cat.getId(), null, null, null, null),
                ApiException.class);

        // 需求 4.9：引用账户不存在 → NOT_FOUND，且事务回滚，原余额不变。
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");
    }

    // ---------------- 删除（回滚原影响，需求 4.6、4.7） ----------------

    @Test
    void deleteExpense_rollsBackBalanceAndRemovesRow() {
        Account acc = account(USER, "现金", "100.00");
        Category cat = category(USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = service().create(USER, "expense", new BigDecimal("30.00"),
                acc.getId(), cat.getId(), null, null, null, null);
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("70.00");

        service().delete(USER, tx.getId());

        // 回滚支出 -30（+30）：恢复到 100。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findByIdAndUserId(tx.getId(), USER)).isEmpty();
    }

    @Test
    void deleteTransfer_restoresBothAccounts() {
        Account src = account(USER, "银行卡", "1000.00");
        Account dst = account(USER, "信用卡", "-500.00");
        Transaction tx = service().create(USER, "transfer", new BigDecimal("500.00"),
                null, null, src.getId(), dst.getId(), null, "还信用卡");
        assertThat(balanceOf(src.getId())).isEqualByComparingTo("500.00");
        assertThat(balanceOf(dst.getId())).isEqualByComparingTo("0.00");

        service().delete(USER, tx.getId());

        // 回滚转账：源 +500 → 1000，目标 -500 → -500。
        assertThat(balanceOf(src.getId())).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(dst.getId())).isEqualByComparingTo("-500.00");
        assertThat(transactionRepository.findByIdAndUserId(tx.getId(), USER)).isEmpty();
    }

    @Test
    void delete_nonexistentTransaction_rejectedWithNotFound() {
        Account acc = account(USER, "现金", "100.00");

        ApiException ex = catchThrowableOfType(
                () -> service().delete(USER, 9999L), ApiException.class);

        // 需求 4.7：目标交易不存在则拒绝且不改余额。
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void delete_otherUsersTransaction_rejectedWithNotFound() {
        Account other = account(OTHER_USER, "别人的", "100.00");
        Category otherCat = category(OTHER_USER, CategoryKind.EXPENSE, "餐饮");
        Transaction tx = new TransactionService(
                transactionRepository, accountRepository, categoryRepository, Clock.fixed(T0, ZONE))
                .create(OTHER_USER, "expense", new BigDecimal("10.00"),
                        other.getId(), otherCat.getId(), null, null, null, null);

        ApiException ex = catchThrowableOfType(
                () -> service().delete(USER, tx.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        // 他人账户余额不变（90 = 100 - 10）。
        assertThat(accountRepository.findByIdAndUserId(other.getId(), OTHER_USER).orElseThrow()
                .getCurrentBalance()).isEqualByComparingTo("90.00");
    }
}
