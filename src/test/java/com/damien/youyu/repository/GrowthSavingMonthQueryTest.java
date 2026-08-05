package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.service.GrowthSavingMonthEvaluator;

/**
 * 储蓄月判定里「写在 SQL 里、mock 无从验证」的两组口径，在真实 H2 上验证（关联需求 4.6、4.7）：
 *
 * <ul>
 *   <li><b>三条排除</b>：{@code deleted_at} 非空的行、{@code ledger_id} 为 NULL 的行与
 *       {@code type = 'transfer'} 的行都不计入月度收入合计与月度支出合计；归属只认
 *       {@code created_by}（需求 4.7）；</li>
 *   <li><b>逐笔交易的月份归属</b>：{@code occurred_at} 落在半开区间
 *       [该月 1 日 00:00:00.000, 次月 1 日 00:00:00.000)；恰好等于次月 1 日 00:00:00.000 的交易归次月、
 *       等于本月 1 日 00:00:00.000 的交易归本月（需求 4.6）。</li>
 * </ul>
 *
 * <p>沿用 {@link AchievementRepositoryMappingTest} 的范式（{@code @DataJpaTest} + 真实 H2 + 真实仓储，
 * 无 mock）。{@link GrowthSavingMonthEvaluator} 在这里<b>手工 new</b> 而不是从上下文注入：
 * {@code @DataJpaTest} 不装配 {@code @Component}，而判定逻辑本身只依赖这一个仓储，手工构造既省一层
 * 上下文配置，又让「同一条 SQL + 同一段算术」端到端跑通。算术与窗口的边界用例在
 * {@code com.damien.youyu.service.GrowthSavingMonthEvaluatorTest} 里用 mock 覆盖，两处不重复。</p>
 *
 * <p>结算日固定取 {@code 2025-06-15}，窗口为 {@code [2025-03-01T00:00, 2025-06-01T00:00)}，
 * 回看 {@code 2025-03/04/05}。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GrowthSavingMonthQueryTest {

    private static final LocalDate SETTLE_DATE = LocalDate.of(2025, 6, 15);
    private static final LocalDateTime WINDOW_FROM = LocalDateTime.of(2025, 3, 1, 0, 0, 0, 0);
    private static final LocalDateTime WINDOW_TO = LocalDateTime.of(2025, 6, 1, 0, 0, 0, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TransactionRepository transactionRepository;

    private GrowthSavingMonthEvaluator evaluator() {
        return new GrowthSavingMonthEvaluator(transactionRepository);
    }

    // ---- 三条排除与归属口径（需求 4.7） ----

    @Test
    void softDeletedLedgerlessAndTransferRowsAreExcludedFromBothSums() {
        long userId = 7101L;
        long otherUserId = 7102L;
        Long ledgerId = newLedger(userId).getId();

        LocalDateTime may = LocalDateTime.of(2025, 5, 10, 9, 0, 0);
        // 计入：收入 1000.00、支出 500.00 → 结余 500.00 ≥ 门槛 200.00
        newTx(userId, ledgerId, TransactionType.INCOME, "1000.00", may, null);
        newTx(userId, ledgerId, TransactionType.EXPENSE, "500.00", may, null);

        // 排除 1：软删（deleted_at 非空）——收入与支出各一笔
        newTx(userId, ledgerId, TransactionType.INCOME, "5000.00", may, may.plusDays(1));
        newTx(userId, ledgerId, TransactionType.EXPENSE, "5000.00", may, may.plusDays(1));
        // 排除 2：ledger_id 为 NULL
        newTx(userId, null, TransactionType.INCOME, "5000.00", may, null);
        newTx(userId, null, TransactionType.EXPENSE, "5000.00", may, null);
        // 排除 3：type = 'transfer'
        newTx(userId, ledgerId, TransactionType.TRANSFER, "5000.00", may, null);
        // 归属只认 created_by：他人的交易与本人两项合计无关
        newTx(otherUserId, ledgerId, TransactionType.INCOME, "5000.00", may, null);
        newTx(otherUserId, ledgerId, TransactionType.EXPENSE, "5000.00", may, null);
        flushAndClear();

        // 分组结果只剩两行：收入 1000.00、支出 500.00
        Map<String, BigDecimal> sums = sumsByType(userId);
        assertThat(sums).hasSize(2);
        assertThat(sums.get("2025-05|income")).isEqualByComparingTo("1000.00");
        assertThat(sums.get("2025-05|expense")).isEqualByComparingTo("500.00");

        // 任一类被排除的支出若计入，结余都会转负、该月就不再是储蓄月，故这条断言同时锁死三条排除。
        assertThat(evaluator().savingMonths(userId, SETTLE_DATE, Set.of())).containsExactly("2025-05");
    }

    /** 只有被排除的行时两项合计均缺行，按 0.00 计，不是储蓄月（需求 4.4、4.7）。 */
    @Test
    void onlyExcludedRowsYieldsNoSumsAndNoSavingMonth() {
        long userId = 7103L;
        Long ledgerId = newLedger(userId).getId();
        LocalDateTime april = LocalDateTime.of(2025, 4, 20, 8, 0, 0);

        newTx(userId, ledgerId, TransactionType.INCOME, "9000.00", april, april.plusDays(1)); // 软删
        newTx(userId, null, TransactionType.INCOME, "9000.00", april, null); // 无账本
        newTx(userId, ledgerId, TransactionType.TRANSFER, "9000.00", april, null); // 转账
        flushAndClear();

        assertThat(sumsByType(userId)).isEmpty();
        assertThat(evaluator().savingMonths(userId, SETTLE_DATE, Set.of())).isEmpty();
    }

    // ---- 逐笔交易的月份归属（需求 4.6） ----

    /**
     * {@code occurred_at} 恰好等于次月 1 日 {@code 00:00:00.000} 的交易归<b>次月</b>；
     * 恰好等于本月 1 日 {@code 00:00:00.000} 的交易归<b>本月</b>。
     *
     * <p>判别性在支出那一笔上：它落在 {@code 2025-06-01T00:00:00.000}，若被归到 5 月，
     * 5 月结余就是 {@code 100.00} &lt; 门槛 {@code 200.00}，5 月不会出现在结果里。它同时也在窗口右边界上，
     * 因此连查询都不该取回这一行。</p>
     */
    @Test
    void transactionAtNextMonthFirstInstantBelongsToNextMonth() {
        long userId = 7201L;
        Long ledgerId = newLedger(userId).getId();

        // 本月 1 日 00:00:00.000 → 归本月（5 月）
        newTx(userId, ledgerId, TransactionType.INCOME, "1000.00",
                LocalDateTime.of(2025, 5, 1, 0, 0, 0, 0), null);
        // 次月 1 日 00:00:00.000 → 归次月（6 月），落在窗口右边界之外
        newTx(userId, ledgerId, TransactionType.EXPENSE, "900.00",
                LocalDateTime.of(2025, 6, 1, 0, 0, 0, 0), null);
        flushAndClear();

        Map<String, BigDecimal> sums = sumsByType(userId);
        assertThat(sums).containsOnlyKeys("2025-05|income");
        assertThat(sums.get("2025-05|income")).isEqualByComparingTo("1000.00");

        assertThat(evaluator().savingMonths(userId, SETTLE_DATE, Set.of())).containsExactly("2025-05");
    }

    /**
     * 自然月最后一刻的交易归<b>本月</b>：4 月 30 日 {@code 23:59:59} 的支出使 4 月结余
     * {@code 100.00} &lt; 门槛 {@code 200.00}，故 4 月不是储蓄月，而 5 月照常成立。
     *
     * <p>末刻刻意取到<b>秒</b>而非 {@code .999}：生产库的 {@code transactions.occurred_at} 是不带小数秒的
     * {@code DATETIME}，{@code .999} 在 MySQL 上会被进位成次日 {@code 00:00:00}，写成 {@code .999}
     * 的用例只在 H2 上成立、无法反映生产行为。毫秒级右边界本身由上一个用例（整点边界，两种精度下逐字相同）
     * 覆盖。</p>
     */
    @Test
    void transactionAtLastInstantOfMonthBelongsToThatMonth() {
        long userId = 7202L;
        Long ledgerId = newLedger(userId).getId();

        newTx(userId, ledgerId, TransactionType.INCOME, "1000.00",
                LocalDateTime.of(2025, 4, 15, 12, 0, 0), null);
        newTx(userId, ledgerId, TransactionType.EXPENSE, "900.00",
                LocalDateTime.of(2025, 4, 30, 23, 59, 59), null);
        // 5 月：收入 1000.00、无支出 → 储蓄月，用来证明判定确实跑到了
        newTx(userId, ledgerId, TransactionType.INCOME, "1000.00",
                LocalDateTime.of(2025, 5, 20, 12, 0, 0), null);
        flushAndClear();

        Map<String, BigDecimal> sums = sumsByType(userId);
        assertThat(sums.get("2025-04|expense")).isEqualByComparingTo("900.00");
        assertThat(sums.get("2025-04|income")).isEqualByComparingTo("1000.00");

        List<String> months = evaluator().savingMonths(userId, SETTLE_DATE, Set.of());
        assertThat(months).containsExactly("2025-05");
        assertThat(months).doesNotContain("2025-04");
    }

    // ---- 辅助 ----

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    /** 把分组查询结果收敛为 {@code "YYYY-MM|type" -> 合计} 便于逐项断言。 */
    private Map<String, BigDecimal> sumsByType(long userId) {
        List<Object[]> rows = transactionRepository
                .sumMonthlyAmountsByCreatedByGroupByMonthAndType(userId, WINDOW_FROM, WINDOW_TO);
        Map<String, BigDecimal> sums = new HashMap<>();
        for (Object[] row : rows) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            String key = String.format("%04d-%02d|%s", year, month, row[2]);
            sums.put(key, new BigDecimal(row[3].toString()));
        }
        return sums;
    }

    private Ledger newLedger(long userId) {
        Ledger l = new Ledger();
        l.setUserId(userId);
        l.setName("储蓄月账本 " + userId);
        l.setType(Ledger.TYPE_COLLABORATIVE);
        l.setCreatedAt(LocalDateTime.of(2025, 3, 1, 0, 0, 0));
        l.setUpdatedAt(LocalDateTime.of(2025, 3, 1, 0, 0, 0));
        em.persist(l);
        em.flush();
        return l;
    }

    private Transaction newTx(Long createdBy, Long ledgerId, TransactionType type, String amount,
            LocalDateTime occurredAt, LocalDateTime deletedAt) {
        Transaction t = new Transaction();
        t.setUserId(createdBy);
        t.setLedgerId(ledgerId);
        t.setCreatedBy(createdBy);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setOccurredAt(occurredAt);
        t.setCreatedAt(occurredAt);
        t.setUpdatedAt(occurredAt);
        t.setDeletedAt(deletedAt);
        return transactionRepository.save(t);
    }
}
