package com.damien.youyu.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 交易流水实体，对应 {@code transactions} 表。
 *
 * <p>金额一律使用 {@link BigDecimal}（DECIMAL(18,2)）且恒为正，方向由 {@link #type} 决定。
 * 转账建模为单条记录（{@code type=transfer}，含 source/destination），不计入收支统计。</p>
 *
 * <ul>
 *   <li>expense/income：{@code accountId}、{@code categoryId} 非空；source/destination 为空。</li>
 *   <li>transfer：{@code sourceAccountId}、{@code destinationAccountId} 非空且不相等；
 *       {@code accountId}、{@code categoryId} 为空。</li>
 * </ul>
 *
 * <p>归属关系以 {@code userId} 外键列表达，所有查询固定携带 user_id 过滤。</p>
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户 id。 */
    @Column(name = "user_id")
    private Long userId;

    /** 归属账本 id（多账本隔离键）。 */
    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    /** 记账人 id（协作账本区分是哪位成员记的账）。 */
    @Column(name = "created_by")
    private Long createdBy;

    /** 交易类型：expense/income/transfer。 */
    @Convert(converter = TransactionTypeConverter.class)
    @Column(name = "type", nullable = false, length = 10)
    private TransactionType type;

    /** 金额，恒为正。 */
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** 支出/收入使用的账户 id。 */
    @Column(name = "account_id")
    private Long accountId;

    /** 转账源账户 id。 */
    @Column(name = "source_account_id")
    private Long sourceAccountId;

    /** 转账目标账户 id。 */
    @Column(name = "destination_account_id")
    private Long destinationAccountId;

    /** 支出/收入分类 id。 */
    @Column(name = "category_id")
    private Long categoryId;

    /** 交易时间。 */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 备注，<=200。 */
    @Column(name = "note", length = 200)
    private String note;

    /** 第三方账单唯一标识（导入去重用，形如 "alipay:订单号"）；手动记账为 null。 */
    @Column(name = "external_id", length = 64)
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Transaction() {
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

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
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

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
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
