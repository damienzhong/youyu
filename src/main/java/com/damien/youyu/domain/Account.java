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
 * 账户实体，对应 {@code accounts} 表。
 *
 * <p>金额一律使用 {@link BigDecimal}（DECIMAL(18,2)），严禁 double/float。
 * {@code current_balance} 随流水事务性更新，并可由 {@code initial_balance} + 全量流水重算校验。
 * 归属关系以 {@code userId} 外键列表达，所有查询固定携带 user_id 过滤。</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户 id。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 账户名称，去空白后 1-50。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 账户类型枚举。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private AccountType type;

    /** 初始余额，用于重算校验。 */
    @Column(name = "initial_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal initialBalance;

    /** 当前余额，随流水更新（信用卡允许为负）。 */
    @Column(name = "current_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal currentBalance;

    /** 列表排序，默认 0。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Account() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccountType getType() {
        return type;
    }

    public void setType(AccountType type) {
        this.type = type;
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
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
