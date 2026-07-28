package com.damien.youyu.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;

import java.math.BigDecimal;

/**
 * 交易仓库。所有查询方法固定携带 {@code ledgerId} 过滤，保证多账本隔离（需求 2.3）。
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /** 分页列出某账本交易，按时间倒序（需求 2.3）。 */
    Page<Transaction> findByLedgerIdOrderByOccurredAtDescIdDesc(Long ledgerId, Pageable pageable);

    /** 列出某账本全部交易（导出/重算用）。 */
    List<Transaction> findByLedgerId(Long ledgerId);

    /**
     * 以流式方式按 id 升序读取某账本全部交易，供数据导出「流式写出、避免全量载入内存」使用。
     * 调用方须在只读事务内以 try-with-resources 消费并关闭该 {@link Stream}。
     */
    @Query("SELECT t FROM Transaction t WHERE t.ledgerId = :ledgerId ORDER BY t.id ASC")
    Stream<Transaction> streamByLedgerIdOrderById(@Param("ledgerId") Long ledgerId);

    /** 按主键 + 归属账本定位交易；不匹配返回空（需求 2.4，越权返回 404/403）。 */
    Optional<Transaction> findByIdAndLedgerId(Long id, Long ledgerId);

    /** 某账本在 [from, to] 时间范围内的交易（inclusive-inclusive）。 */
    List<Transaction> findByLedgerIdAndOccurredAtBetween(Long ledgerId, LocalDateTime from, LocalDateTime to);

    /** 某账本在半开区间 [fromInclusive, toExclusive) 内的交易（报表聚合用，需求 7.1、7.2、7.4）。 */
    List<Transaction> findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            Long ledgerId, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    /** 某账本在半开区间 [fromInclusive, toExclusive) 内的交易，按时间倒序（首页「当月流水」用）。 */
    List<Transaction> findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
            Long ledgerId, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    /** 某账本最近一笔交易，供快速记账默认账户选择（需求 6.1）。 */
    Optional<Transaction> findFirstByLedgerIdOrderByOccurredAtDescIdDesc(Long ledgerId);

    /** 跨多个账本、在半开区间内的交易，按时间倒序（「全部账本」聚合只读视图用）。 */
    List<Transaction> findByLedgerIdInAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
            Collection<Long> ledgerIds, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    /**
     * 某账户是否被任一交易引用（作为普通账户、转账源或转账目标）。账户为用户级，其流水可跨账本，
     * 故按 accountId 判断（accountId 全局唯一，仅其自身流水引用）。用于「有交易的账户不可删除」（需求 3.7）。
     */
    @Query("""
            SELECT COUNT(t) > 0 FROM Transaction t
            WHERE t.accountId = :accountId
               OR t.sourceAccountId = :accountId
               OR t.destinationAccountId = :accountId
            """)
    boolean existsByAccountReferenced(@Param("accountId") Long accountId);

    /** 某分类是否被该账本的任一交易引用（用于「被引用分类不可删除」校验，需求 5.5）。 */
    boolean existsByLedgerIdAndCategoryId(Long ledgerId, Long categoryId);

    /** 该账本已存在的第三方账单标识（账单导入去重用）：返回给定候选集中已入库的 external_id。 */
    @Query("SELECT t.externalId FROM Transaction t "
            + "WHERE t.ledgerId = :ledgerId AND t.externalId IN :externalIds")
    List<String> findExistingExternalIds(
            @Param("ledgerId") Long ledgerId, @Param("externalIds") Collection<String> externalIds);

    /** 删除某账本的全部交易（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    // ---------------- 余额可重算校验的聚合查询（需求 4.13）----------------

    /** 指定账户上、指定类型(expense/income)的金额合计（跨账本，按账户汇总）；无匹配行返回 0。 */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.accountId = :accountId
              AND t.type = :type
            """)
    BigDecimal sumAmountByAccountIdAndType(
            @Param("accountId") Long accountId, @Param("type") TransactionType type);

    /** 以指定账户为转账<b>目标</b>的转账金额合计（流入，跨账本）；无匹配行返回 0（需求 4.3、4.13）。 */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.destinationAccountId = :accountId
            """)
    BigDecimal sumTransferInByAccountId(@Param("accountId") Long accountId);

    /** 以指定账户为转账<b>源</b>的转账金额合计（流出，跨账本）；无匹配行返回 0（需求 4.3、4.13）。 */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.sourceAccountId = :accountId
            """)
    BigDecimal sumTransferOutByAccountId(@Param("accountId") Long accountId);
}
