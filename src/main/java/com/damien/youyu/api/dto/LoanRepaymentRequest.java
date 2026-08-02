package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新增收款(借出)/还款(借入)请求体。
 *
 * <p>{@code amount} 本次金额（恒为正、最多两位小数、不超过剩余）；{@code accountId} 收款钱包/还款账户
 * （选填，非空则联动余额）；{@code occurredAt} 收款/还款日期（缺省当前时间）；{@code note} 备注（<=200）。</p>
 */
public record LoanRepaymentRequest(
        BigDecimal amount,
        Long accountId,
        LocalDateTime occurredAt,
        String note) {
}
