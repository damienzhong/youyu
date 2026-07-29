package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 余额调整请求体：把 {@code accountId} 账户的当前余额校准到 {@code balance}。
 *
 * <p>{@code balance} 为目标余额（DECIMAL(18,2)，可正可负）；{@code occurredAt} 缺省取当前时间；
 * {@code note} 可选（缺省记为「余额调整」）。服务层用一笔补差流水落地差额。</p>
 */
public record BalanceAdjustRequest(
        Long accountId,
        BigDecimal balance,
        LocalDateTime occurredAt,
        String note) {
}
