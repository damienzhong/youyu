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

    /** 删除某账本的全部借贷（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);
}
