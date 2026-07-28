package com.damien.youyu.api.dto;

import java.time.LocalDateTime;

import com.damien.youyu.domain.LedgerInvite;

/**
 * 邀请码响应体。
 */
public record LedgerInviteResponse(String code, LocalDateTime expiresAt) {

    public static LedgerInviteResponse from(LedgerInvite invite) {
        return new LedgerInviteResponse(invite.getCode(), invite.getExpiresAt());
    }
}
