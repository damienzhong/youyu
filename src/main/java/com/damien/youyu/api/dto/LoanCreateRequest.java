package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建借贷请求体。
 *
 * <p>{@code direction} 以字符串接收（BORROW/LEND），由服务层校验；{@code amount} 用
 * {@link BigDecimal} 承载（恒为正、最多两位小数）；{@code counterparty} 对方（1-50）；
 * {@code occurredAt} 缺省取当前时间；{@code note} 可选（<=200）。</p>
 */
public record LoanCreateRequest(
        String direction,
        String counterparty,
        BigDecimal amount,
        LocalDateTime occurredAt,
        String note) {
}
