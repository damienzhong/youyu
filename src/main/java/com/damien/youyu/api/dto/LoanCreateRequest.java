package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建借贷请求体。
 *
 * <p>{@code direction} 以字符串接收（BORROW/LEND）；{@code amount} 恒为正、最多两位小数；
 * {@code counterparty} 对方（1-50）；{@code accountId} 借出账户/存入账户（选填，非空则联动账户余额）；
 * {@code occurredAt} 借款日期（缺省当前时间）；{@code dueDate} 收款/还款日期（选填）；
 * {@code includeInTotal} 待收/待还是否计入净资产（缺省 true）；{@code note} 可选（<=200）。</p>
 */
public record LoanCreateRequest(
        String direction,
        String counterparty,
        BigDecimal amount,
        Long accountId,
        LocalDateTime occurredAt,
        LocalDateTime dueDate,
        Boolean includeInTotal,
        String note) {
}
