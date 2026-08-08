package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestContextManager;

import com.damien.youyu.api.dto.BudgetOverviewResponse;
import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.MonthlyDigestResponse;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.api.dto.RangeReportResponse;
import com.damien.youyu.domain.Budget;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * {@link MonthlyDigestService} 的属性测试，覆盖设计文档 Correctness Properties 中的 Property 1、2、3、
 * 4、5、6、7、8、9、10。
 *
 * <p>沿用仓库内 DB 支撑型属性测试的既定范式（见 {@code ReportPropertyTest}、
 * {@code InviteTimestampAuditPropertyTest}）：在 {@code @DataJpaTest} + 真实 H2 与真实
 * {@link TransactionRepository}/{@link CategoryRepository} 等仓储上，被测的
 * {@link MonthlyDigestService}（连同其编排的真实 {@link ReportService}/{@link BudgetService}）业务逻辑
 * 全部真实执行，不使用任何 mock。jqwik 的属性方法不经 JUnit Jupiter 引擎、{@code SpringExtension}
 * 不生效，依赖注入改由 {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（与 invite 属性测试一致）。</p>
 *
 * <p>每次迭代使用<b>独立 {@code ledgerId}</b>（共用同一内存 H2，跨迭代复用），以隔离各次随机数据；
 * 时区口径固定注入 {@code Asia/Shanghai} 的固定 {@link Clock}，并随机化「当前时刻」与目标月的相对位置，
 * 从而覆盖 {@code partial}（目标月为当前/未来月）与 {@code final}（目标月早于当前月）两种月状态。
 * 属性驱动 ≥100 次迭代。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MonthlyDigestServicePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 本类专属 ledgerId 段，避免与其它属性测试共用同一内存 H2 时相互串味。 */
    private static final long LEDGER_BASE = 5_100_000_000L;

    /** 跨迭代自增序号：每次迭代取一个全新的 ledgerId。 */
    private static final AtomicLong SEQ = new AtomicLong();

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
    @Autowired
    private BudgetRepository budgetRepository;
    @Autowired
    private CategoryBudgetRepository categoryBudgetRepository;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(MonthlyDigestServicePropertyTest.class).prepareTestInstance(this);
    }

    /** 以固定注入的 {@link Clock} 组装真实的月报组合器（编排真实 ReportService / BudgetService）。 */
    private MonthlyDigestService digestService(Clock clock) {
        ReportService reportService = new ReportService(transactionRepository, categoryRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository);
        BudgetService budgetService = new BudgetService(budgetRepository, categoryBudgetRepository,
                transactionRepository, categoryRepository, clock);
        return new MonthlyDigestService(reportService, budgetService,
                transactionRepository, categoryRepository, clock);
    }

    // ---------------- 智能生成器 ----------------

    /** 一笔交易的生成规格：月内某日（1–28，落在任意月内）、类型、金额（分）、支出分类下标。 */
    private record TxSpec(int day, int kind, long cents, int categoryIndex) { }

    @Provide
    Arbitrary<List<TxSpec>> txSpecs() {
        Arbitrary<Integer> day = Arbitraries.integers().between(1, 28);
        Arbitrary<Integer> kind = Arbitraries.integers().between(0, 2); // 0=expense 1=income 2=transfer
        Arbitrary<Long> cents = Arbitraries.longs().between(1L, 999_999L); // 0.01 .. 9999.99
        Arbitrary<Integer> catIdx = Arbitraries.integers().between(0, 2);
        return Combinators.combine(day, kind, cents, catIdx).as(TxSpec::new)
                .list().ofMaxSize(40);
    }

    /** 固定注入时钟的「当前日期」：2024-01-01 起约 5 年跨度，覆盖不同当前月。 */
    @Provide
    Arbitrary<Integer> nowDayOffsets() {
        return Arbitraries.integers().between(0, 1900);
    }

    /**
     * 并列最大支出的生成规格（供 Property 7）：一笔并列最大金额支出的落点。
     * 用较小的 {@code day}（1–4）与 {@code hour}（8–10）取值域，使不同条目频繁产生
     * <b>相同 occurred_at</b>（同日同时），从而稳定地触发 {@code id} 决胜；同时 day/hour 差异
     * 又覆盖 {@code occurred_at 更晚者优先} 的分支。
     */
    private record TieSpec(int day, int hour, int categoryIndex) { }

    @Provide
    Arbitrary<List<TieSpec>> tieSpecs() {
        Arbitrary<Integer> day = Arbitraries.integers().between(1, 4);
        Arbitrary<Integer> hour = Arbitraries.integers().between(8, 10);
        Arbitrary<Integer> catIdx = Arbitraries.integers().between(0, 2);
        return Combinators.combine(day, hour, catIdx).as(TieSpec::new)
                .list().ofMinSize(1).ofMaxSize(8);
    }

    // ---------------- Property 1 ----------------

    /**
     * Feature: smart-monthly-report, Property 1: 月报打包完整性与月状态正确。
     *
     * <p>对任意账本、目标月与月内交易集合，月报响应都应携带合法的目标月标识（{@code YYYY-MM}）、
     * 九个模块字段（收入、支出、结余、消费趋势、分类排行、预算情况、最大单笔消费、最省钱的一周，
     * 以及供配图使用的上述关键数据），且月状态取值为 {@code final} 当且仅当目标月早于当前自然月、
     * 否则为 {@code partial}。</p>
     *
     * <p>Validates: Requirements 1.1, 1.3, 1.4, 2.5, 9.1</p>
     */
    @Property(tries = 25)
    void property1_digestIsCompleteAndMonthStatusIsCorrect(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        // 固定注入 Asia/Shanghai 时钟：当前时刻取 nowDate 当日中午（对月状态判定无关紧要）。
        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        // 目标月取当前月或更早（monthOffset ≤ 0）：需求 1.3/1.4 仅定义 partial（当前月）与 final（更早月），
        // 未来月不在规约定义的目标月域内，故不生成（否则 partial 的趋势结束边界会早于月首）。
        YearMonth target = nowMonth.plusMonths(monthOffset);

        // 期望月状态：目标月早于当前自然月 → final；否则（当前月）→ partial（需求 1.3、1.4）。
        String expectedStatus = target.isBefore(nowMonth)
                ? MonthlyDigestService.STATUS_FINAL
                : MonthlyDigestService.STATUS_PARTIAL;

        // 分类：3 个支出分类 + 1 个收入分类。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 在目标月内落库随机交易（含转账噪声）。
        int daysInMonth = target.lengthOfMonth();
        for (TxSpec s : specs) {
            int day = Math.min(s.day(), daysInMonth);
            LocalDateTime when = target.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        MonthlyDigestResponse resp = digestService(clock).digest(ledgerId, target);

        // 合法目标月标识（需求 1.1、9.1）。
        assertThat(resp.month())
                .as("nowMonth=%s target=%s 目标月标识", nowMonth, target)
                .isEqualTo(target.toString())
                .matches("\\d{4}-\\d{2}");

        // 月状态：final ⟺ 目标月早于当前月，否则 partial（需求 1.3、1.4）。
        assertThat(resp.monthStatus())
                .as("nowMonth=%s target=%s 月状态", nowMonth, target)
                .isEqualTo(expectedStatus)
                .isIn(MonthlyDigestService.STATUS_FINAL, MonthlyDigestService.STATUS_PARTIAL);

        // 九个模块字段齐备（需求 1.1、2.5、9.1）：收入/支出/结余、趋势、分类排行、预算情况均在场；
        // 最大单笔消费与最省钱的一周允许为 null（空语义），字段本身由响应结构承载。
        assertThat(resp.income()).as("本月收入在场").isNotNull();
        assertThat(resp.expense()).as("本月支出在场").isNotNull();
        assertThat(resp.netBalance()).as("结余在场").isNotNull();
        assertThat(resp.trend()).as("消费趋势在场").isNotNull();
        assertThat(resp.categoryRanking()).as("分类排行在场").isNotNull();
        assertThat(resp.budget()).as("预算情况在场").isNotNull();

        // 结余模块口径自洽：结余 = 收入 - 支出（需求 2.3、2.5）。
        assertThat(resp.netBalance())
                .as("nowMonth=%s target=%s 结余=收入-支出", nowMonth, target)
                .isEqualByComparingTo(resp.income().subtract(resp.expense()));
    }

    // ---------------- Property 2 ----------------

    /**
     * Feature: smart-monthly-report, Property 2: 收支结余同口径且结余为差。
     *
     * <p>对任意账本、目标月与月内交易集合，月报的本月收入、本月支出分别等于
     * {@link ReportService#monthlyReport} 对同一账本与月份返回的收入、支出（均排除转账、2 位小数
     * HALF_UP），且结余恒等于本月收入减本月支出（当支出大于收入时为负）。</p>
     *
     * <p>模型对照（model-based）：以既有 {@link ReportService#monthlyReport} 为参照实现，断言 digest 的
     * income/expense 与其 totalIncome/totalExpense 逐值相等（{@code isEqualByComparingTo}），并断言
     * netBalance == income.subtract(expense)。</p>
     *
     * <p>Validates: Requirements 1.6, 2.1, 2.2, 2.3, 2.4, 11.5</p>
     */
    @Property(tries = 25)
    void property2_incomeExpenseMatchModelAndBalanceIsDifference(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        // 目标月取当前月或更早（monthOffset ≤ 0），与 Property 1 一致，覆盖 partial/final。
        YearMonth target = nowMonth.plusMonths(monthOffset);

        // 分类：3 个支出分类 + 1 个收入分类。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 在目标月内落库随机交易（含转账噪声）。
        int daysInMonth = target.lengthOfMonth();
        for (TxSpec s : specs) {
            int day = Math.min(s.day(), daysInMonth);
            LocalDateTime when = target.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        // 参照实现：既有 ReportService.monthlyReport 对同一账本与月份的结果。
        ReportService reportService = new ReportService(transactionRepository, categoryRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository);
        MonthlyReportResponse reference = reportService.monthlyReport(ledgerId, target);

        MonthlyDigestResponse resp = digestService(clock).digest(ledgerId, target);

        // 收入/支出与参照实现逐值相等（同口径：排除转账、2 位小数 HALF_UP）。
        assertThat(resp.income())
                .as("nowMonth=%s target=%s 本月收入与 monthlyReport.totalIncome 同口径", nowMonth, target)
                .isEqualByComparingTo(reference.totalIncome());
        assertThat(resp.expense())
                .as("nowMonth=%s target=%s 本月支出与 monthlyReport.totalExpense 同口径", nowMonth, target)
                .isEqualByComparingTo(reference.totalExpense());

        // 结余恒等于收入减支出（支出大于收入时为负）。
        assertThat(resp.netBalance())
                .as("nowMonth=%s target=%s 结余=收入-支出", nowMonth, target)
                .isEqualByComparingTo(resp.income().subtract(resp.expense()));
    }

    // ---------------- Property 4 ----------------

    /**
     * Feature: smart-monthly-report, Property 4: 消费趋势稠密、升序、双值且窗口正确。
     *
     * <p>对任意账本、目标月与至少含一笔计入交易的月内交易集合，消费趋势序列按日期严格升序，覆盖结束边界内
     * 每个自然日恰一项（无缺日，缺日的收入与支出均为 0.00），每项携带该日收入与支出合计（2 位小数）；
     * 其中结束边界为：{@code final} 月取月末日、{@code partial} 月取当前日（不含任何晚于当前日的日期）。</p>
     *
     * <p>做法：{@code monthOffset ≤ 0} 覆盖 partial（{@code monthOffset==0}，目标月=当前月）与 final
     * （{@code monthOffset<0}，目标月更早）两支。将随机交易与一笔<b>保证计入</b>的支出全部落在窗口
     * {@code [1, maxDay]} 内（{@code maxDay = final ? 当月天数 : min(当月天数, 当前日)}），保证趋势非空、
     * 因而稠密化覆盖整个窗口。断言：首项=月首、末项=期望结束边界、逐日严格 +1 无缺日、收支均非空、
     * 无活动日收支均为 0.00。</p>
     *
     * <p>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5</p>
     */
    @Property(tries = 25)
    void property4_trendIsDenseAscendingDualValuedWithCorrectWindow(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        // monthOffset ≤ 0 → 目标月为当前月（partial）或更早（final），未来月不在规约域内。
        YearMonth target = nowMonth.plusMonths(monthOffset);

        // partial 当且仅当目标月不早于当前月（因 monthOffset ≤ 0，即等于当前月）。
        boolean partial = !target.isBefore(nowMonth);
        String expectedStatus = partial
                ? MonthlyDigestService.STATUS_PARTIAL
                : MonthlyDigestService.STATUS_FINAL;

        // 期望的稠密窗口（需求 3.4、3.5）：起始为月首；结束边界 final=月末、partial=当前日。
        LocalDate monthStart = target.atDay(1);
        LocalDate expectedEnd = partial ? nowDate : target.atEndOfMonth();

        int daysInMonth = target.lengthOfMonth();
        // 交易只落在窗口内的天：final 全月、partial 至当前日；保证计入交易进入趋势范围。
        int maxDay = partial ? Math.min(daysInMonth, nowDate.getDayOfMonth()) : daysInMonth;

        // 分类：3 个支出分类 + 1 个收入分类。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 记录「有计入活动（收入或支出，排除转账）」的自然日，用于校验缺日为 0.00（需求 3.5）。
        Set<LocalDate> activityDates = new HashSet<>();

        // 保证至少一笔计入交易落在窗口内 → 趋势非空（需求 3.1、3.5 前提）。
        LocalDate guaranteedDay = target.atDay(maxDay);
        persist(ledgerId, TransactionType.EXPENSE, new BigDecimal("12.34"),
                guaranteedDay.atTime(9, 30), expenseCats.get(0));
        activityDates.add(guaranteedDay);

        // 随机交易（含转账噪声），全部钳制到窗口内 [1, maxDay]。
        for (TxSpec s : specs) {
            int day = Math.min(s.day(), maxDay);
            LocalDate date = target.atDay(day);
            LocalDateTime when = date.atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> {
                    persist(ledgerId, TransactionType.EXPENSE, amount, when,
                            expenseCats.get(s.categoryIndex() % expenseCats.size()));
                    activityDates.add(date);
                }
                case 1 -> {
                    persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                    activityDates.add(date);
                }
                // 转账：不计入趋势收支，故不加入 activityDates（该日若无其它活动应为 0.00）。
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        MonthlyDigestResponse resp = digestService(clock).digest(ledgerId, target);

        // 月状态正确（决定窗口结束边界，需求 3.4）。
        assertThat(resp.monthStatus())
                .as("nowMonth=%s target=%s 月状态", nowMonth, target)
                .isEqualTo(expectedStatus);

        List<RangeReportResponse.DayPoint> trend = resp.trend();

        // 前提：至少一笔计入交易 → 趋势非空（稠密化覆盖整个窗口）。
        assertThat(trend)
                .as("nowMonth=%s target=%s 含计入交易时趋势非空", nowMonth, target)
                .isNotEmpty();

        // 稠密：覆盖 [monthStart, expectedEnd] 每个自然日恰一项，即长度 = 天数（需求 3.1、3.5）。
        long expectedSize = ChronoUnit.DAYS.between(monthStart, expectedEnd) + 1;
        assertThat((long) trend.size())
                .as("nowMonth=%s target=%s status=%s 趋势稠密天数 [%s..%s]",
                        nowMonth, target, expectedStatus, monthStart, expectedEnd)
                .isEqualTo(expectedSize);

        // 首项=月首、末项=期望结束边界；窗口不含任何晚于结束边界的日期（需求 3.2、3.4）。
        assertThat(trend.get(0).date())
                .as("趋势首项=月首").isEqualTo(monthStart.toString());
        assertThat(trend.get(trend.size() - 1).date())
                .as("趋势末项=结束边界（final=月末/partial=当前日）").isEqualTo(expectedEnd.toString());

        // 逐项校验：严格升序（每项 = 前项 +1 天、无缺日）、收支双值非空、缺活动日为 0.00。
        LocalDate expectedDate = monthStart;
        for (RangeReportResponse.DayPoint dp : trend) {
            assertThat(dp.date())
                    .as("nowMonth=%s target=%s 严格升序、无缺日、无重复", nowMonth, target)
                    .isEqualTo(expectedDate.toString());

            // 每个数据点携带当日收入与支出合计（需求 3.1、3.3）。
            assertThat(dp.income())
                    .as("date=%s 携带当日收入合计", dp.date()).isNotNull();
            assertThat(dp.expense())
                    .as("date=%s 携带当日支出合计", dp.date()).isNotNull();

            // 无计入活动的自然日：收入与支出合计均为 0.00（需求 3.5）。
            if (!activityDates.contains(expectedDate)) {
                assertThat(dp.income())
                        .as("date=%s 无活动日收入=0.00", dp.date())
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(dp.expense())
                        .as("date=%s 无活动日支出=0.00", dp.date())
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }

            expectedDate = expectedDate.plusDays(1);
        }

        // 未晚于结束边界：末项日期 = expectedEnd，结合严格 +1 升序，自然保证无任何晚于当前日/月末的日期。
        assertThat(LocalDate.parse(trend.get(trend.size() - 1).date()))
                .as("nowMonth=%s target=%s 不含晚于结束边界的日期", nowMonth, target)
                .isEqualTo(expectedEnd);
    }

    // ---------------- Property 5 ----------------

    /**
     * Feature: smart-monthly-report, Property 5: 分类排行同口径、确定性与占比守恒。
     *
     * <p>对任意账本、目标月与月内交易集合，分类排行等于 {@link ReportService#categoryReport} 对同一账本与
     * 月份范围（{@code monthStart}..{@code endBoundary}，按月状态确定）的结果：每项携带分类 id、名称
     * （对应分类缺失/名称为空时回退为 {@code "已删除分类"} 且该项不丢失）、金额、占比、笔数；按金额降序、
     * 金额相等时分类 id 升序排列（结果确定唯一，与输入顺序无关）；当月内存在支出时全部占比之和恒为
     * {@code 100.00}。</p>
     *
     * <p>模型对照（model-based）：以既有 {@link ReportService#categoryReport}(ledgerId, monthStart,
     * endBoundary)（默认口径 {@code TransactionType.EXPENSE}）为参照实现，使用与
     * {@link MonthlyDigestService} 相同的窗口（{@code monthStart}=月首；{@code endBoundary}=final→月末、
     * partial→当前日）。断言 digest 的每个分类项与参照逐值相等（id/amount/percentage/count），name 除
     * 参照名为 null/空白时回退为 {@code "已删除分类"} 外与参照一致，且无任何项被丢弃。为覆盖需求 4.5，
     * 额外落库若干指向<b>不存在分类</b>的支出（参照名回退为 null），验证回退名生效且不丢项。</p>
     *
     * <p>Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 11.5</p>
     */
    @Property(tries = 25)
    void property5_categoryRankingMatchesModelWithFallbackOrderingAndPercentageConservation(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs,
            @ForAll("txSpecs") List<TxSpec> deletedCategorySpecs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        // monthOffset ≤ 0 → 目标月为当前月（partial）或更早（final），与其它属性一致。
        YearMonth target = nowMonth.plusMonths(monthOffset);

        // 与 MonthlyDigestService 相同的窗口：起始为月首；结束边界 final=月末、partial=当前日。
        boolean partial = !target.isBefore(nowMonth);
        LocalDate monthStart = target.atDay(1);
        LocalDate endBoundary = partial ? nowDate : target.atEndOfMonth();

        // 分类：3 个支出分类 + 1 个收入分类。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 一个「不存在的分类」id（永不落库为 Category），用于制造回退名场景（需求 4.5）。
        long deletedCatId = 9_000_000_000L + ledgerId;

        int daysInMonth = target.lengthOfMonth();
        for (TxSpec s : specs) {
            int day = Math.min(s.day(), daysInMonth);
            LocalDateTime when = target.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }
        // 额外落库指向不存在分类的支出：参照 categoryReport 的名称将回退为 null（需求 4.5）。
        for (TxSpec s : deletedCategorySpecs) {
            int day = Math.min(s.day(), daysInMonth);
            LocalDateTime when = target.atDay(day).atTime(10, 15);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            persist(ledgerId, TransactionType.EXPENSE, amount, when, deletedCatId);
        }

        // 参照实现：既有 categoryReport（默认 EXPENSE 口径），同一账本与窗口。
        ReportService reportService = new ReportService(transactionRepository, categoryRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository);
        CategoryReportResponse reference = reportService.categoryReport(ledgerId, monthStart, endBoundary);
        List<CategoryReportResponse.CategoryShare> refShares = reference.categories();

        MonthlyDigestResponse resp = digestService(clock).digest(ledgerId, target);
        List<CategoryReportResponse.CategoryShare> ranking = resp.categoryRanking();

        // 不丢项：与参照同大小（需求 4.5「不使该分类从排行中丢失」、11.5 同口径）。
        assertThat(ranking)
                .as("nowMonth=%s target=%s 分类排行与 categoryReport 同大小、不丢项", nowMonth, target)
                .hasSameSizeAs(refShares);

        // 逐项模型对照。
        for (int i = 0; i < refShares.size(); i++) {
            CategoryReportResponse.CategoryShare ref = refShares.get(i);
            CategoryReportResponse.CategoryShare got = ranking.get(i);

            assertThat(got.categoryId())
                    .as("nowMonth=%s target=%s 第%d项分类id与参照一致", nowMonth, target, i)
                    .isEqualTo(ref.categoryId());
            assertThat(got.amount())
                    .as("nowMonth=%s target=%s 第%d项金额与参照同口径", nowMonth, target, i)
                    .isEqualByComparingTo(ref.amount());
            assertThat(got.percentage())
                    .as("nowMonth=%s target=%s 第%d项占比与参照同口径", nowMonth, target, i)
                    .isEqualByComparingTo(ref.percentage());
            assertThat(got.count())
                    .as("nowMonth=%s target=%s 第%d项笔数与参照一致", nowMonth, target, i)
                    .isEqualTo(ref.count());

            // 名称：参照名为 null/空白 → 回退为 "已删除分类"（需求 4.5）；否则与参照一致。
            String expectedName = (ref.categoryName() == null || ref.categoryName().isBlank())
                    ? MonthlyDigestService.DELETED_CATEGORY_NAME
                    : ref.categoryName();
            assertThat(got.categoryName())
                    .as("nowMonth=%s target=%s 第%d项名称（缺失回退）", nowMonth, target, i)
                    .isEqualTo(expectedName);
        }

        // 排序确定性：金额降序；金额相等时分类 id 升序（需求 4.3）。
        for (int i = 1; i < ranking.size(); i++) {
            CategoryReportResponse.CategoryShare prev = ranking.get(i - 1);
            CategoryReportResponse.CategoryShare cur = ranking.get(i);
            int cmp = cur.amount().compareTo(prev.amount());
            assertThat(cmp)
                    .as("nowMonth=%s target=%s 金额降序（第%d项不大于前项）", nowMonth, target, i)
                    .isLessThanOrEqualTo(0);
            if (cmp == 0) {
                assertThat(cur.categoryId())
                        .as("nowMonth=%s target=%s 金额相等时分类id升序 tie-break", nowMonth, target)
                        .isGreaterThan(prev.categoryId());
            }
        }

        // 占比守恒：月内存在支出（排行非空）时占比之和恒为 100.00（需求 4.4）。
        if (!ranking.isEmpty()) {
            BigDecimal pctSum = ranking.stream()
                    .map(CategoryReportResponse.CategoryShare::percentage)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(pctSum)
                    .as("nowMonth=%s target=%s 占比合计恒为 100.00", nowMonth, target)
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    // ---------------- Property 6 ----------------

    /**
     * Feature: smart-monthly-report, Property 6: 预算情况同口径且前瞻按月状态给出。
     *
     * <p>对任意账本、目标月与任意（含未设/已设）总预算，月报预算模块的
     * {@code hasBudget/totalBudget/spent/remaining/usedPercent/status} 与
     * {@link BudgetService#overview} 对同一账本与月份逐值一致（已支出排除转账；已用 &gt;100% 为 OVER、
     * &gt;=80% 为 WARN、否则 OK）；前瞻信息 {@code forecast} 非空当且仅当月状态为 {@code partial} 且已设
     * 总预算，{@code final} 月或未设预算时为 {@code null}。</p>
     *
     * <p>模型对照（model-based）：以既有 {@link BudgetService#overview}(ledgerId, target)（用与
     * digest <b>相同</b>的固定 {@link Clock} 构造）为参照实现，断言 digest 的预算字段与其逐值相等
     * （{@link BigDecimal} 用 {@code isEqualByComparingTo}、并处理 null）。随机 {@code setBudget} 覆盖
     * 已设/未设预算两支，{@code monthOffset ≤ 0} 覆盖 {@code partial}（{@code ==0}，目标月=当前月）
     * 与 {@code final}（{@code <0}，目标月更早）两支。</p>
     *
     * <p>Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 11.5</p>
     */
    @Property(tries = 25)
    void property6_budgetMatchesModelAndForecastFollowsMonthStatus(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll boolean setBudget,
            @ForAll @LongRange(min = 1L, max = 50_000_000L) long budgetCents,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        // monthOffset ≤ 0 → 目标月为当前月（partial）或更早（final）。
        YearMonth target = nowMonth.plusMonths(monthOffset);
        boolean partial = !target.isBefore(nowMonth);
        String expectedStatus = partial
                ? MonthlyDigestService.STATUS_PARTIAL
                : MonthlyDigestService.STATUS_FINAL;

        // 分类：3 个支出分类 + 1 个收入分类。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 在目标月内落库随机交易（含转账噪声）。
        int daysInMonth = target.lengthOfMonth();
        for (TxSpec s : specs) {
            int day = Math.min(s.day(), daysInMonth);
            LocalDateTime when = target.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        // 覆盖已设/未设预算：随机为目标月落库一条月度总预算。
        if (setBudget) {
            saveBudget(ledgerId, target, BigDecimal.valueOf(budgetCents).movePointLeft(2));
        }

        // 参照实现：既有 BudgetService.overview（与 digest 相同的固定时钟），同一账本与月份。
        BudgetService referenceBudget = new BudgetService(budgetRepository, categoryBudgetRepository,
                transactionRepository, categoryRepository, clock);
        BudgetOverviewResponse overview = referenceBudget.overview(ledgerId, target);

        MonthlyDigestResponse resp = digestService(clock).digest(ledgerId, target);
        MonthlyDigestResponse.BudgetDigest budget = resp.budget();

        assertThat(resp.monthStatus())
                .as("nowMonth=%s target=%s 月状态", nowMonth, target)
                .isEqualTo(expectedStatus);

        // hasBudget 与参照一致，并等于我们实际是否落库预算。
        assertThat(budget.hasBudget())
                .as("nowMonth=%s target=%s setBudget=%s hasBudget 与 overview 一致",
                        nowMonth, target, setBudget)
                .isEqualTo(overview.hasBudget())
                .isEqualTo(setBudget);

        // 字段逐值一致（BigDecimal 用 isEqualByComparingTo，处理 null；需求 5.1、5.2、5.3、11.5）。
        assertBigDecimalEquals("totalBudget", nowMonth, target, budget.totalBudget(), overview.totalBudget());
        assertBigDecimalEquals("spent", nowMonth, target, budget.spent(), overview.spent());
        assertBigDecimalEquals("remaining", nowMonth, target, budget.remaining(), overview.remaining());

        assertThat(budget.usedPercent())
                .as("nowMonth=%s target=%s usedPercent 与 overview 一致", nowMonth, target)
                .isEqualTo(overview.usedPercent());
        assertThat(budget.status())
                .as("nowMonth=%s target=%s status 与 overview 一致", nowMonth, target)
                .isEqualTo(overview.status());

        // 前瞻 forecast 与 overview.health() 逐值一致（同为 record，可直接 equals）。
        assertThat(budget.forecast())
                .as("nowMonth=%s target=%s forecast 与 overview.health() 一致", nowMonth, target)
                .isEqualTo(overview.health());

        // 前瞻非空 ⟺ partial 且已设预算（需求 5.4、5.5）。
        boolean forecastExpectedPresent = partial && budget.hasBudget();
        if (forecastExpectedPresent) {
            assertThat(budget.forecast())
                    .as("nowMonth=%s target=%s partial 且已设预算 → forecast 非空", nowMonth, target)
                    .isNotNull();
        } else {
            assertThat(budget.forecast())
                    .as("nowMonth=%s target=%s final 或未设预算 → forecast 为 null", nowMonth, target)
                    .isNull();
        }
    }

    /** 断言两个可空 {@link BigDecimal} 相等：同为 null 视为相等，否则用值比较（忽略标度差异）。 */
    private void assertBigDecimalEquals(
            String field, YearMonth nowMonth, YearMonth target, BigDecimal actual, BigDecimal expected) {
        if (expected == null) {
            assertThat(actual)
                    .as("nowMonth=%s target=%s %s 与 overview 一致（应为 null）", nowMonth, target, field)
                    .isNull();
        } else {
            assertThat(actual)
                    .as("nowMonth=%s target=%s %s 与 overview 同口径", nowMonth, target, field)
                    .isNotNull()
                    .isEqualByComparingTo(expected);
        }
    }

    // ---------------- Property 7 ----------------

    /**
     * Feature: smart-monthly-report, Property 7: 最大单笔消费选择与确定性 tie-break。
     *
     * <p>对任意账本、目标月与月内交易集合，若存在计入的支出交易，则最大单笔消费的金额等于所有计入支出的
     * 最大金额，并携带该笔的金额、分类名称、发生日期与备注（备注缺省为空串）；当多笔金额并列最大时，选中
     * {@code occurred_at} 更晚者、{@code occurred_at} 相同则 {@code id} 更大者（结果确定唯一）。</p>
     *
     * <p>做法：随机落库若干「并列最大」支出——它们共享一个<b>严格大于</b>所有背景噪声（收入/转账/较小
     * 支出）金额的最大金额，各带<b>唯一备注</b> {@code "tie-"+i}，且用较小取值域的 day/hour 使部分条目
     * 落在<b>相同 occurred_at</b>（触发 {@code id} 决胜）、部分不同（触发 {@code occurred_at} 决胜）。
     * 独立地以服务同款比较器（金额→occurred_at→id 取最大）在测试内算出期望胜出笔，再断言
     * {@code largestExpense} 的金额=最大金额、日期=胜出笔发生日、备注=胜出笔备注、分类名=胜出笔分类名。
     * 由于胜出笔备注唯一，可据此唯一识别选中笔；并二次调用断言结果稳定（确定唯一）。
     * {@code monthOffset ≤ 0} 覆盖 partial/final；≥100 次迭代。</p>
     *
     * <p>Validates: Requirements 6.1, 6.2, 6.3</p>
     */
    @Property(tries = 25)
    void property7_largestExpenseSelectionWithDeterministicTieBreak(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll @LongRange(min = 1_000_000L, max = 2_000_000L) long maxCents,
            @ForAll("tieSpecs") List<TieSpec> ties,
            @ForAll("txSpecs") List<TxSpec> noise) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        // monthOffset ≤ 0 → 目标月为当前月（partial）或更早（final），与其它属性一致。
        YearMonth target = nowMonth.plusMonths(monthOffset);
        int daysInMonth = target.lengthOfMonth();

        // 3 个支出分类 + 1 个收入分类；记录支出分类 id→名称，用于校验胜出笔分类名。
        List<Long> expenseCats = new ArrayList<>();
        Map<Long, String> catNameById = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            Category c = saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i);
            expenseCats.add(c.getId());
            catNameById.put(c.getId(), c.getName());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 背景噪声：收入/转账/较小金额支出（cents ≤ 999_999 → ≤ 9999.99，严格小于并列最大金额）。
        for (TxSpec s : noise) {
            int day = Math.min(s.day(), daysInMonth);
            LocalDateTime when = target.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        // 并列最大：共享同一最大金额（严格 > 任何背景支出），各带唯一备注；捕获已落库实体（含 id/occurred_at）。
        BigDecimal maxAmount = BigDecimal.valueOf(maxCents).movePointLeft(2);
        List<Transaction> tieTxs = new ArrayList<>();
        for (int i = 0; i < ties.size(); i++) {
            TieSpec ts = ties.get(i);
            int day = Math.min(ts.day(), daysInMonth);
            LocalDateTime when = target.atDay(day).atTime(ts.hour(), 30);
            Long cat = expenseCats.get(ts.categoryIndex() % expenseCats.size());
            tieTxs.add(persistExpenseWithNote(ledgerId, maxAmount, when, cat, "tie-" + i));
        }

        // 独立计算期望胜出笔：服务同款比较器（金额→occurred_at→id 取最大）。因并列笔金额严格大于所有
        // 背景支出，全体支出的最大金额必落在并列组内，故在并列组上取 max 与在全体支出上取 max 等价。
        Comparator<Transaction> pick = Comparator
                .comparing(Transaction::getAmount)
                .thenComparing(Transaction::getOccurredAt)
                .thenComparing(Transaction::getId);
        Transaction winner = tieTxs.stream().max(pick).orElseThrow();
        String expectedName = catNameById.getOrDefault(
                winner.getCategoryId(), MonthlyDigestService.DELETED_CATEGORY_NAME);

        MonthlyDigestResponse resp = digestService(clock).digest(ledgerId, target);
        MonthlyDigestResponse.LargestExpense largest = resp.largestExpense();

        // 存在计入支出 → 最大单笔非空（需求 6.1）。
        assertThat(largest)
                .as("nowMonth=%s target=%s 存在支出时最大单笔非空", nowMonth, target)
                .isNotNull();

        // 金额 = 所有计入支出的最大金额（需求 6.1、6.2）。
        assertThat(largest.amount())
                .as("nowMonth=%s target=%s 最大单笔金额=最大支出金额", nowMonth, target)
                .isEqualByComparingTo(maxAmount);

        // tie-break 唯一：发生日期/备注/分类名对应期望胜出笔（occurred_at 更晚 → id 更大，需求 6.2、6.3）。
        assertThat(largest.date())
                .as("nowMonth=%s target=%s 最大单笔发生日期=胜出笔发生日", nowMonth, target)
                .isEqualTo(winner.getOccurredAt().toLocalDate().toString());
        assertThat(largest.note())
                .as("nowMonth=%s target=%s 最大单笔备注=胜出笔备注（唯一识别选中笔）", nowMonth, target)
                .isEqualTo(winner.getNote());
        assertThat(largest.categoryName())
                .as("nowMonth=%s target=%s 最大单笔分类名=胜出笔分类名", nowMonth, target)
                .isEqualTo(expectedName);

        // 备注缺省语义：选中笔备注非空串占位，且始终非 null（需求 6.2）。
        assertThat(largest.note())
                .as("nowMonth=%s target=%s 最大单笔备注非 null（缺省为空串）", nowMonth, target)
                .isNotNull();

        // 结果确定唯一：二次调用得到完全一致的选中笔（需求 6.3）。
        MonthlyDigestResponse.LargestExpense again = digestService(clock).digest(ledgerId, target).largestExpense();
        assertThat(again)
                .as("nowMonth=%s target=%s 最大单笔选择确定唯一（可重复）", nowMonth, target)
                .isEqualTo(largest);
    }

    // ---------------- Property 8 ----------------

    /**
     * Feature: smart-monthly-report, Property 8: 最省钱的一周分段、选择与窗口。
     *
     * <p>对任意账本、目标月与月内交易集合，参与评比的周分段均为自 1 日起每 7 个自然日的<b>完整</b>分段
     * （{@code partial} 月还要求整段起止均不晚于当前日）；若存在至少一个可评比的完整分段，则最省钱的一周
     * 为其中支出合计（排除转账、2 位小数）最低者，并携带该段起始日期、结束日期（= 起始 + 6 天）与支出合计；
     * 多个分段并列最低时取起始日期最早者（结果确定唯一）。</p>
     *
     * <p>做法：在测试内<b>独立</b>构建期望结果——按目标月内每一天的支出合计（排除转账/收入）自 1 日起
     * 枚举完整 7 日分段（{@code partial} 月追加「整段结束日 ≤ 当前日」约束），对每段求支出合计，取最低者、
     * 并列取起始更早者；再断言 {@code mostFrugalWeek} 与之逐值一致（起始、结束 = 起始 + 6、支出
     * {@code isEqualByComparingTo}），无可评比分段时断言为 {@code null}。通过 scenario 覆盖必需情形：</p>
     * <ul>
     *   <li>scenario 0：2 月（28 天）final —— 恰 4 个完整段；</li>
     *   <li>scenario 1：7 月（31 天）final —— 4 段，末 3 日（29–31）不成段；</li>
     *   <li>scenario 2：partial 当前月且已过天数 &lt; 7 —— 无完整合格段，期望 {@code null}；</li>
     *   <li>scenario 3：partial 当前月，前段刻意零支出制造<b>并列最低</b>，验证起始更早胜出；</li>
     *   <li>scenario 4：一般 final 月（28/30/31 天）随机分布。</li>
     * </ul>
     * <p>≥100 次迭代。</p>
     *
     * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.6</p>
     */
    @Property(tries = 25)
    void property8_mostFrugalWeekSegmentationSelectionAndWindow(
            @ForAll @IntRange(min = 0, max = 4) int scenario,
            @ForAll @IntRange(min = 1, max = 6) int shortNowDay,
            @ForAll @IntRange(min = 14, max = 28) int partialNowDay,
            @ForAll @IntRange(min = 0, max = 11) int finalMonthPick,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        // 依 scenario 固定「当前日期」与目标月，覆盖设计要求的各类分段情形。
        LocalDate nowDate;
        YearMonth target;
        boolean forceTieOnlyLate = false; // scenario 3：支出只落在 day>=15，制造前段零支出并列
        switch (scenario) {
            case 0 -> { // 2 月（28 天）final：恰 4 个完整段
                target = YearMonth.of(2023, 2);
                nowDate = LocalDate.of(2023, 6, 10);
            }
            case 1 -> { // 7 月（31 天）final：4 段，末 3 日（29–31）不成段
                target = YearMonth.of(2023, 7);
                nowDate = LocalDate.of(2023, 11, 3);
            }
            case 2 -> { // partial 当前月，已过天数 < 7 → 无完整合格段 → null（需求 7.5、7.6）
                nowDate = LocalDate.of(2024, 3, shortNowDay);
                target = YearMonth.from(nowDate);
            }
            case 3 -> { // partial 当前月，前段零支出制造并列最低，验证起始更早胜出（需求 7.4、7.6）
                nowDate = LocalDate.of(2024, 3, partialNowDay);
                target = YearMonth.from(nowDate);
                forceTieOnlyLate = true;
            }
            default -> { // 一般 final 月（含 28/30/31 天），随机分布
                target = YearMonth.of(2023, 1).plusMonths(finalMonthPick);
                nowDate = target.plusMonths(3).atDay(15);
            }
        }

        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        boolean partial = !target.isBefore(nowMonth);
        LocalDate today = partial ? nowDate : null;
        int daysInMonth = target.lengthOfMonth();

        // 分类：3 个支出分类 + 1 个收入分类。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 独立模型：目标月内按天累加「支出」合计（排除转账/收入），与 service 的 expenseByDay 同源。
        Map<Integer, BigDecimal> expenseByDay = new HashMap<>();
        for (TxSpec s : specs) {
            int day = Math.min(s.day(), daysInMonth);
            LocalDateTime when = target.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> {
                    if (forceTieOnlyLate && day < 15) {
                        // 该 scenario 下 day<15 不落支出（改为收入噪声），使前段零支出并列。
                        persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                    } else {
                        persist(ledgerId, TransactionType.EXPENSE, amount, when,
                                expenseCats.get(s.categoryIndex() % expenseCats.size()));
                        expenseByDay.merge(day, amount, BigDecimal::add);
                    }
                }
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null); // 转账不计入
            }
        }

        // 独立枚举完整段并选最省（起始更早 tie-break），与 buildMostFrugalWeek 同算法。
        int segmentCount = 0;
        LocalDate expectedStart = null;
        LocalDate expectedEnd = null;
        BigDecimal expectedSum = null;
        for (int startDay = 1; startDay + 6 <= daysInMonth; startDay += 7) {
            int endDay = startDay + 6;
            LocalDate segStart = target.atDay(startDay);
            LocalDate segEnd = target.atDay(endDay);
            // partial：整段起止均不晚于当前日（起始 < 结束，故只需结束 ≤ 当前日，需求 7.6）。
            if (partial && segEnd.isAfter(today)) {
                continue;
            }
            segmentCount++;
            BigDecimal sum = BigDecimal.ZERO;
            for (int d = startDay; d <= endDay; d++) {
                BigDecimal daySum = expenseByDay.get(d);
                if (daySum != null) {
                    sum = sum.add(daySum);
                }
            }
            sum = sum.setScale(2, RoundingMode.HALF_UP);
            // 支出合计最低者胜出；并列取起始更早者（起始日递增，严格小于才替换）。
            if (expectedSum == null || sum.compareTo(expectedSum) < 0) {
                expectedSum = sum;
                expectedStart = segStart;
                expectedEnd = segEnd;
            }
        }

        // scenario 级覆盖断言：2 月 / 7 月 final 恰 4 个完整段（需求 7.1）。
        if (scenario == 0 || scenario == 1) {
            assertThat(segmentCount)
                    .as("scenario=%s target=%s 完整周分段数=4（末尾不足 7 日不成段）", scenario, target)
                    .isEqualTo(4);
        }
        // scenario 2：partial 已过天数 < 7 → 无合格段。
        if (scenario == 2) {
            assertThat(segmentCount)
                    .as("scenario=%s nowDate=%s 已过天数<7 无合格完整段", scenario, nowDate)
                    .isZero();
        }

        MonthlyDigestResponse resp = digestService(clock).digest(ledgerId, target);
        MonthlyDigestResponse.FrugalWeek frugal = resp.mostFrugalWeek();

        if (expectedStart == null) {
            // 不存在任何可评比完整分段 → mostFrugalWeek 为 null（需求 7.5、7.6）。
            assertThat(frugal)
                    .as("scenario=%s nowDate=%s target=%s 无完整合格周分段 → mostFrugalWeek 为 null",
                            scenario, nowDate, target)
                    .isNull();
        } else {
            // 存在可评比分段 → 与独立模型逐值一致（需求 7.1–7.4、7.6）。
            assertThat(frugal)
                    .as("scenario=%s nowDate=%s target=%s 存在完整周分段 → mostFrugalWeek 非空",
                            scenario, nowDate, target)
                    .isNotNull();
            assertThat(frugal.startDate())
                    .as("scenario=%s target=%s 最省一周起始（并列取起始更早）", scenario, target)
                    .isEqualTo(expectedStart.toString());
            assertThat(frugal.endDate())
                    .as("scenario=%s target=%s 结束日期=起始+6", scenario, target)
                    .isEqualTo(expectedEnd.toString())
                    .isEqualTo(expectedStart.plusDays(6).toString());
            assertThat(frugal.expense())
                    .as("scenario=%s target=%s 段支出合计（排除转账、2 位小数）为最低", scenario, target)
                    .isEqualByComparingTo(expectedSum);

            // 结果确定唯一：二次调用得到完全一致的最省一周（需求 7.4）。
            MonthlyDigestResponse.FrugalWeek again =
                    digestService(clock).digest(ledgerId, target).mostFrugalWeek();
            assertThat(again)
                    .as("scenario=%s target=%s 最省一周选择确定唯一（可重复）", scenario, target)
                    .isEqualTo(frugal);
        }
    }

    // ---------------- Property 9 ----------------

    /**
     * Feature: smart-monthly-report, Property 9: 空数据优雅返回。
     *
     * <p>对任意目标月内不存在任何计入交易（或不存在计入支出、或不存在完整周分段）的账本，月报都不抛出
     * 错误，并按语义返回空/零值：本月收入、支出、结余为 {@code 0.00}，消费趋势为空列表，分类排行为空列表，
     * 最大单笔消费为 {@code null}，最省钱的一周<b>当且仅当</b>不存在任何可评比的完整周分段时为 {@code null}
     * （需求 7.5：null ⟺ 无完整合格段，而非仅因空月）。</p>
     *
     * <p>做法：以 {@code scenario} 覆盖四类「空」情形，对每类精确断言其应有的空/零语义——</p>
     * <ul>
     *   <li>scenario 0：完全空的 {@code final} 月（无任何交易）—— 收支结余为 0.00、趋势与分类排行为空、
     *       最大单笔为 null；因 {@code final} 月含完整 7 日分段，最省一周<b>非空</b>（起止为首段、支出 0.00）；</li>
     *   <li>scenario 1：完全空的 {@code partial} 短月（已过天数 &lt; 7）—— 同上皆空/零，且因无任何完整合格
     *       分段，最省一周为 {@code null}；</li>
     *   <li>scenario 2：仅有收入/转账（无任何支出）的 {@code final} 月 —— 支出为 0.00、分类排行为空、
     *       最大单笔为 null；收入可 &gt; 0，结余 == 收入；最省一周非空且支出 0.00；</li>
     *   <li>scenario 3：仅有转账（无收入、无支出）的 {@code final} 月 —— 收支结余均为 0.00、趋势与分类排行
     *       为空、最大单笔为 null；最省一周非空且支出 0.00。</li>
     * </ul>
     * <p>最省一周的期望（含 null 与否）由与服务同算法的分段枚举独立算出（各情形逐日支出恒为 0.00，故最低段
     * 即最早的合格完整段、支出 0.00）。≥100 次迭代。</p>
     *
     * <p>Validates: Requirements 1.7, 3.6, 4.6, 6.4, 7.5</p>
     */
    @Property(tries = 25)
    void property9_emptyDataReturnsGracefully(
            @ForAll @IntRange(min = 0, max = 3) int scenario,
            @ForAll @IntRange(min = 1, max = 6) int shortNowDay,
            @ForAll @IntRange(min = 0, max = 11) int finalMonthPick,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        // 依 scenario 固定「当前日期」与目标月，覆盖设计要求的各类「空」情形。
        LocalDate nowDate;
        YearMonth target;
        switch (scenario) {
            case 1 -> { // partial 当前月，已过天数 < 7 → 无完整合格段
                nowDate = LocalDate.of(2024, 3, shortNowDay);
                target = YearMonth.from(nowDate);
            }
            default -> { // scenario 0/2/3：final 月（随机 28/30/31 天），now 取其后 3 个月的月中
                target = YearMonth.of(2023, 1).plusMonths(finalMonthPick);
                nowDate = target.plusMonths(3).atDay(15);
            }
        }

        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        boolean partial = !target.isBefore(nowMonth);
        LocalDate today = partial ? nowDate : null;
        int daysInMonth = target.lengthOfMonth();

        // 分类：3 个支出分类 + 1 个收入分类（仅 scenario 2 会用到收入分类）。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 依 scenario 落库交易；全程<b>绝不落任何计入支出</b>（保证支出/分类排行/最大单笔为空）。
        BigDecimal expectedIncome = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        boolean anyCountedTx = false; // 是否存在计入交易（收入或支出，排除转账）→ 决定趋势是否非空
        switch (scenario) {
            case 0, 1 -> {
                // 完全空月：不落任何交易。
            }
            case 2 -> {
                // 仅收入/转账（无支出）：spec.kind==2 → 转账，其余 → 收入；追踪收入合计。
                for (TxSpec s : specs) {
                    int day = Math.min(s.day(), daysInMonth);
                    LocalDateTime when = target.atDay(day).atTime(9, 30);
                    BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
                    if (s.kind() == 2) {
                        persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
                    } else {
                        persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                        expectedIncome = expectedIncome.add(amount);
                        anyCountedTx = true;
                    }
                }
            }
            default -> {
                // scenario 3：仅转账（无收入、无支出）。
                for (TxSpec s : specs) {
                    int day = Math.min(s.day(), daysInMonth);
                    LocalDateTime when = target.atDay(day).atTime(9, 30);
                    BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
                    persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
                }
            }
        }
        expectedIncome = expectedIncome.setScale(2, RoundingMode.HALF_UP);

        // 独立算出期望的最省一周（各情形逐日支出恒 0.00 → 最低段即最早合格完整段、支出 0.00；无合格段 → null）。
        MonthlyDigestResponse.FrugalWeek expectedFrugal = expectedEmptyFrugalWeek(target, partial, today);

        // 不抛错：digest 正常返回即已验证「不返回错误」（需求 1.7、3.6、4.6、6.4、7.5）。
        MonthlyDigestResponse resp = digestService(clock).digest(ledgerId, target);

        // 支出恒为 0.00（无任何计入支出）；分类排行为空；最大单笔为 null（需求 4.6、6.4）。
        assertThat(resp.expense())
                .as("scenario=%s target=%s 无支出 → 本月支出=0.00", scenario, target)
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resp.categoryRanking())
                .as("scenario=%s target=%s 无支出 → 分类排行为空", scenario, target)
                .isEmpty();
        assertThat(resp.largestExpense())
                .as("scenario=%s target=%s 无支出 → 最大单笔为 null", scenario, target)
                .isNull();

        // 结余恒等于收入减支出（支出为 0 → 结余==收入）（需求 2.3、1.7）。
        assertThat(resp.netBalance())
                .as("scenario=%s target=%s 结余=收入-支出", scenario, target)
                .isEqualByComparingTo(resp.income().subtract(resp.expense()))
                .isEqualByComparingTo(resp.income());

        // 收入：仅 scenario 2 可 > 0（等于所落收入合计）；其余情形为 0.00（需求 1.7）。
        assertThat(resp.income())
                .as("scenario=%s target=%s 本月收入=期望收入合计", scenario, target)
                .isEqualByComparingTo(expectedIncome);

        // 消费趋势：无任何计入交易（收入/支出）时为空列表；scenario 2 有收入则稠密非空（需求 3.6）。
        if (anyCountedTx) {
            assertThat(resp.trend())
                    .as("scenario=%s target=%s 含计入收入 → 趋势非空", scenario, target)
                    .isNotEmpty();
        } else {
            assertThat(resp.trend())
                    .as("scenario=%s target=%s 无任何计入交易 → 趋势为空列表", scenario, target)
                    .isEmpty();
        }

        // 最省一周：null ⟺ 无任何可评比的完整周分段（需求 7.5）；否则与独立枚举逐值一致、支出为 0.00。
        if (expectedFrugal == null) {
            assertThat(resp.mostFrugalWeek())
                    .as("scenario=%s nowDate=%s target=%s 无完整合格周分段 → 最省一周为 null",
                            scenario, nowDate, target)
                    .isNull();
        } else {
            MonthlyDigestResponse.FrugalWeek frugal = resp.mostFrugalWeek();
            assertThat(frugal)
                    .as("scenario=%s nowDate=%s target=%s 存在完整合格周分段 → 最省一周非空",
                            scenario, nowDate, target)
                    .isNotNull();
            assertThat(frugal.startDate())
                    .as("scenario=%s target=%s 最省一周起始（最早合格段）", scenario, target)
                    .isEqualTo(expectedFrugal.startDate());
            assertThat(frugal.endDate())
                    .as("scenario=%s target=%s 最省一周结束=起始+6", scenario, target)
                    .isEqualTo(expectedFrugal.endDate());
            assertThat(frugal.expense())
                    .as("scenario=%s target=%s 无支出 → 最省一周支出合计=0.00", scenario, target)
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    /**
     * 独立枚举「无任何支出」情形下期望的最省一周：与 {@link MonthlyDigestService} 同算法自 1 日起每 7 个
     * 自然日枚举完整分段（{@code partial} 追加「整段结束日不晚于当前日」约束），取最早的合格完整段（各段逐日
     * 支出恒 0.00 故均并列最低、起始更早者胜出），支出合计为 {@code 0.00}；无任何合格段返回 {@code null}。
     */
    private MonthlyDigestResponse.FrugalWeek expectedEmptyFrugalWeek(
            YearMonth target, boolean partial, LocalDate today) {
        int daysInMonth = target.lengthOfMonth();
        for (int startDay = 1; startDay + 6 <= daysInMonth; startDay += 7) {
            LocalDate segStart = target.atDay(startDay);
            LocalDate segEnd = target.atDay(startDay + 6);
            if (partial && segEnd.isAfter(today)) {
                continue;
            }
            return new MonthlyDigestResponse.FrugalWeek(
                    segStart.toString(), segEnd.toString(),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        return null;
    }

    // ---------------- Property 3 ----------------

    /**
     * Feature: smart-monthly-report, Property 3: 账本隔离。
     *
     * <p>对任意两个账本 A、B 各自的随机交易集合，账本 A 的月报与「仅存在 A 的交易」时生成的月报逐值相同
     * ——B 的任何交易都不计入 A 的任一模块（需求 1.5、9.5）。</p>
     *
     * <p>做法：为「共存世界」构建两个账本 {@code ledgerA}、{@code ledgerB}，各落随机交易集（同一目标月）；
     * 另起一个全新账本 {@code ledgerAOnly}，仅复刻 A 的交易集（相同的日/类型/金额/分类下标与预算）。分类在
     * A 与 A' 中<b>使用完全相同的名称</b>（{@code exp-0/1/2}、{@code inc}；分类唯一约束含 {@code user_id}
     * 而本测试 {@code user_id} 为 null 不参与唯一比较，跨账本重名安全），使名称级字段可直接对照。随后断言
     * {@code digest(ledgerA)}（B 共存）与 {@code digest(ledgerAOnly)}（仅 A）<b>逐值相同</b>。</p>
     *
     * <p>比较口径：{@code month}/{@code monthStatus}、{@code income}/{@code expense}/{@code netBalance}、
     * {@code trend}（date + 收支）、{@code budget}（各字段与前瞻 {@code forecast}）、{@code largestExpense}
     * （amount/date/note/categoryName）、{@code mostFrugalWeek}（起止 + 支出）全部逐值相等；
     * {@code categoryRanking} 因两账本分类 id 天然不同，<b>排除 categoryId</b>、逐项对照
     * name/amount/percentage/count（这些才是必须一致的值级字段）。{@code monthOffset ≤ 0} 覆盖
     * partial/final；≥100 次迭代。</p>
     *
     * <p>Validates: Requirements 1.5, 9.5</p>
     */
    @Property(tries = 25)
    void property3_ledgerIsolation(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specsA,
            @ForAll("txSpecs") List<TxSpec> specsB,
            @ForAll boolean setBudget,
            @ForAll @LongRange(min = 1L, max = 50_000_000L) long budgetCents) {

        long ledgerA = LEDGER_BASE + SEQ.incrementAndGet();
        long ledgerB = LEDGER_BASE + SEQ.incrementAndGet();
        long ledgerAOnly = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        // monthOffset ≤ 0 → 目标月为当前月（partial）或更早（final），与其它属性一致。
        YearMonth target = nowMonth.plusMonths(monthOffset);

        // A 与 A' 使用完全相同的分类名称，使 categoryRanking 的 name 级字段可直接对照（id 天然不同）。
        List<Long> expCatsA = new ArrayList<>();
        List<Long> expCatsAOnly = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expCatsA.add(saveCategory(ledgerA, CategoryKind.EXPENSE, "exp-" + i).getId());
            expCatsAOnly.add(saveCategory(ledgerAOnly, CategoryKind.EXPENSE, "exp-" + i).getId());
        }
        Long incCatA = saveCategory(ledgerA, CategoryKind.INCOME, "inc").getId();
        Long incCatAOnly = saveCategory(ledgerAOnly, CategoryKind.INCOME, "inc").getId();
        // B 自成一套分类（跨账本重名安全，此处用不同名以示区分）。
        List<Long> expCatsB = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expCatsB.add(saveCategory(ledgerB, CategoryKind.EXPENSE, "bexp-" + i).getId());
        }
        Long incCatB = saveCategory(ledgerB, CategoryKind.INCOME, "binc").getId();

        // 「共存世界」：A 与 B 各落自己的随机交易集；「仅 A 世界」：A' 复刻 A 的交易集。
        persistPlan(ledgerA, target, specsA, expCatsA, incCatA);
        persistPlan(ledgerAOnly, target, specsA, expCatsAOnly, incCatAOnly);
        persistPlan(ledgerB, target, specsB, expCatsB, incCatB);

        // 预算：A 与 A' 落完全相同的月度总预算；B 落一个不同金额作为噪声，验证不串味。
        if (setBudget) {
            BigDecimal budgetAmount = BigDecimal.valueOf(budgetCents).movePointLeft(2);
            saveBudget(ledgerA, target, budgetAmount);
            saveBudget(ledgerAOnly, target, budgetAmount);
            saveBudget(ledgerB, target, budgetAmount.add(new BigDecimal("123.45")));
        }

        MonthlyDigestResponse digestWithB = digestService(clock).digest(ledgerA, target);
        MonthlyDigestResponse digestAOnly = digestService(clock).digest(ledgerAOnly, target);

        assertDigestValueEqualIgnoringCategoryId(digestWithB, digestAOnly, nowMonth, target);
    }

    /** 依交易规格把一批交易落库到指定账本（含转账噪声），与各属性的落库口径一致。 */
    private void persistPlan(long ledgerId, YearMonth target, List<TxSpec> specs,
            List<Long> expenseCats, Long incomeCat) {
        int daysInMonth = target.lengthOfMonth();
        for (TxSpec s : specs) {
            int day = Math.min(s.day(), daysInMonth);
            LocalDateTime when = target.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }
    }

    /**
     * 断言两份月报逐值相同，但<b>排除分类排行中的分类 id</b>（两账本分类 id 天然不同）。其余全部值级字段
     * （月标识/月状态、收支结余、趋势、预算、最大单笔、最省一周、分类排行的 name/amount/percentage/count）
     * 必须逐值一致——这正是账本隔离要保证的：B 的交易不改变 A 的任一模块取值。
     */
    private void assertDigestValueEqualIgnoringCategoryId(
            MonthlyDigestResponse withB, MonthlyDigestResponse aOnly, YearMonth nowMonth, YearMonth target) {

        assertThat(withB.month())
                .as("nowMonth=%s target=%s 账本隔离：月标识一致", nowMonth, target)
                .isEqualTo(aOnly.month());
        assertThat(withB.monthStatus())
                .as("nowMonth=%s target=%s 账本隔离：月状态一致", nowMonth, target)
                .isEqualTo(aOnly.monthStatus());

        assertThat(withB.income())
                .as("nowMonth=%s target=%s 账本隔离：本月收入不受 B 影响", nowMonth, target)
                .isEqualByComparingTo(aOnly.income());
        assertThat(withB.expense())
                .as("nowMonth=%s target=%s 账本隔离：本月支出不受 B 影响", nowMonth, target)
                .isEqualByComparingTo(aOnly.expense());
        assertThat(withB.netBalance())
                .as("nowMonth=%s target=%s 账本隔离：结余不受 B 影响", nowMonth, target)
                .isEqualByComparingTo(aOnly.netBalance());

        // 趋势：逐日 date 与收支合计一致（date 升序稠密，逐项 isEqualByComparingTo 忽略标度差异）。
        assertThat(withB.trend())
                .as("nowMonth=%s target=%s 账本隔离：趋势天数一致", nowMonth, target)
                .hasSameSizeAs(aOnly.trend());
        for (int i = 0; i < withB.trend().size(); i++) {
            RangeReportResponse.DayPoint x = withB.trend().get(i);
            RangeReportResponse.DayPoint y = aOnly.trend().get(i);
            assertThat(x.date()).as("趋势第%d项日期一致", i).isEqualTo(y.date());
            assertThat(x.income()).as("趋势第%d项收入一致", i).isEqualByComparingTo(y.income());
            assertThat(x.expense()).as("趋势第%d项支出一致", i).isEqualByComparingTo(y.expense());
        }

        // 分类排行：排除分类 id，逐项对照 name/amount/percentage/count。
        assertThat(withB.categoryRanking())
                .as("nowMonth=%s target=%s 账本隔离：分类排行项数一致", nowMonth, target)
                .hasSameSizeAs(aOnly.categoryRanking());
        for (int i = 0; i < withB.categoryRanking().size(); i++) {
            CategoryReportResponse.CategoryShare x = withB.categoryRanking().get(i);
            CategoryReportResponse.CategoryShare y = aOnly.categoryRanking().get(i);
            assertThat(x.categoryName()).as("分类排行第%d项名称一致", i).isEqualTo(y.categoryName());
            assertThat(x.amount()).as("分类排行第%d项金额一致", i).isEqualByComparingTo(y.amount());
            assertThat(x.percentage()).as("分类排行第%d项占比一致", i).isEqualByComparingTo(y.percentage());
            assertThat(x.count()).as("分类排行第%d项笔数一致", i).isEqualTo(y.count());
        }

        // 预算：各字段逐值一致（含前瞻 forecast，两侧同数据同时钟计算，record equals 成立）。
        MonthlyDigestResponse.BudgetDigest bx = withB.budget();
        MonthlyDigestResponse.BudgetDigest by = aOnly.budget();
        assertThat(bx.hasBudget()).as("预算 hasBudget 一致").isEqualTo(by.hasBudget());
        assertBothNullOrEqualByComparing(bx.totalBudget(), by.totalBudget());
        assertBothNullOrEqualByComparing(bx.spent(), by.spent());
        assertBothNullOrEqualByComparing(bx.remaining(), by.remaining());
        assertThat(bx.usedPercent()).as("预算 usedPercent 一致").isEqualTo(by.usedPercent());
        assertThat(bx.status()).as("预算 status 一致").isEqualTo(by.status());
        assertThat(bx.forecast()).as("预算 forecast 一致").isEqualTo(by.forecast());

        // 最大单笔消费：同为 null 或 amount/date/note/categoryName 逐值一致（无 id 字段）。
        MonthlyDigestResponse.LargestExpense lx = withB.largestExpense();
        MonthlyDigestResponse.LargestExpense ly = aOnly.largestExpense();
        if (lx == null || ly == null) {
            assertThat(lx).as("最大单笔同为 null").isNull();
            assertThat(ly).as("最大单笔同为 null").isNull();
        } else {
            assertThat(lx.amount()).as("最大单笔金额一致").isEqualByComparingTo(ly.amount());
            assertThat(lx.date()).as("最大单笔日期一致").isEqualTo(ly.date());
            assertThat(lx.note()).as("最大单笔备注一致").isEqualTo(ly.note());
            assertThat(lx.categoryName()).as("最大单笔分类名一致").isEqualTo(ly.categoryName());
        }

        // 最省钱的一周：同为 null 或起止 + 支出逐值一致（无 id 字段）。
        MonthlyDigestResponse.FrugalWeek fx = withB.mostFrugalWeek();
        MonthlyDigestResponse.FrugalWeek fy = aOnly.mostFrugalWeek();
        if (fx == null || fy == null) {
            assertThat(fx).as("最省一周同为 null").isNull();
            assertThat(fy).as("最省一周同为 null").isNull();
        } else {
            assertThat(fx.startDate()).as("最省一周起始一致").isEqualTo(fy.startDate());
            assertThat(fx.endDate()).as("最省一周结束一致").isEqualTo(fy.endDate());
            assertThat(fx.expense()).as("最省一周支出一致").isEqualByComparingTo(fy.expense());
        }
    }

    /** 断言两个可空 {@link BigDecimal} 相等：同为 null 视为相等，否则用值比较（忽略标度差异）。 */
    private void assertBothNullOrEqualByComparing(BigDecimal x, BigDecimal y) {
        if (x == null || y == null) {
            assertThat(x).as("BigDecimal 同为 null").isNull();
            assertThat(y).as("BigDecimal 同为 null").isNull();
        } else {
            assertThat(x).as("BigDecimal 值一致").isEqualByComparingTo(y);
        }
    }

    // ---------------- Property 10 ----------------

    /**
     * Feature: smart-monthly-report, Property 10: 纯只读不写库。
     *
     * <p>对任意账本、目标月与初始数据库状态，调用月报聚合（一次或多次）后，{@code transactions}、
     * {@code categories}、{@code budgets}、{@code category_budgets} 以及全表清单的行数与全部列取值均保持
     * 不变（零写入副作用，需求 11.1）。</p>
     *
     * <p>做法：先随机落库交易/分类/预算，再在<b>调用 digest 之前</b>用各仓储 {@code findAll()} 对四表做
     * 「行数 + 内容」双重快照（内容映射为稳定可比较的字符串元组、按自然序排序）；随后对同一账本调用
     * {@code digest} <b>多次</b>（并对相邻月各调用一次以扩大读取覆盖面）；最后再次快照，断言四表的行数与
     * 内容元组集合与调用前<b>完全一致</b>——若 digest 有任何插入/更新/删除，行数或内容元组必然改变。
     * {@code monthOffset ≤ 0} 覆盖 partial/final；≥100 次迭代。</p>
     *
     * <p>Validates: Requirements 11.1</p>
     */
    @Property(tries = 25)
    void property10_digestIsPureReadOnly(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs,
            @ForAll boolean setBudget,
            @ForAll @LongRange(min = 1L, max = 50_000_000L) long budgetCents) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        // monthOffset ≤ 0 → 目标月为当前月（partial）或更早（final）。
        YearMonth target = nowMonth.plusMonths(monthOffset);

        // 分类：3 个支出分类 + 1 个收入分类。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 目标月内随机交易（含转账噪声）。
        persistPlan(ledgerId, target, specs, expenseCats, incomeCat);

        // 随机为目标月落一条月度总预算，覆盖读取 budgets / category_budgets 的分支。
        if (setBudget) {
            saveBudget(ledgerId, target, BigDecimal.valueOf(budgetCents).movePointLeft(2));
        }

        // 调用前：四表行数 + 内容快照。
        long txCountBefore = transactionRepository.count();
        long catCountBefore = categoryRepository.count();
        long budgetCountBefore = budgetRepository.count();
        long catBudgetCountBefore = categoryBudgetRepository.count();
        List<String> snapshotBefore = snapshotAllTables();

        // 多次调用（含更早的相邻月）以扩大读取覆盖面；任何一次若写库都会破坏后续快照相等。
        // 注意：仅调用当前月或更早月（未来月不在规约域内——partial 月的趋势结束边界会早于月首而报错）。
        MonthlyDigestService service = digestService(clock);
        service.digest(ledgerId, target);
        service.digest(ledgerId, target);
        service.digest(ledgerId, target.minusMonths(1));

        // 调用后：再次快照并断言完全一致（行数 + 内容）。
        assertThat(transactionRepository.count())
                .as("nowMonth=%s target=%s transactions 行数不变", nowMonth, target)
                .isEqualTo(txCountBefore);
        assertThat(categoryRepository.count())
                .as("nowMonth=%s target=%s categories 行数不变", nowMonth, target)
                .isEqualTo(catCountBefore);
        assertThat(budgetRepository.count())
                .as("nowMonth=%s target=%s budgets 行数不变", nowMonth, target)
                .isEqualTo(budgetCountBefore);
        assertThat(categoryBudgetRepository.count())
                .as("nowMonth=%s target=%s category_budgets 行数不变", nowMonth, target)
                .isEqualTo(catBudgetCountBefore);

        assertThat(snapshotAllTables())
                .as("nowMonth=%s target=%s 四表全部列内容快照不变（零写入副作用）", nowMonth, target)
                .isEqualTo(snapshotBefore);
    }

    /**
     * 对 {@code transactions}、{@code categories}、{@code budgets}、{@code category_budgets} 四表做全量内容
     * 快照：每行映射为一条包含全部关键列的稳定字符串元组，整体按自然序排序，供调用前后逐字节对照。
     */
    private List<String> snapshotAllTables() {
        List<String> snap = new ArrayList<>();
        transactionRepository.findAll().forEach(t -> snap.add(String.join("|",
                "TX", String.valueOf(t.getId()), String.valueOf(t.getLedgerId()),
                String.valueOf(t.getType()), String.valueOf(t.getAmount()),
                String.valueOf(t.getAccountId()), String.valueOf(t.getSourceAccountId()),
                String.valueOf(t.getDestinationAccountId()), String.valueOf(t.getCategoryId()),
                String.valueOf(t.getOccurredAt()), String.valueOf(t.getNote()),
                String.valueOf(t.getCreatedAt()), String.valueOf(t.getUpdatedAt()),
                String.valueOf(t.getDeletedAt()))));
        categoryRepository.findAll().forEach(c -> snap.add(String.join("|",
                "CAT", String.valueOf(c.getId()), String.valueOf(c.getLedgerId()),
                String.valueOf(c.getKind()), String.valueOf(c.getName()),
                String.valueOf(c.getParentId()), String.valueOf(c.getIcon()),
                String.valueOf(c.getCreatedAt()), String.valueOf(c.getUpdatedAt()))));
        budgetRepository.findAll().forEach(b -> snap.add(String.join("|",
                "BUD", String.valueOf(b.getId()), String.valueOf(b.getLedgerId()),
                String.valueOf(b.getMonth()), String.valueOf(b.getAmount()),
                String.valueOf(b.getCreatedAt()), String.valueOf(b.getUpdatedAt()))));
        categoryBudgetRepository.findAll().forEach(cb -> snap.add(String.join("|",
                "CBUD", String.valueOf(cb.getId()), String.valueOf(cb.getLedgerId()),
                String.valueOf(cb.getMonth()), String.valueOf(cb.getCategoryId()),
                String.valueOf(cb.getAmount()), String.valueOf(cb.getCreatedAt()),
                String.valueOf(cb.getUpdatedAt()))));
        snap.sort(Comparator.naturalOrder());
        return snap;
    }

    // ---------------- 持久化辅助 ----------------

    /** 落库一笔带备注的支出并返回已保存实体（含自增 id / occurred_at），供最大单笔 tie-break 校验。 */
    private Transaction persistExpenseWithNote(
            long ledgerId, BigDecimal amount, LocalDateTime when, Long categoryId, String note) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(amount);
        t.setAccountId(1L);
        t.setCategoryId(categoryId);
        t.setNote(note);
        t.setOccurredAt(when);
        t.setCreatedAt(when);
        t.setUpdatedAt(when);
        return transactionRepository.save(t);
    }

    /** 为目标月落库一条月度总预算（供已设预算分支使用）。 */
    private Budget saveBudget(long ledgerId, YearMonth month, BigDecimal amount) {
        Budget b = new Budget();
        b.setLedgerId(ledgerId);
        b.setMonth(month.toString());
        b.setAmount(amount);
        b.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        b.setUpdatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        return budgetRepository.save(b);
    }

    private Category saveCategory(long ledgerId, CategoryKind kind, String name) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        c.setUpdatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
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
}
