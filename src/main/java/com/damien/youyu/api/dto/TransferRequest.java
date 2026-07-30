package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转账请求体（账户间动作，脱离账本）。源/目标须均为当前用户拥有的账户且不相等（需求 6）。
 */
public record TransferRequest(
        Long sourceAccountId,
        Long destinationAccountId,
        BigDecimal amount,
        LocalDateTime occurredAt,
        String note) {
}
