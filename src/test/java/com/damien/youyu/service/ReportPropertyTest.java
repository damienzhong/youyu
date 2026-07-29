package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.CategoryReportResponse.CategoryShare;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.api.dto.TrendReportResponse;
import com.damien.youyu.api.dto.TrendReportResponse.MonthPoint;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;

/**
 * {@link ReportService} 的属性测试，覆盖设计文档 Correctness Properties 中的
 * Property 15-17（关联需求 4.12、7.1-7.7）：
 *
 * <ul>
 *   <li>Property 15：对任意用户交易集合与选定范围（月报 / 分类占比 / 月度趋势），报表返回的收入、
 *       支出、结余与各分类金额都等于按 {@code Asia/Shanghai} 自然月/范围对该用户<b>非转账</b>交易独立
 *       重算的结果；转账一律排除；无计入交易的月份/整体各项返回 0。</li>
 *   <li>Property 16：对任意在选定范围内至少含一笔支出的支出集合，分类占比报表各分类百分比之和为
 *       100%（偏差 ≤ 0.05%）。</li>
 *   <li>Property 17：对任意月度趋势请求，若其含起止的自然月跨度 &gt; 24，或起始月份晚于结束月份，则被拒绝
 *       并返回 {@code REPORT_RANGE_INVALID}；合法区间则被接受并逐月产出。</li>
 * </ul>
 *
 * <p>沿用仓库内 DB 支撑型属性测试的既定范式（见 {@code TransactionPropertyTest}、
 * {@code AccountPropertyTest}）：在 {@code @DataJpaTest} + 真实 H2 与真实
 * {@link TransactionRepository}/{@link CategoryRepository} 上，以固定种子的 {@link Random} 在
 * {@code @Test} 循环内智能生成受约束的随机输入，被测的 {@link ReportService} 业务逻辑全部真实执行，
 * 不使用任何 mock。交易时间以 {@link LocalDateTime}（等同 {@code Asia/Shanghai} 本地时刻）持久化，
 * 报表按其自然月/范围边界统计。每个属性至少驱动 ≥100 次迭代。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReportPropertyTest {

    private static final int SCALE = 2;

    /** 各属性迭代次数（均 ≥ 100）。 */
    private static final int P15_ITER = 120;
    private static final int P16_ITER = 120;
    private static final int P17_ITER = 150;

    /** 交易时间窗口的起点自然月。 */
    private static final YearMonth BASE = YearMonth.of(2024, 1);

    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private TransactionTagRepository transactionTagRepository;

    private ReportService service() {
        return new ReportService(transactionRepository, categoryRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository);
    }

    // ---------------- 智能生成器与持久化辅助 ----------------

    /** 合法金额：范围 [0.01, 10000.00]、恰好两位小数（保证多笔累加不越 DECIMAL(18,2)）。 */
    private static BigDecimal randomAmount(Random rng) {
        long cents = 1 + (long) (rng.nextDouble() * 999_999); // 0.01 .. 9999.99+
        return new BigDecimal(cents).movePointLeft(2);
    }

    /** 在给定自然月内随机取一个 LocalDateTime（覆盖当月边界日与随机时分秒）。 */
    private static LocalDateTime randomWithinMonth(Random rng, YearMonth ym) {
        int day = 1 + rng.nextInt(ym.lengthOfMonth());
        int hour = rng.nextInt(24);
        int minute = rng.nextInt(60);
        int second = rng.nextInt(60);
        return ym.atDay(day).atTime(hour, minute, second);
    }

    private Category expenseCategory(long ledgerId, Random rng) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.EXPENSE);
        c.setName("c" + rng.nextInt(1_000_000));
        c.setCreatedAt(BASE.atDay(1).atStartOfDay());
        c.setUpdatedAt(BASE.atDay(1).atStartOfDay());
        return categoryRepository.save(c);
    }

    private Category incomeCategory(long ledgerId, Random rng) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.INCOME);
        c.setName("i" + rng.nextInt(1_000_000));
        c.setCreatedAt(BASE.atDay(1).atStartOfDay());
        c.setUpdatedAt(BASE.atDay(1).atStartOfDay());
        return categoryRepository.save(c);
    }

    private void persist(long ledgerId, TransactionType type, BigDecimal amount, LocalDateTime when,
            Long categoryId) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(type);
        t.setAmount(amount);
        if (type == TransactionType.TRANSFER) {
            t.setSourceAccountId(10L);
            t.setDestinationAccountId(11L);
        } else {
            t.setAccountId(1L);
            t.setCategoryId(categoryId);
        }
        t.setOccurredAt(when);
        t.setCreatedAt(when);
        t.setUpdatedAt(when);
        transactionRepository.save(t);
    }

    /** 一笔已落库交易的模型侧记录（用于独立重算）。 */
    private record TxModel(TransactionType type, BigDecimal amount, LocalDateTime when, Long categoryId) {
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ---------------- Property 15：报表统计 = 模型重算且排除转账 ----------------

    /**
     * Feature: youyu-ledger, Property 15: 对任意用户交易集合与选定时间范围（月报、分类占比、月度趋势），
     * 报表返回的收入、支出、结余与各分类金额都应等于按 Asia/Shanghai（UTC+8）自然月/范围对该用户非转账
     * 交易求和的结果；所有报表统计一律排除 type=transfer 的交易；区间内无计入交易的月份/整体各项返回 0。
     */
    @Test
    void property15_reportsEqualIndependentModelExcludingTransfers() {
        Random rng = new Random(150_015L);
        ReportService svc = service();

        for (int iter = 0; iter < P15_ITER; iter++) {
            long ledgerId = 1_500_000_000L + iter;

            // 交易窗口：连续 1-8 个自然月。
            int windowLen = 1 + rng.nextInt(8);
            YearMonth windowEnd = BASE.plusMonths(windowLen - 1);

            // 支出/收入分类各 1-3 个。
            List<Long> expenseCats = new ArrayList<>();
            for (int i = 0; i < 1 + rng.nextInt(3); i++) {
                expenseCats.add(expenseCategory(ledgerId, rng).getId());
            }
            List<Long> incomeCats = new ArrayList<>();
            for (int i = 0; i < 1 + rng.nextInt(3); i++) {
                incomeCats.add(incomeCategory(ledgerId, rng).getId());
            }

            // 生成 0-40 笔随机交易（含转账噪声）。
            List<TxModel> model = new ArrayList<>();
            int txCount = rng.nextInt(41);
            for (int i = 0; i < txCount; i++) {
                YearMonth ym = BASE.plusMonths(rng.nextInt(windowLen));
                LocalDateTime when = randomWithinMonth(rng, ym);
                BigDecimal amount = randomAmount(rng);
                int kind = rng.nextInt(3);
                if (kind == 0) {
                    Long cat = expenseCats.get(rng.nextInt(expenseCats.size()));
                    persist(ledgerId, TransactionType.EXPENSE, amount, when, cat);
                    model.add(new TxModel(TransactionType.EXPENSE, amount, when, cat));
                } else if (kind == 1) {
                    Long cat = incomeCats.get(rng.nextInt(incomeCats.size()));
                    persist(ledgerId, TransactionType.INCOME, amount, when, cat);
                    model.add(new TxModel(TransactionType.INCOME, amount, when, cat));
                } else {
                    persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
                    model.add(new TxModel(TransactionType.TRANSFER, amount, when, null));
                }
            }

            // ---- 1) 月报：随机月份，可能落在窗口外（应为 0）。 ----
            YearMonth targetMonth = BASE.plusMonths(rng.nextInt(windowLen + 2) - 1);
            MonthlyReportResponse monthly = svc.monthlyReport(ledgerId, targetMonth);
            BigDecimal expIncome = BigDecimal.ZERO;
            BigDecimal expExpense = BigDecimal.ZERO;
            for (TxModel m : model) {
                if (!YearMonth.from(m.when()).equals(targetMonth)) {
                    continue;
                }
                if (m.type() == TransactionType.INCOME) {
                    expIncome = expIncome.add(m.amount());
                } else if (m.type() == TransactionType.EXPENSE) {
                    expExpense = expExpense.add(m.amount());
                }
                // transfer 一律排除。
            }
            assertThat(monthly.totalIncome()).as("iter=%d 月报收入", iter)
                    .isEqualByComparingTo(scale(expIncome));
            assertThat(monthly.totalExpense()).as("iter=%d 月报支出", iter)
                    .isEqualByComparingTo(scale(expExpense));
            assertThat(monthly.balance()).as("iter=%d 月报结余", iter)
                    .isEqualByComparingTo(scale(expIncome.subtract(expExpense)));

            // ---- 2) 分类占比：随机日期范围（含起止），比对总支出与各分类金额。 ----
            LocalDate lo = BASE.atDay(1).minusDays(rng.nextInt(6));
            LocalDate hi = windowEnd.atEndOfMonth().plusDays(rng.nextInt(6));
            LocalDate from = lo.isAfter(hi) ? hi : lo;
            LocalDate to = lo.isAfter(hi) ? lo : hi;
            // 偶尔取更窄的子区间以覆盖部分月份。
            if (rng.nextBoolean() && windowLen > 1) {
                from = BASE.plusMonths(rng.nextInt(windowLen)).atDay(1);
                to = BASE.plusMonths(rng.nextInt(windowLen)).atEndOfMonth();
                if (from.isAfter(to)) {
                    LocalDate tmp = from;
                    from = to;
                    to = tmp;
                }
            }
            CategoryReportResponse cat = svc.categoryReport(ledgerId, from, to);

            LocalDateTime fromDt = from.atStartOfDay();
            LocalDateTime toDt = to.plusDays(1).atStartOfDay();
            Map<Long, BigDecimal> expByCat = new HashMap<>();
            BigDecimal expTotal = BigDecimal.ZERO;
            for (TxModel m : model) {
                if (m.type() != TransactionType.EXPENSE) {
                    continue; // 排除收入与转账。
                }
                if (m.when().isBefore(fromDt) || !m.when().isBefore(toDt)) {
                    continue; // 半开区间 [from, to+1)。
                }
                expByCat.merge(m.categoryId(), m.amount(), BigDecimal::add);
                expTotal = expTotal.add(m.amount());
            }
            assertThat(cat.totalExpense()).as("iter=%d 分类占比总支出", iter)
                    .isEqualByComparingTo(scale(expTotal));
            Map<Long, BigDecimal> actualByCat = new HashMap<>();
            for (CategoryShare s : cat.categories()) {
                actualByCat.put(s.categoryId(), s.amount());
            }
            assertThat(actualByCat.keySet()).as("iter=%d 分类占比分类集合", iter)
                    .isEqualTo(expByCat.keySet());
            for (Map.Entry<Long, BigDecimal> e : expByCat.entrySet()) {
                assertThat(actualByCat.get(e.getKey())).as("iter=%d 分类 %d 金额", iter, e.getKey())
                        .isEqualByComparingTo(scale(e.getValue()));
            }

            // ---- 3) 月度趋势：覆盖整个窗口（≤ 24 月），逐月比对收支。 ----
            TrendReportResponse trend = svc.trendReport(ledgerId, BASE, windowEnd);
            assertThat(trend.months()).as("iter=%d 趋势月份数", iter).hasSize(windowLen);
            Map<YearMonth, BigDecimal> tIncome = new HashMap<>();
            Map<YearMonth, BigDecimal> tExpense = new HashMap<>();
            for (TxModel m : model) {
                YearMonth ym = YearMonth.from(m.when());
                if (m.type() == TransactionType.INCOME) {
                    tIncome.merge(ym, m.amount(), BigDecimal::add);
                } else if (m.type() == TransactionType.EXPENSE) {
                    tExpense.merge(ym, m.amount(), BigDecimal::add);
                }
            }
            for (MonthPoint p : trend.months()) {
                YearMonth ym = YearMonth.parse(p.month());
                assertThat(p.income()).as("iter=%d 趋势 %s 收入", iter, p.month())
                        .isEqualByComparingTo(scale(tIncome.getOrDefault(ym, BigDecimal.ZERO)));
                assertThat(p.expense()).as("iter=%d 趋势 %s 支出", iter, p.month())
                        .isEqualByComparingTo(scale(tExpense.getOrDefault(ym, BigDecimal.ZERO)));
            }
        }
    }

    // ---------------- Property 16：分类占比百分比合计为 100% ----------------

    /**
     * Feature: youyu-ledger, Property 16: 对任意在选定时间范围内至少含一笔支出的用户支出集合，分类占比
     * 报表中各分类百分比之和应为 100%（四舍五入允许偏差不超过 0.05%）。
     */
    @Test
    void property16_categoryPercentagesSumTo100() {
        Random rng = new Random(160_016L);
        ReportService svc = service();
        BigDecimal tolerance = new BigDecimal("0.05");

        for (int iter = 0; iter < P16_ITER; iter++) {
            long ledgerId = 1_600_000_000L + iter;

            // 选定范围：BASE 单个自然月，保证生成的支出都落在范围内。
            YearMonth month = BASE;
            LocalDate from = month.atDay(1);
            LocalDate to = month.atEndOfMonth();

            // 1-5 个支出分类。
            List<Long> cats = new ArrayList<>();
            for (int i = 0; i < 1 + rng.nextInt(5); i++) {
                cats.add(expenseCategory(ledgerId, rng).getId());
            }

            // 至少 1 笔、最多 30 笔支出，均落在范围内。
            int expenseCount = 1 + rng.nextInt(30);
            for (int i = 0; i < expenseCount; i++) {
                Long c = cats.get(rng.nextInt(cats.size()));
                persist(ledgerId, TransactionType.EXPENSE, randomAmount(rng),
                        randomWithinMonth(rng, month), c);
            }
            // 加入收入/转账噪声（不应影响占比合计）。
            int noise = rng.nextInt(6);
            for (int i = 0; i < noise; i++) {
                if (rng.nextBoolean()) {
                    Long ic = incomeCategory(ledgerId, rng).getId();
                    persist(ledgerId, TransactionType.INCOME, randomAmount(rng),
                            randomWithinMonth(rng, month), ic);
                } else {
                    persist(ledgerId, TransactionType.TRANSFER, randomAmount(rng),
                            randomWithinMonth(rng, month), null);
                }
            }

            CategoryReportResponse r = svc.categoryReport(ledgerId, from, to);

            assertThat(r.categories()).as("iter=%d 至少含一笔支出应有分类", iter).isNotEmpty();
            BigDecimal pctSum = BigDecimal.ZERO;
            for (CategoryShare s : r.categories()) {
                pctSum = pctSum.add(s.percentage());
            }
            // 需求 7.3：各分类占比之和为 100%（偏差 ≤ 0.05%）。
            assertThat(pctSum.subtract(new BigDecimal("100")).abs())
                    .as("iter=%d 占比合计=%s", iter, pctSum)
                    .isLessThanOrEqualTo(tolerance);
        }
    }

    // ---------------- Property 17：月度趋势区间非法被拒 ----------------

    /**
     * Feature: youyu-ledger, Property 17: 对任意月度趋势请求，若其月份区间跨度超过 24 个自然月（含起止），
     * 或起始月份晚于结束月份，则请求应被拒绝并返回时间范围无效的错误（REPORT_RANGE_INVALID）；
     * 合法区间（含起止 ≤ 24 月且起始不晚于结束）应被接受并逐月产出。
     */
    @Test
    void property17_trendRangeValidationRejectsInvalidWindows() {
        Random rng = new Random(170_017L);
        ReportService svc = service();
        long ledgerId = 1_700_000_000L;

        for (int iter = 0; iter < P17_ITER; iter++) {
            YearMonth fromMonth = YearMonth.of(2020 + rng.nextInt(8), 1 + rng.nextInt(12));
            int delta = rng.nextInt(81) - 40; // [-40, 40]：覆盖起始晚于结束与超 24 月跨度。
            YearMonth toMonth = fromMonth.plusMonths(delta);

            boolean startAfterEnd = fromMonth.isAfter(toMonth);
            boolean invalid;
            if (startAfterEnd) {
                invalid = true;
            } else {
                long monthCount = ChronoUnit.MONTHS.between(fromMonth, toMonth) + 1;
                invalid = monthCount > 24;
            }

            if (invalid) {
                ApiException ex = catchThrowableOfType(
                        () -> svc.trendReport(ledgerId, fromMonth, toMonth), ApiException.class);
                assertThat(ex).as("iter=%d 非法区间应被拒绝 [%s..%s]", iter, fromMonth, toMonth)
                        .isNotNull();
                assertThat(ex.getCode()).isEqualTo("REPORT_RANGE_INVALID");
            } else {
                TrendReportResponse r = svc.trendReport(ledgerId, fromMonth, toMonth);
                long expectedMonths = ChronoUnit.MONTHS.between(fromMonth, toMonth) + 1;
                assertThat(r.months()).as("iter=%d 合法区间逐月产出 [%s..%s]", iter, fromMonth, toMonth)
                        .hasSize((int) expectedMonths);
            }
        }
    }
}
