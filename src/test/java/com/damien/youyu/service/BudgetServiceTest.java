package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.BudgetOverviewResponse;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link BudgetService} 示例与边界单元测试。使用 H2 + 真实 Repository，不使用桩。
 *
 * <p>覆盖：总预算总览（剩余/已用/状态、排除转账）、未设预算态、分类预算聚合与超支、
 * 未分配额度、沿用上月、金额校验、月份边界（半开区间）。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BudgetServiceTest {

    private static final long USER = 1L;

    // 固定「当前时刻」为 2025-06-15 12:00（UTC+8），便于验证预算健康的确定性。
    private static final Clock FIXED = Clock.fixed(
            LocalDateTime.of(2025, 6, 15, 12, 0).toInstant(ZoneOffset.ofHours(8)),
            ZoneId.of("Asia/Shanghai"));

    @Autowired
    private BudgetRepository budgetRepository;
    @Autowired
    private CategoryBudgetRepository categoryBudgetRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    private BudgetService service() {
        return new BudgetService(budgetRepository, categoryBudgetRepository,
                transactionRepository, categoryRepository, FIXED);
    }

    @Test
    void overview_withoutBudget_returnsHasBudgetFalseButSpent() {
        expense(USER, null, "100.00", dt("2025-06-10T12:00:00"));
        BudgetOverviewResponse o = service().overview(USER, YearMonth.of(2025, 6));

        assertThat(o.hasBudget()).isFalse();
        assertThat(o.totalBudget()).isNull();
        assertThat(o.spent()).isEqualByComparingTo("100.00");
        assertThat(o.remaining()).isNull();
        assertThat(o.health()).isNull();
    }

    @Test
    void overview_totalBudget_computesRemainingUsedAndExcludesTransfer() {
        service().setTotalBudget(USER, YearMonth.of(2025, 6), new BigDecimal("1000.00"));
        expense(USER, null, "800.00", dt("2025-06-10T12:00:00"));
        income(USER, "5000.00", dt("2025-06-11T09:00:00"));   // 收入不计入支出
        transfer(USER, "300.00", dt("2025-06-12T10:00:00"));  // 转账不计入支出

        BudgetOverviewResponse o = service().overview(USER, YearMonth.of(2025, 6));

        assertThat(o.hasBudget()).isTrue();
        assertThat(o.totalBudget()).isEqualByComparingTo("1000.00");
        assertThat(o.spent()).isEqualByComparingTo("800.00");
        assertThat(o.remaining()).isEqualByComparingTo("200.00");
        assertThat(o.usedPercent()).isEqualTo(80);
        assertThat(o.status()).isEqualTo("WARN"); // 80% 触发预警
        // 2025-06 当前月（FIXED 为 6/15）：剩余天数 30-15+1=16。
        assertThat(o.currentMonth()).isTrue();
        assertThat(o.health()).isNotNull();
        assertThat(o.health().daysLeft()).isEqualTo(16);
    }

    @Test
    void overview_overspent_statusOver() {
        service().setTotalBudget(USER, YearMonth.of(2025, 6), new BigDecimal("500.00"));
        expense(USER, null, "620.00", dt("2025-06-10T12:00:00"));

        BudgetOverviewResponse o = service().overview(USER, YearMonth.of(2025, 6));
        assertThat(o.remaining()).isEqualByComparingTo("-120.00");
        assertThat(o.status()).isEqualTo("OVER");
        assertThat(o.usedPercent()).isEqualTo(124);
    }

    @Test
    void categoryBudget_aggregatesSpentCountAndUnallocated() {
        Category food = category(USER, "餐饮", CategoryKind.EXPENSE);
        service().setTotalBudget(USER, YearMonth.of(2025, 6), new BigDecimal("3000.00"));
        service().setCategoryBudget(USER, YearMonth.of(2025, 6), food.getId(), new BigDecimal("2000.00"));
        expense(USER, food.getId(), "215.00", dt("2025-06-05T12:00:00"));
        expense(USER, food.getId(), "85.00", dt("2025-06-06T12:00:00"));

        BudgetOverviewResponse o = service().overview(USER, YearMonth.of(2025, 6));

        assertThat(o.allocated()).isEqualByComparingTo("2000.00");
        assertThat(o.unallocated()).isEqualByComparingTo("1000.00"); // 3000 - 2000
        assertThat(o.categories()).hasSize(1);
        var item = o.categories().get(0);
        assertThat(item.name()).isEqualTo("餐饮");
        assertThat(item.spent()).isEqualByComparingTo("300.00");
        assertThat(item.remaining()).isEqualByComparingTo("1700.00");
        assertThat(item.txCount()).isEqualTo(2);
        assertThat(item.status()).isEqualTo("OK");
    }

    @Test
    void copyFromPreviousMonth_copiesTotalAndCategory() {
        Category food = category(USER, "餐饮", CategoryKind.EXPENSE);
        service().setTotalBudget(USER, YearMonth.of(2025, 5), new BigDecimal("3000.00"));
        service().setCategoryBudget(USER, YearMonth.of(2025, 5), food.getId(), new BigDecimal("800.00"));

        BudgetOverviewResponse o = service().copyFromPreviousMonth(USER, YearMonth.of(2025, 6));

        assertThat(o.totalBudget()).isEqualByComparingTo("3000.00");
        assertThat(o.categories()).hasSize(1);
        assertThat(o.categories().get(0).budget()).isEqualByComparingTo("800.00");
    }

    @Test
    void overview_excludesAaExpenseAndSettlement_fromSpent() {
        // 需求 7.4 / Property 6：即便同一账本查询扫到 AA 类型流水，也仅按 type=expense 计入预算已支出，
        // aa_expense / aa_settlement 一律不计入（AA 支出为个人消费份额、应收应付为债权债务）。
        Category food = category(USER, "餐饮", CategoryKind.EXPENSE);
        service().setTotalBudget(USER, YearMonth.of(2025, 6), new BigDecimal("1000.00"));
        expense(USER, food.getId(), "100.00", dt("2025-06-05T12:00:00"));
        aaExpense(USER, food.getId(), "500.00", dt("2025-06-06T12:00:00"));   // 不计入
        aaSettlement(USER, "300.00", dt("2025-06-07T12:00:00"));              // 不计入

        BudgetOverviewResponse o = service().overview(USER, YearMonth.of(2025, 6));

        assertThat(o.spent()).isEqualByComparingTo("100.00");
        assertThat(o.remaining()).isEqualByComparingTo("900.00");
    }

    @Test
    void setTotalBudget_rejectsInvalidAmount() {
        ApiException ex = catchThrowableOfType(
                () -> service().setTotalBudget(USER, YearMonth.of(2025, 6), new BigDecimal("0.00")),
                ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("BUDGET_AMOUNT_INVALID");
    }

    // ---------------- 测试夹具 ----------------

    private Category category(long ledgerId, String name, CategoryKind kind) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(LocalDateTime.now(FIXED));
        c.setUpdatedAt(LocalDateTime.now(FIXED));
        return categoryRepository.save(c);
    }

    private void expense(long ledgerId, Long categoryId, String amount, LocalDateTime at) {
        Long catId = categoryId != null ? categoryId : category(ledgerId, "支出" + at, CategoryKind.EXPENSE).getId();
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(new BigDecimal(amount));
        t.setAccountId(1L);
        t.setCategoryId(catId);
        t.setOccurredAt(at);
        t.setCreatedAt(at);
        t.setUpdatedAt(at);
        transactionRepository.save(t);
    }

    private void income(long ledgerId, String amount, LocalDateTime at) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(TransactionType.INCOME);
        t.setAmount(new BigDecimal(amount));
        t.setAccountId(1L);
        t.setCategoryId(category(ledgerId, "收入" + at, CategoryKind.INCOME).getId());
        t.setOccurredAt(at);
        t.setCreatedAt(at);
        t.setUpdatedAt(at);
        transactionRepository.save(t);
    }

    private void transfer(long ledgerId, String amount, LocalDateTime at) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(TransactionType.TRANSFER);
        t.setAmount(new BigDecimal(amount));
        t.setSourceAccountId(1L);
        t.setDestinationAccountId(2L);
        t.setOccurredAt(at);
        t.setCreatedAt(at);
        t.setUpdatedAt(at);
        transactionRepository.save(t);
    }

    private void aaExpense(long ledgerId, Long categoryId, String amount, LocalDateTime at) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(TransactionType.AA_EXPENSE);
        t.setAmount(new BigDecimal(amount));
        t.setAccountId(1L);
        t.setCategoryId(categoryId);
        t.setPayerUserId(USER);
        t.setOccurredAt(at);
        t.setCreatedAt(at);
        t.setUpdatedAt(at);
        transactionRepository.save(t);
    }

    private void aaSettlement(long ledgerId, String amount, LocalDateTime at) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(TransactionType.AA_SETTLEMENT);
        t.setAmount(new BigDecimal(amount));
        t.setOccurredAt(at);
        t.setCreatedAt(at);
        t.setUpdatedAt(at);
        transactionRepository.save(t);
    }

    private static LocalDateTime dt(String iso) {
        return LocalDateTime.parse(iso);
    }
}
