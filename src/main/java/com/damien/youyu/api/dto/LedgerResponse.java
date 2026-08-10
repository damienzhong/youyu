package com.damien.youyu.api.dto;

import com.damien.youyu.domain.Ledger;

/**
 * 账本响应体。{@code role} 为当前用户在该账本的角色（OWNER/EDITOR），供前端区分管理权限；
 * 未知时为 null。{@code archived} 为归档（只读）态（AA 账本，需求 8.3），供前端展示「已归档」分组
 * 与只读界面；非 AA 账本恒为 false。
 */
public record LedgerResponse(
        Long id, String name, String type, int sortOrder, boolean isDefault, String role,
        boolean archived) {

    public static LedgerResponse from(Ledger ledger) {
        return from(ledger, null);
    }

    public static LedgerResponse from(Ledger ledger, String role) {
        return new LedgerResponse(
                ledger.getId(), ledger.getName(), ledger.getType(),
                ledger.getSortOrder(), ledger.isDefault(), role, ledger.isArchived());
    }
}
