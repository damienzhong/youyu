package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 修改借贷请求体：可改方向/对方/金额/关联账户/借款日期/到期日/是否计入净资产/备注
 * （结清状态由 settle 接口单独切换）。
 */
public record LoanUpdateRequest(
        String direction,
        String counterparty,
        BigDecimal amount,
        Long accountId,
        LocalDateTime occurredAt,
        LocalDateTime dueDate,
        Boolean includeInTotal,
        String note) {
}
