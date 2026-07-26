package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 月度趋势报表响应体（需求 7.4）。
 *
 * <p>{@code months} 覆盖选定区间 [fromMonth, toMonth] 内的每个自然月（按 {@code Asia/Shanghai} 边界），
 * 无数据的月份 {@code income}/{@code expense} 返回 0.00（需求 7.4、7.7）。金额均为 {@link BigDecimal}
 * 且保留 2 位小数；统计一律排除 {@code type=transfer}（需求 7.5）。区间跨度超过 24 个自然月或起始月份
 * 晚于结束月份时请求会被拒绝（需求 7.6），不会产生本响应。</p>
 */
public record TrendReportResponse(List<MonthPoint> months) {

    /** 单个自然月的收支合计。 */
    public record MonthPoint(String month, BigDecimal income, BigDecimal expense) {
    }
}
