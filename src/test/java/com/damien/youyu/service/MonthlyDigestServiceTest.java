package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.MonthlyDigestResponse;
import com.damien.youyu.api.dto.RangeReportResponse;
import com.damien.youyu.domain.Budget;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;

/**
 * {@link MonthlyDigestService} 的示例与边界单元测试（关联需求 1.5、1.9、3.4、4.5、5.3、5.4、6.3、7.1、7.5、9.5）。
 *
 * <p>采用与 {@code ReportServiceTest} 一致的 {@code @DataJpaTest} + 真实 H2（MODE=MySQL）+ 真实
 * Repository 范式，被测的 {@link MonthlyDigestService}（连同其编排的真实 {@link ReportService} /
 * {@link BudgetService}）全部真实执行，不使用任何 mock。每个 {@code @Test} 用固定注入的
 * {@code Asia/Shanghai} {@link Clock} 精确控制「当前时刻」，从而确定性地覆盖 {@code partial}/{@code final}
 * 月状态与相关窗口边界。</p>
 *
 * <p>这些示例是对 {@code MonthlyDigestServicePropertyTest} 属性测试的补充：以手工构造的确定场景钉住
 * 关键边界行为（稠密趋势填零、最大单笔 tie-break、周分段计数与不足段、预算前瞻按月状态、已删除分类回退名、
 * 账本隔离 Property 3 的定点验证）。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MonthlyDigestServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 各测试使用不同的 ledgerId 段，避免任何潜在的跨测串味（同时 @DataJpaTest 事务回滚亦保证隔离）。 */
    private static final long L_STATUS_PARTIAL = 6_100_000_001L;
    private static final long L_STATUS_FINAL = 6_100_000_002L;
    private static final long L_TREND = 6_100_000_003L;
    private static final long L_TIE = 6_100_000_004L;
    private static final long L_FEB = 6_100_000_005L;
    private static final long L_JULY = 6_100_000_006L;
    private static final long L_PARTIAL_SHORT = 6_100_000_007L;
    private static final long L_BUDGET_ABSENT = 6_100_000_008L;
    private static final long L_BUDGET_PARTIAL = 6_100_000_009L;
    private static final long L_BUDGET_FINAL = 6_100_000_010L;
    private static final long L_DELETED_CAT = 6_100_000_011L;
    private static final long L_ISO_A = 6_100_000_012L;
    private static final long L_ISO_B = 6_100_000_013L;

    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private TransactionTagRepository transactionTagRepository;
    @Autowired
    private BudgetRepository budgetRepository;
    @Autowired
    private CategoryBudgetRepository categoryBudgetRepository;

    /** 以固定注入的 {@link Clock} 组装真实月报组合器（编排真实 ReportService / BudgetService）。 */
    private MonthlyDigestService digestService(Clock clock) {
        ReportService reportService = new ReportService(transactionRepository, categoryRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository);
        BudgetService budgetService = new BudgetService(budgetRepository, categoryBudgetRepository,
                transactionRepository, categoryRepository, clock);
        return new MonthlyDigestService(reportService, budgetService,
                transactionRepository, categoryRepository, clock);
    }

    /** 构造 Asia/Shanghai 时区、指定日期中午的固定时钟。 */
    private Clock clockAt(LocalDate now) {
        return Clock.fixed(now.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
    }

    // ---------------- 月状态：partial / final（需求 1.3、1.4） ----------------

    @Test
    void monthStatus_isPartial_whenTargetIsCurrentMonth() {
        // 当前时刻落在 2025-06 → 目标月 2025-06 为进行中。
        Clock clock = clockAt(LocalDate.of(2025, 6, 15));
        MonthlyDigestResponse resp = digestService(clock).digest(L_STATUS_PARTIAL, YearMonth.of(2025, 6));

        assertThat(resp.month()).isEqualTo("2025-06");
        assertThat(resp.monthStatus()).isEqualTo(MonthlyDigestService.STATUS_PARTIAL);
    }

    @Test
    void monthStatus_isFinal_whenTargetIsBeforeCurrentMonth() {
        // 当前时刻落在 2025-08 → 目标月 2025-06 已完结。
        Clock clock = clockAt(LocalDate.of(2025, 8, 15));
        MonthlyDigestResponse resp = digestService(clock).digest(L_STATUS_FINAL, YearMonth.of(2025, 6));

        assertThat(resp.month()).isEqualTo("2025-06");
        assertThat(resp.monthStatus()).isEqualTo(MonthlyDigestService.STATUS_FINAL);
    }

    // ---------------- 消费趋势：稠密填零（需求 3.1、3.4、3.5） ----------------

    @Test
    void trend_isDenseAndZeroFilled_forFinalMonth() {
        // 2025-06（30 天）已完结月：仅 6/1 与 6/3 有活动，其余日应填 0.00。
        Clock clock = clockAt(LocalDate.of(2025, 8, 15));
        income(L_TREND, "100.00", dt("2025-06-01T09:00:00"));
        expense(L_TREND, "40.00", dt("2025-06-01T20:00:00"));
        expense(L_TREND, "60.00", dt("2025-06-03T12:00:00"));

        MonthlyDigestResponse resp = digestService(clock).digest(L_TREND, YearMonth.of(2025, 6));
        java.util.List<RangeReportResponse.DayPoint> trend = resp.trend();

        // 稠密：覆盖整月 30 天、升序、首末为月首/月末（需求 3.1、3.2、3.4）。
        assertThat(trend).hasSize(30);
        assertThat(trend.get(0).date()).isEqualTo("2025-06-01");
        assertThat(trend.get(29).date()).isEqualTo("2025-06-30");

        // 6/1 有活动。
        assertThat(trend.get(0).income()).isEqualByComparingTo("100.00");
        assertThat(trend.get(0).expense()).isEqualByComparingTo("40.00");
        // 6/2 无活动 → 收支均 0.00（需求 3.5）。
        assertThat(trend.get(1).date()).isEqualTo("2025-06-02");
        assertThat(trend.get(1).income()).isEqualByComparingTo("0.00");
        assertThat(trend.get(1).expense()).isEqualByComparingTo("0.00");
        // 6/3 有活动。
        assertThat(trend.get(2).expense()).isEqualByComparingTo("60.00");
        // 6/30 无活动 → 0.00。
        assertThat(trend.get(29).income()).isEqualByComparingTo("0.00");
        assertThat(trend.get(29).expense()).isEqualByComparingTo("0.00");
    }

    // ---------------- 最大单笔消费：tie-break（需求 6.3） ----------------

    @Test
    void largestExpense_tieBreak_prefersLaterOccurredThenLargerId() {
        // 三笔并列最大金额 500.00：占比决胜为 occurred_at 更晚 → 再 id 更大。
        Clock clock = clockAt(LocalDate.of(2025, 8, 15));
        Category food = saveCategory(L_TIE, CategoryKind.EXPENSE, "餐饮");

        // e1：较早时间。
        persistExpenseWithNote(L_TIE, new BigDecimal("500.00"),
                dt("2025-06-05T09:00:00"), food.getId(), "e1");
        // e2 与 e3：相同（更晚）时间；e3 后保存 → id 更大，应最终胜出。
        persistExpenseWithNote(L_TIE, new BigDecimal("500.00"),
                dt("2025-06-10T09:00:00"), food.getId(), "e2");
        Transaction e3 = persistExpenseWithNote(L_TIE, new BigDecimal("500.00"),
                dt("2025-06-10T09:00:00"), food.getId(), "e3");
        // 一笔更小金额，不应被选中。
        persistExpenseWithNote(L_TIE, new BigDecimal("300.00"),
                dt("2025-06-20T09:00:00"), food.getId(), "small");

        MonthlyDigestResponse resp = digestService(clock).digest(L_TIE, YearMonth.of(2025, 6));
        MonthlyDigestResponse.LargestExpense largest = resp.largestExpense();

        assertThat(largest).isNotNull();
        assertThat(largest.amount()).isEqualByComparingTo("500.00");
        assertThat(largest.date()).isEqualTo("2025-06-10");
        // occurred_at 相同 → id 更大者胜出（e3）。
        assertThat(largest.note()).isEqualTo("e3");
        assertThat(e3.getId()).isNotNull();
        assertThat(largest.categoryName()).isEqualTo("餐饮");
    }

    // ---------------- 周分段：2 月恰 4 个完整段（需求 7.1、7.2、7.4） ----------------

    @Test
    void frugalWeek_february_hasExactlyFourCompleteSegments() {
        // 2025-02（28 天）已完结月：恰好 4 个完整 7 日分段 [1-7][8-14][15-21][22-28]。
        // 逐段支出递减，最低段为第 4 段（22-28），证明第 4 个完整段存在且以 28 日收尾。
        Clock clock = clockAt(LocalDate.of(2025, 5, 15));
        Category c = saveCategory(L_FEB, CategoryKind.EXPENSE, "餐饮");
        expenseCat(L_FEB, "400.00", c.getId(), dt("2025-02-03T12:00:00")); // 段1
        expenseCat(L_FEB, "300.00", c.getId(), dt("2025-02-10T12:00:00")); // 段2
        expenseCat(L_FEB, "200.00", c.getId(), dt("2025-02-17T12:00:00")); // 段3
        expenseCat(L_FEB, "100.00", c.getId(), dt("2025-02-24T12:00:00")); // 段4（最低）

        MonthlyDigestResponse resp = digestService(clock).digest(L_FEB, YearMonth.of(2025, 2));
        MonthlyDigestResponse.FrugalWeek frugal = resp.mostFrugalWeek();

        assertThat(frugal).isNotNull();
        assertThat(frugal.startDate()).isEqualTo("2025-02-22");
        assertThat(frugal.endDate()).isEqualTo("2025-02-28");
        assertThat(frugal.expense()).isEqualByComparingTo("100.00");
    }

    // ---------------- 周分段：7 月末 3 日不成段（需求 7.1、7.2） ----------------

    @Test
    void frugalWeek_july_excludesIncompleteTailOfLastThreeDays() {
        // 2025-07（31 天）已完结月：完整段 [1-7][8-14][15-21][22-28]，末尾 29-31 不成段。
        // 把最低支出放在不成段的 29-31：若被误计入则会被选中。断言最省一周仍为完整段（段1，100.00）。
        Clock clock = clockAt(LocalDate.of(2025, 9, 15));
        Category c = saveCategory(L_JULY, CategoryKind.EXPENSE, "餐饮");
        expenseCat(L_JULY, "100.00", c.getId(), dt("2025-07-03T12:00:00")); // 段1（完整段中最低）
        expenseCat(L_JULY, "200.00", c.getId(), dt("2025-07-10T12:00:00")); // 段2
        expenseCat(L_JULY, "300.00", c.getId(), dt("2025-07-17T12:00:00")); // 段3
        expenseCat(L_JULY, "400.00", c.getId(), dt("2025-07-24T12:00:00")); // 段4
        expenseCat(L_JULY, "1.00", c.getId(), dt("2025-07-30T12:00:00"));   // 不成段的末尾（应被排除）

        MonthlyDigestResponse resp = digestService(clock).digest(L_JULY, YearMonth.of(2025, 7));
        MonthlyDigestResponse.FrugalWeek frugal = resp.mostFrugalWeek();

        assertThat(frugal).isNotNull();
        // 不成段的 29-31 未参与评比：最省一周为完整段 1。
        assertThat(frugal.startDate()).isEqualTo("2025-07-01");
        assertThat(frugal.endDate()).isEqualTo("2025-07-07");
        assertThat(frugal.expense()).isEqualByComparingTo("100.00");
        // 结束边界不晚于最后一个完整段的收尾日 28。
        assertThat(LocalDate.parse(frugal.endDate())).isBeforeOrEqualTo(LocalDate.of(2025, 7, 28));
    }

    // ---------------- 周分段：partial 当前日不足 7 天 → null（需求 7.5、7.6） ----------------

    @Test
    void frugalWeek_partialMonth_withFewerThanSevenElapsedDays_returnsNull() {
        // 进行中月 2025-06，当前日为 6/5（已过天数 < 7）→ 无任何完整合格分段 → 最省一周为 null。
        Clock clock = clockAt(LocalDate.of(2025, 6, 5));
        Category c = saveCategory(L_PARTIAL_SHORT, CategoryKind.EXPENSE, "餐饮");
        expenseCat(L_PARTIAL_SHORT, "10.00", c.getId(), dt("2025-06-02T12:00:00"));
        expenseCat(L_PARTIAL_SHORT, "20.00", c.getId(), dt("2025-06-04T12:00:00"));

        MonthlyDigestResponse resp = digestService(clock).digest(L_PARTIAL_SHORT, YearMonth.of(2025, 6));

        assertThat(resp.monthStatus()).isEqualTo(MonthlyDigestService.STATUS_PARTIAL);
        assertThat(resp.mostFrugalWeek()).isNull();
    }

    // ---------------- 预算：未设预算（需求 5.3） ----------------

    @Test
    void budget_absent_hasBudgetFalse_withNullFields() {
        // 目标月未设置月度总预算：hasBudget=false，且 totalBudget/remaining/status/forecast 为 null。
        Clock clock = clockAt(LocalDate.of(2025, 6, 15));
        expense(L_BUDGET_ABSENT, "50.00", dt("2025-06-02T12:00:00"));

        MonthlyDigestResponse.BudgetDigest budget =
                digestService(clock).digest(L_BUDGET_ABSENT, YearMonth.of(2025, 6)).budget();

        assertThat(budget.hasBudget()).isFalse();
        assertThat(budget.totalBudget()).isNull();
        assertThat(budget.remaining()).isNull();
        assertThat(budget.status()).isNull();
        assertThat(budget.forecast()).isNull();
        assertThat(budget.usedPercent()).isZero();
    }

    // ---------------- 预算：已设 + partial → 前瞻在场（需求 5.1、5.4） ----------------

    @Test
    void budget_set_onPartialMonth_includesForecast() {
        // 进行中月已设总预算：hasBudget=true 且携带前瞻 forecast（需求 5.4）。
        Clock clock = clockAt(LocalDate.of(2025, 6, 15));
        saveBudget(L_BUDGET_PARTIAL, YearMonth.of(2025, 6), new BigDecimal("1000.00"));
        expense(L_BUDGET_PARTIAL, "200.00", dt("2025-06-05T12:00:00"));

        MonthlyDigestResponse.BudgetDigest budget =
                digestService(clock).digest(L_BUDGET_PARTIAL, YearMonth.of(2025, 6)).budget();

        assertThat(budget.hasBudget()).isTrue();
        assertThat(budget.totalBudget()).isEqualByComparingTo("1000.00");
        assertThat(budget.spent()).isEqualByComparingTo("200.00");
        assertThat(budget.remaining()).isEqualByComparingTo("800.00");
        assertThat(budget.status()).isEqualTo("OK");
        assertThat(budget.forecast()).isNotNull();
    }

    // ---------------- 预算：已设 + final → 无前瞻（需求 5.5） ----------------

    @Test
    void budget_set_onFinalMonth_hasNoForecast() {
        // 已完结月已设总预算：hasBudget=true，但不返回前瞻 forecast（需求 5.5）。
        Clock clock = clockAt(LocalDate.of(2025, 8, 15));
        saveBudget(L_BUDGET_FINAL, YearMonth.of(2025, 6), new BigDecimal("1000.00"));
        expense(L_BUDGET_FINAL, "200.00", dt("2025-06-05T12:00:00"));

        MonthlyDigestResponse.BudgetDigest budget =
                digestService(clock).digest(L_BUDGET_FINAL, YearMonth.of(2025, 6)).budget();

        assertThat(budget.hasBudget()).isTrue();
        assertThat(budget.totalBudget()).isEqualByComparingTo("1000.00");
        assertThat(budget.forecast()).isNull();
    }

    // ---------------- 已删除分类回退名（需求 4.5、6.2） ----------------

    @Test
    void deletedCategory_fallsBackToPlaceholderName_inRankingAndLargestExpense() {
        // 一笔支出指向不存在（已删除）的分类 id：分类排行与最大单笔的分类名回退为 "已删除分类"，且排行不丢项。
        Clock clock = clockAt(LocalDate.of(2025, 8, 15));
        long deletedCatId = 9_500_000_000L + L_DELETED_CAT;
        persistExpenseWithNote(L_DELETED_CAT, new BigDecimal("88.00"),
                dt("2025-06-10T12:00:00"), deletedCatId, "orphan");

        MonthlyDigestResponse resp = digestService(clock).digest(L_DELETED_CAT, YearMonth.of(2025, 6));

        // 分类排行：不丢项，名称回退（需求 4.5）。
        assertThat(resp.categoryRanking()).hasSize(1);
        CategoryReportResponse.CategoryShare share = resp.categoryRanking().get(0);
        assertThat(share.categoryId()).isEqualTo(deletedCatId);
        assertThat(share.categoryName()).isEqualTo(MonthlyDigestService.DELETED_CATEGORY_NAME);
        assertThat(share.amount()).isEqualByComparingTo("88.00");

        // 最大单笔的分类名同样回退（需求 6.2）。
        assertThat(resp.largestExpense()).isNotNull();
        assertThat(resp.largestExpense().categoryName())
                .isEqualTo(MonthlyDigestService.DELETED_CATEGORY_NAME);
    }

    // ---------------- 账本隔离：Property 3 的定点验证（需求 1.5、9.5） ----------------

    @Test
    void ledgerIsolation_digestOfLedgerA_excludesLedgerBTransactions() {
        // 账本 A 与 B 同月各有交易；A 的月报仅基于 A，B 的任何交易都不计入 A（Property 3 定点验证）。
        Clock clock = clockAt(LocalDate.of(2025, 8, 15));
        Category catA = saveCategory(L_ISO_A, CategoryKind.EXPENSE, "A-餐饮");

        // 账本 A：收入 100、支出 40。
        income(L_ISO_A, "100.00", dt("2025-06-01T09:00:00"));
        expenseCat(L_ISO_A, "40.00", catA.getId(), dt("2025-06-02T12:00:00"));

        // 账本 B：截然不同的金额，绝不应出现在 A 的月报里。
        Category catB = saveCategory(L_ISO_B, CategoryKind.EXPENSE, "B-购物");
        income(L_ISO_B, "999.00", dt("2025-06-01T09:00:00"));
        expenseCat(L_ISO_B, "888.00", catB.getId(), dt("2025-06-02T12:00:00"));

        MonthlyDigestResponse resp = digestService(clock).digest(L_ISO_A, YearMonth.of(2025, 6));

        // 仅 A 的口径：收入 100、支出 40、结余 60（B 的 999/888 被隔离）。
        assertThat(resp.income()).isEqualByComparingTo("100.00");
        assertThat(resp.expense()).isEqualByComparingTo("40.00");
        assertThat(resp.netBalance()).isEqualByComparingTo("60.00");

        // 分类排行只含 A 的分类；最大单笔为 A 的 40.00，绝非 B 的 888.00。
        assertThat(resp.categoryRanking()).hasSize(1);
        assertThat(resp.categoryRanking().get(0).categoryId()).isEqualTo(catA.getId());
        assertThat(resp.largestExpense()).isNotNull();
        assertThat(resp.largestExpense().amount()).isEqualByComparingTo("40.00");
    }

    // ---------------- 测试数据构造 ----------------

    private static LocalDateTime dt(String iso) {
        return LocalDateTime.parse(iso);
    }

    private Category saveCategory(long ledgerId, CategoryKind kind, String name) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(dt("2024-01-01T00:00:00"));
        c.setUpdatedAt(dt("2024-01-01T00:00:00"));
        return categoryRepository.save(c);
    }

    private Budget saveBudget(long ledgerId, YearMonth month, BigDecimal amount) {
        Budget b = new Budget();
        b.setLedgerId(ledgerId);
        b.setMonth(month.toString());
        b.setAmount(amount);
        b.setCreatedAt(dt("2024-01-01T00:00:00"));
        b.setUpdatedAt(dt("2024-01-01T00:00:00"));
        return budgetRepository.save(b);
    }

    private void income(long ledgerId, String amount, LocalDateTime when) {
        persist(ledgerId, TransactionType.INCOME, new BigDecimal(amount), when, 1L);
    }

    private void expense(long ledgerId, String amount, LocalDateTime when) {
        persist(ledgerId, TransactionType.EXPENSE, new BigDecimal(amount), when, 1L);
    }

    private void expenseCat(long ledgerId, String amount, Long categoryId, LocalDateTime when) {
        persist(ledgerId, TransactionType.EXPENSE, new BigDecimal(amount), when, categoryId);
    }

    private void persist(long ledgerId, TransactionType type, BigDecimal amount, LocalDateTime when,
            Long categoryId) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(type);
        t.setAmount(amount);
        if (type == TransactionType.TRANSFER) {
            t.setSourceAccountId(10L);
            t.setDestinationAccountId(11L);
        } else {
            t.setAccountId(1L);
            t.setCategoryId(categoryId);
        }
        t.setOccurredAt(when);
        t.setCreatedAt(when);
        t.setUpdatedAt(when);
        transactionRepository.save(t);
    }

    /** 落库一笔带备注的支出并返回已保存实体（含自增 id），供最大单笔 tie-break 校验。 */
    private Transaction persistExpenseWithNote(
            long ledgerId, BigDecimal amount, LocalDateTime when, Long categoryId, String note) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(amount);
        t.setAccountId(1L);
        t.setCategoryId(categoryId);
        t.setNote(note);
        t.setOccurredAt(when);
        t.setCreatedAt(when);
        t.setUpdatedAt(when);
        return transactionRepository.save(t);
    }
}
