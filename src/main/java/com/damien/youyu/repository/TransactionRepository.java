package com.damien.youyu.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
public interface TransactionRepository extends JpaRepository<Transaction, Long>, TransactionRepositoryCustom {

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

    /** 某账本内某用户最近记的一笔交易（按记账时间倒序），供「上一笔账户」记忆（需求 7.1）。 */
    Optional<Transaction> findFirstByLedgerIdAndCreatedByOrderByCreatedAtDescIdDesc(
            Long ledgerId, Long createdBy);

    /**
     * 引用某账户（作为账户/源/目标）的全部交易，按时间倒序（账户明细：owner 全量视图，跨账本 + 转账）。
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.accountId = :accountId
               OR t.sourceAccountId = :accountId
               OR t.destinationAccountId = :accountId
            ORDER BY t.occurredAt DESC, t.id DESC
            """)
    List<Transaction> findByAccountReferencedOrderByTime(@Param("accountId") Long accountId);

    /**
     * 某账本内引用某账户的交易，按时间倒序（账户明细：协作成员仅见本账本流水，需求 5.2）。
     * 转账 {@code ledger_id} 为空，天然不在此范围内。
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.ledgerId = :ledgerId
              AND (t.accountId = :accountId
               OR t.sourceAccountId = :accountId
               OR t.destinationAccountId = :accountId)
            ORDER BY t.occurredAt DESC, t.id DESC
            """)
    List<Transaction> findByLedgerIdAndAccountReferencedOrderByTime(
            @Param("ledgerId") Long ledgerId, @Param("accountId") Long accountId);

    /** 某账本某项目的全部交易，按时间倒序（项目明细/统计用）。 */
    List<Transaction> findByLedgerIdAndProjectIdOrderByOccurredAtDescIdDesc(Long ledgerId, Long projectId);

    /** 某账本某商家的全部交易，按时间倒序（商家明细/统计用）。 */
    List<Transaction> findByLedgerIdAndMerchantIdOrderByOccurredAtDescIdDesc(Long ledgerId, Long merchantId);

    /** 某账本某标签的全部交易（经关联表），按时间倒序（标签明细/统计用）。 */
    @Query("SELECT t FROM Transaction t WHERE t.ledgerId = :ledgerId AND t.id IN "
            + "(SELECT tt.transactionId FROM TransactionTag tt WHERE tt.tagId = :tagId) "
            + "ORDER BY t.occurredAt DESC, t.id DESC")
    List<Transaction> findByLedgerIdAndTagId(
            @Param("ledgerId") Long ledgerId, @Param("tagId") Long tagId);

    /** 搜索：某账本备注包含关键词（忽略大小写）。 */
    List<Transaction> findByLedgerIdAndNoteContainingIgnoreCase(Long ledgerId, String q);

    /** 搜索：某账本金额等于给定值。 */
    List<Transaction> findByLedgerIdAndAmount(Long ledgerId, BigDecimal amount);

    /** 搜索：某账本、分类在给定集合内。 */
    List<Transaction> findByLedgerIdAndCategoryIdIn(Long ledgerId, Collection<Long> categoryIds);

    /** 搜索：某账本、商家在给定集合内。 */
    List<Transaction> findByLedgerIdAndMerchantIdIn(Long ledgerId, Collection<Long> merchantIds);

