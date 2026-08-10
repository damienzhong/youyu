package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.damien.youyu.domain.AaSettlement;

/**
 * 结清一条转账后的结算记录响应体（POST {@code /api/aa/settlements}）。
 *
 * <p>反映落库的 {@code aa_settlements} 行：付款成员 → 收款成员、金额、各方所选账户（仅本人侧有值）、
 * 结清人与时间（需求 6.2-6.4）。金额沿用 {@link TransactionResponse} 风格以 {@link BigDecimal}
 * （2 位小数）承载。</p>
 */
public record AaSettlementRecordResponse(
        Long id,
        Long ledgerId,
        Long fromUserId,
        Long toUserId,
        BigDecimal amount,
        Long fromAccountId,
        Long toAccountId,
        Long settledBy,
        LocalDateTime settledAt) {

    /** 由已落库的结算记录构建响应。 */
    public static AaSettlementRecordResponse from(AaSettlement s) {
        return new AaSettlementRecordResponse(
                s.getId(),
                s.getLedgerId(),
                s.getFromUserId(),
                s.getToUserId(),
                s.getAmount(),
                s.getFromAccountId(),
                s.getToAccountId(),
                s.getSettledBy(),
                s.getSettledAt());
    }
}
