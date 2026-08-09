package com.damien.youyu.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * AA 账本结算记录，对应 {@code aa_settlements} 表：一次成员间清账转账
 * （{@code from_user_id} → {@code to_user_id}，金额 {@code amount}）。
 *
 * <p>结清时对涉及本人一方的账户执行增减（{@code from_account_id} / {@code to_account_id} 仅本人侧有值），
 * 并递减相应应收/应付。{@code reverted_at} 非空表示该结算已被撤销（回滚账户与债务），净额计算时忽略已撤销行。</p>
 */
@Entity
@Table(name = "aa_settlements")
public class AaSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属 AA 账本 id。 */
    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    /** 付款成员 user_id。 */
    @Column(name = "from_user_id", nullable = false)
    private Long fromUserId;

    /** 收款成员 user_id。 */
    @Column(name = "to_user_id", nullable = false)
    private Long toUserId;

    /** 结算金额，恒为正。 */
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** 付款方所选账户 id（付款方为本人时有值）。 */
    @Column(name = "from_account_id")
    private Long fromAccountId;

    /** 收款方所选账户 id（收款方为本人时有值）。 */
    @Column(name = "to_account_id")
    private Long toAccountId;

    /** 执行结清操作的用户 id。 */
    @Column(name = "settled_by", nullable = false)
    private Long settledBy;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    /** 撤销时间；非空表示该结算已撤销、净额计算忽略。 */
    @Column(name = "reverted_at")
    private LocalDateTime revertedAt;

    public AaSettlement() {
    }

    public boolean isReverted() {
        return revertedAt != null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public Long getFromUserId() {
        return fromUserId;
    }

    public void setFromUserId(Long fromUserId) {
        this.fromUserId = fromUserId;
    }

    public Long getToUserId() {
        return toUserId;
    }

    public void setToUserId(Long toUserId) {
        this.toUserId = toUserId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Long getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(Long fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public Long getToAccountId() {
        return toAccountId;
    }

    public void setToAccountId(Long toAccountId) {
        this.toAccountId = toAccountId;
    }

    public Long getSettledBy() {
        return settledBy;
    }

    public void setSettledBy(Long settledBy) {
        this.settledBy = settledBy;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
    }

    public LocalDateTime getRevertedAt() {
        return revertedAt;
    }

    public void setRevertedAt(LocalDateTime revertedAt) {
        this.revertedAt = revertedAt;
    }
}
