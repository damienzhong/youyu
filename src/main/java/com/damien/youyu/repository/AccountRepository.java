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
 * 账户仓库。账户为用户级（独立账本共享同一批账户），所有查询固定携带 {@code userId} 过滤。
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 列出某用户的全部账户，按 sort_order、id 升序（需求 3.5）。 */
    List<Account> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    /** 按主键 + 归属用户定位账户；不匹配返回空（越权访问返回 404/403 的基础）。 */
    Optional<Account> findByIdAndUserId(Long id, Long userId);

    /**
     * 按主键 + 归属用户定位账户并加行级悲观写锁，供交易创建/修改/删除时在同一事务内更新余额。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.userId = :userId")
    Optional<Account> findForUpdateByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 某用户账户数量。 */
    long countByUserId(Long userId);

    /** 某用户排序第一（sort_order 最小、其次 id 最小）的账户，供快速记账默认账户回退（需求 6.2）。 */
    Optional<Account> findFirstByUserIdOrderBySortOrderAscIdAsc(Long userId);

    // ---------------- 独立账本：用户级账户池（ledger_id 为空） ----------------

    /** 列出某用户的用户级账户（独立账本共享池，排除协作账本的账本级账户）。 */
    List<Account> findByUserIdAndLedgerIdIsNullOrderBySortOrderAscIdAsc(Long userId);

    /** 按主键定位某用户的用户级账户。 */
    Optional<Account> findByIdAndUserIdAndLedgerIdIsNull(Long id, Long userId);

    /** 按主键定位某用户的用户级账户并加行级悲观写锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.userId = :userId AND a.ledgerId IS NULL")
    Optional<Account> findForUpdateByIdAndUserIdAndLedgerIdIsNull(
            @Param("id") Long id, @Param("userId") Long userId);

    /** 某用户的用户级账户数量。 */
    long countByUserIdAndLedgerIdIsNull(Long userId);

    // ---------------- 协作账本：账本级账户池（ledger_id = 账本） ----------------

    /** 列出某协作账本的账本级账户。 */
    List<Account> findByLedgerIdOrderBySortOrderAscIdAsc(Long ledgerId);

    /** 按主键定位某协作账本的账本级账户。 */
    Optional<Account> findByIdAndLedgerId(Long id, Long ledgerId);

    /** 按主键定位某协作账本的账本级账户并加行级悲观写锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.ledgerId = :ledgerId")
    Optional<Account> findForUpdateByIdAndLedgerId(@Param("id") Long id, @Param("ledgerId") Long ledgerId);

    /** 某协作账本账户数量。 */
    long countByLedgerId(Long ledgerId);

    /** 删除某协作账本的全部账本级账户（协作账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);
}
