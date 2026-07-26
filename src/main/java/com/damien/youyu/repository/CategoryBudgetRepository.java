package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.CategoryBudget;

/**
 * 分类预算仓库。所有查询固定携带 {@code userId} 过滤，保证多租户隔离（需求 2.3）。
 */
@Repository
public interface CategoryBudgetRepository extends JpaRepository<CategoryBudget, Long> {

    /** 某用户某自然月的全部分类预算。 */
    List<CategoryBudget> findByUserIdAndMonth(Long userId, String month);

    /** 某用户某自然月某分类的预算（至多一条）。 */
    Optional<CategoryBudget> findByUserIdAndMonthAndCategoryId(Long userId, String month, Long categoryId);
}
