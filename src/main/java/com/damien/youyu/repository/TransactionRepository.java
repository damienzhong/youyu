package com.damien.youyu.repository;

import java.time.LocalDateTime;
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
 * 交易仓库。所有查询方法固定携带 {@code userId} 过滤，保证多租户隔离（需求 2.3）。
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /** 分页列出某用户交易，按时间倒序（需求 2.3）。 */
    Page<Transaction> findByUserIdOrderByOccurredAtDescIdDesc(Long userId, Pageable pageable);

    /** 列出某用户全部交易（导出/重算用）。 */
    List<Transaction> findByUserId(Long userId);

    /**
     * 以流式方式按 id 升序读取某用户全部交易，供数据导出「流式写出、避免全量载入内存」使用
     * （需求 8.1、8.2；上界 10 万条量级见任务 8.3）。
     *
     * <p>调用方必须在开启的（只读）事务内消费并以 try-with-resources 关闭该 {@link Stream}，
     * 否则底层游标/结果集不会释放。</p>
     */
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId ORDER BY t.id ASC")
    Stream<Transaction> streamByUserIdOrderById(@Param("userId") Long userId);

    /** 按主键 + 归属用户定位交易；不匹配返回空（需求 2.4，越权返回 404/403）。 */
    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    /** 某用户在 [from, to] 时间范围内的交易（inclusive-inclusive，兼容既有调用）。 */
    List<Transaction> findByUserIdAndOccurredAtBetween(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 某用户在半开区间 [fromInclusive, toExclusive) 内的交易（报表聚合用，需求 7.1、7.2、7.4）。
     *
     * <p>自然月/趋势按 {@code Asia/Shanghai} 边界聚合时需「当月 1 日 00:00:00(含) 至次月 1 日 00:00:00(不含)」，
     * 故使用半开区间避免落在边界零点的交易被重复计入相邻区间。</p>
     */
    List<Transaction> findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            Long userId, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    /** 某用户最近一笔交易，供快速记账默认账户选择（需求 6.1）。 */
    Optional<Transaction> findFirstByUserIdOrderByOccurredAtDescIdDesc(Long userId);

    /**
     * 某账户是否被该用户的任一交易引用（作为普通账户、转账源或转账目标）。
     * 用于「有交易的账户不可删除」校验（需求 3.7）。
     */
    @Query("""
            SELECT COUNT(t) > 0 FROM Transaction t
            WHERE t.userId = :userId
              AND (t.accountId = :accountId
                   OR t.sourceAccountId = :accountId
                   OR t.destinationAccountId = :accountId)
            """)
    boolean existsByUserIdAndAccountReferenced(
            @Param("userId") Long userId, @Param("accountId") Long accountId);

    /** 某分类是否被该用户的任一交易引用（用于「被引用分类不可删除」校验，需求 5.5）。 */
    boolean existsByUserIdAndCategoryId(Long userId, Long categoryId);

    // ---------------- 余额可重算校验的聚合查询（需求 4.13）----------------
    //
    // 余额守恒不变式（Property 1）：
    //   current_balance == initial_balance
    //                      + Σ收入(该账户为 account)
    //                      − Σ支出(该账户为 account)
    //                      + Σ转账(该账户为 destination)
    //                      − Σ转账(该账户为 source)
    // 下列聚合查询以 BigDecimal 精确求和，无匹配行时经 COALESCE 归零，供
    // AccountService.recomputeBalance 汇总重算与对账使用。金额一律 DECIMAL(18,2)/BigDecimal。

    /**
     * 某用户在指定账户上、指定类型(expense/income)的金额合计；无匹配行返回 0。
     * 转账不以 accountId 归属，故本查询用于收入/支出方向（需求 4.1、4.2、4.13）。
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.userId = :userId
              AND t.accountId = :accountId
              AND t.type = :type
            """)
    BigDecimal sumAmountByUserIdAndAccountIdAndType(
            @Param("userId") Long userId,
            @Param("accountId") Long accountId,
            @Param("type") TransactionType type);

    /**
     * 某用户以指定账户为转账<b>目标</b>的转账金额合计（流入）；无匹配行返回 0（需求 4.3、4.13）。
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.userId = :userId
              AND t.destinationAccountId = :accountId
            """)
    BigDecimal sumTransferInByUserIdAndAccountId(
            @Param("userId") Long userId, @Param("accountId") Long accountId);

    /**
     * 某用户以指定账户为转账<b>源</b>的转账金额合计（流出）；无匹配行返回 0（需求 4.3、4.13）。
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.userId = :userId
              AND t.sourceAccountId = :accountId
            """)
    BigDecimal sumTransferOutByUserIdAndAccountId(
            @Param("userId") Long userId, @Param("accountId") Long accountId);
}
