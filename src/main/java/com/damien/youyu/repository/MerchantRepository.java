package com.damien.youyu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Merchant;

/**
 * 商家仓库。所有查询固定携带 {@code ledgerId} 过滤，保证多账本隔离（需求 2.3）。
 */
@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    /** 列出某账本全部商家：按排序、id 升序。 */
    List<Merchant> findByLedgerIdOrderBySortOrderAscIdAsc(Long ledgerId);

    /** 按主键 + 归属账本定位；不匹配返回空（越权返回 NOT_FOUND 的基础）。 */
    Optional<Merchant> findByIdAndLedgerId(Long id, Long ledgerId);

    /** 某账本某名商家是否已存在（去重/快速复用）。 */
    Optional<Merchant> findFirstByLedgerIdAndName(Long ledgerId, String name);

    /** 某账本商家数（排序值计算用）。 */
    long countByLedgerId(Long ledgerId);

    /** 删除某账本的全部商家（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    /** 删除某用户的全部商家（注销级联硬删，需求 8.3）。 */
    void deleteByUserId(Long userId);
}
