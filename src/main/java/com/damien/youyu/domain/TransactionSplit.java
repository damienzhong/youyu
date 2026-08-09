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
 * AA 支出的分摊行，对应 {@code transaction_splits} 表：一笔 AA 支出（{@code transaction_id}）
 * 对某参与人（{@code participant_user_id}）的分摊额（{@code share_amount}）。
 *
 * <p>同一笔支出内各参与人分摊额之和恒等于该笔总额（以「分」守恒）。唯一键
 * {@code (transaction_id, participant_user_id)} 保证每人每笔至多一条。</p>
 */
@Entity
@Table(name = "transaction_splits")
public class TransactionSplit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属 AA 支出交易 id。 */
    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    /** 参与分摊的成员 user_id（注册用户）。 */
    @Column(name = "participant_user_id", nullable = false)
    private Long participantUserId;

    /** 该参与人分摊额，恒为非负。 */
    @Column(name = "share_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal shareAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public TransactionSplit() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public Long getParticipantUserId() {
        return participantUserId;
    }

    public void setParticipantUserId(Long participantUserId) {
        this.participantUserId = participantUserId;
    }

    public BigDecimal getShareAmount() {
        return shareAmount;
    }

    public void setShareAmount(BigDecimal shareAmount) {
        this.shareAmount = shareAmount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
