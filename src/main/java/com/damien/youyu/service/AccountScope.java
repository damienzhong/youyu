package com.damien.youyu.service;

import com.damien.youyu.domain.Ledger;

/**
 * 账户作用域：决定一次账户操作落在哪个账户池。
 *
 * <ul>
 *   <li><b>独立账本</b>（{@code collaborative=false}）：用户级账户池，按 {@code userId} 且
 *       {@code ledger_id IS NULL} 隔离——同一用户的多个独立账本共享同一批账户。</li>
 *   <li><b>协作账本</b>（{@code collaborative=true}）：账本级账户池，按 {@code ledgerId} 隔离——
 *       账户归属该协作账本，账本内成员共享。</li>
 * </ul>
 *
 * <p>{@code userId} 始终保留（新建账户的归属/审计），协作账本另置 {@code ledgerId}。</p>
 */
public final class AccountScope {

    private final boolean collaborative;
    private final Long userId;
    private final Long ledgerId;

    private AccountScope(boolean collaborative, Long userId, Long ledgerId) {
        this.collaborative = collaborative;
        this.userId = userId;
        this.ledgerId = ledgerId;
    }

    /** 用户级账户池（独立账本）。 */
    public static AccountScope independent(Long userId) {
        return new AccountScope(false, userId, null);
    }

    /** 账本级账户池（协作账本）。{@code userId} 为操作者（新建账户的归属/审计）。 */
    public static AccountScope collaborative(Long ledgerId, Long userId) {
        return new AccountScope(true, userId, ledgerId);
    }

    /** 依据账本类型解析作用域。 */
    public static AccountScope forLedger(Long userId, Ledger ledger) {
        return "COLLABORATIVE".equals(ledger.getType())
                ? collaborative(ledger.getId(), userId)
                : independent(userId);
    }

    public boolean isCollaborative() {
        return collaborative;
    }

    public Long userId() {
        return userId;
    }

    public Long ledgerId() {
        return ledgerId;
    }
}
