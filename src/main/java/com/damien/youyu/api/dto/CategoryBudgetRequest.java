package com.damien.youyu.api.dto;

import java.math.BigDecimal;

/**
 * 设置分类预算请求体。{@code categoryId} 须为当前用户已存在的分类；
 * {@code amount} 需 &gt;=0.01、&lt;=DECIMAL(18,2) 上限、最多两位小数。
 */
public record CategoryBudgetRequest(Long categoryId, BigDecimal amount) {
}