    /** 跨多个账本、在半开区间内的交易，按时间倒序（「全部账本」聚合只读视图用）。 */
    List<Transaction> findByLedgerIdInAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
            Collection<Long> ledgerIds, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    /**
     * 跨多个账户、在半开区间 {@code [fromInclusive, toExclusive)} 内的交易
     * （资产现金流「账户维度」只读聚合用，assets-monthly-cashflow 需求 1.9、1.11）。
     *
     * <p>按 {@code account_id ∈ (:accountIds)} 过滤——只取落在「本人拥有账户 id 集合」上的交易，
     * 天然满足「只计本人账户变动」，并使 AA 他人实付（{@code account_id} 为空或他人账户）被排除。
     * 软删除交易（{@code deleted_at} 非空）由 {@link Transaction} 的
     * {@code @SQLRestriction("deleted_at is null")} 自动排除，故此处无需显式写 {@code deleted_at IS NULL}
     * （需求 1.9）。纯只读，不写任何表。</p>
     */
    List<Transaction> findByAccountIdInAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            Collection<Long> accountIds, LocalDateTime fromInclusive, LocalDateTime toExclusive);

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

    /**
     * 某账户是否被「他人（{@code createdBy != userId}）」记的任一交易引用（作为账户/源/目标）。
     * 用于注销协作牵连检查（需求 8.2）：本人账户被协作成员在共享账本中记账引用时，直接注销会孤立他人数据，
     * 故此类引用应拦截注销。{@code userId} 传账户 owner 的用户 id。
     */
    @Query("""
            SELECT COUNT(t) > 0 FROM Transaction t
            WHERE (t.accountId = :accountId
               OR t.sourceAccountId = :accountId
               OR t.destinationAccountId = :accountId)
              AND t.createdBy <> :userId
            """)
    boolean existsByAccountReferencedByOtherUser(
            @Param("accountId") Long accountId, @Param("userId") Long userId);

    /** 某分类是否被该账本的任一交易引用（用于「被引用分类不可删除」校验，需求 5.5）。 */
    boolean existsByLedgerIdAndCategoryId(Long ledgerId, Long categoryId);

    /**
     * 某账户是否在某账本内被任一交易引用（作为账户/源/目标）。
     * 用于取消账户在账本的参与（detach）前的历史提示（需求 3.5）。
     */
    @Query("""
            SELECT COUNT(t) > 0 FROM Transaction t
            WHERE t.ledgerId = :ledgerId
              AND (t.accountId = :accountId
               OR t.sourceAccountId = :accountId
               OR t.destinationAccountId = :accountId)
            """)
    boolean existsByLedgerIdAndAccountReferenced(
            @Param("ledgerId") Long ledgerId, @Param("accountId") Long accountId);

    /**
     * 按「记账人 + 客户端幂等键」定位交易（离线记账幂等去重用）；未命中返回空。
     * 归属键用 {@code createdBy}（交易创建路径实际写入的记账人列），配合唯一约束
     * {@code uk_tx_creator_client_token} 保证同一 client_token 至多一笔。
     */
    Optional<Transaction> findByCreatedByAndClientToken(Long createdBy, String clientToken);

    /** 该账本已存在的第三方账单标识（账单导入去重用）：返回给定候选集中已入库的 external_id。 */
    @Query("SELECT t.externalId FROM Transaction t "
            + "WHERE t.ledgerId = :ledgerId AND t.externalId IN :externalIds")
    List<String> findExistingExternalIds(
            @Param("ledgerId") Long ledgerId, @Param("externalIds") Collection<String> externalIds);

    // ---------------- 记账推荐的窗口投影查询（record-suggestion 需求 2.1、2.4、8.1）----------------

    /**
     * 记账推荐窗口内的只读投影行：某账本、未删除、类型为 {@code expense}/{@code income}、
     * {@code occurred_at ∈ [from, to]}（闭区间）的历史流水（record-suggestion 需求 2.1、2.4）。
     *
     * <p>供 {@code RecordSuggestionService} 拉窗口行、交由 {@code RecordSuggestionRanker} 在内存中
     * 按形态分组/排序/截断。返回接口投影 {@link SuggestionRow}，仅取七项字段，避免整实体加载。</p>
     *
     * <p>三点说明：</p>
     * <ol>
     *   <li>软删除行由 {@link Transaction} 的 {@code @SQLRestriction("deleted_at is null")} 自动排除，
     *       故本 JPQL 无需显式写 {@code deleted_at IS NULL}（需求 2.1）。</li>
     *   <li>类型过滤用 {@link TransactionType} 枚举常量而非字符串字面量：{@code type} 经
     *       {@link com.damien.youyu.domain.TransactionTypeConverter} 转换存储，JPQL 中以枚举常量比较可让
     *       转换器正确生效（对齐既有 {@code sumAmountByAccountIdAndType} 的 {@code t.type = :type} 用法），
     *       从而排除 {@code transfer}（需求 2.1）。</li>
     *   <li>{@code BETWEEN} 为闭区间 inclusive-inclusive，与需求 2.4 的
     *       {@code [当日−29 日 00:00:00.000, 当日 23:59:59.999]} 一致，边界由调用方按 {@code Asia/Shanghai}
     *       计算并传入。</li>
     * </ol>
     *
     * <p>纯只读，不写任何表（需求 8.1）。</p>
     */
    @Query("""
            SELECT t.type AS type, t.amount AS amount, t.categoryId AS categoryId,
                   t.accountId AS accountId, t.note AS note, t.occurredAt AS occurredAt, t.id AS id
            FROM Transaction t
            WHERE t.ledgerId = :ledgerId
              AND t.type IN (com.damien.youyu.domain.TransactionType.EXPENSE,
                             com.damien.youyu.domain.TransactionType.INCOME)
              AND t.occurredAt BETWEEN :from AND :to
            """)
    List<SuggestionRow> findSuggestionWindowRows(@Param("ledgerId") Long ledgerId,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    /** 删除某账本的全部交易（账本删除级联）。 */
    void deleteByLedgerId(Long ledgerId);

    // ---------------- 回收站（软删除，走原生 SQL 绕过 @SQLRestriction）----------------

    /** 按主键+账本定位（含已软删除，供恢复/彻底删除）。 */
    @Query(value = "SELECT * FROM transactions WHERE id = :id AND ledger_id = :ledgerId", nativeQuery = true)
    Optional<Transaction> findRawByIdAndLedgerId(@Param("id") Long id, @Param("ledgerId") Long ledgerId);

    /** 列出某账本回收站记录（已软删除），按删除时间倒序。 */
    @Query(value = "SELECT * FROM transactions WHERE ledger_id = :ledgerId AND deleted_at IS NOT NULL "
            + "ORDER BY deleted_at DESC, id DESC", nativeQuery = true)
    List<Transaction> findDeletedByLedgerId(@Param("ledgerId") Long ledgerId);

    /** 物理删除某账本全部交易（含回收站，账本删除级联用）。 */
    @Modifying
    @Query(value = "DELETE FROM transactions WHERE ledger_id = :ledgerId", nativeQuery = true)
    void hardDeleteByLedgerId(@Param("ledgerId") Long ledgerId);

    /**
     * 某用户名下全部交易 id（含回收站软删记录）。走原生 SQL 绕过 {@code @SQLRestriction}，
     * 供注销级联删除时先收集交易 id、再清理其标签关联（需求 8.3）。
     */
    @Query(value = "SELECT id FROM transactions WHERE user_id = :userId", nativeQuery = true)
    List<Long> findAllIdsByUserId(@Param("userId") Long userId);

    /**
     * 物理删除某用户名下全部交易（含回收站软删记录，注销级联硬删用，需求 8.3、8.5）。
     * 走原生 SQL 绕过 {@code @SQLRestriction}，确保软删副本一并被硬删（不保留可恢复副本）。
     */
    @Modifying
    @Query(value = "DELETE FROM transactions WHERE user_id = :userId", nativeQuery = true)
    void hardDeleteByUserId(@Param("userId") Long userId);

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

    // ---------------- 成长体系的「有效记账交易」聚合（需求 7.1–7.6、7.12、4.6）----------------
    //
    // 「有效记账交易」= created_by 等于该用户 AND deleted_at IS NULL AND type IN ('expense','income')
    // AND ledger_id IS NOT NULL。以下四个查询全部 nativeQuery：本实体带
    // @SQLRestriction("deleted_at is null")，走 JPQL 会让四个条件之一变成 Hibernate 注入的隐式条件，
    // 读代码时看不见口径。代价是必须自己写 deleted_at IS NULL——漏写会把回收站记录算进来。
    // 按 created_by 过滤复用既有单列索引 idx_tx_created_by，本组查询不需要新增任何列与索引（需求 7.12）。

    /**
     * 累计记账笔数（需求 7.2）：四个条件在原生 SQL 里逐条可见。
     *
     * <p>刻意走原生 SQL：本实体带 {@code @SQLRestriction("deleted_at is null")}，走 JPQL 会让软删过滤
     * 变成隐式条件，故这里自己写 {@code deleted_at IS NULL}，漏写会把回收站记录算进来。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM transactions WHERE created_by = :userId "
            + "AND deleted_at IS NULL AND type IN ('expense','income') AND ledger_id IS NOT NULL",
            nativeQuery = true)
    long countValidRecordsByCreatedBy(@Param("userId") Long userId);

    /**
     * 累计支出/收入金额（需求 7.3）：一次查询按 type 分组返回至多两行，每行 {@code [type, sum]}。
     *
     * <p>刻意走原生 SQL：本实体带 {@code @SQLRestriction("deleted_at is null")}，走 JPQL 会让软删过滤
     * 变成隐式条件，故这里自己写 {@code deleted_at IS NULL}，漏写会把回收站记录算进来。</p>
     */
    @Query(value = "SELECT type, COALESCE(SUM(amount), 0) FROM transactions WHERE created_by = :userId "
            + "AND deleted_at IS NULL AND type IN ('expense','income') AND ledger_id IS NOT NULL "
            + "GROUP BY type", nativeQuery = true)
    List<Object[]> sumValidAmountsByCreatedByGroupByType(@Param("userId") Long userId);

    // 追补起点（需求 4.6 查询 A）见 TransactionRepositoryCustom#findEarliestRecordCreatedAt：
    // 它要以 getObject(LocalDateTime.class) 逐字回读 MIN(created_at)（零时区换算，需求 4.16），
    // 故与查询 B 一并走 JdbcTemplate 实现，而非本接口的原生 @Query（后者读成经默认时区换算的 Timestamp）。

    // 追补窗口内的记账日集合（需求 4.6 查询 B）见 TransactionRepositoryCustom#findRecordDatesInWindow：
    // 它必须以 LocalDate 逐字回读 CAST(created_at AS DATE)（零时区换算，需求 4.16），故走
    // JdbcTemplate + getObject(LocalDate.class) 实现（本接口继承 TransactionRepositoryCustom）。
    // 不能用原生 @Query：其标量会被读成经 JVM 默认时区换算的 java.sql.Date，非 Asia/Shanghai 时整日平移。

    // ---------------- 成长体系的「预算达成」月度支出合计（需求 5.11、5.15）----------------

    /**
     * 一批账本在某自然月的月度有效支出合计，按 {@code ledger_id} 分组，每行 {@code [ledger_id, sum]}
     * （{@code GrowthBudgetEvaluator} 的预算达成判定用，需求 5.11、5.15）。
     *
     * <p>口径与 {@code BudgetService.monthExpenses} 逐条对齐（需求 5.11）：按 {@code ledger_id} 过滤、
     * 只计 {@code type='expense'}、排除 {@code deleted_at} 非空、按 {@code occurred_at} 落在半开区间
     * {@code [月首 00:00, 次月首 00:00)} 取值。三点必须点明：</p>
     * <ol>
     *   <li>这里按 <b>{@code occurred_at}</b> 聚合，与记账日历按 {@code created_at} 刻意不同——预算衡量
     *       「这笔钱花在哪个月」，日历衡量「哪天来记账」（需求 5.11 对比需求 4.1）。</li>
     *   <li>过滤条件<b>不复用</b>累计统计那套（{@code countValidRecordsByCreatedBy} 等按 {@code created_by}
     *       跨全部账本；本查询按 {@code ledger_id} 限自有账本），需求 5.13 明确要求两处彼此独立。</li>
     *   <li>用 {@code ledger_id IN (:ledgerIds)} 一次取回全部自有账本、在应用层按账本分组，使预算判定的
     *       读查询数不随账本数增长（需求 5.15）。</li>
     * </ol>
     *
     * <p>刻意走原生 SQL：本实体带 {@code @SQLRestriction("deleted_at is null")}，走 JPQL 会让软删过滤
     * 变成隐式条件，故这里自己写 {@code deleted_at IS NULL}，使「有效支出」口径逐条可见。</p>
     */
    @Query(value = "SELECT ledger_id, COALESCE(SUM(amount), 0) FROM transactions "
            + "WHERE ledger_id IN (:ledgerIds) AND type = 'expense' AND deleted_at IS NULL "
            + "AND occurred_at >= :fromInclusive AND occurred_at < :toExclusive "
            + "GROUP BY ledger_id", nativeQuery = true)
    List<Object[]> sumMonthlyExpenseByLedgerIds(@Param("ledgerIds") Collection<Long> ledgerIds,
                                                @Param("fromInclusive") LocalDateTime fromInclusive,
                                                @Param("toExclusive") LocalDateTime toExclusive);

    // ---------------- 成就系统的「旅行记账笔数」聚合（achievement-system 需求 3.9、3.10、3.11）----------------

    /**
     * 旅行记账笔数 {@code TRAVEL_RECORD_COUNT}：该用户「旅行」分类树下的有效支出笔数
     * （achievement-system 需求 3.9、3.10、3.11）。跨该用户记账的全部账本合并计算，
     * 不按会话账本或 {@code X-Ledger-Id} 头过滤。
     *
     * <p>四点说明，改动本查询前必须逐条读完：</p>
     * <ol>
     *   <li><b>归属只认 {@code t.created_by}</b>，刻意<b>不用 {@code t.user_id}</b>——后者是 {@code V9}
     *       之后的历史遗留列、可空，用它会漏计协作账本里的交易（需求 3.10）。</li>
     *   <li>分类名称用 {@code TRIM(name) = '旅行'} <b>逐字符相等</b>判定，<b>绝不用
     *       {@code LIKE '%旅行%'}</b> 或任何前缀/包含/模糊匹配：「旅行保险」「旅行装备」不该算进旅行达人
     *       （需求 3.9）。</li>
     *   <li>{@code kind} 用普通 {@code =} 比较，<b>刻意不加 {@code COLLATE utf8mb4_bin}</b>：
     *       {@code ck_categories_kind} 的取值集合是 {@code ('EXPENSE','INCOME')}、应用写入路径只写大写，
     *       因此库里只有大写两种取值，普通 {@code =} 与区分大小写比较结果逐例相同；而加了 COLLATE 会让
     *       这条查询在 H2（{@code MODE=MySQL}）测试库直接报错，代价远大于收益。
     *       「旅行」是汉字，无大小写之分。</li>
     *   <li>与 {@code categories} 是 <b>1:1 join</b>（{@code c.id = t.category_id} 命中至多一行、
     *       {@code p.id = c.parent_id} 亦然），因此<b>同一交易至多被计 1 次</b>；又因 {@code categories}
     *       只有一层 {@code parent_id}（层级上界 2），一次 {@code LEFT JOIN} 父分类即同时覆盖
     *       「父分类自身的交易」与「子分类的交易」两种情形，<b>不需要递归 CTE</b>（需求 3.9）。</li>
     * </ol>
     *
     * <p>不要求分类的 {@code user_id} 等于该用户 id（协作账本内的分类归账本所有者）。
     * 本查询只读，不新增任何列与索引。</p>
     *
     * <p>刻意走原生 SQL：本实体带 {@code @SQLRestriction("deleted_at is null")}，走 JPQL 会让软删过滤
     * 变成隐式条件，故这里自己写 {@code deleted_at IS NULL}，使「有效支出」口径逐条可见。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM transactions t "
            + "JOIN categories c ON c.id = t.category_id "
            + "LEFT JOIN categories p ON p.id = c.parent_id "
            + "WHERE t.created_by = :userId AND t.type = 'expense' AND t.deleted_at IS NULL "
            + "AND t.ledger_id IS NOT NULL "
            + "AND ((c.kind = 'EXPENSE' AND TRIM(c.name) = '旅行') "
            + "  OR (p.kind = 'EXPENSE' AND TRIM(p.name) = '旅行'))", nativeQuery = true)
    long countTravelExpenses(@Param("userId") Long userId);

    // ---------------- 成就系统的「储蓄月」按月份 × 类型分组金额合计（achievement-system 需求 4.6、4.7、4.11）----------------

    /**
     * 回看窗口内的月度金额合计，按「年 × 月 × 交易类型」分组，每行
     * {@code [year, month, type, sum]}（{@code GrowthSavingMonthEvaluator} 的储蓄月判定用，
     * achievement-system 需求 4.6、4.7、4.11）。跨该用户记账的全部账本合并计算，
     * 不按会话账本或 {@code X-Ledger-Id} 头过滤。
     *
     * <p>三点说明，改动本查询前必须逐条读完：</p>
     * <ol>
     *   <li>分组用 {@code YEAR(occurred_at)} / {@code MONTH(occurred_at)}，<b>刻意不用
     *       {@code DATE_FORMAT}</b>：前者在 MySQL 与 H2（{@code MODE=MySQL}）上行为一致，
     *       后者在 H2 上的支持随版本漂移，用它会让同一条 SQL 在生产库与测试库上给出不同结果
     *       （或直接在测试库报错）。</li>
     *   <li>{@code type IN ('expense','income')} 顺带把 {@code transfer} 排除在两项合计之外
     *       （需求 4.7 的「有效记账交易」口径：另两条排除是 {@code deleted_at IS NULL} 与
     *       {@code ledger_id IS NOT NULL}）。</li>
     *   <li>一条查询覆盖 <b>3 个回看月 × 2 个类型</b>共至多 6 组，<b>不按月循环</b>——这是需求 4.11
     *       「单次结算内成就侧新增读查询不超过 3 条」的组成部分（另两条是协作成员数与旅行记账笔数）。</li>
     * </ol>
     *
     * <p>月份归属用半开区间 {@code occurred_at ∈ [fromInclusive, toExclusive)}：恰好落在
     * {@code toExclusive} 的交易归下一月，由调用方把边界取到「月首 00:00:00.000」（需求 4.6）。
     * 归属只认 {@code occurred_at}，<b>不用 {@code created_at}</b>——后者是记账日历的口径，两者刻意不同。
     * 归属只认 {@code created_by}，<b>不用 {@code t.user_id}</b>（{@code V9} 之后的历史遗留列、可空）。</p>
     *
     * <p>某个月在结果中缺行即表示该月该类型无有效交易，调用方按 {@code 0.00} 计（需求 4.4）。
     * 本查询只读，不新增任何列与索引。</p>
     *
     * <p>刻意走原生 SQL：本实体带 {@code @SQLRestriction("deleted_at is null")}，走 JPQL 会让软删过滤
     * 变成隐式条件，故这里自己写 {@code deleted_at IS NULL}，使「有效记账交易」口径逐条可见。</p>
     */
    @Query(value = "SELECT YEAR(occurred_at), MONTH(occurred_at), type, COALESCE(SUM(amount), 0) "
            + "FROM transactions WHERE created_by = :userId "
            + "AND deleted_at IS NULL AND ledger_id IS NOT NULL "
            + "AND type IN ('expense','income') "
            + "AND occurred_at >= :fromInclusive AND occurred_at < :toExclusive "
            + "GROUP BY YEAR(occurred_at), MONTH(occurred_at), type", nativeQuery = true)
    List<Object[]> sumMonthlyAmountsByCreatedByGroupByMonthAndType(
            @Param("userId") Long userId,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive);
}
