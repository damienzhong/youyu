package com.damien.youyu.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.LoanRepayment;

/**
 * 借贷收款/还款仓库。查询按 {@code ledgerId} 隔离；余额重算用的增量按账户跨账本汇总。
 */
@Repository
public interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, Long> {

    /** 某借贷的收款/还款明细，按发生时间倒序、id 倒序。 */
    List<LoanRepayment> findByLoanIdOrderByOccurredAtDescIdDesc(Long loanId);

    /** 按主键 + 归属用户定位（越权返回空）。 */
    Optional<LoanRepayment> findByIdAndUserId(Long id, Long userId);

    /** 某用户、收款钱包/还款账户为某账户的全部收款/还款（账户流水投影用）。 */
    List<LoanRepayment> findByUserIdAndAccountId(Long userId, Long accountId);

    /**
     * 某账户上收款/还款对余额的净增量：借出(LEND)收款 +amount、借入(BORROW)还款 −amount，无记录返回 0。
     * 供账户余额重算纳入还款影响。
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN l.direction = com.damien.youyu.domain.LoanDirection.LEND "
            + "THEN r.amount ELSE -r.amount END), 0) "
            + "FROM LoanRepayment r JOIN Loan l ON r.loanId = l.id WHERE r.accountId = :accountId")
    BigDecimal sumDeltaByAccount(@Param("accountId") Long accountId);

    /** 删除某借贷的全部收款/还款（删除借贷时级联）。 */
    void deleteByLoanId(Long loanId);

    /** 删除某账本的全部收款/还款（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户的全部收款/还款（注销级联）。 */
    void deleteByUserId(Long userId);
}
