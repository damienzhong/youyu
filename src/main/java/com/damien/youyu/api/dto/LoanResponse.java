package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.damien.youyu.domain.Loan;

/**
 * 借贷响应体。
 *
 * <p>{@code direction} 以枚举名（BORROW/LEND）返回；{@code amount} 为 DECIMAL(18,2)。
 * {@code accountId} 关联账户（可空）；{@code dueDate} 收款/还款日期（可空）；
 * {@code includeInTotal} 待收/待还是否计入净资产；{@code settled} 是否已结清、
 * {@code settledAt} 结清时间（未结清为 null）。</p>
 */
public record LoanResponse(
        Long id,
        String direction,
        String counterparty,
        BigDecimal amount,
        Long accountId,
        LocalDateTime occurredAt,
        LocalDateTime dueDate,
        boolean includeInTotal,
        boolean settled,
        LocalDateTime settledAt,
        String note) {

    public static LoanResponse from(Loan loan) {
        return new LoanResponse(
                loan.getId(),
                loan.getDirection().name(),
                loan.getCounterparty(),
                loan.getAmount(),
                loan.getAccountId(),
                loan.getOccurredAt(),
                loan.getDueDate(),
                loan.isIncludeInTotal(),
                loan.isSettled(),
                loan.getSettledAt(),
                loan.getNote());
    }
}
