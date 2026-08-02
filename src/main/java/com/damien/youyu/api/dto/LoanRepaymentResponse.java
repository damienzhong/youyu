package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.damien.youyu.domain.LoanRepayment;

/**
 * 收款/还款子台账响应体。
 */
public record LoanRepaymentResponse(
        Long id,
        Long loanId,
        BigDecimal amount,
        Long accountId,
        LocalDateTime occurredAt,
        String note) {

    public static LoanRepaymentResponse from(LoanRepayment r) {
        return new LoanRepaymentResponse(
                r.getId(), r.getLoanId(), r.getAmount(), r.getAccountId(),
                r.getOccurredAt(), r.getNote());
    }
}
