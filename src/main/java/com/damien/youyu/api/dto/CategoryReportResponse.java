package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分类占比报表响应体（需求 7.2、7.3）。
 *
 * <p>{@code from}/{@code to} 为选定日期范围（{@code YYYY-MM-DD}，含起止边界）。
 * {@code totalExpense} 为该范围内该用户全部支出合计；{@code categories} 为各支出分类的金额与占比，
 * 按金额降序、分类 id 升序排列。占比 {@code percentage} 保留 2 位小数，且当范围内至少含一笔支出时
 * 各分类占比之和恒为 100.00（对最后一项做余数校正，需求 7.3）。统计一律排除 {@code type=transfer}
 * （需求 7.5）；范围内无支出时 {@code totalExpense} 为 0.00 且 {@code categories} 为空（需求 7.7）。</p>
 */
public record CategoryReportResponse(
        String from,
        String to,
        BigDecimal totalExpense,
        List<CategoryShare> categories) {

    /** 单个分类的金额、占所选类别（支出/收入）总额的百分比与笔数。 */
    public record CategoryShare(
            Long categoryId,
            String categoryName,
            BigDecimal amount,
            BigDecimal percentage,
            long count) {
    }
}
