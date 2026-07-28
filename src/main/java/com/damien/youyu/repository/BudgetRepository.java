package com.damien.youyu.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Budget;

/**
 * 月度总预算仓库。所有查询固定携带 {@code ledgerId} 过滤，保证多账本隔离（需求 2.3）。
 */
@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /** 某账本某自然月的总预算（至多一条）。 */
    Optional<Budget> findByLedgerIdAndMonth(Long ledgerId, String month);

    /** 删除某账本的全部月度总预算（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);
}
