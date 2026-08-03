package com.damien.youyu.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.InviteRelation;
import com.damien.youyu.domain.InviteStatus;

/**
 * 用户邀请关系仓库。
 *
 * <p>全部查询以 {@code inviterId} / {@code inviteeId} 为入口：邀请人视角的统计与分页固定携带
 * {@code inviter_id} 过滤（复合索引 {@code idx_invite_relations_inviter_time} 支撑），
 * 被邀请人视角至多一行（唯一索引 {@code uk_invite_relations_invitee} 保证）。</p>
 *
 * <p>两个 count 口径刻意不同，不可互相替代：{@link #countByInviterId} 是关系总条数（含被邀请人
 * 已注销的 {@code INVALID} 行，即列表的 {@code total}，需求 7.5）；
 * {@link #countByInviterIdAndStatus} 传 {@code REGISTERED} 才是对外展示的「已邀请人数」（需求 7.6）。</p>
 */
@Repository
public interface InviteRelationRepository extends JpaRepository<InviteRelation, Long> {

    /** 某邀请人名下邀请关系总条数，含 {@code INVALID} 行，不受分页影响（需求 7.5）。 */
    long countByInviterId(Long inviterId);

    /** 某邀请人名下指定状态的关系条数；传 {@code REGISTERED} 即已邀请人数（需求 7.6）。 */
    long countByInviterIdAndStatus(Long inviterId, InviteStatus status);

    /** 分页列出某邀请人名下的邀请关系；排序由调用方通过 {@code Pageable} 指定（需求 7.1、7.2）。 */
    Page<InviteRelation> findByInviterId(Long inviterId, Pageable pageable);

    /** 按被邀请人定位其唯一的邀请关系（唯一索引保证至多一行）。 */
    Optional<InviteRelation> findByInviteeId(Long inviteeId);

    /**
     * 注销时把该用户作为被邀请人的关系置 {@code INVALID}，只改 {@code status} 与
     * {@code updated_at}，其余四列（{@code inviter_id} / {@code invitee_id} /
     * {@code register_time} / {@code created_at}）一律不动（需求 10.2）。
     *
     * <p>唯一索引 {@code uk_invite_relations_invitee} 保证影响行数 ≤ 1；该用户作为
     * <b>邀请人</b> 的行不受本次更新影响（需求 10.3）。</p>
     *
     * @return 实际影响行数，0 表示该用户不是任何人的被邀请人
     */
    @Modifying
    @Query("UPDATE InviteRelation r SET r.status = com.damien.youyu.domain.InviteStatus.INVALID, "
            + "r.updatedAt = :now WHERE r.inviteeId = :inviteeId")
    int markInvalidByInviteeId(@Param("inviteeId") Long inviteeId, @Param("now") LocalDateTime now);
}
