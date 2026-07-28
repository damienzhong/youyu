package com.damien.youyu.api.dto;

import com.damien.youyu.domain.Ledger;

/**
 * 账本响应体。
 */
public record LedgerResponse(Long id, String name, int sortOrder, boolean isDefault) {

    public static LedgerResponse from(Ledger ledger) {
        return new LedgerResponse(
                ledger.getId(), ledger.getName(), ledger.getSortOrder(), ledger.isDefault());
    }
}
