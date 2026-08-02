package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.damien.youyu.domain.Loan;

/**
 * 借贷响应体。
 *
 * <p>{@code direction} 枚举名（BORROW/LEND）；{@code amount} 本金；{@code repaidAmount} 已收/已还累计；
 * {@code remaining} 剩余待收/待还（amount − repaidAmount）；{@code accountId} 关联账户（可空）；
 * {@code dueDate} 收款/还款日期（可空）；{@code includeInTotal} 剩余是否计入净资产；
 * {@code settled} 是否已结清、{@code settledAt} 结清时间。</p>
 */
public record LoanResponse(
        Long id,
        String direction,
        String counterparty,
        BigDecimal amount,
        BigDecimal repaidAmount,
        BigDecimal remaining,
        Long accountId,
        LocalDateTime occurredAt,
        LocalDateTime dueDate,
        boolean includeInTotal,
        boolean settled,
        LocalDateTime settledAt,
        String note) {

    public static LoanResponse from(Loan loan) {
        BigDecimal repaid = loan.getRepaidAmount() == null ? BigDecimal.ZERO : loan.getRepaidAmount();
        return new LoanResponse(
                loan.getId(),
                loan.getDirection().name(),
                loan.getCounterparty(),
                loan.getAmount(),
                repaid,
                loan.getAmount().subtract(repaid),
                loan.getAccountId(),
                loan.getOccurredAt(),
                loan.getDueDate(),
                loan.isIncludeInTotal(),
                loan.isSettled(),
                loan.getSettledAt(),
                loan.getNote());
    }
}
