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

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Loan;
import com.damien.youyu.domain.LoanDirection;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.LoanRepository;

/**
 * {@link LoanService} 单元测试。使用 H2 + 真实 Repository，不使用桩。
 *
 * <p>覆盖：创建校验、待还/待收汇总（仅未结清）、结清切换清零汇总、越权隔离、字段校验，
 * 以及关联账户时的余额联动（借入入账 +、借出出账 −，结清/删除回补）。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LoanServiceTest {

    private static final long USER = 1L;
    private static final long OTHER = 2L;

    private static final Clock FIXED = Clock.fixed(
            LocalDateTime.of(2025, 6, 15, 12, 0).toInstant(ZoneOffset.ofHours(8)),
            ZoneId.of("Asia/Shanghai"));

    @Autowired
    private LoanRepository loanRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerRepository accountLedgerRepository;

    private LoanService service() {
        LedgerAccountResolver resolver =
                new LedgerAccountResolver(accountRepository, accountLedgerRepository);
        return new LoanService(loanRepository, resolver, accountRepository, FIXED);
    }

    // 无账户便捷创建：userId=ledgerId=ledger，accountId=null，includeInTotal=true。
    private Loan create(long ledger, String dir, String cp, String amount,
            LocalDateTime occurred, String note) {
        return service().create(ledger, ledger, dir, cp, amount == null ? null : new BigDecimal(amount),
                null, occurred, null, true, note);
    }

    @Test
    void create_persistsUnsettledLoanWithDefaults() {
        Loan loan = create(USER, "BORROW", " 张三 ", "100.00", dt("2025-06-10T09:00:00"), "  房租周转  ");

        assertThat(loan.getId()).isNotNull();
        assertThat(loan.getDirection()).isEqualTo(LoanDirection.BORROW);
        assertThat(loan.getCounterparty()).isEqualTo("张三"); // 去空白
        assertThat(loan.getAmount()).isEqualByComparingTo("100.00");
        assertThat(loan.isSettled()).isFalse();
        assertThat(loan.getSettledAt()).isNull();
        assertThat(loan.isIncludeInTotal()).isTrue();
        assertThat(loan.getNote()).isEqualTo("房租周转");
    }

    @Test
    void create_nullOccurredAt_defaultsToNow() {
        Loan loan = create(USER, "LEND", "李四", "50.00", null, null);
        assertThat(loan.getOccurredAt()).isEqualTo(LocalDateTime.now(FIXED));
        assertThat(loan.getNote()).isNull();
    }

    @Test
    void outstanding_sumsOnlyUnsettledByDirection() {
        create(USER, "BORROW", "甲", "100.00", dt("2025-06-01T10:00:00"), null);
        create(USER, "BORROW", "乙", "200.00", dt("2025-06-02T10:00:00"), null);
        create(USER, "LEND", "丙", "30.00", dt("2025-06-03T10:00:00"), null);

        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("300.00");
        assertThat(service().outstanding(USER, LoanDirection.LEND)).isEqualByComparingTo("30.00");
    }

    @Test
    void outstanding_noRecords_returnsZero() {
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("0.00");
    }

    @Test
    void settle_removesFromOutstandingAndStampsSettledAt() {
        Loan loan = create(USER, "BORROW", "甲", "100.00", dt("2025-06-01T10:00:00"), null);
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("100.00");

        Loan settled = service().setSettled(USER, USER, loan.getId(), true);
        assertThat(settled.isSettled()).isTrue();
        assertThat(settled.getSettledAt()).isEqualTo(LocalDateTime.now(FIXED));
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("0.00");

        // 置回未结清：重新计入汇总且清空 settled_at。
        Loan reopened = service().setSettled(USER, USER, loan.getId(), false);
        assertThat(reopened.isSettled()).isFalse();
        assertThat(reopened.getSettledAt()).isNull();
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("100.00");
    }

    @Test
    void list_unsettledFirst() {
        create(USER, "BORROW", "甲", "100.00", dt("2025-06-01T10:00:00"), null);
        Loan b = create(USER, "LEND", "乙", "200.00", dt("2025-06-05T10:00:00"), null);
        service().setSettled(USER, USER, b.getId(), true);

        List<Loan> list = service().list(USER);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).isSettled()).isFalse(); // 未结清优先
        assertThat(list.get(1).isSettled()).isTrue();
    }

    @Test
    void crossUser_isIsolated() {
        Loan mine = create(USER, "BORROW", "甲", "100.00", dt("2025-06-01T10:00:00"), null);

        // 他人看不到、改不到、删不到。
        assertThat(service().list(OTHER)).isEmpty();
        assertThat(service().outstanding(OTHER, LoanDirection.BORROW)).isEqualByComparingTo("0.00");
        ApiException ex = catchThrowableOfType(
                () -> service().delete(OTHER, OTHER, mine.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void create_rejectsInvalidDirection() {
        ApiException ex = catchThrowableOfType(
                () -> create(USER, "GIVE", "甲", "1.00", null, null), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LOAN_FIELD_INVALID");
        assertThat(ex.getField()).isEqualTo("direction");
    }

    @Test
    void create_rejectsBlankCounterparty() {
        ApiException ex = catchThrowableOfType(
                () -> create(USER, "LEND", "   ", "1.00", null, null), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LOAN_FIELD_INVALID");
        assertThat(ex.getField()).isEqualTo("counterparty");
    }

    @Test
    void create_rejectsNonPositiveAmount() {
        ApiException ex = catchThrowableOfType(
                () -> create(USER, "LEND", "甲", "0.00", null, null), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LOAN_FIELD_INVALID");
        assertThat(ex.getField()).isEqualTo("amount");
    }

    // ---------------- 资金联动（关联账户）----------------

    @Test
    void lend_withAccount_deductsBalanceAndRestoresOnSettle() {
        Account acc = attachedAccount("现金", "1000.00");

        Loan loan = service().create(USER, USER, "LEND", "老王", new BigDecimal("300.00"),
                acc.getId(), dt("2025-06-10T09:00:00"), null, true, null);
        // 借出出账：余额 1000 - 300 = 700。
        assertThat(reload(acc).getCurrentBalance()).isEqualByComparingTo("700.00");

        // 结清（收回）：余额回补至 1000。
        service().setSettled(USER, USER, loan.getId(), true);
        assertThat(reload(acc).getCurrentBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void borrow_withAccount_addsBalanceAndReversesOnDelete() {
        Account acc = attachedAccount("储蓄卡", "1000.00");

        Loan loan = service().create(USER, USER, "BORROW", "银行", new BigDecimal("500.00"),
                acc.getId(), dt("2025-06-10T09:00:00"), null, true, null);
        // 借入入账：余额 1000 + 500 = 1500。
        assertThat(reload(acc).getCurrentBalance()).isEqualByComparingTo("1500.00");

        // 删除未结记录：回补至 1000。
        service().delete(USER, USER, loan.getId());
        assertThat(reload(acc).getCurrentBalance()).isEqualByComparingTo("1000.00");
    }

    // 建一个归属 USER、并纳入账本 USER 的账户。
    private Account attachedAccount(String name, String balance) {
        LocalDateTime now = LocalDateTime.now(FIXED);
        Account a = new Account();
        a.setUserId(USER);
        a.setName(name);
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setIncludeInTotal(true);
        a.setHidden(false);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        a = accountRepository.save(a);
        AccountLedger link = new AccountLedger();
        link.setAccountId(a.getId());
        link.setLedgerId(USER);
        link.setVisibleToOthers(true);
        link.setShowBalance(true);
        link.setCreatedAt(now);
        accountLedgerRepository.save(link);
        return a;
    }

    private Account reload(Account a) {
        return accountRepository.findById(a.getId()).orElseThrow();
    }

    private static LocalDateTime dt(String iso) {
        return LocalDateTime.parse(iso);
    }
}
