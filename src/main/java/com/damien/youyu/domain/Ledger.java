package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 账本实体，对应 {@code ledgers} 表。
 *
 * <p>账本是一个记账空间：分类/交易/预算/借贷归属到某个账本；账户是独立实体，通过 {@code account_ledger}
 * 关联被账本引用。一个用户可拥有多个账本，其中恰有一个为默认账本（{@code isDefault}）。多租户隔离在账本
 * 维度进行：业务数据按 {@code ledger_id} 过滤，账本本身按 {@code user_id} 归属用户。</p>
 */
@Entity
@Table(name = "ledgers")
public class Ledger {

    /** 类型：个人账本（仅本人记账）。 */
    public static final String TYPE_PERSONAL = "PERSONAL";
    /** 类型：协作账本（可邀请成员共同记账）。 */
    public static final String TYPE_COLLABORATIVE = "COLLABORATIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 账本名称，去空白后 1-50。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 账本类型：PERSONAL（个人）/ COLLABORATIVE（协作）。 */
    @Column(name = "type", nullable = false, length = 16)
    private String type = TYPE_PERSONAL;

    /** 列表排序。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** 是否默认账本（每用户唯一）。 */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Ledger() {
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /** 是否协作账本。 */
    public boolean isCollaborative() {
        return TYPE_COLLABORATIVE.equals(type);
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
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
