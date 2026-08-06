package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;

/**
 * 分类仓库。所有查询方法固定携带 {@code ledgerId} 过滤，保证多账本隔离（需求 2.3）。
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 列出某账本的全部分类。 */
    List<Category> findByLedgerId(Long ledgerId);

    /** 列出某账本某种类(支出/收入)的全部分类（需求 5.6）。 */
    List<Category> findByLedgerIdAndKind(Long ledgerId, CategoryKind kind);

    /** 列出某账本某父分类下的子分类。 */
    List<Category> findByLedgerIdAndParentId(Long ledgerId, Long parentId);

    /** 按主键 + 归属账本定位分类；不匹配返回空。 */
    Optional<Category> findByIdAndLedgerId(Long id, Long ledgerId);

    /** 同一账本、同一种类、同一父级范围内是否已存在同名子分类（需求 5.8，parentId 非空时使用）。 */
    boolean existsByLedgerIdAndKindAndParentIdAndName(
            Long ledgerId, CategoryKind kind, Long parentId, String name);

    /** 同一账本、同一种类的父分类(parent_id 为 NULL)是否已存在同名（需求 5.8）。 */
    boolean existsByLedgerIdAndKindAndParentIdIsNullAndName(
            Long ledgerId, CategoryKind kind, String name);

    /** 定位某账本某种类的顶级同名分类（惰性获取系统「余额调整」分类用）。 */
    Optional<Category> findFirstByLedgerIdAndKindAndParentIdIsNullAndName(
            Long ledgerId, CategoryKind kind, String name);

    /** 某分类是否含子分类（删除校验：含子分类禁止删除，需求 5.9）。 */
    boolean existsByLedgerIdAndParentId(Long ledgerId, Long parentId);

    /** 某账本分类数量。 */
    long countByLedgerId(Long ledgerId);

    /** 跨多个账本列出分类（「全部账本」聚合只读视图用）。 */
    List<Category> findByLedgerIdIn(java.util.Collection<Long> ledgerIds);

    /**
     * 按主键集合批量取分类（record-suggestion 展示用，需求 8.1）。
     * 只读派生查询，仅供记账推荐取候选分类的 name/icon；不做账本过滤，调用方仅以候选代表流水的
     * {@code categoryId} 集合查询，越权数据不进入候选（候选本身已按当前账本历史派生）。
     */
    List<Category> findByIdIn(java.util.Collection<Long> ids);

    /** 删除某账本的全部分类（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户的全部分类（注销级联硬删，需求 8.3）。 */
    void deleteByUserId(Long userId);
}
