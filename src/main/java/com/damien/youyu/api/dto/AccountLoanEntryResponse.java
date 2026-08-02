package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户流水中的「借贷」条目：把借贷的初始出/入账与每笔收款/还款，投影为该账户视角的一笔流水。
 *
 * <p>{@code kind}：INITIAL（借出/借入本金）| REPAYMENT（收款/还款）。
 * {@code direction}：BORROW/LEND。{@code amount}：对该账户的方向增量（流入为正、流出为负）。
 * 借贷为用户级，仅出现在「账户流水」，不进入「账本流水」。</p>
 */
public record AccountLoanEntryResponse(
        String kind,
        Long loanId,
        String direction,
        String counterparty,
        BigDecimal amount,
        LocalDateTime occurredAt,
        String note) {
}
