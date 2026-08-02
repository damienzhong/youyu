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
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Loan;
import com.damien.youyu.domain.LoanDirection;
import com.damien.youyu.domain.LoanRepayment;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.LoanRepaymentRepository;
import com.damien.youyu.repository.LoanRepository;

/**
 * {@link LoanService} 单元测试（借贷为用户级）。H2 + 真实 Repository。
 *
 * <p>覆盖：创建校验、剩余=本金−已收/已还、部分收/还与结清推导、越权隔离、字段校验，
 * 以及关联账户时的余额联动（借出出账 −、收款回补 +；借入入账 +、删除回补）。</p>
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
    private LoanRepaymentRepository repaymentRepository;
    @Autowired
    private AccountRepository accountRepository;

    private LoanService service() {
        return new LoanService(loanRepository, repaymentRepository, accountRepository, FIXED);
    }

    // 无账户便捷创建：accountId=null，includeInTotal=true。
    private Loan create(long userId, String dir, String cp, String amount,
            LocalDateTime occurred, String note) {
        return service().create(userId, dir, cp, amount == null ? null : new BigDecimal(amount),
                null, occurred, null, true, note);
    }

    @Test
    void create_persistsUnsettledLoanWithDefaults() {
        Loan loan = create(USER, "BORROW", " 张三 ", "100.00", dt("2025-06-10T09:00:00"), "  房租周转  ");

        assertThat(loan.getId()).isNotNull();
        assertThat(loan.getUserId()).isEqualTo(USER);
        assertThat(loan.getDirection()).isEqualTo(LoanDirection.BORROW);
        assertThat(loan.getCounterparty()).isEqualTo("张三");
        assertThat(loan.getAmount()).isEqualByComparingTo("100.00");
        assertThat(loan.getRepaidAmount()).isEqualByComparingTo("0.00");
        assertThat(loan.isSettled()).isFalse();
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
    void repayment_partialThenFull_updatesRemainingAndSettles() {
        Loan loan = create(USER, "BORROW", "甲", "100.00", dt("2025-06-01T10:00:00"), null);

        service().addRepayment(USER, loan.getId(), new BigDecimal("30.00"), null, null, null);
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("70.00");
        assertThat(service().get(USER, loan.getId()).isSettled()).isFalse();

        LoanRepayment last = service().addRepayment(USER, loan.getId(),
                new BigDecimal("70.00"), null, null, null);
        Loan settled = service().get(USER, loan.getId());
        assertThat(settled.isSettled()).isTrue();
        assertThat(settled.getSettledAt()).isEqualTo(LocalDateTime.now(FIXED));
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("0.00");

        service().deleteRepayment(USER, last.getId());
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("70.00");
        assertThat(service().get(USER, loan.getId()).isSettled()).isFalse();
    }

    @Test
    void repayment_rejectsOverRemaining() {
        Loan loan = create(USER, "LEND", "甲", "100.00", dt("2025-06-01T10:00:00"), null);
        ApiException ex = catchThrowableOfType(
                () -> service().addRepayment(USER, loan.getId(),
                        new BigDecimal("150.00"), null, null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LOAN_FIELD_INVALID");
        assertThat(ex.getField()).isEqualTo("amount");
    }

    @Test
    void list_unsettledFirst() {
        create(USER, "BORROW", "甲", "100.00", dt("2025-06-01T10:00:00"), null);
        Loan b = create(USER, "LEND", "乙", "200.00", dt("2025-06-05T10:00:00"), null);
        service().addRepayment(USER, b.getId(), new BigDecimal("200.00"), null, null, null);

        List<Loan> list = service().list(USER);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).isSettled()).isFalse();
        assertThat(list.get(1).isSettled()).isTrue();
    }

    @Test
    void crossUser_isIsolated() {
        Loan mine = create(USER, "BORROW", "甲", "100.00", dt("2025-06-01T10:00:00"), null);

        assertThat(service().list(OTHER)).isEmpty();
        assertThat(service().outstanding(OTHER, LoanDirection.BORROW)).isEqualByComparingTo("0.00");
        ApiException ex = catchThrowableOfType(
                () -> service().delete(OTHER, mine.getId()), ApiException.class);
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

    // ---------------- 资金联动（关联账户，owner 级）----------------

    @Test
    void lend_withAccount_deductsBalanceAndRepaymentRestores() {
        Account acc = ownedAccount("现金", "1000.00");

        Loan loan = service().create(USER, "LEND", "老王", new BigDecimal("300.00"),
                acc.getId(), dt("2025-06-10T09:00:00"), null, true, null);
        assertThat(reload(acc).getCurrentBalance()).isEqualByComparingTo("700.00");

        service().addRepayment(USER, loan.getId(), new BigDecimal("300.00"),
                acc.getId(), dt("2025-06-20T09:00:00"), null);
        assertThat(reload(acc).getCurrentBalance()).isEqualByComparingTo("1000.00");
        assertThat(service().get(USER, loan.getId()).isSettled()).isTrue();
    }

    @Test
    void borrow_withAccount_addsBalanceAndReversesOnDelete() {
        Account acc = ownedAccount("储蓄卡", "1000.00");

        Loan loan = service().create(USER, "BORROW", "银行", new BigDecimal("500.00"),
                acc.getId(), dt("2025-06-10T09:00:00"), null, true, null);
        assertThat(reload(acc).getCurrentBalance()).isEqualByComparingTo("1500.00");

        service().delete(USER, loan.getId());
        assertThat(reload(acc).getCurrentBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void account_ownedByOther_isRejected() {
        Account others = ownedAccountFor(OTHER, "别人卡", "1000.00");
        ApiException ex = catchThrowableOfType(
                () -> service().create(USER, "LEND", "甲", new BigDecimal("10.00"),
                        others.getId(), null, null, true, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    private Account ownedAccount(String name, String balance) {
        return ownedAccountFor(USER, name, balance);
    }

    private Account ownedAccountFor(long userId, String name, String balance) {
        LocalDateTime now = LocalDateTime.now(FIXED);
        Account a = new Account();
        a.setUserId(userId);
        a.setName(name);
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setIncludeInTotal(true);
        a.setHidden(false);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    private Account reload(Account a) {
        return accountRepository.findById(a.getId()).orElseThrow();
    }

    private static LocalDateTime dt(String iso) {
        return LocalDateTime.parse(iso);
    }
}
