package com.damien.youyu.domain;

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
 * 分类实体，对应 {@code categories} 表。
 *
 * <p>两级层级：{@code parentId} 为空表示父分类；非空表示子分类。支出与收入分类各自独立。
 * 归属关系以 {@code userId} 外键列表达，所有查询固定携带 user_id 过滤。</p>
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户 id。 */
    @Column(name = "user_id")
    private Long userId;

    /** 归属账本 id（多账本隔离键）。 */
    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    /** 父分类 id，空=父分类。 */
    @Column(name = "parent_id")
    private Long parentId;

    /** 分类种类：EXPENSE/INCOME。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private CategoryKind kind;

    /** 分类名称，去空白后 1-50。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 图标标识（内置线性图标集的 key，如 food/transport）。为空时前端按名称回退推断。 */
    @Column(name = "icon", length = 32)
    private String icon;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Category() {
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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public CategoryKind getKind() {
        return kind;
    }

    public void setKind(CategoryKind kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
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
