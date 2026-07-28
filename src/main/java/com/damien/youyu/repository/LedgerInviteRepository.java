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
}
