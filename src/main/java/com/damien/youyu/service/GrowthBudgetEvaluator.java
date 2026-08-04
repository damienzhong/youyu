package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.damien.youyu.domain.Budget;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 预算达成判定：给定用户与结算日，返回应发放 {@code BUDGET_MET} 的自然月集合（需求 5）。
 *
 * <h2>回看窗口与查询预算</h2>
 *
 * <p>固定回看结算日所属月的<b>前 1 / 2 / 3 个自然月</b>共 3 个已结束自然月（需求 5.1、5.10），
 * <b>不判定结算日所属月</b>（未结束的自然月不参与）。读查询数固定 ≤8 且不随账本数增长（需求 5.15）：
 * 1 次取自有账本清单；每个回看月至多 2 次（预算行 + 月度支出合计），共 ≤6；已由 {@code existingKeys}
 * 命中的月份直接跳过、不额外查库。两个按月查询都用 {@code ledger_id IN (:ids)} 一次取回全部自有账本的
 * 数据，在应用层按账本分组，故查询数不随账本数量增长。</p>
 *
 * <h2>三条刻意的口径</h2>
 *
 * <ol>
 *   <li><b>月度支出按 {@code occurred_at} 聚合，与记账日历按 {@code created_at} 刻意不同</b>：预算衡量的是
 *       「这笔钱花在哪个月」，记账日历（{@code GrowthCalendarService}）衡量的是「哪天来记账」。二者不可混用
 *       （见 {@link TransactionRepository#sumMonthlyExpenseByLedgerIds}，需求 5.11 对比需求 4.1）。</li>
 *   <li><b>过滤条件不复用累计统计那套</b>：累计统计按 {@code created_by} 跨该用户的全部账本
 *       （含协作账本，需求 7.8），预算达成只看该用户<b>自己拥有</b>的账本（{@code ledgers.user_id} 等于该
 *       用户，需求 5.3、5.13）。需求 5.13 明确要求两处查询条件彼此独立、不复用同一段过滤代码——因为一处
 *       衡量「自己记了多少账」，一处衡量「自己设的预算守住了没有」。</li>
 *   <li><b>口径以需求 5.11 自述为准，{@code BudgetService} 将来变更不自动跟随</b>：本类与
 *       {@code BudgetService.monthExpenses} 当前口径逐条一致，但这是两份各自独立的实现。需求 5.14 规定
 *       {@code BudgetService} 的月度支出口径若变更，本类<b>不跟随</b>，任何口径调整须先改需求文档。</li>
 * </ol>
 *
 * <h2>达成判定（需求 5.3–5.7）</h2>
 *
 * <p>某已结束自然月 M 达成预算，当且仅当存在至少一个该用户自有账本 L 满足：{@code budgets} 中有
 * {@code (ledger_id=L, budget_month=M)} 行、该账本在 M 内的月度有效支出合计 &gt; 0（零支出不算达成，
 * 需求 5.5）、且该合计 ≤ 该行 {@code amount}（合计等于预算视为达成，需求 5.6）。多个账本同时达成时，
 * M 只写入一条 {@code BUDGET_MET:M}（{@code break}，不叠加、不可通过新建账本刷取，需求 5.7）。</p>
 */
@Component
public class GrowthBudgetEvaluator {

    /** 回看结算日所属月的前 1/2/3 个自然月，固定 3 个已结束自然月（需求 5.1、5.10）。 */
    static final int LOOKBACK_MONTHS = 3;

    /** {@code BUDGET_MET} 事件键前缀（需求 3.7）。 */
    private static final String BUDGET_MET_PREFIX = "BUDGET_MET:";

    /** 金额保留 2 位小数（需求 5.11）。 */
    private static final int SCALE = 2;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    private final LedgerRepository ledgerRepository;
    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    public GrowthBudgetEvaluator(LedgerRepository ledgerRepository,
                                 BudgetRepository budgetRepository,
                                 TransactionRepository transactionRepository) {
        this.ledgerRepository = ledgerRepository;
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * 回看结算日所属月的前 1/2/3 个自然月，返回应发放 {@code BUDGET_MET} 的月份（{@code YYYY-MM}）。
     *
     * @param userId       令牌所标识的用户 id
     * @param settleDate   结算日；其所属自然月不参与判定（需求 5.1）
     * @param existingKeys 该用户已有的事件键，用于跳过已发放的月份（不额外查库，需求 5.15）；
     *                     {@code null} 按空集处理
     * @return 应发放 {@code BUDGET_MET} 的月份键（{@code YYYY-MM}）列表，按回看顺序（近→远），可能为空
     */
    public List<String> metMonths(Long userId, LocalDate settleDate, Set<String> existingKeys) {
        // 查询 1：自有账本清单（ledgers.user_id 等于该用户）。空则无从达成，直接返回（需求 5.13：
        // 只看自有账本，协作账本的预算不由该成员设定，不参与判定）。
        List<Ledger> ownedLedgers = ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        if (ownedLedgers.isEmpty()) {
            return List.of();
        }
        List<Long> ledgerIds = new ArrayList<>(ownedLedgers.size());
        for (Ledger ledger : ownedLedgers) {
            ledgerIds.add(ledger.getId());
        }
        Set<String> existing = (existingKeys == null) ? Set.of() : existingKeys;

        YearMonth settleMonth = YearMonth.from(settleDate);
        List<String> result = new ArrayList<>(LOOKBACK_MONTHS);
        for (int back = 1; back <= LOOKBACK_MONTHS; back++) {
            YearMonth month = settleMonth.minusMonths(back);
            String monthKey = month.toString(); // YearMonth.toString() 恒为 YYYY-MM（需求 3.7）

            // 已发放的月份直接跳过，不额外查库（需求 5.15 的查询预算据此成立）。
            if (existing.contains(BUDGET_MET_PREFIX + monthKey)) {
                continue;
            }

            // 查询 2k：该月全部自有账本的总预算行（ledger_id IN (:ids) 一次取回，不随账本数增长）。
            List<Budget> budgets = budgetRepository.findByLedgerIdInAndMonth(ledgerIds, monthKey);
            if (budgets.isEmpty()) {
                // 未设总预算即无从达成（需求 5.4）。跳过时不发第二次查询，进一步压低查询数。
                continue;
            }

            // 查询 2k+1：该月各自有账本的月度有效支出合计（按 occurred_at 半开区间聚合，见类级说明口径①②③）。
            LocalDateTime fromInclusive = month.atDay(1).atStartOfDay();
            LocalDateTime toExclusive = month.plusMonths(1).atDay(1).atStartOfDay();
            Map<Long, BigDecimal> spentByLedger = sumMonthlyExpenseByLedger(ledgerIds, fromInclusive, toExclusive);

            for (Budget budget : budgets) {
                BigDecimal spent = spentByLedger.getOrDefault(budget.getLedgerId(), ZERO);
                // 达成：支出合计 > 0（零支出不算，需求 5.5）且 ≤ 预算金额（等于视为达成，需求 5.6）。
                if (spent.compareTo(BigDecimal.ZERO) > 0 && spent.compareTo(budget.getAmount()) <= 0) {
                    result.add(monthKey);
                    break; // 多账本命中不叠加，每月至多 1 条（需求 5.7）。
                }
            }
        }
        return result;
    }

    /**
     * 把 {@code [ledger_id, sum]} 分组结果收敛为 {@code ledgerId -> 合计（2 位小数）} 的映射。
     *
     * <p>原生查询按 {@code ledger_id} 分组，无支出的账本不出现在结果里，故读取端用
     * {@code getOrDefault(..., 0.00)} 兜底。金额一律 {@link BigDecimal}、保留 2 位小数（需求 5.11、7.11）。</p>
     */
    private Map<Long, BigDecimal> sumMonthlyExpenseByLedger(
            List<Long> ledgerIds, LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        Map<Long, BigDecimal> spentByLedger = new HashMap<>();
        List<Object[]> rows = transactionRepository
                .sumMonthlyExpenseByLedgerIds(ledgerIds, fromInclusive, toExclusive);
        for (Object[] row : rows) {
            Long ledgerId = ((Number) row[0]).longValue();
            BigDecimal sum = (row[1] == null)
                    ? ZERO
                    : new BigDecimal(row[1].toString()).setScale(SCALE, RoundingMode.HALF_UP);
            spentByLedger.put(ledgerId, sum);
        }
        return spentByLedger;
    }
}
