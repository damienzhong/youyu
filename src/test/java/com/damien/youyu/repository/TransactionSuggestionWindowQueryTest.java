package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;

/**
 * {@link TransactionRepository#findSuggestionWindowRows} 的窗口投影查询验证（record-suggestion 任务 1.2）。
 *
 * <p>沿用仓库既有 {@code @DataJpaTest} 切片范式（{@link StreakSegmentRepositoryTest} /
 * {@link ReminderRepositoryTest}）：真实 H2（{@code MODE=MySQL}）+ 真实仓储、无 mock，表由 Hibernate
 * 依实体生成。以下断言全是<b>落库事实</b>——账本隔离、软删排除（{@code @SQLRestriction}）、类型过滤
 * （排除 transfer）、{@code BETWEEN} 闭区间的边界开闭——用测试替身会把被测机制删掉，故必须在真实连接上跑。</p>
 *
 * <p>覆盖任务 1.2 的五组口径（需求 2.1、2.4）：</p>
 * <ul>
 *   <li>只返回本账本、未删除、{@code expense}/{@code income}、窗口内的行；</li>
 *   <li>软删行（{@code deleted_at} 非空）被 {@code @SQLRestriction} 排除；</li>
 *   <li>转账（{@code type=transfer}）被类型过滤排除；</li>
 *   <li>其它账本的行不串入当前账本结果；</li>
 *   <li>{@code occurred_at} 边界：窗口起点当日 {@code 00:00:00.000}、终点当日 {@code 23:59:59.999} 纳入，
 *       窗口外（起点前一刻、终点后一刻）排除。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionSuggestionWindowQueryTest {

    /** 当前账本；跨账本隔离断言用另一账本 {@link #OTHER_LEDGER}。 */
    private static final Long LEDGER = 4001L;
    private static final Long OTHER_LEDGER = 4002L;

    /** 窗口闭区间 [FROM, TO]：起点当日 00:00:00.000、终点当日 23:59:59.999（需求 2.4）。 */
    private static final LocalDateTime FROM = LocalDateTime.of(2025, 6, 1, 0, 0, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2025, 6, 30, 23, 59, 59, 999_000_000);

    /** 落库/回读的挂钟基准时间。 */
    private static final LocalDateTime AUDIT = LocalDateTime.of(2025, 6, 15, 12, 0, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TransactionRepository repository;

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    /** 落一笔 expense/income 流水（accountId/categoryId 非空、无 source/destination），返回其 id。 */
    private Long persistRecord(Long ledgerId, TransactionType type, LocalDateTime occurredAt) {
        return persistRecord(ledgerId, type, occurredAt, false);
    }

    /** 落一笔 expense/income 流水，可选软删除（deleted_at 非空）。 */
    private Long persistRecord(Long ledgerId, TransactionType type, LocalDateTime occurredAt, boolean softDeleted) {
        Transaction t = new Transaction();
        t.setUserId(9001L);
        t.setLedgerId(ledgerId);
        t.setCreatedBy(9001L);
        t.setType(type);
        t.setAmount(new BigDecimal("35.00"));
        t.setAccountId(7001L);
        t.setCategoryId(6001L);
        t.setNote("午餐");
        t.setOccurredAt(occurredAt);
        t.setCreatedAt(AUDIT);
        t.setUpdatedAt(AUDIT);
        if (softDeleted) {
            t.setDeletedAt(AUDIT);
        }
        em.persist(t);
        return t.getId();
    }

    /** 落一笔转账流水（type=transfer，含 source/destination，account/category 为空），返回其 id。 */
    private Long persistTransfer(Long ledgerId, LocalDateTime occurredAt) {
        Transaction t = new Transaction();
        t.setUserId(9001L);
        t.setLedgerId(ledgerId);
        t.setCreatedBy(9001L);
        t.setType(TransactionType.TRANSFER);
        t.setAmount(new BigDecimal("100.00"));
        t.setSourceAccountId(7001L);
        t.setDestinationAccountId(7002L);
        t.setOccurredAt(occurredAt);
        t.setCreatedAt(AUDIT);
        t.setUpdatedAt(AUDIT);
        em.persist(t);
        return t.getId();
    }

    private List<Long> queryIds() {
        return repository.findSuggestionWindowRows(LEDGER, FROM, TO)
                .stream().map(SuggestionRow::getId).toList();
    }

    // ---- 只返回本账本、未删除、expense/income、窗口内的行（需求 2.1、2.4） ----

    @Test
    void returnsOnlyLedgerLocalUndeletedExpenseIncomeWithinWindow() {
        Long expense = persistRecord(LEDGER, TransactionType.EXPENSE, AUDIT);
        Long income = persistRecord(LEDGER, TransactionType.INCOME, AUDIT);
        // 干扰项：转账、软删、跨账本、窗口外——都不应入选
        persistTransfer(LEDGER, AUDIT);
        persistRecord(LEDGER, TransactionType.EXPENSE, AUDIT, true);
        persistRecord(OTHER_LEDGER, TransactionType.EXPENSE, AUDIT);
        persistRecord(LEDGER, TransactionType.EXPENSE, FROM.minusNanos(1_000_000));
        persistRecord(LEDGER, TransactionType.INCOME, TO.plusNanos(1_000_000));
        flushAndClear();

        assertThat(queryIds()).containsExactlyInAnyOrder(expense, income);
    }

    // ---- 软删行被 @SQLRestriction 排除（需求 2.1） ----

    @Test
    void excludesSoftDeletedRows() {
        Long alive = persistRecord(LEDGER, TransactionType.EXPENSE, AUDIT, false);
        persistRecord(LEDGER, TransactionType.EXPENSE, AUDIT, true);
        persistRecord(LEDGER, TransactionType.INCOME, AUDIT, true);
        flushAndClear();

        assertThat(queryIds()).containsExactly(alive);
    }

    // ---- 转账被类型过滤排除（需求 2.1） ----

    @Test
    void excludesTransfers() {
        Long expense = persistRecord(LEDGER, TransactionType.EXPENSE, AUDIT);
        persistTransfer(LEDGER, AUDIT);
        persistTransfer(LEDGER, AUDIT.plusHours(1));
        flushAndClear();

        List<SuggestionRow> rows = repository.findSuggestionWindowRows(LEDGER, FROM, TO);
        assertThat(rows).extracting(SuggestionRow::getId).containsExactly(expense);
        assertThat(rows).extracting(SuggestionRow::getType)
                .allMatch(type -> type == TransactionType.EXPENSE || type == TransactionType.INCOME);
    }

    // ---- 跨账本不串（需求 2.1） ----

    @Test
    void doesNotLeakAcrossLedgers() {
        Long mine = persistRecord(LEDGER, TransactionType.EXPENSE, AUDIT);
        persistRecord(OTHER_LEDGER, TransactionType.EXPENSE, AUDIT);
        persistRecord(OTHER_LEDGER, TransactionType.INCOME, AUDIT);
        flushAndClear();

        assertThat(queryIds()).containsExactly(mine);
        // 反向确认：查另一账本只见其自身的两行，且不含本账本的行
        assertThat(repository.findSuggestionWindowRows(OTHER_LEDGER, FROM, TO)).hasSize(2);
    }

    // ---- occurred_at 边界：起点/终点纳入，窗口外排除（需求 2.4） ----

    @Test
    void includesClosedIntervalBoundariesAndExcludesOutside() {
        Long atStart = persistRecord(LEDGER, TransactionType.EXPENSE, FROM);              // == FROM，入选
        Long atEnd = persistRecord(LEDGER, TransactionType.INCOME, TO);                   // == TO，入选
        Long justBefore = persistRecord(LEDGER, TransactionType.EXPENSE, FROM.minusNanos(1_000_000)); // < FROM，排除
        Long justAfter = persistRecord(LEDGER, TransactionType.INCOME, TO.plusNanos(1_000_000));      // > TO，排除
        flushAndClear();

        List<Long> ids = queryIds();
        assertThat(ids).containsExactlyInAnyOrder(atStart, atEnd);
        assertThat(ids).doesNotContain(justBefore, justAfter);
    }

    // ---- 空窗口内无行返回空列表、不报错（需求 2.4 派生） ----

    @Test
    void returnsEmptyWhenNoMatchingRows() {
        persistRecord(LEDGER, TransactionType.EXPENSE, FROM.minusDays(1));
        flushAndClear();

        assertThat(repository.findSuggestionWindowRows(LEDGER, FROM, TO)).isEmpty();
    }
}
