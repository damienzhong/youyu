package com.damien.youyu.api.dto;

import java.math.BigDecimal;

/**
 * 本月报表响应体（需求 7.1）。
 *
 * <p>{@code month} 为自然月标识（{@code YYYY-MM}，按 {@code Asia/Shanghai} 边界统计）。
 * {@code totalIncome}/{@code totalExpense}/{@code balance} 均为 {@link BigDecimal} 且保留 2 位小数；
 * {@code balance = totalIncome - totalExpense}。统计一律排除 {@code type=transfer}（需求 7.5）。</p>
 */
public record MonthlyReportResponse(
        String month,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance) {
}
