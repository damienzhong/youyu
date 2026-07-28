package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.MemberReportResponse;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link ReportService#memberReport} 的示例测试：协作账本按记账人聚合支出、占比合计 100、排除转账。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MemberReportTest {

    private static final long LEDGER = 100L;
    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;

    private ReportService service() {
        return new ReportService(transactionRepository, categoryRepository);
    }

    private void expense(long createdBy, String amount, String day) {
        Transaction t = new Transaction();
        t.setLedgerId(LEDGER);
        t.setCreatedBy(createdBy);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(new BigDecimal(amount));
        t.setAccountId(1L);
        t.setCategoryId(1L);
        t.setOccurredAt(LocalDateTime.parse(day + "T12:00:00"));
        t.setCreatedAt(LocalDateTime.parse(day + "T12:00:00"));
        t.setUpdatedAt(LocalDateTime.parse(day + "T12:00:00"));
        transactionRepository.save(t);
    }

    private void transfer(long createdBy, String amount, String day) {
        Transaction t = new Transaction();
        t.setLedgerId(LEDGER);
        t.setCreatedBy(createdBy);
        t.setType(TransactionType.TRANSFER);
        t.setAmount(new BigDecimal(amount));
        t.setSourceAccountId(1L);
        t.setDestinationAccountId(2L);
        t.setOccurredAt(LocalDateTime.parse(day + "T12:00:00"));
        t.setCreatedAt(LocalDateTime.parse(day + "T12:00:00"));
        t.setUpdatedAt(LocalDateTime.parse(day + "T12:00:00"));
        transactionRepository.save(t);
    }

    @Test
    void memberReport_aggregatesByRecorder_percentagesSumTo100_excludesTransfer() {
        expense(ALICE, "30.00", "2025-06-05");
        expense(ALICE, "10.00", "2025-06-08"); // Alice 共 40
        expense(BOB, "60.00", "2025-06-10");    // Bob 60
        transfer(BOB, "1000.00", "2025-06-11"); // 转账应被排除

        MemberReportResponse r = service().memberReport(
                LEDGER, LocalDate.parse("2025-06-01"), LocalDate.parse("2025-06-30"));

        assertThat(r.totalExpense()).isEqualByComparingTo("100.00");
        assertThat(r.members()).hasSize(2);
        // 金额降序：Bob(60) 在前，Alice(40) 在后。
        assertThat(r.members().get(0).userId()).isEqualTo(BOB);
        assertThat(r.members().get(0).amount()).isEqualByComparingTo("60.00");
        assertThat(r.members().get(0).percentage()).isEqualByComparingTo("60.00");
        assertThat(r.members().get(0).count()).isEqualTo(1);
        assertThat(r.members().get(1).userId()).isEqualTo(ALICE);
        assertThat(r.members().get(1).amount()).isEqualByComparingTo("40.00");
        assertThat(r.members().get(1).count()).isEqualTo(2);
        // 占比合计恰为 100。
        BigDecimal sumPct = r.members().stream()
                .map(MemberReportResponse.MemberShare::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumPct).isEqualByComparingTo("100.00");
    }

    @Test
    void memberReport_empty_returnsZero() {
        MemberReportResponse r = service().memberReport(
                LEDGER, LocalDate.parse("2025-06-01"), LocalDate.parse("2025-06-30"));
        assertThat(r.totalExpense()).isEqualByComparingTo("0.00");
        assertThat(r.members()).isEmpty();
    }
}
