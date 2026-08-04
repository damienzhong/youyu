package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.UserGrowth;

/**
 * 成长数据层的映射与查询验证（H2，表由 Hibernate 依实体生成，Flyway 关闭）。
 *
 * <p>覆盖任务 1.9 的口径（需求 11.17、10.3、12.11、7.2、7.4、7.5、7.6）：</p>
 * <ul>
 *   <li>{@code user_growth}（恰好 10 列）、{@code growth_events}（恰好 6 列）实体↔表结构一致；</li>
 *   <li>{@code UserGrowth} 以显式 {@code userId} 保存后可按主键读回（主键不带 {@code @GeneratedValue}）；</li>
 *   <li>{@code sumExpByUserId} 无行时返回 0（{@code COALESCE} 而非 {@code null}）；</li>
 *   <li>{@code findDailyRecordKeys} 的键升序等于日期升序（{@code YYYY-MM-DD} 字典序即日期序）；</li>
 *   <li>两个 {@code deleteByUserId} 在无行时影响行数为 0 且不抛错；</li>
 *   <li>四个「有效记账交易」聚合查询的口径：软删 / {@code transfer} / {@code ledger_id} 为 NULL
 *       三类行一律不计入。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GrowthRepositoryMappingTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private UserGrowthRepository userGrowthRepository;

    @Autowired
    private GrowthEventRepository growthEventRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /** 让后续读取一定回库，避免持久化上下文里的旧实体掩盖实际的映射与写入效果。 */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @SuppressWarnings("unchecked")
    private List<String> columnNamesOf(String table) {
        return em.getEntityManager()
                .createNativeQuery("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = ?1 ORDER BY COLUMN_NAME")
                .setParameter(1, table)
                .getResultList();
    }

    private UserGrowth newGrowth(long userId, long exp, int level) {
        UserGrowth g = new UserGrowth();
        g.setUserId(userId);
        g.setExp(exp);
        g.setLevel(level);
        g.setTotalRecordDays(3);
        g.setCurrentStreakDays(2);
        g.setMaxStreakDays(5);
        g.setLastRecordDate(LocalDate.of(2025, 6, 1));
        g.setLastSettledAt(BASE);
        g.setCreatedAt(BASE);
        g.setUpdatedAt(BASE);
        return g;
    }

    private GrowthEvent newEvent(long userId, String type, String key, int exp) {
        GrowthEvent e = new GrowthEvent();
        e.setUserId(userId);
        e.setEventType(type);
        e.setEventKey(key);
        e.setExpAmount(exp);
        e.setCreatedAt(BASE);
        return e;
    }

    private Transaction newTx(Long createdBy, Long ledgerId, TransactionType type,
            String amount, LocalDateTime createdAt, LocalDateTime deletedAt) {
        Transaction t = new Transaction();
        t.setUserId(createdBy);
        t.setLedgerId(ledgerId);
        t.setCreatedBy(createdBy);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setOccurredAt(createdAt);
        t.setCreatedAt(createdAt);
        t.setUpdatedAt(createdAt);
        t.setDeletedAt(deletedAt);
        return transactionRepository.save(t);
    }

    // ---- 实体↔表结构一致（需求 11.17） ----

    @Test
    void userGrowthTableHasExactlyTenMappedColumns() {
        assertThat(columnNamesOf("USER_GROWTH")).containsExactlyInAnyOrder(
                "USER_ID", "EXP", "LEVEL", "TOTAL_RECORD_DAYS", "CURRENT_STREAK_DAYS",
                "MAX_STREAK_DAYS", "LAST_RECORD_DATE", "LAST_SETTLED_AT", "CREATED_AT", "UPDATED_AT");
    }

    @Test
    void growthEventsTableHasExactlySixMappedColumns() {
        assertThat(columnNamesOf("GROWTH_EVENTS")).containsExactlyInAnyOrder(
                "ID", "USER_ID", "EVENT_TYPE", "EVENT_KEY", "EXP_AMOUNT", "CREATED_AT");
    }

    // ---- 以显式 userId 保存后按主键读回（需求 11.17） ----

    @Test
    void userGrowthPersistsWithExplicitUserIdAndRoundTripsAllColumns() {
        userGrowthRepository.save(newGrowth(4001L, 234L, 24));
        flushAndClear();

        // 主键即写入的 userId（不带 @GeneratedValue，库不改写它）
        UserGrowth reloaded = userGrowthRepository.findById(4001L).orElseThrow();
        assertThat(reloaded.getUserId()).isEqualTo(4001L);
        assertThat(reloaded.getExp()).isEqualTo(234L);
        assertThat(reloaded.getLevel()).isEqualTo(24);
        assertThat(reloaded.getTotalRecordDays()).isEqualTo(3);
        assertThat(reloaded.getCurrentStreakDays()).isEqualTo(2);
        assertThat(reloaded.getMaxStreakDays()).isEqualTo(5);
        assertThat(reloaded.getLastRecordDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(reloaded.getLastSettledAt()).isEqualTo(BASE);
        assertThat(reloaded.getCreatedAt()).isEqualTo(BASE);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(BASE);

        // 主键落库即写入值
        Object rawId = em.getEntityManager()
                .createNativeQuery("SELECT user_id FROM user_growth WHERE user_id = ?1")
                .setParameter(1, 4001L)
                .getSingleResult();
        assertThat(((Number) rawId).longValue()).isEqualTo(4001L);
    }

    @Test
    void growthEventRoundTripsAllColumns() {
        GrowthEvent saved = growthEventRepository.save(
                newEvent(4002L, GrowthEventType.BADGE, "BADGE:RECORD_100", 0));
        flushAndClear();

        GrowthEvent reloaded = growthEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getId()).isNotNull();
        assertThat(reloaded.getUserId()).isEqualTo(4002L);
        assertThat(reloaded.getEventType()).isEqualTo(GrowthEventType.BADGE);
        assertThat(reloaded.getEventKey()).isEqualTo("BADGE:RECORD_100");
        assertThat(reloaded.getExpAmount()).isEqualTo(0);
        assertThat(reloaded.getCreatedAt()).isEqualTo(BASE);
    }

    // ---- sumExpByUserId 无行时返回 0 ----

    @Test
    void sumExpReturnsZeroWhenNoRows() {
        // 从未写入任何事件的用户
        assertThat(growthEventRepository.sumExpByUserId(9999L)).isZero();

        // 有事件时为 exp_amount 之和（数据库聚合）
        growthEventRepository.save(newEvent(4003L, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", 5));
        growthEventRepository.save(newEvent(4003L, GrowthEventType.DAILY_RECORD, "DAILY_RECORD:2025-06-01", 5));
        growthEventRepository.save(newEvent(4003L, GrowthEventType.BADGE, "BADGE:RECORD_100", 0));
        flushAndClear();
        assertThat(growthEventRepository.sumExpByUserId(4003L)).isEqualTo(10L);
    }

    // ---- findDailyRecordKeys 的键升序等于日期升序（需求 10.3、12.11 读取口径） ----

    @Test
    void findDailyRecordKeysReturnedAscendingEqualsDateAscending() {
        long userId = 4004L;
        // 刻意乱序插入，含跨月、跨年
        List<String> insertedDates = List.of(
                "2025-06-03", "2025-06-01", "2025-06-10", "2025-05-28", "2024-12-31", "2025-01-01");
        for (String d : insertedDates) {
            growthEventRepository.save(
                    newEvent(userId, GrowthEventType.DAILY_RECORD, "DAILY_RECORD:" + d, 5));
        }
        // 混入非 DAILY_RECORD 事件，确认查询只取 DAILY_RECORD
        growthEventRepository.save(newEvent(userId, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", 5));
        growthEventRepository.save(newEvent(userId, GrowthEventType.BADGE, "BADGE:RECORD_100", 0));
        flushAndClear();

        List<String> keys = growthEventRepository.findDailyRecordKeys(userId);

        // 只含 DAILY_RECORD 且条数正确
        assertThat(keys).hasSize(insertedDates.size());
        assertThat(keys).allMatch(k -> k.startsWith("DAILY_RECORD:"));

        // 键升序
        assertThat(keys).isSorted();

        // 键升序 == 日期升序：把键解析成日期后排序，与直接对键排序结果逐项一致
        List<LocalDate> datesFromKeys = new ArrayList<>();
        for (String k : keys) {
            datesFromKeys.add(LocalDate.parse(k.substring("DAILY_RECORD:".length())));
        }
        List<LocalDate> expectedDatesAscending = new ArrayList<>(datesFromKeys);
        expectedDatesAscending.sort(Comparator.naturalOrder());
        assertThat(datesFromKeys).containsExactlyElementsOf(expectedDatesAscending);
    }

    // ---- 两个 deleteByUserId 无行时影响 0 且不抛错（需求 12.11） ----

    @Test
    void deleteByUserIdAffectsZeroRowsWhenNoRowsAndDoesNotThrow() {
        assertThatCode(() -> {
            assertThat(userGrowthRepository.deleteByUserId(123456L)).isZero();
            assertThat(growthEventRepository.deleteByUserId(123456L)).isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    void deleteByUserIdRemovesOnlyTargetUsersRows() {
        userGrowthRepository.save(newGrowth(4005L, 10L, 2));
        growthEventRepository.save(newEvent(4005L, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", 5));
        growthEventRepository.save(newEvent(4005L, GrowthEventType.DAILY_RECORD, "DAILY_RECORD:2025-06-01", 5));
        userGrowthRepository.save(newGrowth(4006L, 5L, 1));
        growthEventRepository.save(newEvent(4006L, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", 5));
        flushAndClear();

        assertThat(growthEventRepository.deleteByUserId(4005L)).isEqualTo(2);
        assertThat(userGrowthRepository.deleteByUserId(4005L)).isEqualTo(1);
        flushAndClear();

        // 目标用户清空，其它用户不受影响
        assertThat(userGrowthRepository.findById(4005L)).isEmpty();
        assertThat(growthEventRepository.countByUserId(4005L)).isZero();
        assertThat(userGrowthRepository.findById(4006L)).isPresent();
        assertThat(growthEventRepository.countByUserId(4006L)).isEqualTo(1);
    }

    // ---- 四个交易聚合查询的口径：软删 / transfer / ledger_id 为 NULL 一律不计入 ----
    //      （需求 7.2 笔数、7.4/7.5/7.6 有效记账交易的四个条件）

    @Test
    void transactionAggregatesExcludeSoftDeletedTransferAndNullLedgerRows() {
        long userId = 5001L;
        long ledgerId = 7001L;

        // 有效：expense 两笔 + income 一笔
        newTx(userId, ledgerId, TransactionType.EXPENSE, "10.00", BASE.plusDays(1), null);
        newTx(userId, ledgerId, TransactionType.EXPENSE, "20.50", BASE.plusDays(2), null);
        newTx(userId, ledgerId, TransactionType.INCOME, "100.00", BASE.plusDays(3), null);

        // 应被排除的三类：
        // 1) 软删（deleted_at 非空）
        newTx(userId, ledgerId, TransactionType.EXPENSE, "999.00", BASE.plusDays(4), BASE.plusDays(5));
        // 2) transfer 类型
        newTx(userId, ledgerId, TransactionType.TRANSFER, "888.00", BASE.plusDays(6), null);
        // 3) ledger_id 为 NULL
        newTx(userId, null, TransactionType.EXPENSE, "777.00", BASE.plusDays(7), null);
        // 另一用户的有效记账，验证按 created_by 过滤
        newTx(9002L, ledgerId, TransactionType.EXPENSE, "5.00", BASE.plusDays(1), null);
        flushAndClear();

        // 7.2 笔数：只数三笔有效记账
        assertThat(transactionRepository.countValidRecordsByCreatedBy(userId)).isEqualTo(3);

        // 7.3 分组金额：expense=30.50、income=100.00，且不含被排除的行
        List<Object[]> sums = transactionRepository.sumValidAmountsByCreatedByGroupByType(userId);
        BigDecimal expenseSum = null;
        BigDecimal incomeSum = null;
        for (Object[] row : sums) {
            String type = (String) row[0];
            BigDecimal sum = (BigDecimal) row[1];
            if ("expense".equals(type)) {
                expenseSum = sum;
            } else if ("income".equals(type)) {
                incomeSum = sum;
            }
        }
        assertThat(sums).hasSize(2);
        assertThat(expenseSum).isEqualByComparingTo("30.50");
        assertThat(incomeSum).isEqualByComparingTo("100.00");

        // 4.6 查询 A：最早的有效记账 created_at（下界为 null 时不加时间下界）
        assertThat(transactionRepository.findEarliestRecordCreatedAt(userId, null))
                .isEqualTo(BASE.plusDays(1));
        // 有下界时，只取 >= 下界的最早有效记账
        assertThat(transactionRepository.findEarliestRecordCreatedAt(userId, BASE.plusDays(2)))
                .isEqualTo(BASE.plusDays(2));

        // 4.6 查询 B：窗口内 distinct 记账日，排除软删/transfer/ledger 为 NULL。
        // 仓储以 LocalDate 逐字回读（getObject(LocalDate.class)，零时区换算，需求 4.16）。
        List<LocalDate> localDates = transactionRepository.findRecordDatesInWindow(
                userId, BASE.minusDays(10), BASE.plusDays(100));
        assertThat(localDates).containsExactly(
                BASE.plusDays(1).toLocalDate(),
                BASE.plusDays(2).toLocalDate(),
                BASE.plusDays(3).toLocalDate());
        // 被排除行对应的日期（+4/+6/+7）不出现
        assertThat(localDates).doesNotContain(
                BASE.plusDays(4).toLocalDate(),
                BASE.plusDays(6).toLocalDate(),
                BASE.plusDays(7).toLocalDate());
    }

    @Test
    void transactionAggregatesReturnEmptyOrZeroWhenNoValidRows() {
        long userId = 5002L;
        // 只有会被排除的行
        newTx(userId, 7002L, TransactionType.TRANSFER, "50.00", BASE.plusDays(1), null);
        newTx(userId, null, TransactionType.EXPENSE, "60.00", BASE.plusDays(2), null);
        newTx(userId, 7002L, TransactionType.EXPENSE, "70.00", BASE.plusDays(3), BASE.plusDays(4));
        flushAndClear();

        assertThat(transactionRepository.countValidRecordsByCreatedBy(userId)).isZero();
        assertThat(transactionRepository.sumValidAmountsByCreatedByGroupByType(userId)).isEmpty();
        assertThat(transactionRepository.findEarliestRecordCreatedAt(userId, null)).isNull();
        assertThat(transactionRepository.findRecordDatesInWindow(
                userId, BASE.minusDays(10), BASE.plusDays(100))).isEmpty();
    }
}
