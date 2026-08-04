package com.damien.youyu.repository;

import java.util.Collection;
import java.util.List;
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

    /**
     * 一次取回多个自有账本在某月的总预算行（成长体系的预算达成判定用，需求 5.11）。
     *
     * <p>用 {@code ledger_id IN (:ledgerIds)} 一次取回、在应用层按账本分组，使预算判定的查询数
     * 不随账本数增长（需求 4.6 的读查询预算）。派生查询名中的 {@code Month} 对应实体字段
     * {@code month}（实际列名 {@code budget_month}，避开 MySQL 保留字）。</p>
     */
    List<Budget> findByLedgerIdInAndMonth(Collection<Long> ledgerIds, String month);

    /** 删除某账本的全部月度总预算（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户的全部月度总预算（注销级联硬删，需求 8.3）。 */
    void deleteByUserId(Long userId);
}
