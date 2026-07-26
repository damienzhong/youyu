package com.damien.youyu.api.dto;

import java.math.BigDecimal;

/**
 * 创建账户请求体。
 *
 * <p>{@code type} 以字符串接收，由服务层按受支持枚举校验（非法值返回带 {@code field=type} 的
 * ACCOUNT_FIELD_INVALID，需求 3.3）；{@code initialBalance} 用 {@link BigDecimal} 承载金额，
 * 服务层校验范围与两位小数（需求 3.1）。{@code sortOrder} 可选，缺省为 0。</p>
 */
public record AccountCreateRequest(
        String name,
        String type,
        BigDecimal initialBalance,
        Integer sortOrder) {
}
