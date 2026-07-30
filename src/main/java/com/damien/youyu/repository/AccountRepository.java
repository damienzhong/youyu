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
 * 账户仓库。账户是独立于账本的一等实体，始终归属某个用户（owner=user_id），
 * 归属相关查询固定携带 {@code userId} 过滤；记账时对可用账户按主键加锁更新余额。
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 列出某用户拥有的全部账户，按 sort_order、id 升序（需求 3.5）。 */
    List<Account> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    /** 按主键 + 归属用户定位账户；不匹配返回空（越权访问返回 NOT_FOUND 的基础）。 */
    Optional<Account> findByIdAndUserId(Long id, Long userId);

    /**
     * 按主键 + 归属用户定位账户并加行级悲观写锁，供账户 owner 自身操作时在同一事务内更新余额。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.userId = :userId")
    Optional<Account> findForUpdateByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 按主键定位账户并加行级悲观写锁，供记账时更新余额（记账人可能是使用他人共享账户的协作成员，
     * 账户可用性由 {@code LedgerAccountResolver} 事先校验）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findForUpdateById(@Param("id") Long id);

    /** 某用户账户数量。 */
    long countByUserId(Long userId);

    /** 某用户排序第一（sort_order 最小、其次 id 最小）的账户，供快速记账默认账户回退（需求 6.2）。 */
    Optional<Account> findFirstByUserIdOrderBySortOrderAscIdAsc(Long userId);
}
