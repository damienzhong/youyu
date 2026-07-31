package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
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
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link AccountService} 的示例与边界单元测试（关联需求 3.1-3.9）。
 *
 * <p>使用 H2 + 真实 {@link AccountRepository}/{@link TransactionRepository}，不使用任何桩，
 * 以固定 {@link Clock} 做确定性时间。属性测试（Property 4-8）在任务 4.2 中实现。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long USER = 1L;
    private static final long OTHER_USER = 2L;

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private com.damien.youyu.repository.AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private AccountService service() {
        return new AccountService(accountRepository, accountLedgerRepository, transactionRepository,
                Clock.fixed(T0, ZONE));
    }

    // ---------------- 创建 ----------------

    @Test
    void create_success_setsCurrentBalanceToInitialBalance() {
        Account account = service().create(USER, "现金", "CASH", new BigDecimal("100.50"), 0);

        assertThat(account.getId()).isNotNull();
        assertThat(account.getUserId()).isEqualTo(USER);
        assertThat(account.getName()).isEqualTo("现金");
        assertThat(account.getType()).isEqualTo(AccountType.CASH);
        assertThat(account.getInitialBalance()).isEqualByComparingTo("100.50");
        // 需求 3.1：current_balance 初始化为初始余额。
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("100.50");
    }

    @Test
    void create_trimsName() {
        Account account = service().create(USER, "  工资卡  ", "BANK_CARD", new BigDecimal("0.00"), 0);
        assertThat(account.getName()).isEqualTo("工资卡");
    }

    @Test
    void create_creditCard_allowsNegativeInitialBalance() {
        // 需求 3.4：信用卡允许负余额（欠款）。
        Account account = service().create(USER, "信用卡", "CREDIT_CARD", new BigDecimal("-1234.56"), 0);
        assertThat(account.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("-1234.56");
    }

    @Test
    void create_invalidName_emptyOrTooLong_rejectedWithFieldName() {
        ApiException empty = catchThrowableOfType(
                () -> service().create(USER, "   ", "CASH", BigDecimal.ZERO, 0), ApiException.class);
        assertThat(empty.getCode()).isEqualTo("ACCOUNT_FIELD_INVALID");
        assertThat(empty.getField()).isEqualTo("name");

        ApiException tooLong = catchThrowableOfType(
                () -> service().create(USER, "n".repeat(51), "CASH", BigDecimal.ZERO, 0),
                ApiException.class);
        assertThat(tooLong.getField()).isEqualTo("name");

        // 需求 3.3：不持久化任何数据。
        assertThat(accountRepository.countByUserId(USER)).isZero();
    }

    @Test
    void create_invalidType_rejectedWithFieldType() {
        ApiException ex = catchThrowableOfType(
                () -> service().create(USER, "账户", "BITCOIN", BigDecimal.ZERO, 0), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("ACCOUNT_FIELD_INVALID");
        assertThat(ex.getField()).isEqualTo("type");
        assertThat(accountRepository.countByUserId(USER)).isZero();
    }

    @Test
    void create_invalidBalance_tooManyDecimalsOrOutOfRange_rejectedWithFieldInitialBalance() {
        ApiException tooManyDecimals = catchThrowableOfType(
                () -> service().create(USER, "账户", "CASH", new BigDecimal("1.234"), 0),
                ApiException.class);
        assertThat(tooManyDecimals.getCode()).isEqualTo("ACCOUNT_FIELD_INVALID");
        assertThat(tooManyDecimals.getField()).isEqualTo("initialBalance");

        ApiException overMax = catchThrowableOfType(
                () -> service().create(USER, "账户", "CASH", new BigDecimal("10000000000000000.00"), 0),
                ApiException.class);
        assertThat(overMax.getField()).isEqualTo("initialBalance");

        assertThat(accountRepository.countByUserId(USER)).isZero();
    }

    @Test
    void create_defaultsSortOrderToZeroWhenNull() {
        Account account = service().create(USER, "现金", "CASH", BigDecimal.ZERO, null);
        assertThat(account.getSortOrder()).isZero();
    }

    // ---------------- 列表 ----------------

    @Test
    void list_returnsOnlyOwnAccountsOrderedBySortOrder() {
        AccountService service = service();
        service.create(USER, "B", "CASH", BigDecimal.ZERO, 2);
        service.create(USER, "A", "CASH", BigDecimal.ZERO, 1);
        service.create(OTHER_USER, "别人的", "CASH", BigDecimal.ZERO, 0);

        List<Account> list = service.list(USER);

        // 隔离：仅返回本人账户，按 sort_order 升序。
        assertThat(list).extracting(Account::getName).containsExactly("A", "B");
    }

    @Test
    void list_emptyWhenNoAccounts() {
        assertThat(service().list(USER)).isEmpty();
    }

    // ---------------- 修改 ----------------

    @Test
    void update_changesNameAndType_preservesBalance() {
        AccountService service = service();
        Account created = service.create(USER, "现金", "CASH", new BigDecimal("88.88"), 0);

        Account updated = service.update(USER, created.getId(), "零钱", "WECHAT");

        assertThat(updated.getName()).isEqualTo("零钱");
        assertThat(updated.getType()).isEqualTo(AccountType.WECHAT);
        // 需求 3.6：保留余额。
        assertThat(updated.getCurrentBalance()).isEqualByComparingTo("88.88");
        assertThat(updated.getInitialBalance()).isEqualByComparingTo("88.88");
    }

    @Test
    void update_otherUsersAccount_returnsNotFound() {
        Account created = service().create(OTHER_USER, "别人的", "CASH", BigDecimal.ZERO, 0);

        ApiException ex = catchThrowableOfType(
                () -> service().update(USER, created.getId(), "改名", "CASH"), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void update_invalidType_rejected() {
        Account created = service().create(USER, "现金", "CASH", BigDecimal.ZERO, 0);

        ApiException ex = catchThrowableOfType(
                () -> service().update(USER, created.getId(), "现金", "GOLD"), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("ACCOUNT_FIELD_INVALID");
        assertThat(ex.getField()).isEqualTo("type");
    }

    // ---------------- 删除 ----------------

    @Test
    void delete_accountWithoutTransactions_succeeds() {
        AccountService service = service();
        Account created = service.create(USER, "现金", "CASH", BigDecimal.ZERO, 0);

        service.delete(USER, created.getId());

        assertThat(accountRepository.findByIdAndUserId(created.getId(), USER)).isEmpty();
    }

    @Test
    void delete_accountReferencedByTransaction_rejectedWithAccountInUse() {
        AccountService service = service();
        Account created = service.create(USER, "现金", "CASH", new BigDecimal("10.00"), 0);
        persistExpense(USER, created.getId(), new BigDecimal("5.00"));

        ApiException ex = catchThrowableOfType(
                () -> service.delete(USER, created.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("ACCOUNT_IN_USE");
        // 需求 3.7：账户保持不变。
        assertThat(accountRepository.findByIdAndUserId(created.getId(), USER)).isPresent();
    }

    @Test
    void delete_otherUsersAccount_returnsNotFound() {
        Account created = service().create(OTHER_USER, "别人的", "CASH", BigDecimal.ZERO, 0);

        ApiException ex = catchThrowableOfType(
                () -> service().delete(USER, created.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 信用卡还款提醒 ----------------

    @Test
    void repayReminders_onlyCreditWithReminder_sortedByDaysUntil() {
        AccountService service = service();
        // today = 2025-06-01（固定时钟）。
        // 卡A：还款日 20 → 2025-06-20，剩余 19 天，欠款 500。
        service.create(USER, "卡A", "CREDIT_CARD", new BigDecimal("-500.00"), 0,
                true, false, null, new BigDecimal("10000"), 5, 20, true, null);
        // 卡B：还款日 3 → 2025-06-03，剩余 2 天，余额 0 → 待还 0。
        service.create(USER, "卡B", "CREDIT_CARD", new BigDecimal("0.00"), 0,
                true, false, null, new BigDecimal("5000"), 1, 3, true, null);
        // 卡C：未开启提醒 → 不计入。
        service.create(USER, "卡C", "CREDIT_CARD", new BigDecimal("-100.00"), 0,
                true, false, null, new BigDecimal("5000"), null, null, false, null);
        // 普通账户 → 不计入。
        service.create(USER, "现金", "CASH", new BigDecimal("100.00"), 0);

        List<AccountService.RepayReminderView> list = service.repayReminders(USER);

        assertThat(list).hasSize(2);
        // 按剩余天数升序：卡B(2) 在前，卡A(19) 在后。
        assertThat(list.get(0).name()).isEqualTo("卡B");
        assertThat(list.get(0).daysUntil()).isEqualTo(2);
        assertThat(list.get(0).owed()).isEqualByComparingTo("0.00");
        assertThat(list.get(1).name()).isEqualTo("卡A");
        assertThat(list.get(1).daysUntil()).isEqualTo(19);
        assertThat(list.get(1).owed()).isEqualByComparingTo("500.00");
        assertThat(list.get(1).nextRepayDate().toString()).isEqualTo("2025-06-20");
    }

    private void persistExpense(Long ledgerId, Long accountId, BigDecimal amount) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Transaction tx = new Transaction();
        tx.setLedgerId(ledgerId);
        tx.setType(TransactionType.EXPENSE);
        tx.setAmount(amount);
        tx.setAccountId(accountId);
        tx.setCategoryId(1L);
        tx.setOccurredAt(now);
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        transactionRepository.save(tx);
    }
}
