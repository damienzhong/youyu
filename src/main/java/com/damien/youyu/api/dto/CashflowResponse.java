package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * 资产月度现金流响应体（需求 2.1、2.6）。
 *
 * <p>{@code month} 为选定自然月标识（{@code YYYY-MM}，按 {@code Asia/Shanghai} 边界统计）。
 * 其余五项均为两位小数纯字符串（如 {@code "0.00"}、{@code "23.50"}），源自 {@link BigDecimal}
 * （{@code DECIMAL(18,2)} 语义，无二进制浮点）：{@code outflow} 实际流出、{@code inflow} 实际流入、
 * {@code netInflow} 净流入（可为负，{@code = inflow − outflow}）、{@code todayOutflow}/{@code todayInflow}
 * 今日实际流出/流入（历史月为 {@code "0.00"}）。</p>
 */
public record CashflowResponse(
        String month,
        String outflow,
        String inflow,
        String netInflow,
        String todayOutflow,
        String todayInflow) {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 由聚合得到的五项金额（{@link BigDecimal}）与选定自然月构造响应体。
     *
     * <p>金额统一以 {@code setScale(2, HALF_UP).toPlainString()} 序列化为两位小数纯字符串，
     * 与既有 DTO / 导出口径一致；{@code month} 输出为 {@code YYYY-MM}。</p>
     */
    public static CashflowResponse of(
            YearMonth month,
            BigDecimal outflow,
            BigDecimal inflow,
            BigDecimal netInflow,
            BigDecimal todayOutflow,
            BigDecimal todayInflow) {
        return new CashflowResponse(
                month.format(MONTH_FORMAT),
                money(outflow),
                money(inflow),
                money(netInflow),
                money(todayOutflow),
                money(todayInflow));
    }

    /** 金额序列化：DECIMAL(18,2) → 两位小数纯字符串（HALF_UP）。 */
    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
