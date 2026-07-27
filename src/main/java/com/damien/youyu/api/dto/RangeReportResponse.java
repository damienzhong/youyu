package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 区间收支报表响应体（统计页周/月/自定义视角）。
 *
 * <p>{@code from}/{@code to} 为选定日期范围（{@code YYYY-MM-DD}，含起止边界）。
 * {@code income}/{@code expense}/{@code balance} 为该范围内的总收入/总支出/结余（排除转账）。
 * {@code days} 为该范围内<b>有收支活动</b>的自然日明细（按日期升序，稀疏：无活动的日期不返回），
 * 供按日柱状图与收支明细表使用。金额一律 {@link BigDecimal}（DECIMAL(18,2)）。</p>
 */
public record RangeReportResponse(
        String from,
        String to,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance,
        List<DayPoint> days) {

    /** 单个自然日的收入与支出（排除转账）。 */
    public record DayPoint(
            String date,
            BigDecimal income,
            BigDecimal expense) {
    }
}
