package com.damien.youyu.api.dto;

import java.math.BigDecimal;

/**
 * 创建记账模板请求体。
 *
 * <p>{@code name} 模板名（1-50，必填）；{@code type} 类型字符串（expense/income/transfer）；
 * {@code amount} 预填金额（可空）；账户/分类引用均可空；{@code note} 预填备注（<=200，可空）。</p>
 */
public record TransactionTemplateCreateRequest(
        String name,
        String type,
        BigDecimal amount,
        Long accountId,
        Long categoryId,
        Long sourceAccountId,
        Long destinationAccountId,
        String note) {
}
