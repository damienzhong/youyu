package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 按项目/商家/标签过滤的交易列表 + 汇总。
 * {@code expenseTotal}/{@code incomeTotal} 为该维度下支出/收入合计，{@code count} 为笔数。
 */
public record FilteredTransactionsResponse(
        BigDecimal expenseTotal,
        BigDecimal incomeTotal,
        int count,
        List<TransactionResponse> transactions) {
}
