package com.damien.youyu.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 借贷往来实体，对应 {@code loans} 表。
 *
 * <p>记录借入(BORROW)/借出(LEND)的一笔往来款项，含对方、本金、发生时间、是否已结清与备注。
 * 借贷为独立台账，不参与账户余额与净资产计算（资产页单独一行汇总未结金额）。
 * 金额一律使用 {@link BigDecimal}（DECIMAL(18,2)）。归属以 {@code userId} 表达，
 * 所有查询固定携带 user_id 过滤（需求 2.3）。</p>
 */
@Entity
@Table(name = "loans")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 归属账本 id（多账本隔离键）。 */
    @Column(name = "ledger_id")
    private Long ledgerId;

    /** 借贷方向：BORROW 借入 / LEND 借出。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private LoanDirection direction;

    /** 对方名称，去空白后 1-50。 */
    @Column(name = "counterparty", nullable = false, length = 50)
    private String counterparty;

    /** 本金，>=0.01。 */
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** 发生时间。 */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 是否已结清（结清后不计入待还/待收汇总）。列 tinyint(1)，与 Hibernate boolean 默认 BIT 一致。 */
    @Column(name = "settled", nullable = false)
    private boolean settled = false;

    /** 结清时间（settled=true 时置为当前时刻，否则为空）。 */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** 备注，<=200。 */
    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Loan() {
        // JPA / 服务层构造
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public LoanDirection getDirection() {
        return direction;
    }

    public void setDirection(LoanDirection direction) {
        this.direction = direction;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(String counterparty) {
        this.counterparty = counterparty;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public boolean isSettled() {
        return settled;
    }

    public void setSettled(boolean settled) {
        this.settled = settled;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
