package com.damien.youyu.domain;

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
 * 用户邀请关系，对应 {@code invite_relations} 表。只追加 + 状态更新的历史表。
 *
 * <p>邀请关系只在「新账号被创建的那一刻」写入，一次写定不可改绑；同一 {@code inviteeId}
 * 至多一行（由唯一索引 {@code uk_invite_relations_invitee} 保证）。</p>
 *
 * <p><b>{@code inviterId} / {@code inviteeId} 刻意声明为裸 {@link Long}，不得改成
 * {@code @ManyToOne User} 关联：</b></p>
 * <ol>
 *   <li>任一方注销都保留该行，这两个 id <b>可能是悬空 id</b>（指向已删除的 {@code users} 行）。
 *       若映射成关联实体，读取一条邀请人或被邀请人已注销的关系时 Hibernate 会因找不到目标行抛
 *       {@code EntityNotFoundException}，把「历史留痕」这个核心语义直接打断。</li>
 *   <li>表上刻意没有任何指向 {@code users(id)} 的外键。映射成关联实体会诱导后续开发者
 *       （或 {@code ddl-auto}）顺手补上外键，而外键会让注销时的级联删除抹掉邀请关系行。</li>
 * </ol>
 *
 * <p>被邀请人昵称不在本实体上，由服务层按当前页的 {@code inviteeId} 批量查询 {@code users}
 * 后 null-安全填充（缺失或空白一律为 {@code null}）。</p>
 *
 * <p>{@code @Table} 上声明唯一约束 {@code uk_invite_relations_invitee} 与复合索引
 * {@code idx_invite_relations_inviter_time}，与迁移脚本同名同列：生产由 Flyway 建表
 * （{@code ddl-auto=validate} 不校验索引，故此声明对生产无影响），而测试环境的 H2 表结构由
 * Hibernate 依本实体生成——不声明这个唯一约束，H2 上就没有唯一索引，「同一 invitee 重复插入」
 * 的冲突分支（{@code ALREADY_BOUND}）在测试里会静默变成两行都写入。</p>
 */
@Entity
@Table(name = "invite_relations",
        uniqueConstraints = @UniqueConstraint(name = "uk_invite_relations_invitee",
                columnNames = "invitee_id"),
        indexes = @Index(name = "idx_invite_relations_inviter_time",
                columnList = "inviter_id, register_time"))
public class InviteRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invite_id")
    private Long inviteId;

    /** 邀请人用户 id。裸 id，无外键，邀请人注销后成为悬空 id。 */
    @Column(name = "inviter_id", nullable = false)
    private Long inviterId;

    /** 被邀请人用户 id。裸 id，无外键，被邀请人注销后成为悬空 id；全表至多一行。 */
    @Column(name = "invitee_id", nullable = false)
    private Long inviteeId;

    /** 被邀请人注册时刻，与其 {@code users.created_at} 严格相等。 */
    @Column(name = "register_time", nullable = false)
    private LocalDateTime registerTime;

    /** 关系状态，仅描述被邀请人；名称即库中取值。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InviteStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public InviteRelation() {
        // JPA / 服务层构造
    }

    public Long getInviteId() {
        return inviteId;
    }

    public void setInviteId(Long inviteId) {
        this.inviteId = inviteId;
    }

    public Long getInviterId() {
        return inviterId;
    }

    public void setInviterId(Long inviterId) {
        this.inviterId = inviterId;
    }

    public Long getInviteeId() {
        return inviteeId;
    }

    public void setInviteeId(Long inviteeId) {
        this.inviteeId = inviteeId;
    }

    public LocalDateTime getRegisterTime() {
        return registerTime;
    }

    public void setRegisterTime(LocalDateTime registerTime) {
        this.registerTime = registerTime;
    }

    public InviteStatus getStatus() {
        return status;
    }

    public void setStatus(InviteStatus status) {
        this.status = status;
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
