package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.BudgetOverviewResponse;
import com.damien.youyu.api.dto.BudgetOverviewResponse.BudgetHealth;
import com.damien.youyu.api.dto.BudgetOverviewResponse.CategoryBudgetItem;
import com.damien.youyu.domain.Budget;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryBudget;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 预算服务：月度总预算 + 分类预算 + 预算健康（前瞻）。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>金额一律 {@link BigDecimal} 保留 2 位小数（HALF_UP），范围 [0.01, 9,999,999,999,999,999.99]。</li>
 *   <li>已支出按 {@code Asia/Shanghai} 自然月半开区间 [当月 1 日 00:00, 次月 1 日 00:00) 聚合，
 *       且仅计 {@code type=expense}（排除转账/收入，需求 4.12/7.5）。</li>
 *   <li>状态阈值：已用 &gt;100% 为 OVER，&gt;=80% 为 WARN，否则 OK。</li>
 *   <li>预算健康仅对「当前自然月且已设总预算」给出：剩余天数、日均可用、按当前速度预计月底结余。</li>
 *   <li>所有读写按会话 {@code ledgerId} 隔离（需求 2.3/2.4）。</li>
 * </ul>
 */
@Service
public class BudgetService {

    static final int SCALE = 2;
    static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int WARN_PERCENT = 80;

    private final BudgetRepository budgetRepository;
    private final CategoryBudgetRepository categoryBudgetRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final Clock clock;

    public BudgetService(
            BudgetRepository budgetRepository,
            CategoryBudgetRepository categoryBudgetRepository,
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            Clock clock) {
        this.budgetRepository = budgetRepository;
        this.categoryBudgetRepository = categoryBudgetRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.clock = clock;
    }

    // ---------------- 查询：总览 ----------------

    /** 某自然月的预算总览（总预算 + 健康 + 分类预算）。 */
    @Transactional(readOnly = true)
    public BudgetOverviewResponse overview(Long ledgerId, YearMonth month) {
        String monthKey = month.toString();

        // 本月支出聚合（排除转账/收入）。
        List<Transaction> expenses = monthExpenses(ledgerId, month);
        BigDecimal spent = ZERO;
        Map<Long, BigDecimal> spentByCat = new LinkedHashMap<>();
        Map<Long, Integer> countByCat = new LinkedHashMap<>();
        for (Transaction t : expenses) {
            spent = spent.add(t.getAmount());
            spentByCat.merge(t.getCategoryId(), t.getAmount(), BigDecimal::add);
            countByCat.merge(t.getCategoryId(), 1, Integer::sum);
        }
        spent = scale(spent);

        Budget total = budgetRepository.findByLedgerIdAndMonth(ledgerId, monthKey).orElse(null);
        boolean isCurrent = month.equals(YearMonth.now(clock));
        Map<Long, String> nameById = categoryNameMap(ledgerId);

        // 分类预算明细。
        List<CategoryBudget> catBudgets = categoryBudgetRepository.findByLedgerIdAndMonth(ledgerId, monthKey);
        BigDecimal allocated = ZERO;
        List<CategoryBudgetItem> items = new ArrayList<>(catBudgets.size());
        List<CategoryBudget> ordered = new ArrayList<>(catBudgets);
        ordered.sort(Comparator.comparing(CategoryBudget::getCategoryId));
        for (CategoryBudget cb : ordered) {
            BigDecimal budget = scale(cb.getAmount());
            allocated = allocated.add(budget);
            BigDecimal catSpent = scale(spentByCat.getOrDefault(cb.getCategoryId(), BigDecimal.ZERO));
            BigDecimal remaining = scale(budget.subtract(catSpent));
            int usedPct = percent(catSpent, budget);
            int count = countByCat.getOrDefault(cb.getCategoryId(), 0);
            items.add(new CategoryBudgetItem(
                    cb.getCategoryId(),
                    nameById.getOrDefault(cb.getCategoryId(), "已删除分类"),
                    budget, catSpent, remaining, usedPct, count, statusOf(catSpent, budget, usedPct)));
        }
        allocated = scale(allocated);

        if (total == null) {
            // 未设总预算：仍返回分类预算与支出，便于前端引导设置。
            return new BudgetOverviewResponse(
                    monthKey, false, null, spent, null, 0, null, isCurrent, null,
                    allocated, null, items);
        }

        BigDecimal totalBudget = scale(total.getAmount());
        BigDecimal remaining = scale(totalBudget.subtract(spent));
        int usedPct = percent(spent, totalBudget);
        String status = statusOf(spent, totalBudget, usedPct);
        BigDecimal unallocated = scale(totalBudget.subtract(allocated));

        BudgetHealth health = (isCurrent) ? computeHealth(month, totalBudget, spent, remaining) : null;

        return new BudgetOverviewResponse(
                monthKey, true, totalBudget, spent, remaining, usedPct, status, isCurrent, health,
                allocated, unallocated, items);
    }

    // ---------------- 写：总预算 ----------------

    /** 设置/更新某自然月的月度总预算，返回最新总览。 */
    @Transactional
    public BudgetOverviewResponse setTotalBudget(Long ledgerId, YearMonth month, BigDecimal rawAmount) {
        BigDecimal amount = validateAmount(rawAmount);
        String monthKey = month.toString();
        LocalDateTime now = LocalDateTime.now(clock);
        Budget budget = budgetRepository.findByLedgerIdAndMonth(ledgerId, monthKey).orElseGet(() -> {
            Budget b = new Budget();
            b.setLedgerId(ledgerId);
            b.setMonth(monthKey);
            b.setCreatedAt(now);
            return b;
        });
        budget.setAmount(amount);
        budget.setUpdatedAt(now);
        budgetRepository.save(budget);
        return overview(ledgerId, month);
    }

