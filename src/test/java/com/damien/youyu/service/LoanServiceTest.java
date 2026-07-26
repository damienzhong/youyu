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

import com.damien.youyu.domain.Loan;
import com.damien.youyu.domain.LoanDirection;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.LoanRepository;

/**
 * {@link LoanService} 单元测试。使用 H2 + 真实 Repository，不使用桩。
 *
 * <p>覆盖：创建校验、待还/待收汇总（仅未结清）、结清切换清零汇总、越权隔离、字段校验。</p>
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

    private LoanService service() {
        return new LoanService(loanRepository, FIXED);
    }

    @Test
    void create_persistsUnsettledLoanWithDefaults() {
        Loan loan = service().create(USER, "BORROW", " 张三 ", new BigDecimal("100.00"),
                dt("2025-06-10T09:00:00"), "  房租周转  ");

        assertThat(loan.getId()).isNotNull();
        assertThat(loan.getDirection()).isEqualTo(LoanDirection.BORROW);
        assertThat(loan.getCounterparty()).isEqualTo("张三"); // 去空白
        assertThat(loan.getAmount()).isEqualByComparingTo("100.00");
        assertThat(loan.isSettled()).isFalse();
        assertThat(loan.getSettledAt()).isNull();
        assertThat(loan.getNote()).isEqualTo("房租周转");
    }

    @Test
    void create_nullOccurredAt_defaultsToNow() {
        Loan loan = service().create(USER, "LEND", "李四", new BigDecimal("50.00"), null, null);
        assertThat(loan.getOccurredAt()).isEqualTo(LocalDateTime.now(FIXED));
        assertThat(loan.getNote()).isNull();
    }

    @Test
    void outstanding_sumsOnlyUnsettledByDirection() {
        service().create(USER, "BORROW", "甲", new BigDecimal("100.00"), dt("2025-06-01T10:00:00"), null);
        service().create(USER, "BORROW", "乙", new BigDecimal("200.00"), dt("2025-06-02T10:00:00"), null);
        service().create(USER, "LEND", "丙", new BigDecimal("30.00"), dt("2025-06-03T10:00:00"), null);

        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("300.00");
        assertThat(service().outstanding(USER, LoanDirection.LEND)).isEqualByComparingTo("30.00");
    }

    @Test
    void outstanding_noRecords_returnsZero() {
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("0.00");
    }

    @Test
    void settle_removesFromOutstandingAndStampsSettledAt() {
        Loan loan = service().create(USER, "BORROW", "甲", new BigDecimal("100.00"),
                dt("2025-06-01T10:00:00"), null);
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("100.00");

        Loan settled = service().setSettled(USER, loan.getId(), true);
        assertThat(settled.isSettled()).isTrue();
        assertThat(settled.getSettledAt()).isEqualTo(LocalDateTime.now(FIXED));
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("0.00");

        // 置回未结清：重新计入汇总且清空 settled_at。
        Loan reopened = service().setSettled(USER, loan.getId(), false);
        assertThat(reopened.isSettled()).isFalse();
        assertThat(reopened.getSettledAt()).isNull();
        assertThat(service().outstanding(USER, LoanDirection.BORROW)).isEqualByComparingTo("100.00");
    }

    @Test
    void list_unsettledFirst() {
        service().create(USER, "BORROW", "甲", new BigDecimal("100.00"), dt("2025-06-01T10:00:00"), null);
        Loan b = service().create(USER, "LEND", "乙", new BigDecimal("200.00"), dt("2025-06-05T10:00:00"), null);
        service().setSettled(USER, b.getId(), true);

        List<Loan> list = service().list(USER);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).isSettled()).isFalse(); // 未结清优先
        assertThat(list.get(1).isSettled()).isTrue();
    }

    @Test
    void crossUser_isIsolated() {
        Loan mine = service().create(USER, "BORROW", "甲", new BigDecimal("100.00"),
                dt("2025-06-01T10:00:00"), null);

        // 他人看不到、改不到、删不到。
        assertThat(service().list(OTHER)).isEmpty();
        assertThat(service().outstanding(OTHER, LoanDirection.BORROW)).isEqualByComparingTo("0.00");
        ApiException ex = catchThrowableOfType(
                () -> service().delete(OTHER, mine.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void create_rejectsInvalidDirection() {
        ApiException ex = catchThrowableOfType(
                () -> service().create(USER, "GIVE", "甲", new BigDecimal("1.00"), null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LOAN_FIELD_INVALID");
        assertThat(ex.getField()).isEqualTo("direction");
    }

    @Test
    void create_rejectsBlankCounterparty() {
        ApiException ex = catchThrowableOfType(
                () -> service().create(USER, "LEND", "   ", new BigDecimal("1.00"), null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LOAN_FIELD_INVALID");
        assertThat(ex.getField()).isEqualTo("counterparty");
    }

    @Test
    void create_rejectsNonPositiveAmount() {
        ApiException ex = catchThrowableOfType(
                () -> service().create(USER, "LEND", "甲", new BigDecimal("0.00"), null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LOAN_FIELD_INVALID");
        assertThat(ex.getField()).isEqualTo("amount");
    }

    private static LocalDateTime dt(String iso) {
        return LocalDateTime.parse(iso);
    }
}
