package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionSplit;

/**
 * AA 支出响应体（POST / PUT {@code /api/aa/expenses}）。
 *
 * <p>金额沿用 {@link TransactionResponse} 风格以 {@link BigDecimal}（2 位小数）承载。除交易主体外，附带
 * 各参与人分摊明细 {@code splits}（Σ share = amount，见需求 4.5 / Property 1）。付款人为本人时
 * {@code accountId} 为付款账户，否则为空（需求 3.7）。</p>
 */
public record AaExpenseResponse(
        Long id,
        Long ledgerId,
        Long createdBy,
        Long payerUserId,
        String type,
        BigDecimal amount,
        Long accountId,
        Long categoryId,
        LocalDateTime occurredAt,
        String note,
        List<Share> splits) {

    /** 单个参与人的分摊额。 */
    public record Share(Long userId, BigDecimal amount) {
    }

    /** 由 AA 支出交易与其分摊行构建响应。 */
    public static AaExpenseResponse from(Transaction tx, List<TransactionSplit> splits) {
        List<Share> shares = splits == null ? List.of()
                : splits.stream()
                        .map(s -> new Share(s.getParticipantUserId(), s.getShareAmount()))
                        .toList();
        return new AaExpenseResponse(
                tx.getId(),
                tx.getLedgerId(),
                tx.getCreatedBy(),
                tx.getPayerUserId(),
                tx.getType().getCode(),
                tx.getAmount(),
                tx.getAccountId(),
                tx.getCategoryId(),
                tx.getOccurredAt(),
                tx.getNote(),
                shares);
    }
}
