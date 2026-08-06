package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 智能月报聚合响应（需求 1、9）。纯只读派生，不对应任何数据库表，不落库。
 *
 * <p>一次返回目标月九个模块的数据包：本月收入、本月支出、结余、消费趋势、分类排行、
 * 预算情况、最大单笔消费、最省钱的一周，以及供月报配图（海报）使用的上述关键数据。
 * 金额一律 {@link BigDecimal} 保留 2 位小数（HALF_UP），百分比保留 2 位小数；自然月/自然日
 * 边界按 {@code Asia/Shanghai}（UTC+08:00）；所有金额统计排除 {@code type=transfer}，
 * 与既有 {@code /api/reports/*}、{@code /api/budgets} 逐值同口径（需求 11.3、11.5）。</p>
 *
 * <p><b>复用既有嵌套 record</b>：{@code trend} 复用 {@link RangeReportResponse.DayPoint}、
 * {@code categoryRanking} 复用 {@link CategoryReportResponse.CategoryShare}、
 * {@code budget.forecast} 复用 {@link BudgetOverviewResponse.BudgetHealth}。此为只读复用，
 * 不改动这些既有 record 的字段集，因此不触碰既有接口契约（需求 11.3）。</p>
 *
 * <p><b>空/缺省语义</b>：目标月内无任何计入交易时，{@code income}/{@code expense}/{@code netBalance}
 * 为 {@code 0.00}，{@code trend} 为空列表，{@code categoryRanking} 为空列表，
 * {@code largestExpense} 与 {@code mostFrugalWeek} 为 {@code null}，且不返回错误
 * （需求 1.7、3.6、4.6、6.4、7.5）。</p>
 *
 * @param month           目标月 YYYY-MM（Asia/Shanghai 边界）
 * @param monthStatus     月状态：{@code partial}（进行中）/ {@code final}（已完结）
 * @param income          本月收入（排除转账，2 位小数）——与 /api/reports/monthly 同值
 * @param expense         本月支出（排除转账，2 位小数）——与 /api/reports/monthly 同值
 * @param netBalance      结余 = income - expense（可为负）
 * @param trend           消费趋势：按自然日升序、稠密（范围内每日一项）；空月为空列表
 * @param categoryRanking 分类排行：金额降序、id 升序、占比合计 100.00、含笔数；空月为空列表
 * @param budget          预算情况；未设预算/前瞻缺省以字段空值表达（见 {@link BudgetDigest}）
 * @param largestExpense  最大单笔消费；目标月无计入支出时为 {@code null}
 * @param mostFrugalWeek  最省钱的一周；目标月无完整周分段时为 {@code null}
 */
public record MonthlyDigestResponse(
        String month,
        String monthStatus,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal netBalance,
        List<RangeReportResponse.DayPoint> trend,
        List<CategoryReportResponse.CategoryShare> categoryRanking,
        BudgetDigest budget,
        LargestExpense largestExpense,
        FrugalWeek mostFrugalWeek) {

    /**
     * 预算情况（口径同 {@code BudgetService.overview}，需求 5）。
     *
     * <p>未设置月度总预算时（{@code hasBudget=false}），{@code totalBudget}、{@code remaining}、
     * {@code status} 与 {@code forecast} 均为 {@code null}，{@code usedPercent} 为 0，且不返回错误
     * （需求 5.3）。前瞻 {@code forecast} 仅在月状态为 {@code partial} 且已设预算时非 {@code null}；
     * 月状态为 {@code final} 或未设预算时为 {@code null}（需求 5.4、5.5）。</p>
     *
     * @param hasBudget   是否已设月度总预算；false 时其余预算字段为 null（需求 5.3）
     * @param totalBudget 月度总预算（未设为 null）
     * @param spent       本月已支出（排除转账）
     * @param remaining   剩余 = 总预算 - 已支出（未设为 null）
     * @param usedPercent 已用百分比（未设为 0；可超过 100）
     * @param status      OK / WARN(&gt;=80%) / OVER(&gt;100%)；未设为 null
     * @param forecast    预算前瞻，仅 partial 且已设预算时非 null；final 或未设预算为 null（需求 5.4、5.5）
     */
    public record BudgetDigest(
            boolean hasBudget,
            BigDecimal totalBudget,
            BigDecimal spent,
            BigDecimal remaining,
            int usedPercent,
            String status,
            BudgetOverviewResponse.BudgetHealth forecast) {
    }

    /**
     * 最大单笔消费（需求 6）。目标月无计入支出时整个对象为 {@code null}。
     *
     * @param amount       金额（2 位小数）
     * @param categoryName 分类名称（已删除分类回退为 {@code "已删除分类"}）
     * @param date         发生日期 YYYY-MM-DD
     * @param note         备注（缺省为空串）
     */
    public record LargestExpense(
            BigDecimal amount,
            String categoryName,
            String date,
            String note) {
    }

    /**
     * 最省钱的一周（需求 7）：目标月内支出合计最低的完整 7 日分段。
     * 目标月不存在任何完整 7 日分段时整个对象为 {@code null}。
     *
     * @param startDate 起始日期 YYYY-MM-DD
     * @param endDate   结束日期 YYYY-MM-DD（= 起始 + 6 天）
     * @param expense   该段支出合计（排除转账，2 位小数）
     */
    public record FrugalWeek(
            String startDate,
            String endDate,
            BigDecimal expense) {
    }
}
