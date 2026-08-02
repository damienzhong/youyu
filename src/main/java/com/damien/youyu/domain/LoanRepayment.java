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
 * 借贷收款/还款子台账，对应 {@code loan_repayments} 表。
 *
 * <p>一笔借贷可有多次收款(借出)/还款(借入)。每条记录含金额、收款钱包/还款账户、发生时间与备注。
 * 金额一律 {@link BigDecimal}（DECIMAL(18,2)）。归属以 {@code ledgerId} 隔离。</p>
 */
@Entity
@Table(name = "loan_repayments")
public class LoanRepayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属借贷 id。 */
    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    /** 归属账本 id（历史列，可空）。 */
    @Column(name = "ledger_id")
    private Long ledgerId;

    /** 归属用户 id（隔离键）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 本次收款/还款金额，>=0.01。 */
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** 收款钱包（借出）/还款账户（借入）。可空。 */
    @Column(name = "account_id")
    private Long accountId;

    /** 收款/还款日期。 */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 备注，<=200。 */
    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public LoanRepayment() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLoanId() {
        return loanId;
    }

    public void setLoanId(Long loanId) {
        this.loanId = loanId;
    }

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
