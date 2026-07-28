package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Account;

import jakarta.persistence.LockModeType;

/**
 * 账户仓库。所有查询方法固定携带 {@code ledgerId} 过滤，保证多账本隔离（需求 2.3）。
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 列出某账本的全部账户，按 sort_order、id 升序（需求 3.5）。 */
    List<Account> findByLedgerIdOrderBySortOrderAscIdAsc(Long ledgerId);

    /** 按主键 + 归属账本定位账户；不匹配返回空（越权访问返回 404/403 的基础）。 */
    Optional<Account> findByIdAndLedgerId(Long id, Long ledgerId);

    /**
     * 按主键 + 归属账本定位账户并加行级悲观写锁（{@code SELECT ... FOR UPDATE}），
     * 供交易创建/修改/删除时在同一事务内更新余额，避免并发丢失更新（需求 4.1-4.3、4.10）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.ledgerId = :ledgerId")
    Optional<Account> findForUpdateByIdAndLedgerId(@Param("id") Long id, @Param("ledgerId") Long ledgerId);

    /** 某账本账户数量。 */
    long countByLedgerId(Long ledgerId);

    /** 某账本排序第一（sort_order 最小、其次 id 最小）的账户，供快速记账默认账户回退（需求 6.2）。 */
    Optional<Account> findFirstByLedgerIdOrderBySortOrderAscIdAsc(Long ledgerId);

    /** 跨多个账本列出账户（「全部账本」聚合只读视图用）。 */
    List<Account> findByLedgerIdInOrderBySortOrderAscIdAsc(java.util.Collection<Long> ledgerIds);

    /** 删除某账本的全部账户（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);
}
