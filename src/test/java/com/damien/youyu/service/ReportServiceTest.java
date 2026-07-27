package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.CategoryReportResponse.CategoryShare;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.api.dto.TrendReportResponse;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link ReportService} 的示例与边界单元测试（关联需求 4.12、7.1-7.7）。
 *
 * <p>使用 H2 + 真实 Repository，不使用任何桩。覆盖：本月收支结余（排除转账）、空月返回 0、
 * 分类占比合计 100%（含余数校正）、月度趋势填零月、区间非法被拒、自然月边界（半开区间）。
 * 属性测试（Property 15/16/17）在任务 7.2 中实现。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReportServiceTest {

    private static final long USER = 1L;
    private static final long OTHER_USER = 2L;

    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    private ReportService service() {
        return new ReportService(transactionRepository, categoryRepository);
    }

    // ---------------- 本月报表（需求 7.1、7.5、7.7） ----------------

    @Test
    void monthlyReport_sumsIncomeAndExpense_excludingTransfer() {
        // 2025-06 内：收入 2500 + 500；支出 23.50 + 76.50；转账 1000（应被排除）。
        income(USER, "2500.00", dt("2025-06-01T09:00:00"));
        income(USER, "500.00", dt("2025-06-15T09:00:00"));
        expense(USER, "23.50", dt("2025-06-10T12:30:00"));
        expense(USER, "76.50", dt("2025-06-20T18:00:00"));
        transfer(USER, "1000.00", dt("2025-06-12T10:00:00"));

        MonthlyReportResponse r = service().monthlyReport(USER, YearMonth.of(2025, 6));

        assertThat(r.month()).isEqualTo("2025-06");
        assertThat(r.totalIncome()).isEqualByComparingTo("3000.00");
        assertThat(r.totalExpense()).isEqualByComparingTo("100.00");
        // 需求 7.1：结余 = 收入 - 支出；需求 7.5：转账不计入。
        assertThat(r.balance()).isEqualByComparingTo("2900.00");
    }

    @Test
    void monthlyReport_emptyMonth_returnsZero() {
        // 需求 7.7：范围内无计入交易，各项为 0。
        MonthlyReportResponse r = service().monthlyReport(USER, YearMonth.of(2025, 6));

        assertThat(r.totalIncome()).isEqualByComparingTo("0.00");
        assertThat(r.totalExpense()).isEqualByComparingTo("0.00");
        assertThat(r.balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void monthlyReport_naturalMonthBoundary_isHalfOpenInShanghai() {
        // 边界：当月 1 日 00:00:00 计入本月；次月 1 日 00:00:00 不计入本月（需求 7.1）。
        income(USER, "100.00", dt("2025-06-01T00:00:00")); // 本月边界（含）
        income(USER, "200.00", dt("2025-06-30T23:59:59")); // 本月末（含）
        income(USER, "999.00", dt("2025-07-01T00:00:00")); // 次月边界（不含）
        income(USER, "888.00", dt("2025-05-31T23:59:59")); // 上月末（不含）

        MonthlyReportResponse r = service().monthlyReport(USER, YearMonth.of(2025, 6));

        assertThat(r.totalIncome()).isEqualByComparingTo("300.00");
    }

    @Test
    void monthlyReport_isolatedByUser() {
        income(USER, "100.00", dt("2025-06-01T09:00:00"));
        income(OTHER_USER, "500.00", dt("2025-06-01T09:00:00"));

        MonthlyReportResponse r = service().monthlyReport(USER, YearMonth.of(2025, 6));

        // 需求 2.3：仅统计本人交易。
        assertThat(r.totalIncome()).isEqualByComparingTo("100.00");
    }

    // ---------------- 分类占比（需求 7.2、7.3、7.5、7.7） ----------------

    @Test
    void categoryReport_percentagesSumTo100_withRemainderCorrection() {
        // 三个分类各 100，占比理论各 33.333%，四舍五入需余数校正保证合计 100.00。
        Category c1 = category(USER, CategoryKind.EXPENSE, "餐饮");
        Category c2 = category(USER, CategoryKind.EXPENSE, "交通");
        Category c3 = category(USER, CategoryKind.EXPENSE, "购物");
        expenseWithCategory(USER, "100.00", c1.getId(), dt("2025-06-10T12:00:00"));
        expenseWithCategory(USER, "100.00", c2.getId(), dt("2025-06-11T12:00:00"));
        expenseWithCategory(USER, "100.00", c3.getId(), dt("2025-06-12T12:00:00"));

        CategoryReportResponse r = service().categoryReport(
                USER, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));

        assertThat(r.totalExpense()).isEqualByComparingTo("300.00");
        assertThat(r.categories()).hasSize(3);
        BigDecimal pctSum = r.categories().stream()
                .map(CategoryShare::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 需求 7.3：占比之和恒为 100.00。
        assertThat(pctSum).isEqualByComparingTo("100.00");
        // 每个分类金额正确。
        assertThat(r.categories()).allSatisfy(
                s -> assertThat(s.amount()).isEqualByComparingTo("100.00"));
    }

    @Test
    void categoryReport_excludesTransferAndIncome() {
        Category c1 = category(USER, CategoryKind.EXPENSE, "餐饮");
        expenseWithCategory(USER, "80.00", c1.getId(), dt("2025-06-10T12:00:00"));
        income(USER, "500.00", dt("2025-06-10T12:00:00"));
        transfer(USER, "300.00", dt("2025-06-10T12:00:00"));

        CategoryReportResponse r = service().categoryReport(
                USER, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));

        // 需求 7.2/7.5：仅统计支出，排除收入与转账。
        assertThat(r.totalExpense()).isEqualByComparingTo("80.00");
        assertThat(r.categories()).hasSize(1);
        assertThat(r.categories().get(0).percentage()).isEqualByComparingTo("100.00");
    }

    @Test
    void categoryReport_inclusiveBoundaries_coverBothEndDays() {
        Category c1 = category(USER, CategoryKind.EXPENSE, "餐饮");
        expenseWithCategory(USER, "10.00", c1.getId(), dt("2025-06-01T00:00:00")); // from 当日（含）
        expenseWithCategory(USER, "20.00", c1.getId(), dt("2025-06-30T23:30:00")); // to 当日（含）
        expenseWithCategory(USER, "99.00", c1.getId(), dt("2025-07-01T00:00:00")); // to 之后（不含）

        CategoryReportResponse r = service().categoryReport(
                USER, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));

        // 需求 7.2：含起止边界，覆盖 to 当日整天。
        assertThat(r.totalExpense()).isEqualByComparingTo("30.00");
    }

    @Test
    void categoryReport_emptyRange_returnsZeroAndEmptyList() {
        CategoryReportResponse r = service().categoryReport(
                USER, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));

        // 需求 7.7：无支出返回 0 与空列表。
        assertThat(r.totalExpense()).isEqualByComparingTo("0.00");
        assertThat(r.categories()).isEmpty();
    }

    // ---------------- 月度趋势（需求 7.4、7.6、7.7） ----------------

    @Test
    void trendReport_fillsZeroForMonthsWithoutData() {
        income(USER, "100.00", dt("2025-06-15T09:00:00"));
        expense(USER, "40.00", dt("2025-08-15T09:00:00"));
        transfer(USER, "500.00", dt("2025-07-15T09:00:00")); // 排除

        TrendReportResponse r = service().trendReport(
                USER, YearMonth.of(2025, 6), YearMonth.of(2025, 8));

        // 需求 7.4：区间每月一项（6/7/8），无数据月返回 0；7 月仅有转账应为 0。
        assertThat(r.months()).hasSize(3);
        assertThat(r.months().get(0).month()).isEqualTo("2025-06");
        assertThat(r.months().get(0).income()).isEqualByComparingTo("100.00");
        assertThat(r.months().get(0).expense()).isEqualByComparingTo("0.00");
        assertThat(r.months().get(1).month()).isEqualTo("2025-07");
        assertThat(r.months().get(1).income()).isEqualByComparingTo("0.00");
        assertThat(r.months().get(1).expense()).isEqualByComparingTo("0.00");
        assertThat(r.months().get(2).month()).isEqualTo("2025-08");
        assertThat(r.months().get(2).expense()).isEqualByComparingTo("40.00");
    }

    @Test
    void trendReport_singleMonthRange_isAllowed() {
        TrendReportResponse r = service().trendReport(
                USER, YearMonth.of(2025, 6), YearMonth.of(2025, 6));
        assertThat(r.months()).hasSize(1);
    }

    @Test
    void trendReport_exactly24Months_isAllowed() {
        // 含起止共 24 个自然月（2024-01 .. 2025-12）应被接受。
        TrendReportResponse r = service().trendReport(
                USER, YearMonth.of(2024, 1), YearMonth.of(2025, 12));
        assertThat(r.months()).hasSize(24);
    }

    @Test
    void trendReport_moreThan24Months_rejected() {
        // 含起止共 25 个自然月（2024-01 .. 2026-01）应被拒绝（需求 7.6）。
        ApiException ex = catchThrowableOfType(() -> service().trendReport(
                USER, YearMonth.of(2024, 1), YearMonth.of(2026, 1)), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("REPORT_RANGE_INVALID");
    }

    @Test
    void trendReport_startAfterEnd_rejected() {
        ApiException ex = catchThrowableOfType(() -> service().trendReport(
                USER, YearMonth.of(2025, 8), YearMonth.of(2025, 6)), ApiException.class);
        // 需求 7.6：起始晚于结束被拒。
        assertThat(ex.getCode()).isEqualTo("REPORT_RANGE_INVALID");
    }

    // ---------------- 分类占比：笔数 + 收入类别 ----------------

    @Test
    void categoryReport_includesPerCategoryCount() {
        Category food = category(USER, CategoryKind.EXPENSE, "餐饮");
        expenseWithCategory(USER, "20.00", food.getId(), dt("2025-06-03T12:00:00"));
        expenseWithCategory(USER, "30.00", food.getId(), dt("2025-06-04T12:00:00"));

        CategoryReportResponse r = service().categoryReport(
                USER, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));

        assertThat(r.categories()).hasSize(1);
        assertThat(r.categories().get(0).amount()).isEqualByComparingTo("50.00");
        assertThat(r.categories().get(0).count()).isEqualTo(2);
    }

    @Test
    void categoryReport_incomeKind_aggregatesIncomeOnly() {
        Category salary = category(USER, CategoryKind.INCOME, "工资");
        incomeWithCategory(USER, "8000.00", salary.getId(), dt("2025-06-10T09:00:00"));
        expense(USER, "100.00", dt("2025-06-11T12:00:00")); // 支出不计入收入类别

        CategoryReportResponse r = service().categoryReport(
                USER, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), TransactionType.INCOME);

        assertThat(r.totalExpense()).isEqualByComparingTo("8000.00"); // 字段承载所选类别总额
        assertThat(r.categories()).hasSize(1);
        assertThat(r.categories().get(0).categoryId()).isEqualTo(salary.getId());
        assertThat(r.categories().get(0).count()).isEqualTo(1);
    }

    // ---------------- 区间收支报表 ----------------

    @Test
    void rangeReport_totalsAndSparseDailyPoints_excludeTransfer() {
        income(USER, "1000.00", dt("2025-06-01T09:00:00"));
        expense(USER, "40.00", dt("2025-06-01T20:00:00"));
        expense(USER, "60.00", dt("2025-06-03T12:00:00"));
        transfer(USER, "500.00", dt("2025-06-02T10:00:00")); // 排除

        var r = service().rangeReport(USER, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));

        assertThat(r.income()).isEqualByComparingTo("1000.00");
        assertThat(r.expense()).isEqualByComparingTo("100.00");
        assertThat(r.balance()).isEqualByComparingTo("900.00");
        // 仅 6/1 与 6/3 有活动（6/2 只有转账，被排除）。
        assertThat(r.days()).hasSize(2);
        assertThat(r.days().get(0).date()).isEqualTo("2025-06-01");
        assertThat(r.days().get(0).income()).isEqualByComparingTo("1000.00");
        assertThat(r.days().get(0).expense()).isEqualByComparingTo("40.00");
        assertThat(r.days().get(1).date()).isEqualTo("2025-06-03");
        assertThat(r.days().get(1).expense()).isEqualByComparingTo("60.00");
    }

    @Test
    void rangeReport_overMaxDays_rejected() {
        ApiException ex = catchThrowableOfType(() -> service().rangeReport(
                USER, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 2)), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("REPORT_RANGE_INVALID");
    }

    // ---------------- 测试数据构造 ----------------

    private static LocalDateTime dt(String iso) {
        return LocalDateTime.parse(iso);
    }

    private Category category(long userId, CategoryKind kind, String name) {
        Category c = new Category();
        c.setUserId(userId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(dt("2024-01-01T00:00:00"));
        c.setUpdatedAt(dt("2024-01-01T00:00:00"));
        return categoryRepository.save(c);
    }

    private void income(long userId, String amount, LocalDateTime when) {
        save(userId, TransactionType.INCOME, amount, when, 1L, null, null);
    }

    private void expense(long userId, String amount, LocalDateTime when) {
        save(userId, TransactionType.EXPENSE, amount, when, 1L, null, null);
    }

    private void expenseWithCategory(long userId, String amount, Long categoryId, LocalDateTime when) {
        save(userId, TransactionType.EXPENSE, amount, when, categoryId, null, null);
    }

    private void incomeWithCategory(long userId, String amount, Long categoryId, LocalDateTime when) {
        save(userId, TransactionType.INCOME, amount, when, categoryId, null, null);
    }

    private void transfer(long userId, String amount, LocalDateTime when) {
        save(userId, TransactionType.TRANSFER, amount, when, null, 10L, 11L);
    }

    private void save(long userId, TransactionType type, String amount, LocalDateTime when,
            Long categoryId, Long sourceAccountId, Long destinationAccountId) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        if (type == TransactionType.TRANSFER) {
            t.setSourceAccountId(sourceAccountId);
            t.setDestinationAccountId(destinationAccountId);
        } else {
            t.setAccountId(1L);
            t.setCategoryId(categoryId);
        }
        t.setOccurredAt(when);
        t.setCreatedAt(when);
        t.setUpdatedAt(when);
        transactionRepository.save(t);
    }
}
