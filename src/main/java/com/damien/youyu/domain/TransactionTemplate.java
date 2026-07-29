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
 * 记账模板实体，对应 {@code transaction_templates} 表。
 *
 * <p>保存一笔常用记账的「形态」（类型/分类/账户/金额/备注），记一笔时一键套用并预填表单。
 * 模板本身不产生流水、不影响余额。金额可空（模板可只固定分类/账户，金额记账时再填）；
 * 账户/分类引用可空、无外键约束，被引用对象删除后模板仍存在（套用时前端做空值兜底）。
 * 归属 {@code (userId, ledgerId)}，查询固定携带 {@code ledgerId} 保证多账本隔离。</p>
 */
@Entity
@Table(name = "transaction_templates")
public class TransactionTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    /** 模板名，去空白后 1-50。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 类型：expense/income/transfer。 */
    @Column(name = "type", nullable = false, length = 10)
    private String type;

    /** 预填金额（可空）。 */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "source_account_id")
    private Long sourceAccountId;

    @Column(name = "destination_account_id")
    private Long destinationAccountId;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public TransactionTemplate() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getSourceAccountId() {
        return sourceAccountId;
    }

    public void setSourceAccountId(Long sourceAccountId) {
        this.sourceAccountId = sourceAccountId;
    }

    public Long getDestinationAccountId() {
        return destinationAccountId;
    }

    public void setDestinationAccountId(Long destinationAccountId) {
        this.destinationAccountId = destinationAccountId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
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
