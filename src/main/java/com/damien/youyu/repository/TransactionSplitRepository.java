package com.damien.youyu.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.TransactionSplit;

/** AA 支出分摊行仓库。 */
@Repository
public interface TransactionSplitRepository extends JpaRepository<TransactionSplit, Long> {

    /** 某笔 AA 支出的全部分摊行。 */
    List<TransactionSplit> findByTransactionId(Long transactionId);

    /** 一批 AA 支出的全部分摊行（净额计算批量读取用）。 */
    List<TransactionSplit> findByTransactionIdIn(Collection<Long> transactionIds);

    /** 删除某笔 AA 支出的全部分摊行（编辑/删除支出时回滚用）。 */
    @Modifying
    @Query("DELETE FROM TransactionSplit s WHERE s.transactionId = :transactionId")
    void deleteByTransactionId(@Param("transactionId") Long transactionId);
}
