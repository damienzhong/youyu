package com.damien.youyu.api.dto;

import java.math.BigDecimal;

import com.damien.youyu.domain.TransactionTemplate;

/**
 * 记账模板响应体。{@code amount} 与各账户/分类引用可为 null（套用时前端做空值兜底）。
 */
public record TransactionTemplateResponse(
        Long id,
        String name,
        String type,
        BigDecimal amount,
        Long accountId,
        Long categoryId,
        Long sourceAccountId,
        Long destinationAccountId,
        String note) {

    public static TransactionTemplateResponse from(TransactionTemplate t) {
        return new TransactionTemplateResponse(
                t.getId(),
                t.getName(),
                t.getType(),
                t.getAmount(),
                t.getAccountId(),
                t.getCategoryId(),
                t.getSourceAccountId(),
                t.getDestinationAccountId(),
                t.getNote());
    }
}
