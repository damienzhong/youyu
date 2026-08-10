package com.damien.youyu.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 待确认生成项，对应 {@code recurring_pending_items} 表（迁移脚本 {@code V38__recurring_transactions.sql}）。
 * 一行 = 某规则某期次到期后生成的一条待确认建议，携带生成时刻的记账模板快照。
 *
 * <p><b>构造性幂等：</b>{@code @Table} 声明唯一约束 {@code uk_recurring_pending_rule_date}
 * 对 {@code (rule_id, occurrence_date)} 唯一，构造性保证同一规则同一期次到期日至多一条记录
 * （无论 {@code PENDING}/{@code CONFIRMED}/{@code SKIPPED}；需求 3.3、9.3）。重复 / 并发生成撞唯一键时
 * 服务层捕获 {@code DataIntegrityViolationException} 静默视为「已生成」。生产由 Flyway 建约束，
 * 测试环境的 H2 表结构由 Hibernate 依本实体生成，故此处如实声明该唯一约束与两个索引。</p>
 *
 * <p><b>模板快照：</b>{@code type} / {@code amount} / {@code categoryId} / {@code accountId} / {@code note}
 * 持有生成时刻的模板快照——编辑规则只对之后新生成的项生效，既有 {@code PENDING} 项保留原值（需求 6.3、6.4）；
 * 确认 / 修改后确认读取本项快照（或用户改后的值），不回读规则。</p>
 *
 * <p><b>裸 id 关联，不建外键：</b>{@code ruleId} / {@code ledgerId} / {@code categoryId} / {@code accountId} /
 * {@code confirmedTransactionId} 均为裸 {@link Long}，不映射为关联、不建外键（需求 9.2）。{@code ledgerId}
 * 为冗余账本 id，便于账本隔离查询而不必回表规则。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@Entity
@Table(name = "recurring_pending_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_recurring_pending_rule_date",
                columnNames = {"rule_id", "occurrence_date"}),
        indexes = {
                @Index(name = "idx_recurring_pending_ledger_status_date",
                        columnList = "ledger_id, status, occurrence_date"),
                @Index(name = "idx_recurring_pending_rule", columnList = "rule_id")
        })
public class RecurringPendingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源规则 id。裸 id，无外键，无关联映射（原因见类级 Javadoc）。 */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    /** 冗余账本 id，便于账本隔离查询（避免回表规则）。裸 id，无外键。 */
    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    /** 期次到期自然日（{@code Asia/Shanghai}）。 */
    @Column(name = "occurrence_date", nullable = false)
    private LocalDate occurrenceDate;

    /** 状态：{@code PENDING} / {@code CONFIRMED} / {@code SKIPPED}，以枚举名存储。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PendingStatus status;

    /** 生成时快照的模板类型：{@code expense} / {@code income}。 */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    /** 生成时快照的模板金额，保留 2 位小数（HALF_UP）。 */
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** 生成时快照的模板分类 id。裸 id，无外键。 */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** 生成时快照的模板账户 id。裸 id，无外键。 */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 生成时快照的模板备注，≤200，可空。 */
    @Column(name = "note", length = 200)
    private String note;

    /** 确认后指向真实流水（{@code transactions.id}）；未确认为空。裸 id，无外键。 */
    @Column(name = "confirmed_transaction_id")
    private Long confirmedTransactionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RecurringPendingItem() {
        // JPA / 服务层构造
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public LocalDate getOccurrenceDate() {
        return occurrenceDate;
    }

    public void setOccurrenceDate(LocalDate occurrenceDate) {
        this.occurrenceDate = occurrenceDate;
    }

    public PendingStatus getStatus() {
        return status;
    }

    public void setStatus(PendingStatus status) {
        this.status = status;
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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Long getConfirmedTransactionId() {
        return confirmedTransactionId;
    }

    public void setConfirmedTransactionId(Long confirmedTransactionId) {
        this.confirmedTransactionId = confirmedTransactionId;
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
