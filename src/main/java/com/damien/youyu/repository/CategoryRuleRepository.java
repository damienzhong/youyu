package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.CategoryRule;

/**
 * 自动分类规则仓库。查询固定携带 {@code ledgerId}，保证多账本隔离。
 */
@Repository
public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Long> {

    /** 某账本全部规则（自动分类时一次性载入）。 */
    List<CategoryRule> findByLedgerId(Long ledgerId);

    /** 定位某账本某种类某商户的规则（保存时覆盖用）。 */
    Optional<CategoryRule> findByLedgerIdAndKindAndMerchantKey(Long ledgerId, CategoryKind kind, String merchantKey);

    /** 删除某账本的全部规则（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户创建的全部规则（注销级联硬删）。 */
    void deleteByUserId(Long userId);
}
