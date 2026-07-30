package com.damien.youyu.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.TransactionTag;

/**
 * 交易-标签关联仓库（多对多）。
 */
@Repository
public interface TransactionTagRepository extends JpaRepository<TransactionTag, Long> {

    /** 某交易的全部关联。 */
    List<TransactionTag> findByTransactionId(Long transactionId);

    /** 一批交易的全部关联（列表批量取标签，避免 N+1）。 */
    List<TransactionTag> findByTransactionIdIn(Collection<Long> transactionIds);

    /** 某账本全部标签关联（导出时预载 交易→标签 映射，避免 N+1）。 */
    @Query("SELECT tt FROM TransactionTag tt WHERE tt.tagId IN "
            + "(SELECT t.id FROM Tag t WHERE t.ledgerId = :ledgerId)")
    List<TransactionTag> findByLedgerId(@Param("ledgerId") Long ledgerId);

    /** 删除某交易的全部关联（修改/删除交易时重置）。 */
    void deleteByTransactionId(Long transactionId);

    /** 删除某标签的全部关联（删除标签时清理）。 */
    void deleteByTagId(Long tagId);

    /** 删除一批交易的全部标签关联（注销级联硬删：按注销者名下交易清理，需求 8.3）。 */
    void deleteByTransactionIdIn(Collection<Long> transactionIds);

    /** 删除某账本全部标签关联（账本删除级联）：按该账本的标签清理关联行。 */
    @Modifying
    @Query("DELETE FROM TransactionTag tt WHERE tt.tagId IN "
            + "(SELECT t.id FROM Tag t WHERE t.ledgerId = :ledgerId)")
    void deleteByLedgerId(@Param("ledgerId") Long ledgerId);
}
