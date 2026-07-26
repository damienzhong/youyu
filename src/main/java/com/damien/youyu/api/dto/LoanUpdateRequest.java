package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 修改借贷请求体：可改方向/对方/金额/发生时间/备注（结清状态由 settle 接口单独切换）。
 */
public record LoanUpdateRequest(
        String direction,
        String counterparty,
        BigDecimal amount,
        LocalDateTime occurredAt,
        String note) {
}
