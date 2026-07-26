package com.damien.youyu.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Budget;

/**
 * 月度总预算仓库。所有查询固定携带 {@code userId} 过滤，保证多租户隔离（需求 2.3）。
 */
@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /** 某用户某自然月的总预算（至多一条）。 */
    Optional<Budget> findByUserIdAndMonth(Long userId, String month);
}
