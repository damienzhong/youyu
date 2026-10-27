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
import jakarta.persistence.UniqueConstraint;

/**
 * 自动分类规则，对应 {@code category_rules} 表。
 *
 * <p>用户在账单导入预览里改了某笔的分类并勾选「以后都这样记」时写入：同一账本、同一收支种类下，
 * 规整后商户名为 {@code merchantKey} 的流水，自动分类时直接用 {@code categoryId}。
 * 是自动分类的最高优先级一层（高于历史习惯与内置商户库）。</p>
 */
@Entity
@Table(name = "category_rules", uniqueConstraints = @UniqueConstraint(
        name = "uk_category_rules_ledger_kind_key", columnNames = {"ledger_id", "kind", "merchant_key"}))
public class CategoryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private CategoryKind kind;

    /** 规整后的商户名（见 {@code CategoryClassifier#merchantKey}）。 */
    @Column(name = "merchant_key", nullable = false, length = 64)
    private String merchantKey;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CategoryRule() {
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

    public CategoryKind getKind() {
        return kind;
    }

    public void setKind(CategoryKind kind) {
        this.kind = kind;
    }

    public String getMerchantKey() {
        return merchantKey;
    }

    public void setMerchantKey(String merchantKey) {
        this.merchantKey = merchantKey;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
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
