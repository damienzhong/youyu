package com.damien.youyu.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;

/**
 * 账户/账本可见性关联仓库（{@code account_ledger}）。
 *
 * <p>表达账户参与哪些账本、是否对协作成员可见/显示余额。记账可选集与可见性解析基于此表。</p>
 */
@Repository
public interface AccountLedgerRepository extends JpaRepository<AccountLedger, Long> {

    /** 某账本的全部账户关联行。 */
    List<AccountLedger> findByLedgerId(Long ledgerId);

    /** 某账户的全部账本关联行。 */
    List<AccountLedger> findByAccountId(Long accountId);

    /** 一批账户的全部账本关联行（资产页展示"参与账本"用）。 */
    List<AccountLedger> findByAccountIdIn(Collection<Long> accountIds);

    /** 定位某账户在某账本的关联行。 */
    Optional<AccountLedger> findByAccountIdAndLedgerId(Long accountId, Long ledgerId);

    /** 某账户是否已参与某账本。 */
    boolean existsByAccountIdAndLedgerId(Long accountId, Long ledgerId);

    /** 删除某账本的全部关联行（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某账户的全部关联行（账户删除级联）。 */
    void deleteByAccountId(Long accountId);

    /** 删除某组账户在某账本的关联行（成员退出协作账本时取消其账户暴露）。 */
    void deleteByAccountIdInAndLedgerId(Collection<Long> accountIds, Long ledgerId);

    /** 删除一批账户的全部关联行（注销级联硬删：按注销者拥有的账户清理，需求 8.3）。 */
    void deleteByAccountIdIn(Collection<Long> accountIds);

    /** 删除一批账本的全部关联行（注销级联硬删：按注销者拥有的账本清理，需求 8.3）。 */
    void deleteByLedgerIdIn(Collection<Long> ledgerIds);

    /**
     * 某用户在某账本记账时可选的账户：该用户拥有且参与此账本的账户，
     * 并集他人拥有、参与此账本且 {@code visible_to_others=true} 的账户，按 sort_order、id 升序。
     */
    @Query("SELECT a FROM Account a JOIN AccountLedger al ON al.accountId = a.id "
            + "WHERE al.ledgerId = :ledgerId "
            + "AND (a.userId = :userId OR al.visibleToOthers = true) "
            + "ORDER BY a.sortOrder ASC, a.id ASC")
    List<Account> findSelectableAccounts(@Param("userId") Long userId, @Param("ledgerId") Long ledgerId);

    /** 参与某账本的全部账户（不论可见性），按 sort_order、id 升序（导出用）。 */
    @Query("SELECT a FROM Account a JOIN AccountLedger al ON al.accountId = a.id "
            + "WHERE al.ledgerId = :ledgerId ORDER BY a.sortOrder ASC, a.id ASC")
    List<Account> findAccountsByLedgerId(@Param("ledgerId") Long ledgerId);
}
