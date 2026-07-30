package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 账户与账本的多对多可见性关联，对应 {@code account_ledger} 表。
 *
 * <p>一行表示"某账户参与某账本"：该账户会出现在此账本的账户选择器中。两个标志正交：</p>
 * <ul>
 *   <li>{@code visibleToOthers}：协作账本内其他成员能否看到/选用该账户（个人账本单成员时无意义）。</li>
 *   <li>{@code showBalance}：其他成员能否看到该账户的真实余额（AA 场景可关闭；owner 始终可见）。</li>
 * </ul>
 *
 * <p>账户 owner 始终能查看/选用自己的账户，不受上述标志限制；标志仅约束协作账本内的其他成员。</p>
 */
@Entity
@Table(name = "account_ledger")
public class AccountLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 账户 id。 */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 账本 id。 */
    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    /** 协作账本内是否对其他成员可见/可选（默认可见）。 */
    @Column(name = "visible_to_others", nullable = false)
    private boolean visibleToOthers = true;

    /** 是否对其他成员显示真实余额（默认显示）。 */
    @Column(name = "show_balance", nullable = false)
    private boolean showBalance = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public AccountLedger() {
        // JPA / 服务层构造
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public boolean isVisibleToOthers() {
        return visibleToOthers;
    }

    public void setVisibleToOthers(boolean visibleToOthers) {
        this.visibleToOthers = visibleToOthers;
    }

    public boolean isShowBalance() {
        return showBalance;
    }

    public void setShowBalance(boolean showBalance) {
        this.showBalance = showBalance;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
