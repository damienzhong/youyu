package com.damien.youyu.api.dto;

import com.damien.youyu.domain.Ledger;

/**
 * 账本响应体。{@code role} 为当前用户在该账本的角色（OWNER/EDITOR），供前端区分管理权限；
 * 未知时为 null。
 */
public record LedgerResponse(
        Long id, String name, String type, int sortOrder, boolean isDefault, String role) {

    public static LedgerResponse from(Ledger ledger) {
        return from(ledger, null);
    }

    public static LedgerResponse from(Ledger ledger, String role) {
        return new LedgerResponse(
                ledger.getId(), ledger.getName(), ledger.getType(),
                ledger.getSortOrder(), ledger.isDefault(), role);
    }
}