    // ---------------- 写：分类预算 ----------------

    /** 设置/更新某自然月某分类的预算，返回最新总览。 */
    @Transactional
    public BudgetOverviewResponse setCategoryBudget(
            Long ledgerId, YearMonth month, Long categoryId, BigDecimal rawAmount) {
        if (categoryId == null) {
            throw ApiException.notFound("分类不存在");
        }
        BigDecimal amount = validateAmount(rawAmount);
        Category category = categoryRepository.findByIdAndLedgerId(categoryId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("分类不存在"));
        if (category.getKind() != CategoryKind.EXPENSE) {
            throw new ApiException("BUDGET_CATEGORY_INVALID", HttpStatus.BAD_REQUEST,
                    "只能给支出分类设置预算", "categoryId");
        }
        String monthKey = month.toString();
        LocalDateTime now = LocalDateTime.now(clock);
        CategoryBudget cb = categoryBudgetRepository
                .findByLedgerIdAndMonthAndCategoryId(ledgerId, monthKey, categoryId)
                .orElseGet(() -> {
                    CategoryBudget c = new CategoryBudget();
                    c.setLedgerId(ledgerId);
                    c.setMonth(monthKey);
                    c.setCategoryId(categoryId);
                    c.setCreatedAt(now);
                    return c;
                });
        cb.setAmount(amount);
        cb.setUpdatedAt(now);
        categoryBudgetRepository.save(cb);
        return overview(ledgerId, month);
    }

    /** 删除某自然月某分类的预算（不存在则静默，幂等），返回最新总览。 */
    @Transactional
    public BudgetOverviewResponse deleteCategoryBudget(Long ledgerId, YearMonth month, Long categoryId) {
        String monthKey = month.toString();
        categoryBudgetRepository
                .findByLedgerIdAndMonthAndCategoryId(ledgerId, monthKey, categoryId)
                .ifPresent(categoryBudgetRepository::delete);
        return overview(ledgerId, month);
    }

    // ---------------- 写：沿用上月 ----------------

    /**
     * 把上一自然月的总预算与分类预算复制到目标月（覆盖目标月已有同项），返回最新总览。
     * 上月无任何预算则为无操作。
     */
    @Transactional
    public BudgetOverviewResponse copyFromPreviousMonth(Long ledgerId, YearMonth month) {
        String prevKey = month.minusMonths(1).toString();
        LocalDateTime now = LocalDateTime.now(clock);

        budgetRepository.findByLedgerIdAndMonth(ledgerId, prevKey)
                .ifPresent(prev -> setTotalBudget(ledgerId, month, prev.getAmount()));

        for (CategoryBudget prev : categoryBudgetRepository.findByLedgerIdAndMonth(ledgerId, prevKey)) {
            setCategoryBudget(ledgerId, month, prev.getCategoryId(), prev.getAmount());
        }
        return overview(ledgerId, month);
    }

    // ---------------- 内部工具 ----------------

    private List<Transaction> monthExpenses(Long ledgerId, YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        return transactionRepository
                .findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(ledgerId, from, to)
                .stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .toList();
    }

    /** 预算健康：剩余天数、日均可用、按当前速度预计月底结余。 */
    private BudgetHealth computeHealth(
            YearMonth month, BigDecimal totalBudget, BigDecimal spent, BigDecimal remaining) {
        LocalDate today = LocalDate.now(clock);
        int daysInMonth = month.lengthOfMonth();
        int dayOfMonth = today.getDayOfMonth();
        int daysLeft = Math.max(0, daysInMonth - dayOfMonth + 1);

        BigDecimal dailyAvailable = ZERO;
        if (daysLeft > 0 && remaining.compareTo(BigDecimal.ZERO) > 0) {
            dailyAvailable = remaining.divide(BigDecimal.valueOf(daysLeft), SCALE, RoundingMode.HALF_UP);
        }

        // 按已过天数的日均支出速度外推整月，预计月底结余。
        BigDecimal projectedBalance;
        if (dayOfMonth > 0) {
            BigDecimal dailyPace = spent.divide(BigDecimal.valueOf(dayOfMonth), 6, RoundingMode.HALF_UP);
            BigDecimal projectedSpend = dailyPace.multiply(BigDecimal.valueOf(daysInMonth));
            projectedBalance = scale(totalBudget.subtract(projectedSpend));
        } else {
            projectedBalance = scale(totalBudget.subtract(spent));
        }
        boolean projectedOver = projectedBalance.compareTo(BigDecimal.ZERO) < 0;
        return new BudgetHealth(daysLeft, dailyAvailable, projectedBalance, projectedOver);
    }

    private Map<Long, String> categoryNameMap(Long ledgerId) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (Category c : categoryRepository.findByLedgerId(ledgerId)) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }

    /** 已用百分比（整数，HALF_UP）；预算 &lt;=0 返回 0；可超过 100。 */
    private int percent(BigDecimal spent, BigDecimal budget) {
        if (budget.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return spent.multiply(HUNDRED).divide(budget, 0, RoundingMode.HALF_UP).intValue();
    }

    private String statusOf(BigDecimal spent, BigDecimal budget, int usedPct) {
        if (spent.compareTo(budget) > 0) {
            return "OVER";
        }
        return usedPct >= WARN_PERCENT ? "WARN" : "OK";
    }

    private BigDecimal validateAmount(BigDecimal raw) {
        if (raw == null) {
            throw ApiException.budgetAmountInvalid();
        }
        if (raw.scale() > SCALE) {
            throw ApiException.budgetAmountInvalid();
        }
        BigDecimal amount = raw.setScale(SCALE, RoundingMode.UNNECESSARY);
        if (amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw ApiException.budgetAmountInvalid();
        }
        return amount;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
