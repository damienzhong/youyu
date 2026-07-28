package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 账本成员实体，对应 {@code ledger_members} 表。
 *
 * <p>成员关系是账本访问控制的唯一真源：任一成员(OWNER/EDITOR)可读写该账本的业务数据；
 * 仅 OWNER 可改名/删除/邀请/移除成员。每个账本的创建者为 OWNER；协作账本可通过邀请码加入
 * EDITOR 成员。独立账本仅有创建者一个 OWNER 成员。</p>
 */
@Entity
@Table(name = "ledger_members")
public class LedgerMember {

    /** 角色：账本创建者。 */
    public static final String ROLE_OWNER = "OWNER";
    /** 角色：受邀协作成员。 */
    public static final String ROLE_EDITOR = "EDITOR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role", nullable = false, length = 16)
    private String role = ROLE_EDITOR;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public LedgerMember() {
    }

    public boolean isOwner() {
        return ROLE_OWNER.equals(role);
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
