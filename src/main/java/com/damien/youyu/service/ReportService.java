package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.CategoryReportResponse.CategoryShare;
import com.damien.youyu.api.dto.MemberReportResponse;
import com.damien.youyu.api.dto.MemberReportResponse.MemberShare;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.api.dto.RangeReportResponse;
import com.damien.youyu.api.dto.TrendReportResponse;
import com.damien.youyu.api.dto.TrendReportResponse.MonthPoint;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 报表服务：本月收支结余、分类占比、月度趋势三类报表（关联需求 4.12、7.1-7.7）。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>自然月/范围边界一律按 {@code Asia/Shanghai}（UTC+8）：月报为「当月 1 日 00:00:00(含)
 *       至次月 1 日 00:00:00(不含)」的半开区间（需求 7.1）；分类占比范围含起止日期两端（需求 7.2）。</li>
 *   <li>所有报表统计一律排除 {@code type=transfer} 的交易（需求 4.12、7.5）。</li>
 *   <li>金额与百分比一律用 {@link BigDecimal}，保留 2 位小数（HALF_UP）。</li>
 *   <li>分类占比：各分类占比之和恒为 100.00（对排序后最后一项做余数校正，需求 7.3）。</li>
 *   <li>月度趋势：区间内每个自然月都返回一项，无数据月份收支为 0（需求 7.4、7.7）；
 *       区间跨度超过 24 个自然月或起始晚于结束则拒绝（{@code REPORT_RANGE_INVALID}，需求 7.6）。</li>
 *   <li>空范围/无计入交易返回 0（需求 7.7）。</li>
 * </ul>
 *
 * <p>所有查询按会话 {@code ledgerId} 隔离（需求 2.3）。占用了半开区间查询
 * {@link TransactionRepository#findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan}
 * 以保证边界零点的交易不被重复计入相邻区间。</p>
 */
@Service
public class ReportService {

    /** 金额与百分比统一保留的小数位（DECIMAL(18,2)、百分比 2 位）。 */
    static final int SCALE = 2;

    /** 月度趋势允许的最大自然月数（含起止，需求 7.6）。 */
    static final int TREND_MAX_MONTHS = 24;

    /** 区间报表允许的最大自然日数（含起止），约一年，防止超大区间。 */
    static final int RANGE_MAX_DAYS = 366;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;

    public ReportService(
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * 本月报表：给定自然月的总收入、总支出与结余（需求 7.1、7.5、7.7）。
     *
     * @param ledgerId 会话用户
     * @param month  目标自然月（按 {@code Asia/Shanghai} 边界）
     */
    @Transactional(readOnly = true)
    public MonthlyReportResponse monthlyReport(Long ledgerId, YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();

        List<Transaction> txs = fetchHalfOpen(ledgerId, from, to);
        BigDecimal income = sumByType(txs, TransactionType.INCOME);
        BigDecimal expense = sumByType(txs, TransactionType.EXPENSE);
        BigDecimal balance = scale(income.subtract(expense));

        return new MonthlyReportResponse(month.toString(), income, expense, balance);
    }

    /**
     * 分类占比报表：选定日期范围（含起止边界）内各支出分类的金额与占总支出百分比（需求 7.2、7.3、7.5、7.7）。
     *
     * @param ledgerId 会话用户
     * @param from   起始日期（含），按 {@code Asia/Shanghai}
     * @param to     结束日期（含），按 {@code Asia/Shanghai}
     */
    @Transactional(readOnly = true)
    public CategoryReportResponse categoryReport(Long ledgerId, LocalDate from, LocalDate to) {
        return categoryReport(ledgerId, from, to, TransactionType.EXPENSE);
    }

    /**
     * 分类占比报表（按类别：支出/收入）：选定日期范围（含起止边界）内各分类的金额、占该类别总额百分比
     * 与笔数（需求 7.2、7.3、7.5、7.7）。
     *
     * @param kind 统计类别：{@link TransactionType#EXPENSE} 支出 / {@link TransactionType#INCOME} 收入
     */
    @Transactional(readOnly = true)
    public CategoryReportResponse categoryReport(Long ledgerId, LocalDate from, LocalDate to,
            TransactionType kind) {
        // 含起止边界：覆盖 to 当日整天，用半开区间 [from 00:00, (to+1) 00:00)。
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();

        List<Transaction> txs = fetchHalfOpen(ledgerId, fromDt, toDt).stream()
                .filter(t -> t.getType() == kind)
                .toList();

        // 按分类聚合金额与笔数（保持稳定顺序），并求总额。
        Map<Long, BigDecimal> amountByCategory = new LinkedHashMap<>();
        Map<Long, Long> countByCategory = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction t : txs) {
            amountByCategory.merge(t.getCategoryId(), t.getAmount(), BigDecimal::add);
            countByCategory.merge(t.getCategoryId(), 1L, Long::sum);
            total = total.add(t.getAmount());
        }

        BigDecimal totalAmount = scale(total);
        if (amountByCategory.isEmpty() || totalAmount.compareTo(ZERO) == 0) {
            // 需求 7.7：范围内无该类别交易，返回 0 与空分类列表。
            return new CategoryReportResponse(from.toString(), to.toString(), ZERO, List.of());
        }

        Map<Long, String> nameById = categoryNameMap(ledgerId);

        // 排序：金额降序、分类 id 升序，保证确定性与「大类在前」。
        List<Map.Entry<Long, BigDecimal>> ordered = new ArrayList<>(amountByCategory.entrySet());
        ordered.sort(Comparator
                .comparing((Map.Entry<Long, BigDecimal> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));

        List<CategoryShare> shares = new ArrayList<>(ordered.size());
        BigDecimal accumulatedPct = BigDecimal.ZERO;
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<Long, BigDecimal> e = ordered.get(i);
            BigDecimal amount = scale(e.getValue());
            BigDecimal pct;
            if (i == ordered.size() - 1) {
                // 需求 7.3：最后一项做余数校正，保证合计恰为 100.00。
                pct = HUNDRED.setScale(SCALE, RoundingMode.HALF_UP).subtract(accumulatedPct);
            } else {
                pct = e.getValue()
                        .multiply(HUNDRED)
                        .divide(totalAmount, SCALE, RoundingMode.HALF_UP);
                accumulatedPct = accumulatedPct.add(pct);
            }
            shares.add(new CategoryShare(
                    e.getKey(), nameById.get(e.getKey()), amount, pct,
                    countByCategory.getOrDefault(e.getKey(), 0L)));
        }

        return new CategoryReportResponse(from.toString(), to.toString(), totalAmount, shares);
    }

    /**
     * 区间收支报表：给定日期范围（含起止边界）的总收入/支出/结余，以及有活动自然日的按日明细（需求 7.1、7.5、7.7）。
     *
     * <p>供统计页「周/月/自定义」视角的 KPI、按日柱状图与收支明细表使用。区间跨度超过
     * {@value #RANGE_MAX_DAYS} 天则拒绝（{@code REPORT_RANGE_INVALID}）。</p>
     *
     * @throws ApiException REPORT_RANGE_INVALID（起始晚于结束，或跨度超过上限）
     */
    @Transactional(readOnly = true)
    public RangeReportResponse rangeReport(Long ledgerId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.reportRangeInvalid();
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > RANGE_MAX_DAYS) {
            throw ApiException.reportRangeInvalid();
        }

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();
        List<Transaction> txs = fetchHalfOpen(ledgerId, fromDt, toDt);

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        // 按日聚合（稀疏，仅有活动的日期），保持日期升序。
        Map<LocalDate, BigDecimal[]> byDay = new java.util.TreeMap<>();
        for (Transaction t : txs) {
            if (t.getType() == TransactionType.TRANSFER) {
                continue; // 报表排除转账（需求 7.5）
            }
            LocalDate day = t.getOccurredAt().toLocalDate();
            BigDecimal[] slot = byDay.computeIfAbsent(day,
                    d -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            if (t.getType() == TransactionType.INCOME) {
                slot[0] = slot[0].add(t.getAmount());
                income = income.add(t.getAmount());
            } else {
                slot[1] = slot[1].add(t.getAmount());
                expense = expense.add(t.getAmount());
            }
        }

        List<RangeReportResponse.DayPoint> days = new ArrayList<>(byDay.size());
        for (Map.Entry<LocalDate, BigDecimal[]> e : byDay.entrySet()) {
            days.add(new RangeReportResponse.DayPoint(
                    e.getKey().toString(), scale(e.getValue()[0]), scale(e.getValue()[1])));
        }

        BigDecimal incomeS = scale(income);
        BigDecimal expenseS = scale(expense);
        return new RangeReportResponse(
                from.toString(), to.toString(),
                incomeS, expenseS, scale(incomeS.subtract(expenseS)), days);
    }

    /**
     * 月度趋势报表：区间 [fromMonth, toMonth] 内每个自然月的收入与支出，无数据月份返回 0（需求 7.4、7.6、7.7）。
     *
     * @param ledgerId    会话用户
     * @param fromMonth 起始自然月（含）
     * @param toMonth   结束自然月（含）
     * @throws ApiException REPORT_RANGE_INVALID（起始晚于结束，或区间自然月数超过 24，需求 7.6）
     */
    @Transactional(readOnly = true)
    public TrendReportResponse trendReport(Long ledgerId, YearMonth fromMonth, YearMonth toMonth) {
        // 需求 7.6：起始不得晚于结束。
        if (fromMonth.isAfter(toMonth)) {
            throw ApiException.reportRangeInvalid();
        }
        long monthCount = ChronoUnit.MONTHS.between(fromMonth, toMonth) + 1;
        // 需求 7.6：区间自然月数不得超过 24。
        if (monthCount > TREND_MAX_MONTHS) {
            throw ApiException.reportRangeInvalid();
        }

        LocalDateTime from = fromMonth.atDay(1).atStartOfDay();
        LocalDateTime to = toMonth.plusMonths(1).atDay(1).atStartOfDay();
        List<Transaction> txs = fetchHalfOpen(ledgerId, from, to);

        // 聚合每月收入/支出。
        Map<YearMonth, BigDecimal> incomeByMonth = new LinkedHashMap<>();
        Map<YearMonth, BigDecimal> expenseByMonth = new LinkedHashMap<>();
        for (Transaction t : txs) {
            YearMonth ym = YearMonth.from(t.getOccurredAt());
            if (t.getType() == TransactionType.INCOME) {
                incomeByMonth.merge(ym, t.getAmount(), BigDecimal::add);
            } else if (t.getType() == TransactionType.EXPENSE) {
                expenseByMonth.merge(ym, t.getAmount(), BigDecimal::add);
            }
            // transfer 一律排除（需求 4.12、7.5）。
        }

        // 需求 7.4/7.7：区间内每个自然月都产出一项，无数据月份为 0。
        List<MonthPoint> points = new ArrayList<>((int) monthCount);
        YearMonth cursor = fromMonth;
        while (!cursor.isAfter(toMonth)) {
            BigDecimal income = scale(incomeByMonth.getOrDefault(cursor, BigDecimal.ZERO));
            BigDecimal expense = scale(expenseByMonth.getOrDefault(cursor, BigDecimal.ZERO));
            points.add(new MonthPoint(cursor.toString(), income, expense));
            cursor = cursor.plusMonths(1);
        }

        return new TrendReportResponse(points);
    }

    /**
     * 成员消费占比报表：选定日期范围（含起止边界）内各成员的支出金额、占总支出百分比与笔数
     * （协作账本用）。转账一律排除；各成员占比之和恒为 100.00（末项余数校正）。返回的
     * {@code displayName} 为空，由上层按 {@code userId} 补齐。
     *
     * @throws ApiException REPORT_RANGE_INVALID（起始晚于结束，或跨度超过上限）
     */
    @Transactional(readOnly = true)
    public MemberReportResponse memberReport(Long ledgerId, LocalDate from, LocalDate to) {
        return memberReport(ledgerId, from, to, TransactionType.EXPENSE);
    }

    /**
     * 成员占比报表（按类别：支出/收入）：选定日期范围内各成员在该类别的金额、占比与笔数。
     *
     * @param kind {@link TransactionType#EXPENSE} 支出 / {@link TransactionType#INCOME} 收入
     */
    @Transactional(readOnly = true)
    public MemberReportResponse memberReport(Long ledgerId, LocalDate from, LocalDate to,
            TransactionType kind) {
        if (from.isAfter(to)) {
            throw ApiException.reportRangeInvalid();
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > RANGE_MAX_DAYS) {
            throw ApiException.reportRangeInvalid();
        }

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();
        List<Transaction> txs = fetchHalfOpen(ledgerId, fromDt, toDt).stream()
                .filter(t -> t.getType() == kind)
                .toList();

        // 按记账人聚合金额与笔数（createdBy 为空归入 0L「未知」）。
        Map<Long, BigDecimal> amountByMember = new LinkedHashMap<>();
        Map<Long, Long> countByMember = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction t : txs) {
            Long who = t.getCreatedBy() == null ? 0L : t.getCreatedBy();
            amountByMember.merge(who, t.getAmount(), BigDecimal::add);
            countByMember.merge(who, 1L, Long::sum);
            total = total.add(t.getAmount());
        }

        BigDecimal totalAmount = scale(total);
        if (amountByMember.isEmpty() || totalAmount.compareTo(ZERO) == 0) {
            return new MemberReportResponse(from.toString(), to.toString(), ZERO, List.of());
        }

        List<Map.Entry<Long, BigDecimal>> ordered = new ArrayList<>(amountByMember.entrySet());
        ordered.sort(Comparator
                .comparing((Map.Entry<Long, BigDecimal> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));

        List<MemberShare> shares = new ArrayList<>(ordered.size());
        BigDecimal accumulatedPct = BigDecimal.ZERO;
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<Long, BigDecimal> e = ordered.get(i);
            BigDecimal amount = scale(e.getValue());
            BigDecimal pct;
            if (i == ordered.size() - 1) {
                pct = HUNDRED.setScale(SCALE, RoundingMode.HALF_UP).subtract(accumulatedPct);
            } else {
                pct = e.getValue().multiply(HUNDRED).divide(totalAmount, SCALE, RoundingMode.HALF_UP);
                accumulatedPct = accumulatedPct.add(pct);
            }
            shares.add(new MemberShare(e.getKey(), null, amount, pct,
                    countByMember.getOrDefault(e.getKey(), 0L)));
        }

        return new MemberReportResponse(from.toString(), to.toString(), totalAmount, shares);
    }

    // ---------------- 内部工具 ----------------

    private List<Transaction> fetchHalfOpen(Long ledgerId, LocalDateTime from, LocalDateTime to) {
        return transactionRepository
                .findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(ledgerId, from, to);
    }

    /** 累加某类型交易金额（转账在报表中一律不属于收入/支出，故按 type 精确过滤，需求 7.5）。 */
    private BigDecimal sumByType(List<Transaction> txs, TransactionType type) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Transaction t : txs) {
            if (t.getType() == type) {
                sum = sum.add(t.getAmount());
            }
        }
        return scale(sum);
    }

    private Map<Long, String> categoryNameMap(Long ledgerId) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (Category c : categoryRepository.findByLedgerId(ledgerId)) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
