package com.damien.youyu.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.AaSettlement;

/** AA 账本结算记录仓库。 */
@Repository
public interface AaSettlementRepository extends JpaRepository<AaSettlement, Long> {

    /** 某账本全部结算（含已撤销），用于展示/追溯。 */
    List<AaSettlement> findByLedgerId(Long ledgerId);

    /** 某账本未撤销的结算，用于净额计算。 */
    List<AaSettlement> findByLedgerIdAndRevertedAtIsNull(Long ledgerId);
}
