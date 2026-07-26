package com.damien.youyu.api.dto;

import java.math.BigDecimal;

/**
 * 设置月度总预算请求体。{@code amount} 需 &gt;=0.01、&lt;=DECIMAL(18,2) 上限、最多两位小数。
 */
public record BudgetAmountRequest(BigDecimal amount) {
}
