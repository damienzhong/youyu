package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.damien.youyu.domain.Transaction;

/**
 * 交易响应体。{@code type} 以小写编码（expense/income/transfer）返回，{@code amount} 恒为正
 * （DECIMAL(18,2)），方向由 type 决定。转账仅含 source/destination；支出/收入仅含 account/category。
 * {@code createdBy} 为记账人 id（协作账本区分成员）。{@code tagIds} 为标签 id 列表（无标签为空列表）。
 */
public record TransactionResponse(
        Long id,
        Long ledgerId,
        Long createdBy,
        String type,
        BigDecimal amount,
        Long accountId,
        Long categoryId,
        Long sourceAccountId,
        Long destinationAccountId,
        LocalDateTime occurredAt,
        String note,
        Long projectId,
        Long merchantId,
        List<Long> tagIds) {

    /** 基础工厂：不含标签（标签空列表）。用于不关心标签的聚合视图。 */
    public static TransactionResponse from(Transaction tx) {
        return from(tx, List.of());
    }

    /** 带标签工厂：由调用方注入该交易的标签 id 列表。 */
    public static TransactionResponse from(Transaction tx, List<Long> tagIds) {
        return new TransactionResponse(
                tx.getId(),
                tx.getLedgerId(),
                tx.getCreatedBy(),
                tx.getType().getCode(),
                tx.getAmount(),
                tx.getAccountId(),
                tx.getCategoryId(),
                tx.getSourceAccountId(),
                tx.getDestinationAccountId(),
                tx.getOccurredAt(),
                tx.getNote(),
                tx.getProjectId(),
                tx.getMerchantId(),
                tagIds == null ? List.of() : tagIds);
    }
}
