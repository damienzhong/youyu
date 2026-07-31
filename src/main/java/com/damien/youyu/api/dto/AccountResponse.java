package com.damien.youyu.api.dto;

import java.math.BigDecimal;

import com.damien.youyu.domain.Account;

/**
 * 账户响应体：含当前余额与初始余额（均为 DECIMAL(18,2)）及扩展字段。
 *
 * <p>{@code type} 以枚举名（如 {@code CASH}）返回；信用卡的 {@code currentBalance} 允许为负（需求 3.4）。
 * {@code ownerId} 为账户归属用户；{@code canSeeBalance} 表示当前查看者是否可见余额——协作账本内当账户
 * owner 关闭 show_balance 时对其他成员为 false，此时金额字段置空脱敏（需求 4.3、4.4）。</p>
 */
public record AccountResponse(
        Long id,
        Long ownerId,
        String name,
        String type,
        String group,
        BigDecimal initialBalance,
        BigDecimal currentBalance,
        int sortOrder,
        boolean includeInTotal,
        boolean hidden,
        String note,
        BigDecimal creditLimit,
        Integer billDay,
        Integer repayDay,
        boolean repayReminder,
        boolean canSeeBalance) {

    /** 账户 owner 视图：余额完全可见。 */
    public static AccountResponse from(Account account) {
        return from(account, true);
    }

    /**
     * 按余额可见性构建：{@code canSeeBalance=false} 时对金额字段脱敏（置空），
     * 仅保留账户身份与非敏感属性，供协作账本内其他成员查看。
     */
    public static AccountResponse from(Account account, boolean canSeeBalance) {
        return new AccountResponse(
                account.getId(),
                account.getUserId(),
                account.getName(),
                account.getType().name(),
                account.getType().getGroup().name(),
                canSeeBalance ? account.getInitialBalance() : null,
                canSeeBalance ? account.getCurrentBalance() : null,
                account.getSortOrder(),
                account.isIncludeInTotal(),
                account.isHidden(),
                account.getNote(),
                canSeeBalance ? account.getCreditLimit() : null,
                account.getBillDay(),
                account.getRepayDay(),
                account.isRepayReminder(),
                canSeeBalance);
    }
}
