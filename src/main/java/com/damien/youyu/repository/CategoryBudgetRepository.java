package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.CategoryBudget;

/**
 * 分类预算仓库。所有查询固定携带 {@code ledgerId} 过滤，保证多账本隔离（需求 2.3）。
 */
@Repository
public interface CategoryBudgetRepository extends JpaRepository<CategoryBudget, Long> {

    /** 某账本某自然月的全部分类预算。 */
    List<CategoryBudget> findByLedgerIdAndMonth(Long ledgerId, String month);

    /** 某账本某自然月某分类的预算（至多一条）。 */
    Optional<CategoryBudget> findByLedgerIdAndMonthAndCategoryId(Long ledgerId, String month, Long categoryId);

    /** 删除某账本的全部分类预算（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户的全部分类预算（注销级联硬删，需求 8.3）。 */
    void deleteByUserId(Long userId);
}
