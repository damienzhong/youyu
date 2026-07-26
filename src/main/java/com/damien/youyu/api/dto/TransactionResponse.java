package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.damien.youyu.domain.Transaction;

/**
 * 交易响应体。{@code type} 以小写编码（expense/income/transfer）返回，{@code amount} 恒为正
 * （DECIMAL(18,2)），方向由 type 决定。转账仅含 source/destination；支出/收入仅含 account/category。
 */
public record TransactionResponse(
        Long id,
        String type,
        BigDecimal amount,
        Long accountId,
        Long categoryId,
        Long sourceAccountId,
        Long destinationAccountId,
        LocalDateTime occurredAt,
        String note) {

    public static TransactionResponse from(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getType().getCode(),
                tx.getAmount(),
                tx.getAccountId(),
                tx.getCategoryId(),
                tx.getSourceAccountId(),
                tx.getDestinationAccountId(),
                tx.getOccurredAt(),
                tx.getNote());
    }
}
