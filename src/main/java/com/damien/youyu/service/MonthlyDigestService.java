package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.BudgetOverviewResponse;
import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.MonthlyDigestResponse;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.api.dto.RangeReportResponse;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 智能月报组合器（只读 read-only composer）：编排既有 {@link ReportService} / {@link BudgetService}，
 * 并对月内交易补充两项内存计算（最大单笔消费、最省钱的一周），把九个模块打包为一个
 * {@link MonthlyDigestResponse}（design.md「Architecture」「Components and Interfaces」）。
 *
 * <p>边界与口径全部沿用既有服务：金额一律 {@link BigDecimal} 保留 2 位小数（HALF_UP）；自然月/自然日
 * 边界按 {@code Asia/Shanghai}（由注入的 {@link java.time.Clock} 决定）；所有金额统计排除
 * {@code type=transfer}。全过程只读、无任何写语句（需求 11.1）。</p>
 *
 * <p><b>月状态与结束边界</b>（需求 1.3、1.4）：</p>
 * <ul>
 *   <li>{@code status = month.isBefore(YearMonth.now(clock)) ? "final" : "partial"}。</li>
 *   <li>结束边界：{@code final} → 目标月月末日；{@code partial} → 当前日 {@code LocalDate.now(clock)}。
 *       用于消费趋势的稠密化窗口与最省钱的一周的完整分段评比。</li>
 * </ul>
 */
@Service
public class MonthlyDigestService {

    /** 月状态：已完结（目标月早于当前自然月）。 */
    static final String STATUS_FINAL = "final";

    /** 月状态：进行中（目标月为当前自然月且当月未结束）。 */
    static final String STATUS_PARTIAL = "partial";

    /** 金额统一保留的小数位。 */
    private static final int SCALE = 2;

    /** 周分段长度：自 1 日起每 7 个自然日为一段（需求 7.1）。 */
    private static final int WEEK_LENGTH = 7;

    /** 零值（0.00），供空月/占位返回。 */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    /** 已删除分类的回退名称（需求 4.5）：分类名缺失/为空时套用，且不使该分类从排行中丢失。 */
    static final String DELETED_CATEGORY_NAME = "已删除分类";

    private final ReportService reportService;
    private final BudgetService budgetService;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final java.time.Clock clock;

    public MonthlyDigestService(
            ReportService reportService,
            BudgetService budgetService,
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            java.time.Clock clock) {
        this.reportService = reportService;
        this.budgetService = budgetService;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.clock = clock;
    }

    /**
     * 生成目标月的智能月报数据包（需求 1）。纯只读派生，不落库。
     *
     * <p>本方法目前完成骨架：月状态与结束边界计算（任务 2.1）；收支/趋势/分类/预算的组合
     * （任务 2.2）、最大单笔/最省钱的一周（任务 2.3）与空数据语义打包（任务 2.4）由后续任务填充。</p>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月（按 {@code Asia/Shanghai} 边界）
     * @return 目标月九个模块的数据包
     */
    @Transactional(readOnly = true)
    public MonthlyDigestResponse digest(Long ledgerId, YearMonth month) {
        // 月状态：目标月早于当前自然月为已完结，否则进行中（需求 1.3、1.4）。
        String monthStatus = month.isBefore(YearMonth.now(clock)) ? STATUS_FINAL : STATUS_PARTIAL;

        // 目标月自然日边界：起始为 1 日；结束边界随月状态而定，用于趋势稠密化与周分段评比。
        LocalDate monthStart = month.atDay(1);
        // 结束边界：final → 月末日；partial → 当前日（需求 1.3、1.4、3.4、7.6）。
        LocalDate endBoundary = monthStatus.equals(STATUS_FINAL)
                ? month.atEndOfMonth()
                : LocalDate.now(clock);

        // 收支结余（需求 2、11.5）：复用既有 monthlyReport 口径，结余为收入减支出。
        MonthlyReportResponse monthly = reportService.monthlyReport(ledgerId, month);
        BigDecimal income = monthly.totalIncome();
        BigDecimal expense = monthly.totalExpense();
        BigDecimal netBalance = monthly.balance();

        // 消费趋势（需求 3）：复用 rangeReport 按日明细，再稠密化为范围内每日一项。
        List<RangeReportResponse.DayPoint> trend = buildTrend(ledgerId, monthStart, endBoundary);

        // 分类排行（需求 4）：复用 categoryReport 排序/占比/笔数，对缺失分类名套用回退名。
        List<CategoryReportResponse.CategoryShare> categoryRanking =
                buildCategoryRanking(ledgerId, monthStart, endBoundary);

        // 预算情况（需求 5）：复用 overview；前瞻仅在 overview 返回非空时携带。
        MonthlyDigestResponse.BudgetDigest budget = buildBudget(ledgerId, month);

        // 最大单笔消费与最省钱的一周（需求 6、7）：一次性读取月内交易、过滤 expense 后在内存计算。
        // 半开区间 [月首 00:00, 次月首 00:00)，与既有报表/预算口径一致（账本隔离 + 排除转账）。
        LocalDate nextMonthStart = month.plusMonths(1).atDay(1);
        List<Transaction> expenses = fetchMonthExpenses(ledgerId, monthStart, nextMonthStart);

        MonthlyDigestResponse.LargestExpense largestExpense = buildLargestExpense(ledgerId, expenses);
        MonthlyDigestResponse.FrugalWeek mostFrugalWeek =
                buildMostFrugalWeek(month, monthStatus, expenses);

        return new MonthlyDigestResponse(
                month.toString(),
                monthStatus,
                income,
                expense,
                netBalance,
                trend,
                categoryRanking,
                budget,
                largestExpense,
                mostFrugalWeek);
    }

