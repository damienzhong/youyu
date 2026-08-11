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
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link AccountService#recomputeBalance(Long, Long)} 的示例与边界单元测试（关联需求 4.13）。
 *
 * <p>校验「余额守恒的可重算校验」：由 {@code initial_balance} + 全量流水聚合重算的结果，
 * 应恒等于随流水事务性更新的 {@code current_balance}。使用 H2 + 真实 Repository，
 * 通过 {@link TransactionService} 施加真实的支出/收入/转账/修改/删除操作，不使用任何桩。
 * 守恒不变式的属性测试（Property 1，操作序列生成）在任务 6.4 中实现。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountRecomputeBalanceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long USER = 1L;

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private com.damien.youyu.repository.AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    private final Clock clock = Clock.fixed(T0, ZONE);

    @Autowired
    private com.damien.youyu.repository.LoanRepository loanRepository;
    @Autowired
    private com.damien.youyu.repository.LoanRepaymentRepository loanRepaymentRepository;

    private AccountService accountService() {
        return new AccountService(accountRepository, accountLedgerRepository, transactionRepository,
                loanRepository, loanRepaymentRepository, clock);
    }

    private LedgerAccountResolver resolver() {
        return new LedgerAccountResolver(accountRepository, accountLedgerRepository);
    }

    private TransactionService transactionService() {
        return new TransactionService(transactionRepository, accountRepository, categoryRepository,
                resolver(), clock, new GrowthSettlementTrigger(null, clock),
                new BudgetReminderTrigger(null));
    }

    /** 创建账户并纳入账本 USER，使其可用于该账本记账。 */
    private Account account(String name, String balance) {
        return accountService().create(USER, name, "CASH", new BigDecimal(balance), 0,
                true, false, null, null, USER);
    }

    private Category category(CategoryKind kind, String name) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setLedgerId(USER);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private BigDecimal currentBalanceOf(Long accountId) {
        return accountRepository.findByIdAndUserId(accountId, USER).orElseThrow().getCurrentBalance();
    }

    // ---------------- 无流水：重算=初始余额 ----------------

    @Test
    void recompute_noTransactions_equalsInitialBalance() {
        Account acc = account("现金", "123.45");

        BigDecimal recomputed = accountService().recomputeBalance(USER, acc.getId());

        // 需求 4.13：无任何流水时重算结果即初始余额，且缩放到两位小数。
        assertThat(recomputed).isEqualByComparingTo("123.45");
        assertThat(recomputed.scale()).isEqualTo(2);
        assertThat(recomputed).isEqualByComparingTo(currentBalanceOf(acc.getId()));
    }

    @Test
    void recompute_negativeInitialBalance_noTransactions_equalsInitialBalance() {
        Account credit = accountService()
                .create(USER, "信用卡", "CREDIT_CARD", new BigDecimal("-500.00"), 0);

        BigDecimal recomputed = accountService().recomputeBalance(USER, credit.getId());

        assertThat(recomputed).isEqualByComparingTo("-500.00");
    }

    // ---------------- 混合流水：重算=current_balance ----------------

    @Test
    void recompute_afterExpenseIncome_equalsCurrentBalance() {
        Account acc = account("现金", "100.00");
        Category expenseCat = category(CategoryKind.EXPENSE, "餐饮");
        Category incomeCat = category(CategoryKind.INCOME, "工资");
        TransactionService tx = transactionService();

        tx.create(USER, USER, "expense", new BigDecimal("30.00"), acc.getId(), expenseCat.getId(),
                null, null);
        tx.create(USER, USER, "income", new BigDecimal("250.00"), acc.getId(), incomeCat.getId(),
                null, null);
        tx.create(USER, USER, "expense", new BigDecimal("12.34"), acc.getId(), expenseCat.getId(),
                null, null);

        // 100 - 30 + 250 - 12.34 = 307.66
        BigDecimal recomputed = accountService().recomputeBalance(USER, acc.getId());
        assertThat(recomputed).isEqualByComparingTo("307.66");
        // 需求 4.13：重算值恒等于随流水更新的当前余额。
        assertThat(recomputed).isEqualByComparingTo(currentBalanceOf(acc.getId()));
    }

    @Test
    void recompute_afterTransfers_equalsCurrentBalanceForBothAccounts() {
        Account a = account("现金", "500.00");
        Account b = account("银行卡", "0.00");
        TransactionService tx = transactionService();

        // 两笔转账：a -> b 共 320.75；金额守恒（总额不变）。转账脱离账本。
        tx.transfer(USER, a.getId(), b.getId(), new BigDecimal("200.00"), null, null);
        tx.transfer(USER, a.getId(), b.getId(), new BigDecimal("120.75"), null, null);

        AccountService svc = accountService();
        BigDecimal reA = svc.recomputeBalance(USER, a.getId());
        BigDecimal reB = svc.recomputeBalance(USER, b.getId());

        assertThat(reA).isEqualByComparingTo("179.25"); // 500 - 320.75
        assertThat(reB).isEqualByComparingTo("320.75"); // 0 + 320.75
        assertThat(reA).isEqualByComparingTo(currentBalanceOf(a.getId()));
        assertThat(reB).isEqualByComparingTo(currentBalanceOf(b.getId()));
        // 推论：转账不改变账户余额之和（500 = 179.25 + 320.75）。
        assertThat(reA.add(reB)).isEqualByComparingTo("500.00");
    }

    @Test
    void recompute_afterMixedOperationsWithUpdateAndDelete_equalsCurrentBalance() {
        Account a = account("现金", "1000.00");
        Account b = account("银行卡", "200.00");
        Category expenseCat = category(CategoryKind.EXPENSE, "餐饮");
        Category incomeCat = category(CategoryKind.INCOME, "工资");
        TransactionService tx = transactionService();

        var expense = tx.create(USER, USER, "expense", new BigDecimal("40.00"), a.getId(),
                expenseCat.getId(), null, null);
        tx.create(USER, USER, "income", new BigDecimal("300.00"), b.getId(), incomeCat.getId(),
                null, null);
        // 转账脱离账本，直接影响账户余额。
        tx.transfer(USER, a.getId(), b.getId(), new BigDecimal("150.00"), null, null);

        // 修改：把支出金额从 40 改为 55（同账户）。
        tx.update(USER, USER, expense.getId(), "expense", new BigDecimal("55.00"), a.getId(),
                expenseCat.getId(), null, null);

        AccountService svc = accountService();
        BigDecimal reA = svc.recomputeBalance(USER, a.getId());
        BigDecimal reB = svc.recomputeBalance(USER, b.getId());

        // a: 1000 - 55(改后支出) - 150(转出) = 795
        // b: 200 + 300(收入) + 150(转入) = 650
        assertThat(reA).isEqualByComparingTo("795.00");
        assertThat(reB).isEqualByComparingTo("650.00");
        assertThat(reA).isEqualByComparingTo(currentBalanceOf(a.getId()));
        assertThat(reB).isEqualByComparingTo(currentBalanceOf(b.getId()));
    }

    // ---------------- 隔离与不存在 ----------------

    @Test
    void recompute_otherUsersAccount_returnsNotFound() {
        Account acc = account("现金", "10.00");

        ApiException ex = catchThrowableOfType(
                () -> accountService().recomputeBalance(999L, acc.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void recompute_missingAccount_returnsNotFound() {
        ApiException ex = catchThrowableOfType(
                () -> accountService().recomputeBalance(USER, 424242L), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }
}
