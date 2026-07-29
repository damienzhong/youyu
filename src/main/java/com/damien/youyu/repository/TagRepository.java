package com.damien.youyu.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Tag;

/**
 * 标签仓库。所有查询固定携带 {@code ledgerId} 过滤，保证多账本隔离（需求 2.3）。
 */
@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

    /** 列出某账本全部标签：按排序、id 升序。 */
    List<Tag> findByLedgerIdOrderBySortOrderAscIdAsc(Long ledgerId);

    /** 按主键 + 归属账本定位；不匹配返回空。 */
    Optional<Tag> findByIdAndLedgerId(Long id, Long ledgerId);

    /** 某账本某名标签（同名去重/幂等复用）。 */
    Optional<Tag> findFirstByLedgerIdAndName(Long ledgerId, String name);

    /** 批量按 id + 账本定位（校验标签集合归属）。 */
    List<Tag> findByLedgerIdAndIdIn(Long ledgerId, Collection<Long> ids);

    /** 某账本标签数（排序值计算用）。 */
    long countByLedgerId(Long ledgerId);

    /** 删除某账本的全部标签（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);
}
