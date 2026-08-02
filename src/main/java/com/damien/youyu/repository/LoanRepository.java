package com.damien.youyu.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Loan;
import com.damien.youyu.domain.LoanDirection;

/**
 * 借贷仓库。所有查询固定携带 {@code ledgerId} 过滤，保证多账本隔离（需求 2.3）。
 */
@Repository
public interface LoanRepository extends JpaRepository<Loan, Long> {

    /** 列出某账本全部借贷：未结清优先，其次按发生时间倒序、id 倒序。 */
    List<Loan> findByLedgerIdOrderBySettledAscOccurredAtDescIdDesc(Long ledgerId);

    /** 按主键 + 归属账本定位；不匹配返回空（越权返回 NOT_FOUND 的基础）。 */
    Optional<Loan> findByIdAndLedgerId(Long id, Long ledgerId);

    /** 某方向未结清金额合计（无记录返回 null）。 */
    @Query("SELECT SUM(l.amount) FROM Loan l "
            + "WHERE l.ledgerId = :ledgerId AND l.direction = :direction AND l.settled = false")
    BigDecimal sumOutstandingByDirection(
            @Param("ledgerId") Long ledgerId, @Param("direction") LoanDirection direction);

    /**
     * 某账户上未结清借贷对余额的净增量（跨账本汇总）：借入 +amount、借出 -amount，无记录返回 0。
     * 供账户余额重算（{@code recompute}）纳入借贷影响，保证与实时增量一致。
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN l.direction = com.damien.youyu.domain.LoanDirection.BORROW "
            + "THEN l.amount ELSE -l.amount END), 0) "
            + "FROM Loan l WHERE l.accountId = :accountId AND l.settled = false")
    BigDecimal sumActiveDeltaByAccount(@Param("accountId") Long accountId);

    /** 删除某账本的全部借贷（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户的全部借贷（注销级联硬删，需求 8.3）。 */
    void deleteByUserId(Long userId);
}