    /**
     * 一次性读取目标月内当前账本、未删除、{@code type=expense} 的交易（需求 6.1、7.2）。
     * 复用半开区间查询 {@link TransactionRepository#findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan}
     * （账本隔离 + 边界一致），软删除由实体 {@code @SQLRestriction} 自动排除，此处再过滤 {@code expense}
     * （天然排除转账与收入）。
     */
    private List<Transaction> fetchMonthExpenses(
            Long ledgerId, LocalDate monthStart, LocalDate nextMonthStart) {
        LocalDateTime from = monthStart.atStartOfDay();
        LocalDateTime to = nextMonthStart.atStartOfDay();
        return transactionRepository
                .findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(ledgerId, from, to)
                .stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .toList();
    }

    /**
     * 选出最大单笔消费（需求 6.1–6.4）。按 {@code amount} 取最大，并列时 {@code occurred_at} 更晚者优先、
     * {@code occurred_at} 相同则 {@code id} 更大者优先，保证结果确定唯一。携带金额（2 位小数）、分类名称
     * （分类缺失/名称为空回退为 {@link #DELETED_CATEGORY_NAME}）、发生日期（{@code YYYY-MM-DD}）与备注
     * （缺省为空串）。无计入支出时返回 {@code null}（需求 6.4）。
     */
    private MonthlyDigestResponse.LargestExpense buildLargestExpense(
            Long ledgerId, List<Transaction> expenses) {
        if (expenses.isEmpty()) {
            return null;
        }
        Comparator<Transaction> pick = Comparator
                .comparing(Transaction::getAmount)
                .thenComparing(Transaction::getOccurredAt)
                .thenComparing(Transaction::getId);
        Transaction top = expenses.stream().max(pick).orElseThrow();

        Map<Long, String> nameById = categoryNameMap(ledgerId);
        String rawName = top.getCategoryId() == null ? null : nameById.get(top.getCategoryId());
        String categoryName = (rawName == null || rawName.isBlank()) ? DELETED_CATEGORY_NAME : rawName;
        String note = top.getNote() == null ? "" : top.getNote();

        return new MonthlyDigestResponse.LargestExpense(
                top.getAmount().setScale(SCALE, RoundingMode.HALF_UP),
                categoryName,
                top.getOccurredAt().toLocalDate().toString(),
                note);
    }

