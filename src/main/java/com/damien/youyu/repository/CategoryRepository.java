package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;

/**
 * 分类仓库。所有查询方法固定携带 {@code userId} 过滤，保证多租户隔离（需求 2.3）。
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 列出某用户的全部分类。 */
    List<Category> findByUserId(Long userId);

    /** 列出某用户某种类(支出/收入)的全部分类（需求 5.6）。 */
    List<Category> findByUserIdAndKind(Long userId, CategoryKind kind);

    /** 列出某用户某父分类下的子分类。 */
    List<Category> findByUserIdAndParentId(Long userId, Long parentId);

    /** 按主键 + 归属用户定位分类；不匹配返回空。 */
    Optional<Category> findByIdAndUserId(Long id, Long userId);

    /** 同一用户、同一种类、同一父级范围内是否已存在同名子分类（需求 5.8，parentId 非空时使用）。 */
    boolean existsByUserIdAndKindAndParentIdAndName(
            Long userId, CategoryKind kind, Long parentId, String name);

    /** 同一用户、同一种类的父分类(parent_id 为 NULL)是否已存在同名（需求 5.8，父级重名的应用层补充校验）。 */
    boolean existsByUserIdAndKindAndParentIdIsNullAndName(
            Long userId, CategoryKind kind, String name);

    /** 某分类是否含子分类（删除校验：含子分类禁止删除，需求 5.9）。 */
    boolean existsByUserIdAndParentId(Long userId, Long parentId);

    /** 某用户分类数量。 */
    long countByUserId(Long userId);
}
