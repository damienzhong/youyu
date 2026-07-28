package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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

    /** 删除某账本某成员（移除成员/退出）。 */
    void deleteByLedgerIdAndUserId(Long ledgerId, Long userId);

    /** 删除某账本全部成员（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);
}
