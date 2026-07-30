package com.damien.youyu.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.LedgerInvite;

/**
 * 账本邀请码仓库。
 */
@Repository
public interface LedgerInviteRepository extends JpaRepository<LedgerInvite, Long> {

    /** 按邀请码定位（加入账本用）。 */
    Optional<LedgerInvite> findByCode(String code);

    /** 删除某账本的全部邀请码（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户创建的全部邀请码（注销级联硬删，需求 8.3）。 */
    void deleteByCreatedBy(Long createdBy);

    /** 删除一批账本的全部邀请码（注销级联硬删：按注销者拥有的账本清理，需求 8.3）。 */
    void deleteByLedgerIdIn(java.util.Collection<Long> ledgerIds);
}
