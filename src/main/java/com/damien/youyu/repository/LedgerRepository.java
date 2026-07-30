package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Ledger;

/**
 * 账本仓库。账本按 {@code userId} 归属用户；业务数据的隔离在各自仓库按 {@code ledgerId} 进行。
 */
@Repository
public interface LedgerRepository extends JpaRepository<Ledger, Long> {

    /** 列出某用户的全部账本，按 sort_order、id 升序。 */
    List<Ledger> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    /** 按主键 + 归属用户定位账本；不匹配返回空（越权访问返回 404 的基础）。 */
    Optional<Ledger> findByIdAndUserId(Long id, Long userId);

    /** 某用户的默认账本（每用户唯一）。 */
    Optional<Ledger> findFirstByUserIdAndIsDefaultTrue(Long userId);

    /** 某用户排序第一的账本，作为无默认账本时的回退。 */
    Optional<Ledger> findFirstByUserIdOrderBySortOrderAscIdAsc(Long userId);

    /** 某用户账本数量（删除时保证至少保留一个）。 */
    long countByUserId(Long userId);

    /** 删除某用户拥有的全部账本（注销级联硬删，需求 8.3）。 */
    void deleteByUserId(Long userId);
}