    /**
     * 选出最省钱的一周（需求 7.1–7.6）。自 1 日起每 7 个自然日为一段（第 {@code k} 段覆盖第
     * {@code 7k+1..7k+7} 日），仅 {@code 7k+7 ≤ 当月天数} 的完整段参评；{@code partial} 追加要求整段
     * 结束日期不晚于当前日 {@code LocalDate.now(clock)}。取各完整段支出合计（排除转账、2 位小数）最低者，
     * 并列时起始日期更早者优先。无任何可评比的完整周分段时返回 {@code null}（需求 7.5）。
     */
    private MonthlyDigestResponse.FrugalWeek buildMostFrugalWeek(
            YearMonth month, String monthStatus, List<Transaction> expenses) {
        int daysInMonth = month.lengthOfMonth();
        boolean partial = STATUS_PARTIAL.equals(monthStatus);
        LocalDate today = partial ? LocalDate.now(clock) : null;

        // 按日（当月天序）累加支出合计，供各分段快速求和。
        Map<Integer, BigDecimal> expenseByDay = new HashMap<>();
        for (Transaction t : expenses) {
            int day = t.getOccurredAt().toLocalDate().getDayOfMonth();
            expenseByDay.merge(day, t.getAmount(), BigDecimal::add);
        }

        MonthlyDigestResponse.FrugalWeek best = null;
        BigDecimal bestSum = null;
        // k=0,1,2,...：第 k 段 [7k+1, 7k+7]；仅当 7k+7 ≤ 当月天数 为完整段。
        for (int startDay = 1; startDay + WEEK_LENGTH - 1 <= daysInMonth; startDay += WEEK_LENGTH) {
            int endDay = startDay + WEEK_LENGTH - 1;
            LocalDate startDate = month.atDay(startDay);
            LocalDate endDate = month.atDay(endDay);
            // partial：整段起止均不晚于当前日（起始 < 结束，故只需结束 ≤ 当前日，需求 7.6）。
            if (partial && endDate.isAfter(today)) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int d = startDay; d <= endDay; d++) {
                BigDecimal daySum = expenseByDay.get(d);
                if (daySum != null) {
                    sum = sum.add(daySum);
                }
            }
            sum = sum.setScale(SCALE, RoundingMode.HALF_UP);
            // 支出合计最低者胜出；并列取起始更早者（本循环起始日递增，先到者即更早，严格小于才替换）。
            if (bestSum == null || sum.compareTo(bestSum) < 0) {
                bestSum = sum;
                best = new MonthlyDigestResponse.FrugalWeek(
                        startDate.toString(), endDate.toString(), sum);
            }
        }
        return best;
    }

    /** 当前账本分类 id → 名称映射，供最大单笔消费的分类名解析（回退口径同分类排行，需求 4.5/6.2）。 */
    private Map<Long, String> categoryNameMap(Long ledgerId) {
        Map<Long, String> map = new HashMap<>();
        for (Category c : categoryRepository.findByLedgerId(ledgerId)) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }

    /**
     * 构建稠密消费趋势（需求 3.1–3.6）。复用 {@link ReportService#rangeReport} 得稀疏按日明细，
     * 再在 {@code [monthStart, endBoundary]} 内逐日补齐：缺日的收入与支出均为 0.00，升序、无缺日。
     * 若月内无任何计入交易（稀疏明细为空）则返回空列表（需求 3.6）。
     */
    private List<RangeReportResponse.DayPoint> buildTrend(
            Long ledgerId, LocalDate monthStart, LocalDate endBoundary) {
        RangeReportResponse range = reportService.rangeReport(ledgerId, monthStart, endBoundary);
        List<RangeReportResponse.DayPoint> sparse = range.days();
        if (sparse.isEmpty()) {
            // 需求 3.6：目标月内无任何计入交易 → 空趋势序列。
            return List.of();
        }
        Map<String, RangeReportResponse.DayPoint> byDate = new HashMap<>();
        for (RangeReportResponse.DayPoint dp : sparse) {
            byDate.put(dp.date(), dp);
        }
        List<RangeReportResponse.DayPoint> dense = new ArrayList<>();
        for (LocalDate d = monthStart; !d.isAfter(endBoundary); d = d.plusDays(1)) {
            String key = d.toString();
            RangeReportResponse.DayPoint dp = byDate.get(key);
            dense.add(dp != null ? dp : new RangeReportResponse.DayPoint(key, ZERO, ZERO));
        }
        return dense;
    }

    /**
     * 构建分类排行（需求 4.1–4.6）。复用 {@link ReportService#categoryReport}（金额降序、id 升序
     * tie-break、占比末项校正、含笔数），对名称为空/空白的分类项套用回退名 {@link #DELETED_CATEGORY_NAME}，
     * 且不使该分类从排行中丢失，保留既有排序与占比。空月返回空列表（需求 4.6）。
     */
    private List<CategoryReportResponse.CategoryShare> buildCategoryRanking(
            Long ledgerId, LocalDate monthStart, LocalDate endBoundary) {
        CategoryReportResponse report = reportService.categoryReport(ledgerId, monthStart, endBoundary);
        List<CategoryReportResponse.CategoryShare> shares = report.categories();
        if (shares.isEmpty()) {
            return List.of();
        }
        List<CategoryReportResponse.CategoryShare> result = new ArrayList<>(shares.size());
        for (CategoryReportResponse.CategoryShare s : shares) {
            String name = (s.categoryName() == null || s.categoryName().isBlank())
                    ? DELETED_CATEGORY_NAME
                    : s.categoryName();
            result.add(new CategoryReportResponse.CategoryShare(
                    s.categoryId(), name, s.amount(), s.percentage(), s.count()));
        }
        return result;
    }

    /**
     * 构建预算情况（需求 5.1–5.5、11.5）。复用 {@link BudgetService#overview} 逐值口径，抽取
     * {@code hasBudget/totalBudget/spent/remaining/usedPercent/status}；前瞻 {@code forecast}（health）
     * 仅在 overview 返回非空时携带——overview 仅在「当前月且已设预算」时给出 health，恰好满足
     * 「partial 且已设预算才前瞻、final 或未设预算不前瞻」。
     */
    private MonthlyDigestResponse.BudgetDigest buildBudget(Long ledgerId, YearMonth month) {
        BudgetOverviewResponse overview = budgetService.overview(ledgerId, month);
        return new MonthlyDigestResponse.BudgetDigest(
                overview.hasBudget(),
                overview.totalBudget(),
                overview.spent(),
                overview.remaining(),
                overview.usedPercent(),
                overview.status(),
                overview.health());
    }
}
