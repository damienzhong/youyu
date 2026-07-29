package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 通用维度占比报表（按项目 / 标签）：选定日期范围（含起止边界）内，各维度项在指定类别（支出/收入）的
 * 金额、占比与笔数。转账一律排除。
 *
 * <p>{@code dimension} 取 {@code project} / {@code tag}。项目为单值归属，仅统计有项目的流水；
 * 标签为多值归属，一笔可计入其多个标签（故 {@code total} 为「按标签计」的加权总额，占比之和恒为 100.00，
 * 末项余数校正）。</p>
 */
public record DimensionReportResponse(
        String from,
        String to,
        String dimension,
        BigDecimal total,
        List<DimensionShare> items) {

    /** 单个维度项（项目/标签）的金额、占比与笔数。 */
    public record DimensionShare(
            Long id, String name, BigDecimal amount, BigDecimal percentage, long count) {
    }
}
