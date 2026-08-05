package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.LedgerMember;

/**
 * 账本成员仓库。成员关系是账本访问控制的唯一真源。
 */
@Repository
public interface LedgerMemberRepository extends JpaRepository<LedgerMember, Long> {

    /** 某用户参与的全部账本成员记录（列出可访问账本用）。 */
    List<LedgerMember> findByUserId(Long userId);

    /** 某账本的全部成员。 */
    List<LedgerMember> findByLedgerId(Long ledgerId);

    /** 定位某用户在某账本的成员记录（授权判定用）。 */
    Optional<LedgerMember> findByLedgerIdAndUserId(Long ledgerId, Long userId);

    /** 某用户是否为某账本成员。 */
    boolean existsByLedgerIdAndUserId(Long ledgerId, Long userId);

    /** 某账本成员数。 */
    long countByLedgerId(Long ledgerId);

    /**
     * 某账本中「非该用户」的成员数量（注销协作牵连检查用，需求 8.2）：>0 表示该账本除本人外仍有其他
     * 成员，注销前需先转交/删除该账本。
     */
    long countByLedgerIdAndUserIdNot(Long ledgerId, Long userId);

    /** 删除某账本某成员（移除成员/退出）。 */
    void deleteByLedgerIdAndUserId(Long ledgerId, Long userId);

    /** 删除某账本全部成员（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户的全部成员记录（注销级联硬删：其在各账本的成员/邀请归属，需求 8.3）。 */
    void deleteByUserId(Long userId);

    // ---------------- 成就系统的「协作成员数」聚合（achievement-system 需求 3.3、3.4、3.5）----------------

    /**
     * 协作成员数 {@code COLLAB_MEMBER_COUNT}：该用户拥有的账本上、由<b>别人</b>以 {@code EDITOR} 身份
     * 加入的<b>成员行行数</b>（achievement-system 需求 3.3、3.4）。
     *
     * <p><b>按成员行计数，不按去重用户计数</b>：同一个人以 {@code EDITOR} 加入本人 2 个账本计 2
     * （成员行 2 行）。唯一约束 {@code uk_ledger_member (ledger_id, user_id)} 保证同一账本内不会重复计数。</p>
     *
     * <p>三类成员行被排除在该计数之外：</p>
     * <ol>
     *   <li>{@code role = 'OWNER'} 的成员行（账本创建者自己不算协作成员）；</li>
     *   <li>{@code user_id} 等于该用户本人 id 的成员行（本人不是自己的协作成员）；</li>
     *   <li>该用户作为 {@code EDITOR} <b>加入他人账本</b>的成员行——这些行的 {@code ledger_id}
     *       所属账本的 {@code ledgers.user_id} 不等于该用户，被 {@code l.userId = :userId} 天然排除。</li>
     * </ol>
     *
     * <p><b>账本归属只认 {@code ledgers.user_id}</b>，这是唯一依据：成就 {@code COLLAB_1} 衡量的是
     * 「有别人加入了我的账本」，而非「我加入了别人的账本」，因此不能用 {@code ledger_members} 里
     * 本人的 {@code OWNER} 行来推断归属（需求 3.4）。</p>
     *
     * <p>本查询只读，不对 {@code ledger_members} 与 {@code ledgers} 执行任何写语句（需求 3.5），
     * 且不新增任何列与索引。</p>
     */
    @Query("""
            SELECT COUNT(m) FROM LedgerMember m, Ledger l
            WHERE l.id = m.ledgerId
              AND l.userId = :userId
              AND m.role = 'EDITOR'
              AND m.userId <> :userId
            """)
    long countEditorsOfOwnedLedgers(@Param("userId") Long userId);
}
