package com.damien.youyu.api.dto;

import java.math.BigDecimal;

import com.damien.youyu.domain.Account;

/**
 * 账户响应体：含当前余额与初始余额（均为 DECIMAL(18,2)）及扩展字段。
 *
 * <p>{@code type} 以枚举名（如 {@code CASH}）返回；信用卡的 {@code currentBalance} 允许为负（需求 3.4）。
 * {@code includeInTotal} 余额是否计入净资产；{@code hidden} 是否隐藏（记账不展示）；{@code note} 备注。</p>
 */
public record AccountResponse(
        Long id,
        String name,
        String type,
        BigDecimal initialBalance,
        BigDecimal currentBalance,
        int sortOrder,
        boolean includeInTotal,
        boolean hidden,
        String note,
        BigDecimal creditLimit) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType().name(),
                account.getInitialBalance(),
                account.getCurrentBalance(),
                account.getSortOrder(),
                account.isIncludeInTotal(),
                account.isHidden(),
                account.getNote(),
                account.getCreditLimit());
    }
}
