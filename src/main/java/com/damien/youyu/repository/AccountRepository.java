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
 * 账户仓库。所有查询方法固定携带 {@code userId} 过滤，保证多租户隔离（需求 2.3）。
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 列出某用户的全部账户，按 sort_order、id 升序（需求 3.5）。 */
    List<Account> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    /** 按主键 + 归属用户定位账户；不匹配返回空（越权访问返回 404/403 的基础）。 */
    Optional<Account> findByIdAndUserId(Long id, Long userId);

    /**
     * 按主键 + 归属用户定位账户并加行级悲观写锁（{@code SELECT ... FOR UPDATE}），
     * 供交易创建/修改/删除时在同一事务内更新余额，避免并发丢失更新（需求 4.1-4.3、4.10）。
     * 不匹配返回空（账户不存在或不属于当前用户 → NOT_FOUND，需求 4.9）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.userId = :userId")
    Optional<Account> findForUpdateByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 某用户账户数量。 */
    long countByUserId(Long userId);

    /** 某用户排序第一（sort_order 最小、其次 id 最小）的账户，供快速记账默认账户回退（需求 6.2）。 */
    Optional<Account> findFirstByUserIdOrderBySortOrderAscIdAsc(Long userId);
}
