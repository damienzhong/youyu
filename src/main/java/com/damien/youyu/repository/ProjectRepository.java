package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Project;

/**
 * 项目仓库。所有查询固定携带 {@code ledgerId} 过滤，保证多账本隔离（需求 2.3）。
 */
@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** 列出某账本全部项目：未归档优先，其次按排序、id 升序。 */
    List<Project> findByLedgerIdOrderByArchivedAscSortOrderAscIdAsc(Long ledgerId);

    /** 按主键 + 归属账本定位；不匹配返回空（越权返回 NOT_FOUND 的基础）。 */
    Optional<Project> findByIdAndLedgerId(Long id, Long ledgerId);

    /** 某账本项目数（排序值计算用）。 */
    long countByLedgerId(Long ledgerId);

    /** 删除某账本的全部项目（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户的全部项目（注销级联硬删，需求 8.3）。 */
    void deleteByUserId(Long userId);
}
