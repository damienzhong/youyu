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

    /** 列出某用户全部借贷：未结清优先，其次按发生时间倒序、id 倒序。 */
    List<Loan> findByUserIdOrderBySettledAscOccurredAtDescIdDesc(Long userId);

    /** 按主键 + 归属用户定位；不匹配返回空（越权返回 NOT_FOUND 的基础）。 */
    Optional<Loan> findByIdAndUserId(Long id, Long userId);

    /** 某用户、关联某账户的全部借贷（账户流水投影用）。 */
    List<Loan> findByUserIdAndAccountId(Long userId, Long accountId);

    /** 某方向未结清剩余金额合计（剩余 = amount - repaidAmount），无记录返回 null。 */
    @Query("SELECT SUM(l.amount - l.repaidAmount) FROM Loan l "
            + "WHERE l.userId = :userId AND l.direction = :direction AND l.settled = false")
    BigDecimal sumOutstandingByDirection(
            @Param("userId") Long userId, @Param("direction") LoanDirection direction);

    /**
     * 某账户上借贷初始出/入账对余额的净增量（跨账本、全部借贷，含已结清）：借入 +amount、借出 -amount。
     * 初始增量为永久增量（回补由收款/还款子台账承担），故不按 settled 过滤。无记录返回 0。
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN l.direction = com.damien.youyu.domain.LoanDirection.BORROW "
            + "THEN l.amount ELSE -l.amount END), 0) "
            + "FROM Loan l WHERE l.accountId = :accountId")
    BigDecimal sumCreationDeltaByAccount(@Param("accountId") Long accountId);

    /** 删除某账本的全部借贷（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户的全部借贷（注销级联硬删，需求 8.3）。 */
    void deleteByUserId(Long userId);
}
