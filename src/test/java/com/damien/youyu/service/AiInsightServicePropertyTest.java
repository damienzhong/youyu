package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestContextManager;

import com.damien.youyu.api.dto.AiInsightsResponse;
import com.damien.youyu.api.dto.AiInsightsResponse.AiInsight;
import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.CategoryReportResponse.CategoryShare;
import com.damien.youyu.api.dto.DimensionReportResponse;
import com.damien.youyu.api.dto.DimensionReportResponse.DimensionShare;
import com.damien.youyu.config.AiInsightProperties;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Merchant;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
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
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * {@link AiInsightService} 的属性测试，覆盖设计文档 Correctness Properties 中的 Property 1
 * （响应完整性与月状态正确）。
 *
 * <p>沿用仓库内 DB 支撑型属性测试的既定范式（见 {@code MonthlyDigestServicePropertyTest}、
 * {@code ReportPropertyTest}）：在 {@code @DataJpaTest} + 真实 H2（{@code MODE=MySQL}）与真实
 * {@link TransactionRepository}/{@link CategoryRepository} 等仓储上，被测的 {@link AiInsightService}
 * （连同其编排的真实 {@link ReportService}/{@link InsightNarrator}）业务逻辑全部真实执行，不使用任何
 * mock。jqwik 的属性方法不经 JUnit Jupiter 引擎、{@code SpringExtension} 不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成。</p>
 *
 * <p>每次迭代使用<b>独立 {@code ledgerId}</b>（共用同一内存 H2，跨迭代复用），以隔离各次随机数据；
 * 时区口径固定注入 {@code Asia/Shanghai} 的固定 {@link Clock}，并随机化「当前时刻」与目标月的相对位置
 * （目标月偏移 ≤ 0），从而覆盖 {@code partial}（目标月为当前月）与 {@code final}（目标月早于当前月）
 * 两种月状态，进而覆盖兜底（partial 短路 / 上月无基线 / 无候选）与非兜底两条路径。属性驱动 ≥100 次迭代。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AiInsightServicePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 本类专属 ledgerId 段，避免与其它属性测试共用同一内存 H2 时相互串味。 */
    private static final long LEDGER_BASE = 5_300_000_000L;

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

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(AiInsightServicePropertyTest.class).prepareTestInstance(this);
    }

    /** 以固定注入的 {@link Clock} 组装真实的 AI 趣味分析组合器（编排真实 ReportService / InsightNarrator）。 */
    private AiInsightService aiInsightService(Clock clock) {
        ReportService reportService = new ReportService(transactionRepository, categoryRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository);
        InsightNarrator narrator = new InsightNarrator();
        AiInsightProperties props = new AiInsightProperties();
        return new AiInsightService(reportService, categoryRepository, merchantRepository, clock, props, narrator);
    }

    /** 构造与 {@link #aiInsightService(Clock)} 内部编排完全相同口径的真实 {@link ReportService}，作为 Property 2 的模型对照参照。 */
    private ReportService reportService() {
        return new ReportService(transactionRepository, categoryRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository);
    }

    // ---------------- 智能生成器 ----------------

    /**
     * 一笔交易的生成规格：落在目标月往前 {@code monthsBack}（0..3）个自然月内某日（1–28）、类型、金额（分）、
     * 支出分类下标。{@code monthsBack} 让交易分布到目标月与上月（及更早），从而既能触发上月无基线的兜底、
     * 也能构造出可比基线 + 显著候选的非兜底路径。
     */
    private record TxSpec(int monthsBack, int day, int kind, long cents, int categoryIndex) { }

    @Provide
    Arbitrary<List<TxSpec>> txSpecs() {
        Arbitrary<Integer> monthsBack = Arbitraries.integers().between(0, 3);
        Arbitrary<Integer> day = Arbitraries.integers().between(1, 28);
        Arbitrary<Integer> kind = Arbitraries.integers().between(0, 2); // 0=expense 1=income 2=transfer
        Arbitrary<Long> cents = Arbitraries.longs().between(1L, 999_999L); // 0.01 .. 9999.99
        Arbitrary<Integer> catIdx = Arbitraries.integers().between(0, 2);
        return Combinators.combine(monthsBack, day, kind, cents, catIdx).as(TxSpec::new)
                .list().ofMaxSize(60);
    }

    /** 固定注入时钟的「当前日期」：2024-01-01 起约 5 年跨度，覆盖不同当前月。 */
    @Provide
    Arbitrary<Integer> nowDayOffsets() {
        return Arbitraries.integers().between(0, 1900);
    }

    // ---------------- Property 1 ----------------

    /**
     * Feature: ai-fun-analysis, Property 1: 响应完整性与月状态正确。
     *
     * <p>对任意账本、目标月与交易集合，AI 趣味分析响应都应携带合法的目标月标识（{@code YYYY-MM}）与
     * 月状态，且月状态为 {@code final} 当且仅当目标月早于当前自然月、否则为 {@code partial}；无论兜底态
     * 还是非兜底态，{@code month} 与 {@code monthStatus} 均在场；非兜底态时 {@code insights} 条数在 1 到 N
     * 之间，兜底态时 {@code insights} 为空且 {@code fallbackText} 非空。</p>
     *
     * <p>Validates: Requirements 1.1, 1.3, 1.4, 9.6</p>
     */
    @Property(tries = 25)
    void property1_responseIsCompleteAndMonthStatusIsCorrect(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        // 固定注入 Asia/Shanghai 时钟：当前时刻取 nowDate 当日中午（对月状态判定无关紧要）。
        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        // 目标月取当前月或更早（monthOffset ≤ 0）：需求 1.3/1.4 仅定义 partial（当前月）与 final（更早月）。
        YearMonth target = nowMonth.plusMonths(monthOffset);

        // 期望月状态：目标月早于当前自然月 → final；否则（当前月）→ partial（需求 1.3、1.4）。
        String expectedStatus = target.isBefore(nowMonth)
                ? AiInsightService.STATUS_FINAL
                : AiInsightService.STATUS_PARTIAL;

        // 分类：3 个支出分类 + 1 个收入分类。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 在目标月及其往前若干月内落库随机交易（含转账噪声），分布到目标月/上月/更早月。
        for (TxSpec s : specs) {
            YearMonth m = target.minusMonths(s.monthsBack());
            int day = Math.min(s.day(), m.lengthOfMonth());
            LocalDateTime when = m.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        int maxCount = new AiInsightProperties().maxCountClamped();

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 合法目标月标识 + 与目标月相等（需求 1.1、9.6）。
        assertThat(resp.month())
                .as("nowMonth=%s target=%s 目标月标识", nowMonth, target)
                .isEqualTo(target.toString())
                .matches("\\d{4}-\\d{2}");

        // 月状态：final ⟺ 目标月早于当前月，否则 partial（需求 1.3、1.4）。无论兜底与否均在场（需求 9.6）。
        assertThat(resp.monthStatus())
                .as("nowMonth=%s target=%s 月状态", nowMonth, target)
                .isEqualTo(expectedStatus)
                .isIn(AiInsightService.STATUS_FINAL, AiInsightService.STATUS_PARTIAL);

        // insights 列表始终在场（需求 1.1、9.5）。
        assertThat(resp.insights())
                .as("nowMonth=%s target=%s insights 列表在场", nowMonth, target)
                .isNotNull();

        if (resp.isFallback()) {
            // 兜底态：insights 为空、fallbackText 非空（长度 1..100，需求 9.1、9.2、9.3、9.4）。
            assertThat(resp.insights())
                    .as("nowMonth=%s target=%s 兜底态 insights 为空", nowMonth, target)
                    .isEmpty();
            assertThat(resp.fallbackText())
                    .as("nowMonth=%s target=%s 兜底态 fallbackText 非空且 1..100 字符", nowMonth, target)
                    .isNotNull()
                    .isNotBlank();
            assertThat(resp.fallbackText().length())
                    .as("nowMonth=%s target=%s 兜底文案长度 1..100", nowMonth, target)
                    .isBetween(1, 100);
        } else {
            // 非兜底态：insights 条数在 1..N 之间、fallbackText 为 null（需求 1.1、9.5）。
            assertThat(resp.insights().size())
                    .as("nowMonth=%s target=%s 非兜底态 insights 条数在 1..N（N=%d）", nowMonth, target, maxCount)
                    .isBetween(1, maxCount);
            assertThat(resp.fallbackText())
                    .as("nowMonth=%s target=%s 非兜底态 fallbackText 为 null", nowMonth, target)
                    .isNull();
        }
    }

    // ---------------- Property 2 ----------------

    /**
     * Feature: ai-fun-analysis, Property 2: 同口径口径一致（模型对照）。
     *
     * <p>以真实 {@link ReportService}（{@code monthlyReport / categoryReport / dimensionReport(dim=merchant)}）
     * 对同一账本、同一全月范围（{@code month.atDay(1)..month.atEndOfMonth()}）的输出作为参照模型，断言
     * {@link AiInsightService} 派生出的每条洞察的原始指标都与参照报表<b>逐值相等</b>
     * （{@code isEqualByComparingTo}）：{@code CATEGORY_DELTA} 与 {@code TOP_MOVER} 的
     * {@code currentValue/previousValue/deltaAmount/changeRate} 追溯到 {@code categoryReport} 的分类支出；
     * {@code SAVINGS_TOTAL} 的 {@code currentValue/previousValue/deltaAmount/changeRate} 追溯到
     * {@code monthlyReport} 的月度总支出；{@code FREQUENCY_DELTA} 的 {@code currentCount/previousCount/
     * deltaCount/changeRate}（countRate）追溯到 {@code categoryReport}（分类维度）或 {@code dimensionReport
     * (dim=merchant)}（商户维度）的笔数。三者均排除 {@code type=transfer}、按 {@code Asia/Shanghai} 半开区间、
     * 金额 2 位小数 HALF_UP，从源头坐实与 {@code /api/reports/*} 同口径。{@code TREND_STREAK} 的按月序列深检由
     * Property 8 覆盖，这里跳过（其 {@code currentValue/previousValue} 存的是连续段两端值而非月总额）。仅对实际
     * 出现（非兜底）的洞察断言；构造在 M 与 prev 均落库的数据以获得可比基线。≥100 次迭代。</p>
     *
     * <p>Validates: Requirements 1.6, 2.1, 3.1, 4.1, 4.2, 5.1, 6.1, 13.5</p>
     */
    @Property(tries = 25)
    void property2_derivedMetricsAreValueEqualToReportService(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset ≤ 0
        YearMonth prev = target.minusMonths(1);

        // 分类：3 个支出分类 + 1 个收入分类（与 Property 1 一致）。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 随机交易分布到目标月/上月/更早月（含转账噪声），保证 M 与 prev 都可能落库 → 构造可比基线。
        for (TxSpec s : specs) {
            YearMonth m = target.minusMonths(s.monthsBack());
            int day = Math.min(s.day(), m.lengthOfMonth());
            LocalDateTime when = m.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        // 确定性商户支出，覆盖 FREQUENCY_DELTA 的商户维度（prev 与 M 的笔数不同以触发候选）：
        // 商户 A：prev 5 笔 / M 2 笔；商户 B：prev 2 笔 / M 6 笔。同时计入分类口径与月度总额，与参照一致。
        long merchantA = 7_001L;
        long merchantB = 7_002L;
        Long catForMerchant = expenseCats.get(0);
        seedMerchant(ledgerId, prev, merchantA, 5, catForMerchant, "31.50");
        seedMerchant(ledgerId, target, merchantA, 2, catForMerchant, "31.50");
        seedMerchant(ledgerId, prev, merchantB, 2, catForMerchant, "18.00");
        seedMerchant(ledgerId, target, merchantB, 6, catForMerchant, "18.00");

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 仅对非兜底态（实际出现的洞察）做模型对照；兜底态无原始指标可对照，直接通过。
        if (resp.isFallback()) {
            return;
        }

        // 以真实 ReportService 对同一账本、同一全月范围构建参照模型（同口径：排除 transfer、Asia/Shanghai 半开区间、2dp HALF_UP）。
        ReportService rs = reportService();
        CategoryReportResponse curCat = rs.categoryReport(ledgerId, target.atDay(1), target.atEndOfMonth());
        CategoryReportResponse prevCat = rs.categoryReport(ledgerId, prev.atDay(1), prev.atEndOfMonth());
        DimensionReportResponse curMer = rs.dimensionReport(
                ledgerId, target.atDay(1), target.atEndOfMonth(), TransactionType.EXPENSE, "merchant");
        DimensionReportResponse prevMer = rs.dimensionReport(
                ledgerId, prev.atDay(1), prev.atEndOfMonth(), TransactionType.EXPENSE, "merchant");
        BigDecimal curTotalExpense = rs.monthlyReport(ledgerId, target).totalExpense();
        BigDecimal prevTotalExpense = rs.monthlyReport(ledgerId, prev).totalExpense();

        Map<Long, BigDecimal> curCatAmount = new HashMap<>();
        Map<Long, Long> curCatCount = new HashMap<>();
        for (CategoryShare s : curCat.categories()) {
            curCatAmount.put(s.categoryId(), s.amount());
            curCatCount.put(s.categoryId(), s.count());
        }
        Map<Long, BigDecimal> prevCatAmount = new HashMap<>();
        Map<Long, Long> prevCatCount = new HashMap<>();
        for (CategoryShare s : prevCat.categories()) {
            prevCatAmount.put(s.categoryId(), s.amount());
            prevCatCount.put(s.categoryId(), s.count());
        }
        Map<Long, Long> curMerCount = new HashMap<>();
        for (DimensionShare s : curMer.items()) {
            curMerCount.put(s.id(), s.count());
        }
        Map<Long, Long> prevMerCount = new HashMap<>();
        for (DimensionShare s : prevMer.items()) {
            prevMerCount.put(s.id(), s.count());
        }

        for (AiInsight in : resp.insights()) {
            String label = String.format("target=%s type=%s dimId=%s", target, in.type(), in.dimensionId());
            switch (in.type()) {
                case "CATEGORY_DELTA", "TOP_MOVER" -> {
                    BigDecimal refCur = curCatAmount.getOrDefault(in.dimensionId(), BigDecimal.ZERO);
                    BigDecimal refPrev = prevCatAmount.getOrDefault(in.dimensionId(), BigDecimal.ZERO);
                    assertThat(in.currentValue())
                            .as("%s currentValue == categoryReport(M) 分类支出", label)
                            .isEqualByComparingTo(refCur);
                    assertThat(in.previousValue())
                            .as("%s previousValue == categoryReport(prev) 分类支出", label)
                            .isEqualByComparingTo(refPrev);
                    assertThat(in.deltaAmount())
                            .as("%s deltaAmount == cur − prev", label)
                            .isEqualByComparingTo(scale2(refCur.subtract(refPrev)));
                    // prev > 0 为该两类候选的前提，changeRate 恒有定义 = deltaAmount ÷ prev × 100（2dp）。
                    assertThat(in.changeRate())
                            .as("%s changeRate == deltaAmount ÷ prev × 100（2dp）", label)
                            .isEqualByComparingTo(pct(scale2(refCur.subtract(refPrev)), refPrev));
                }
                case "SAVINGS_TOTAL" -> {
                    assertThat(in.currentValue())
                            .as("%s currentValue == monthlyReport(M).totalExpense", label)
                            .isEqualByComparingTo(curTotalExpense);
                    assertThat(in.previousValue())
                            .as("%s previousValue == monthlyReport(prev).totalExpense", label)
                            .isEqualByComparingTo(prevTotalExpense);
                    // savings = 上月总支出 − 目标月总支出（deltaAmount 存 savings）。
                    assertThat(in.deltaAmount())
                            .as("%s deltaAmount == prevTotalExpense − curTotalExpense", label)
                            .isEqualByComparingTo(scale2(prevTotalExpense.subtract(curTotalExpense)));
                    assertThat(in.changeRate())
                            .as("%s changeRate == savings ÷ prevTotalExpense × 100（2dp）", label)
                            .isEqualByComparingTo(
                                    pct(scale2(prevTotalExpense.subtract(curTotalExpense)), prevTotalExpense));
                }
                case "FREQUENCY_DELTA" -> {
                    long refCurCount;
                    long refPrevCount;
                    if ("MERCHANT".equals(in.dimension())) {
                        refCurCount = curMerCount.getOrDefault(in.dimensionId(), 0L);
                        refPrevCount = prevMerCount.getOrDefault(in.dimensionId(), 0L);
                    } else {
                        refCurCount = curCatCount.getOrDefault(in.dimensionId(), 0L);
                        refPrevCount = prevCatCount.getOrDefault(in.dimensionId(), 0L);
                    }
                    assertThat(in.currentCount())
                            .as("%s currentCount == 报表笔数（M）", label)
                            .isEqualTo((int) refCurCount);
                    assertThat(in.previousCount())
                            .as("%s previousCount == 报表笔数（prev）", label)
                            .isEqualTo((int) refPrevCount);
                    assertThat(in.deltaCount())
                            .as("%s deltaCount == curCount − prevCount", label)
                            .isEqualTo((int) (refCurCount - refPrevCount));
                    // prevCount > 0 为频次候选的前提，countRate 恒有定义。
                    assertThat(in.changeRate())
                            .as("%s countRate == deltaCount ÷ prevCount × 100（2dp）", label)
                            .isEqualByComparingTo(pct(
                                    BigDecimal.valueOf(refCurCount - refPrevCount),
                                    BigDecimal.valueOf(refPrevCount)));
                }
                default -> {
                    // TREND_STREAK 的按月序列深检由 Property 8 覆盖，这里不对照。
                }
            }
        }
    }

    /** 以固定金额在指定自然月落 {@code count} 笔某商户支出（第 5..(5+count) 日，避免与随机交易日冲突过多）。 */
    private void seedMerchant(long ledgerId, YearMonth ym, long merchantId, int count,
            Long categoryId, String amount) {
        for (int i = 0; i < count; i++) {
            int day = Math.min(5 + i, ym.lengthOfMonth());
            LocalDateTime when = ym.atDay(day).atTime(14, 15);
            persistMerchantExpense(ledgerId, new BigDecimal(amount), when, categoryId, merchantId);
        }
    }

    /** 金额/变化率统一 2 位小数 HALF_UP（与生产口径一致）。 */
    private static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 变化率百分比：{@code delta ÷ base × 100}（2dp，HALF_UP）；仅在 {@code base > 0} 时调用（候选前提保证）。 */
    private static BigDecimal pct(BigDecimal delta, BigDecimal base) {
        return delta.multiply(new BigDecimal("100")).divide(base, 2, java.math.RoundingMode.HALF_UP);
    }

    // ---------------- Property 3 ----------------

    /**
     * Feature: ai-fun-analysis, Property 3: 账本隔离。
     *
     * <p>对任意两个账本 A、B 各自的随机交易集合，账本 A 的 AI 趣味分析结果与「仅存在 A 的交易」时生成的
     * 结果<b>逐值相同</b>——账本 B 的任何交易都不计入 A 的任一洞察。</p>
     *
     * <p>验证手法（保持 A 的分类 id 在两次计算间完全稳定，从而可对整个 {@link AiInsightsResponse} 做值相等
     * 断言）：<b>先</b>只落库 A 的交易并计算 {@code respAlone = insights(A, target)}；<b>再</b>把 B 的随机交易
     * 落到另一个全新账本 {@code ledgerB} 后，重新计算 {@code respWithB = insights(A, target)}。由于 A 的账本、
     * 分类行、时钟、目标月完全未变，若账本隔离成立，两次结果必逐值相等（{@code AiInsightsResponse} 为 record，
     * 其 {@code equals} 递归比较所有字段，含 {@code insights} 列表逐元素与各 {@link BigDecimal} 字段）。这直接
     * 坐实「B 的数据不泄漏进 A」。两账本使用各自独立的分类与随机交易集，互不共享维度对象。≥100 次迭代。</p>
     *
     * <p>Validates: Requirements 1.5, 10.5</p>
     */
    @Property(tries = 25)
    void property3_ledgerIsolation(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specsA,
            @ForAll("txSpecs") List<TxSpec> specsB) {

        long ledgerA = LEDGER_BASE + SEQ.incrementAndGet();
        long ledgerB = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset ≤ 0

        // 先仅落库账本 A 的分类与随机交易，并在「仅存在 A」时计算一次洞察。
        seedLedger(ledgerA, target, specsA);
        AiInsightsResponse respAlone = aiInsightService(clock).insights(ledgerA, target);

        // 再把账本 B 的随机交易落到另一个全新账本（B 用自己独立的分类，与 A 不共享任何维度对象）。
        seedLedger(ledgerB, target, specsB);
        AiInsightsResponse respWithB = aiInsightService(clock).insights(ledgerA, target);

        // 账本隔离：加入无关账本 B 的数据后，账本 A 的洞察结果必与「仅存在 A」时逐值相同。
        assertThat(respWithB)
                .as("nowMonth=%s target=%s 账本 A 洞察不受无关账本 B 交易影响（逐值相同）", nowMonth, target)
                .isEqualTo(respAlone);
    }

    // ---------------- Property 4 ----------------

    /**
     * Feature: ai-fun-analysis, Property 4: 金额与变化率 2 位小数。
     *
     * <p>对任意账本、目标月与交易集合，返回的每条洞察中，所有<b>在场（非 null）</b>的金额字段
     * （{@code currentValue}、{@code previousValue}、{@code deltaAmount}、{@code score}）均恰好保留
     * 2 位小数（{@code scale() == 2}），变化率字段 {@code changeRate} 在有定义（非 null）时同样恰好保留
     * 2 位小数。异构五类洞察中某些字段按类型为 {@code null}（例如 {@code SAVINGS_TOTAL} 无维度字段、
     * 变化率基线为 0 → null），因此仅对实际在场的字段断言小数位；兜底态无洞察，直接通过。</p>
     *
     * <p>沿用 Property 2 的播种口径：随机交易分布到目标月 / 上月 / 更早月（含转账噪声）以获得可比基线，
     * 并追加确定性商户支出以触发 {@code FREQUENCY_DELTA} 的商户维度，从而尽量覆盖各类洞察的金额/变化率
     * 字段。属性驱动 ≥120 次迭代。</p>
     *
     * <p>Validates: Requirements 1.7</p>
     */
    @Property(tries = 25)
    void property4_amountsAndChangeRateHaveTwoDecimals(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset ≤ 0
        YearMonth prev = target.minusMonths(1);

        // 分类：3 个支出分类 + 1 个收入分类（与 Property 2 一致）。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 随机交易分布到目标月/上月/更早月（含转账噪声），保证 M 与 prev 都可能落库 → 构造可比基线。
        for (TxSpec s : specs) {
            YearMonth m = target.minusMonths(s.monthsBack());
            int day = Math.min(s.day(), m.lengthOfMonth());
            LocalDateTime when = m.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        // 确定性商户支出，覆盖 FREQUENCY_DELTA 的商户维度（与 Property 2 相同口径）。
        long merchantA = 7_001L;
        long merchantB = 7_002L;
        Long catForMerchant = expenseCats.get(0);
        seedMerchant(ledgerId, prev, merchantA, 5, catForMerchant, "31.50");
        seedMerchant(ledgerId, target, merchantA, 2, catForMerchant, "31.50");
        seedMerchant(ledgerId, prev, merchantB, 2, catForMerchant, "18.00");
        seedMerchant(ledgerId, target, merchantB, 6, catForMerchant, "18.00");

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 兜底态无洞察，无金额/变化率字段可断言，直接通过。
        if (resp.isFallback()) {
            return;
        }

        for (AiInsight in : resp.insights()) {
            String label = String.format("target=%s type=%s dimId=%s", target, in.type(), in.dimensionId());
            // 金额字段：在场（非 null）时恰好保留 2 位小数。
            assertScale2(in.currentValue(), label + " currentValue");
            assertScale2(in.previousValue(), label + " previousValue");
            assertScale2(in.deltaAmount(), label + " deltaAmount");
            assertScale2(in.score(), label + " score");
            // 变化率字段：有定义（非 null）时恰好保留 2 位小数。
            assertScale2(in.changeRate(), label + " changeRate");
        }
    }

    /** 断言 {@link BigDecimal} 字段在场（非 null）时恰好保留 2 位小数（{@code scale() == 2}）；为 null 视为该字段不在场，直接通过。 */
    private static void assertScale2(BigDecimal value, String fieldLabel) {
        if (value == null) {
            return;
        }
        assertThat(value.scale())
                .as("%s 应恰好保留 2 位小数（value=%s scale=%d）", fieldLabel, value.toPlainString(), value.scale())
                .isEqualTo(2);
    }

    /**
     * 为指定账本创建 3 个支出分类 + 1 个收入分类，并按 {@code specs} 落库随机交易（含转账噪声），
     * 分布到目标月 / 上月 / 更早月。分类名以账本 id 前缀，确保不同账本分类互相独立。
     */
    private void seedLedger(long ledgerId, YearMonth target, List<TxSpec> specs) {
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        for (TxSpec s : specs) {
            YearMonth m = target.minusMonths(s.monthsBack());
            int day = Math.min(s.day(), m.lengthOfMonth());
            LocalDateTime when = m.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }
    }

    // ---------------- Property 5 ----------------

    /** 单个分类的 prev / cur 支出规格（分）；某月为 0 表示该分类该月无交易（即上月支出为 0 → 新增消费）。 */
    private record CatDeltaSpec(int prevCents, int curCents) { }

    /**
     * Property 5 专用生成器：固定 <b>2 个</b>支出分类，各自的上月 / 目标月支出（分）在阈值（金额 20.00 元 /
     * 变化率 10.00%）附近随机取值，并以 {@code oneOf(just(0), 1..30000)} 让「上月支出为 0」（新增消费，需求 2.8）
     * 与「上月支出 > 0」两侧都获得充分覆盖，从而覆盖门控两侧。
     *
     * <p>固定 2 个分类是刻意为之：候选总数上界 = {@code CATEGORY_DELTA(≤2) + TOP_MOVER(≤2) + SAVINGS_TOTAL(≤1) = 5}
     * = N（默认 5），因此不会发生 N 截断隐藏本应出现的候选，得以对「达标分类必然出现」做正向断言；
     * 单笔/月的落库使各分类 {@code |deltaCount| ≤ 1 < 2} 而不触发 {@code FREQUENCY_DELTA}，且交易无 {@code merchantId}
     * 故商户维度频次为空。</p>
     */
    @Provide
    Arbitrary<List<CatDeltaSpec>> catDeltaSpecs() {
        Arbitrary<Integer> prevCents = Arbitraries.oneOf(
                Arbitraries.just(0),
                Arbitraries.integers().between(1, 30_000));
        Arbitrary<Integer> curCents = Arbitraries.oneOf(
                Arbitraries.just(0),
                Arbitraries.integers().between(1, 30_000));
        return Combinators.combine(prevCents, curCents).as(CatDeltaSpec::new)
                .list().ofMinSize(2).ofMaxSize(2);
    }

    /**
     * Feature: ai-fun-analysis, Property 5: 分类涨跌门控、字段、方向与变化率。
     *
     * <p>对任意（已完结）目标月与两分类的上月 / 目标月支出，断言 {@code CATEGORY_DELTA} 洞察的门控、字段、方向与
     * 变化率均正确：</p>
     * <ol>
     *   <li><b>门控 + 字段 + 方向 + 变化率（正确性）</b>：对每条<b>返回的</b> {@code CATEGORY_DELTA} 洞察，断言
     *       上月支出 &gt; 0、{@code |变化率| ≥ 10.00}、{@code |变化量| ≥ 20.00}，方向 {@code DOWN} 当且仅当
     *       {@code cur < prev}、{@code UP} 当且仅当 {@code cur > prev}，且 {@code dimensionId / currentValue /
     *       previousValue / deltaAmount / changeRate} 与同口径参照 {@link ReportService#categoryReport} 逐值相等
     *       （{@code isEqualByComparingTo}）（需求 2.2、2.3、2.4、2.5）。</li>
     *   <li><b>上月为 0 不生成（需求 2.8）</b>：任何上月支出为 0 的分类都不产出 {@code CATEGORY_DELTA}（无关截断）。</li>
     *   <li><b>正向可达（无截断）</b>：满足门控且<b>在目标月 M 有支出</b>（在 {@code categoryReport(M)} 迭代域内，需求 2.1）
     *       的分类<b>必然</b>出现在返回集合中——构造使候选总数 ≤ 5 = N，杜绝 N 截断掩盖达标候选。</li>
     * </ol>
     *
     * <p>播种口径：每个分类在 prev 落一笔（= prevCents）、在 M 落一笔（= curCents）；并在 M−2 放一笔<b>等于 prev</b>
     * 的「阻断笔」，使 M−1 与 M−2 相等 → 任何连续单调段在 M−1 处终止（长度 2 &lt; 3），从而不触发 {@code TREND_STREAK}
     * 而稳住候选总数；M−2 不落入 {@code categoryReport(M)/categoryReport(prev)/monthlyReport} 的月范围，故不影响任何
     * 被断言的派生指标。目标月固定早于当前月（{@code monthOffset < 0}）以取 {@code final} 且具可比基线。≥120 次迭代。</p>
     *
     * <p>Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.8</p>
     */
    @Property(tries = 25)
    void property5_categoryDeltaGatingFieldsDirectionAndRate(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = -1) int monthOffset,
            @ForAll("catDeltaSpecs") List<CatDeltaSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset < 0 → final（已完结）
        YearMonth prev = target.minusMonths(1);
        YearMonth beforePrev = target.minusMonths(2);

        // 2 个支出分类：各在 prev 落一笔（= prevCents）、在 M 落一笔（= curCents）；prev>0 时在 M−2 放等于 prev 的阻断笔。
        List<Long> catIds = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            Long cid = saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId();
            catIds.add(cid);
            CatDeltaSpec s = specs.get(i);
            if (s.prevCents() > 0) {
                BigDecimal prevAmount = BigDecimal.valueOf(s.prevCents()).movePointLeft(2);
                persist(ledgerId, TransactionType.EXPENSE, prevAmount, prev.atDay(10).atTime(9, 30), cid);
                // M−2 阻断笔（= prev），仅用于终止连续段（防 TREND_STREAK），不进入被断言的月范围。
                persist(ledgerId, TransactionType.EXPENSE, prevAmount, beforePrev.atDay(10).atTime(9, 30), cid);
            }
            if (s.curCents() > 0) {
                BigDecimal curAmount = BigDecimal.valueOf(s.curCents()).movePointLeft(2);
                persist(ledgerId, TransactionType.EXPENSE, curAmount, target.atDay(10).atTime(9, 30), cid);
            }
        }

        // 参照模型：与生产同口径的 categoryReport(M) 与 categoryReport(prev)（默认 EXPENSE、Asia/Shanghai、2dp HALF_UP）。
        ReportService rs = reportService();
        CategoryReportResponse curCat = rs.categoryReport(ledgerId, target.atDay(1), target.atEndOfMonth());
        CategoryReportResponse prevCat = rs.categoryReport(ledgerId, prev.atDay(1), prev.atEndOfMonth());
        Map<Long, BigDecimal> curAmt = new HashMap<>();
        for (CategoryShare cs : curCat.categories()) {
            curAmt.put(cs.categoryId(), cs.amount());
        }
        Map<Long, BigDecimal> prevAmt = new HashMap<>();
        for (CategoryShare cs : prevCat.categories()) {
            prevAmt.put(cs.categoryId(), cs.amount());
        }

        AiInsightProperties props = new AiInsightProperties();
        BigDecimal rateMin = props.getCategoryRatePctMin().abs();   // 10.00
        BigDecimal amountMin = props.getCategoryAmountMin().abs();  // 20.00

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 收集返回的 CATEGORY_DELTA 洞察，按分类 id 索引。
        Map<Long, AiInsight> returnedByCat = new HashMap<>();
        for (AiInsight in : resp.insights()) {
            if (AiInsightService.TYPE_CATEGORY_DELTA.equals(in.type())) {
                returnedByCat.put(in.dimensionId(), in);
            }
        }

        // 断言 1：每条返回的 CATEGORY_DELTA 都满足门控、字段、方向与变化率（与参照逐值相等）（需求 2.2、2.3、2.4、2.5）。
        for (AiInsight in : returnedByCat.values()) {
            Long id = in.dimensionId();
            BigDecimal cur = curAmt.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal prevA = prevAmt.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal delta = scale2(cur.subtract(prevA));
            String label = String.format("target=%s catId=%s cur=%s prev=%s", target, id, cur, prevA);

            // 门控：prev > 0（需求 2.2、2.8）、|变化率| ≥ 下限、|变化量| ≥ 下限（需求 2.3）。
            assertThat(prevA)
                    .as("%s 上月支出 > 0（需求 2.2、2.8）", label)
                    .isGreaterThan(BigDecimal.ZERO);
            BigDecimal rate = pct(delta, prevA);
            assertThat(rate.abs())
                    .as("%s |变化率| ≥ 变化率下限（需求 2.3）", label)
                    .isGreaterThanOrEqualTo(rateMin);
            assertThat(delta.abs())
                    .as("%s |变化量| ≥ 金额下限（需求 2.3）", label)
                    .isGreaterThanOrEqualTo(amountMin);

            // 方向：DOWN ⟺ cur < prev、UP ⟺ cur > prev（需求 2.5）。门控保证 |delta| ≥ 20 → cur ≠ prev。
            String expectedDir = cur.compareTo(prevA) < 0
                    ? AiInsightService.DIRECTION_DOWN
                    : AiInsightService.DIRECTION_UP;
            assertThat(in.direction())
                    .as("%s 方向 DOWN⟺cur<prev / UP⟺cur>prev（需求 2.5）", label)
                    .isEqualTo(expectedDir);

            // 字段：与参照 categoryReport 逐值相等（需求 2.4）。
            assertThat(in.dimension())
                    .as("%s 维度为 CATEGORY", label)
                    .isEqualTo(AiInsightService.DIMENSION_CATEGORY);
            assertThat(in.currentValue())
                    .as("%s currentValue == categoryReport(M) 分类支出", label)
                    .isEqualByComparingTo(cur);
            assertThat(in.previousValue())
                    .as("%s previousValue == categoryReport(prev) 分类支出", label)
                    .isEqualByComparingTo(prevA);
            assertThat(in.deltaAmount())
                    .as("%s deltaAmount == cur − prev（2dp）", label)
                    .isEqualByComparingTo(delta);
            assertThat(in.changeRate())
                    .as("%s changeRate == deltaAmount ÷ prev × 100（2dp）", label)
                    .isEqualByComparingTo(rate);
        }

        // 断言 2：上月支出为 0 的分类绝不产出 CATEGORY_DELTA（需求 2.8）；此结论与是否截断无关。
        for (Long id : catIds) {
            BigDecimal prevA = prevAmt.getOrDefault(id, BigDecimal.ZERO);
            if (prevA.compareTo(BigDecimal.ZERO) == 0) {
                assertThat(returnedByCat)
                        .as("target=%s catId=%s 上月支出为 0 → 无 CATEGORY_DELTA（需求 2.8）", target, id)
                        .doesNotContainKey(id);
            }
        }

        // 断言 3（正向可达）：满足门控的分类必然出现（候选总数 ≤ 5 = N，无截断）。
        // 门控域为「目标月 M 的支出分类」（需求 2.1）：cur=0（分类在 M 无支出、不在 categoryReport(M) 中）
        // 的分类不在迭代域内，不会生成 CATEGORY_DELTA，故仅对在 M 出现的分类做正向断言。
        for (Long id : catIds) {
            if (!curAmt.containsKey(id)) {
                continue; // 不在目标月支出分类集合内（需求 2.1）
            }
            BigDecimal cur = curAmt.get(id);
            BigDecimal prevA = prevAmt.getOrDefault(id, BigDecimal.ZERO);
            if (prevA.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal delta = scale2(cur.subtract(prevA));
            BigDecimal rate = pct(delta, prevA);
            boolean gate = rate.abs().compareTo(rateMin) >= 0 && delta.abs().compareTo(amountMin) >= 0;
            if (gate) {
                assertThat(returnedByCat)
                        .as("target=%s catId=%s 达标分类应出现 CATEGORY_DELTA（无截断，需求 2.3）", target, id)
                        .containsKey(id);
            }
        }
    }

    // ---------------- Property 6 ----------------

    /** 单个分类的 prev / cur 支出规格（分）；某月为 0 表示该分类该月无支出（用于构造上月总支出为 0 的无定义分支）。 */
    private record SavingsSpec(int prevCents, int curCents) { }

    /**
     * Property 6 专用生成器：1..3 个支出分类，各自的上月 / 目标月支出（分）以 {@code oneOf(just(0), 1..15000)}
     * 取值，使「上月总支出为 0」（全部分类上月为 0 → 无定义分支，需求 3.8）与「上月总支出 &gt; 0」两侧都获得
     * 充分覆盖；金额跨度（0.01..150.00 元）叠加多分类求和，使节省额 {@code savings = 上月总支出 − 目标月总支出}
     * 落在金额下限 50.00 元两侧（达标 / 不足）均可触达，从而覆盖门控两侧。
     */
    @Provide
    Arbitrary<List<SavingsSpec>> savingsSpecs() {
        Arbitrary<Integer> prevCents = Arbitraries.oneOf(
                Arbitraries.just(0),
                Arbitraries.integers().between(1, 15_000));
        Arbitrary<Integer> curCents = Arbitraries.oneOf(
                Arbitraries.just(0),
                Arbitraries.integers().between(1, 15_000));
        return Combinators.combine(prevCents, curCents).as(SavingsSpec::new)
                .list().ofMinSize(1).ofMaxSize(3);
    }

    /**
     * Feature: ai-fun-analysis, Property 6: 节省总额门控、算术与角色。
     *
     * <p>对任意（已完结）目标月与交易集合，{@code savings = 上月总支出 − 目标月总支出}（2dp，可负）；
     * {@code SAVINGS_TOTAL} 洞察生成当且仅当「上月总支出 &gt; 0 且 {@code |savings|} 不小于金额下限（50.00 元）」，
     * 其变化率仅在上月总支出 &gt; 0 时有定义（= {@code savings ÷ 上月总支出 × 100}，2dp）；{@code savings > 0} 时
     * 角色为 {@code IMPROVE}（节省）、{@code savings < 0} 时角色为 {@code OVERSPEND}（多花）；上月总支出为 0 时不
     * 生成且不报错。</p>
     *
     * <p>参照口径：以真实 {@link ReportService#monthlyReport(Long, YearMonth)} 的 {@code totalExpense()} 作为
     * 目标月 / 上月总支出的模型对照（同口径：排除 transfer、{@code Asia/Shanghai} 半开区间、2dp HALF_UP）。
     * 目标月固定早于当前月（{@code monthOffset < 0}）以取 {@code final} 而非 {@code partial}（partial 会短路兜底、
     * 不产出任何洞察）。断言对 N 截断稳健——存在性正向断言仅在返回列表未满（{@code size < N=5}）时施加，因为
     * {@code scoreDedupSortTruncate} 先按 score DESC 排序、type 优先级只打破同分平局，{@code SAVINGS_TOTAL}
     * 在列表已满时可能被更高分候选合法截断（详见断言 3）；而「存在即正确」（断言 1）与「上月为 0 不生成」（断言 2）
     * 与是否截断无关，始终成立：</p>
     * <ol>
     *   <li><b>存在即正确</b>：若返回了 {@code SAVINGS_TOTAL} 洞察，则上月总支出 &gt; 0、{@code |savings| ≥ 50.00}、
     *       {@code currentValue == 目标月总支出}、{@code previousValue == 上月总支出}、{@code deltaAmount == savings}、
     *       {@code changeRate == savings ÷ 上月总支出 × 100（2dp）}、{@code savings ≠ 0} 且角色为 {@code IMPROVE}
     *       当且仅当 {@code savings > 0}、否则 {@code OVERSPEND}（需求 3.2、3.3、3.4、3.6、3.7）；且至多一条（去重）。</li>
     *   <li><b>上月为 0 不生成（需求 3.8）</b>：上月总支出为 0 时无 {@code SAVINGS_TOTAL} 且不报错。</li>
     *   <li><b>正向可达（对 N 截断稳健）</b>：{@code 上月总支出 > 0} 且 {@code |savings| ≥ 50.00} 且
     *       {@code savings ≠ 0} 时，{@code SAVINGS_TOTAL} 会被生成为候选；由于 {@code scoreDedupSortTruncate}
     *       先按 score DESC 排序、type 优先级仅用于打破同分平局，其 {@code score = |savings|}（边界处可能仅
     *       50.00）可能低于多个 {@code CATEGORY_DELTA/TOP_MOVER} 候选而被截出 top-N=5。故仅在返回列表未满
     *       （{@code size < N=5}，无挤占）时断言其<b>必然</b>出现；列表已满时它可能被更高分候选合法截断，跳过存在性
     *       断言（需求 3.4、3.5）。</li>
     * </ol>
     *
     * <p>Validates: Requirements 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8</p>
     */
    @Property(tries = 25)
    void property6_savingsTotalGatingArithmeticAndRole(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = -1) int monthOffset,
            @ForAll("savingsSpecs") List<SavingsSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset < 0 → final（已完结）
        YearMonth prev = target.minusMonths(1);

        // 每个分类在 prev 落一笔（= prevCents）、在 M 落一笔（= curCents），支出总额随分类求和 → 覆盖门控两侧与 prev==0 分支。
        for (int i = 0; i < specs.size(); i++) {
            Long cid = saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId();
            SavingsSpec s = specs.get(i);
            if (s.prevCents() > 0) {
                persist(ledgerId, TransactionType.EXPENSE, BigDecimal.valueOf(s.prevCents()).movePointLeft(2),
                        prev.atDay(10).atTime(9, 30), cid);
            }
            if (s.curCents() > 0) {
                persist(ledgerId, TransactionType.EXPENSE, BigDecimal.valueOf(s.curCents()).movePointLeft(2),
                        target.atDay(10).atTime(9, 30), cid);
            }
        }

        // 参照模型：与生产同口径的月度总支出（排除 transfer、Asia/Shanghai 半开区间、2dp HALF_UP）。
        ReportService rs = reportService();
        BigDecimal curTotalExpense = rs.monthlyReport(ledgerId, target).totalExpense();
        BigDecimal prevTotalExpense = rs.monthlyReport(ledgerId, prev).totalExpense();
        BigDecimal savings = scale2(prevTotalExpense.subtract(curTotalExpense));

        BigDecimal savingsMin = new AiInsightProperties().getSavingsAmountMin().abs(); // 50.00

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 收集 SAVINGS_TOTAL 洞察（去重：至多一条）。
        AiInsight savingsInsight = null;
        for (AiInsight in : resp.insights()) {
            if (AiInsightService.TYPE_SAVINGS_TOTAL.equals(in.type())) {
                assertThat(savingsInsight)
                        .as("target=%s SAVINGS_TOTAL 至多一条（去重）", target)
                        .isNull();
                savingsInsight = in;
            }
        }

        boolean prevPositive = prevTotalExpense.compareTo(BigDecimal.ZERO) > 0;
        boolean gateAmount = savings.abs().compareTo(savingsMin) >= 0;
        boolean nonZero = savings.signum() != 0;
        String label = String.format("target=%s prevTotal=%s curTotal=%s savings=%s",
                target, prevTotalExpense, curTotalExpense, savings);

        // 断言 2：上月总支出为 0 → 无 SAVINGS_TOTAL、不报错（需求 3.8）；此结论与是否截断无关。
        if (!prevPositive) {
            assertThat(savingsInsight)
                    .as("%s 上月总支出为 0 → 无 SAVINGS_TOTAL（需求 3.8）", label)
                    .isNull();
        }

        // 断言 3（正向可达，对 N 截断稳健）：prev>0 && |savings|≥50 && savings≠0 → SAVINGS_TOTAL 应被生成为候选。
        // 但 scoreDedupSortTruncate 先按 score DESC 排序、type 优先级只用于打破同分平局；SAVINGS_TOTAL 的
        // score = |savings|（例如边界处仅 50.00）可能低于 5 个以上 CATEGORY_DELTA/TOP_MOVER 候选（|deltaAmount|>50），
        // 从而被截断出 top-N=5。故仅当返回列表未满（size < N=5）时才断言其必然在场——此时不存在挤占，达标候选不会被截断；
        // 列表已满（size == 5）时它可能被更高分候选合法截掉，跳过存在性断言（断言 1 在其出现时仍完整校验正确性）（需求 3.4、3.5）。
        if (prevPositive && gateAmount && nonZero && resp.insights().size() < 5) {
            assertThat(savingsInsight)
                    .as("%s 达标且列表未满（<5）应出现 SAVINGS_TOTAL（需求 3.4、3.5）", label)
                    .isNotNull();
        }

        // 断言 1：存在即正确——门控、算术、角色与参照逐值相等（需求 3.2、3.3、3.4、3.6、3.7）。
        if (savingsInsight != null) {
            assertThat(prevTotalExpense)
                    .as("%s 存在 SAVINGS_TOTAL → 上月总支出 > 0（需求 3.8）", label)
                    .isGreaterThan(BigDecimal.ZERO);
            assertThat(savings.abs())
                    .as("%s |savings| ≥ 金额下限 50.00（需求 3.4、3.5）", label)
                    .isGreaterThanOrEqualTo(savingsMin);
            assertThat(savings.signum())
                    .as("%s savings ≠ 0（收支持平不构成节省/多花）", label)
                    .isNotZero();
            assertThat(savingsInsight.currentValue())
                    .as("%s currentValue == monthlyReport(M).totalExpense（需求 3.1）", label)
                    .isEqualByComparingTo(curTotalExpense);
            assertThat(savingsInsight.previousValue())
                    .as("%s previousValue == monthlyReport(prev).totalExpense（需求 3.1）", label)
                    .isEqualByComparingTo(prevTotalExpense);
            assertThat(savingsInsight.deltaAmount())
                    .as("%s deltaAmount == prevTotalExpense − curTotalExpense（需求 3.2）", label)
                    .isEqualByComparingTo(savings);
            assertThat(savingsInsight.changeRate())
                    .as("%s changeRate == savings ÷ prevTotalExpense × 100（2dp）（需求 3.3）", label)
                    .isEqualByComparingTo(pct(savings, prevTotalExpense));

            // 角色：savings > 0 → IMPROVE（节省）、savings < 0 → OVERSPEND（多花）（需求 3.6、3.7）。
            String expectedRole = savings.signum() > 0
                    ? AiInsightService.ROLE_IMPROVE
                    : AiInsightService.ROLE_OVERSPEND;
            assertThat(savingsInsight.role())
                    .as("%s 角色 IMPROVE⟺savings>0 / OVERSPEND⟺savings<0（需求 3.6、3.7）", label)
                    .isEqualTo(expectedRole);
        }
    }

    // ---------------- Property 7 ----------------

    /** 单个维度对象（分类或商户）的 prev / cur 支出笔数规格；prev 为 0 表示上月无笔数（→ 变化率无定义分支）。 */
    private record FreqCountSpec(int prevCount, int curCount) { }

    /**
     * Property 7 专用生成器：1..4 个维度对象，各自的上月 / 目标月支出<b>笔数</b>以
     * {@code prevCount ∈ oneOf(just(0), 1..10)}、{@code curCount ∈ 0..12} 取值，使「上月笔数为 0」
     * （变化率无定义、恒不生成，需求 4.3）与「上月笔数 &gt; 0」两侧都获得充分覆盖，并让笔数变化量
     * （跨 0/±1/±2..）与笔数变化率（跨 20.00% 上限）落在<b>次数下限（2 笔）与变化率下限（20.00%）两侧</b>
     * （达标 / 不足）均可触达，从而覆盖门控两侧。分类维度与商户维度各取一份该规格。
     */
    @Provide
    Arbitrary<List<FreqCountSpec>> freqCountSpecs() {
        Arbitrary<Integer> prevCount = Arbitraries.oneOf(
                Arbitraries.just(0),
                Arbitraries.integers().between(1, 10));
        Arbitrary<Integer> curCount = Arbitraries.integers().between(0, 12);
        return Combinators.combine(prevCount, curCount).as(FreqCountSpec::new)
                .list().ofMinSize(1).ofMaxSize(4);
    }

    /**
     * Feature: ai-fun-analysis, Property 7: 频次变化门控、笔数算术与方向。
     *
     * <p>对任意（已完结）目标月与交易集合，覆盖<b>分类维度</b>（{@link AiInsightService#DIMENSION_CATEGORY}）
     * 与<b>商户维度</b>（{@link AiInsightService#DIMENSION_MERCHANT}）：对每个维度对象，
     * {@code deltaCount = 目标月笔数 − 上月笔数}（整数），笔数变化率
     * {@code countRate = deltaCount ÷ 上月笔数 × 100} 仅在上月笔数 &gt; 0 时有定义（2dp，HALF_UP）；生成
     * {@code FREQUENCY_DELTA} 洞察当且仅当「上月笔数 &gt; 0 且 {@code |countRate| ≥ frequencyRatePctMin}
     * （20.00%）且 {@code |deltaCount| ≥ frequencyCountMin}（2 笔）」；方向 {@code DOWN} 当且仅当目标月笔数
     * &lt; 上月笔数、{@code UP} 当且仅当 &gt;。</p>
     *
     * <p>参照口径：以真实 {@link ReportService#categoryReport} 的每分类 {@code count()}（分类维度）与
     * {@link ReportService#dimensionReport}（{@code dim=merchant}、{@code kind=EXPENSE}）的每商户
     * {@code count()}（商户维度）作为目标月 / 上月笔数的模型对照（同口径：排除 transfer、{@code Asia/Shanghai}
     * 半开区间）。目标月固定早于当前月（{@code monthOffset < 0}）以取 {@code final}（partial 会短路兜底）。</p>
     *
     * <p>播种：商户维度与分类维度均以确定性锚点覆盖各分支——商户 A（prev 5 / cur 2 → Δ−3、−60%，DOWN 达标）、
     * B（prev 2 / cur 6 → Δ+4、+200%，UP 达标）、C（prev 0 / cur 3 → 上月为 0、恒不生成）、D（prev 3 / cur 3 →
     * Δ0，次数不足）、E（prev 10 / cur 11 → Δ+1、+10%，次数与变化率均不足）；分类维度同构（另含 prev 1 / cur 1
     * 的次数不足分支）。再叠加随机 {@code freqCountSpecs}（两份，分别用于商户与分类）覆盖两侧阈值。所有支出金额取
     * {@code 0.01} 元的极小值：既让笔数打分（{@code |deltaCount|}）在跨类型排序中占主导（{@code FREQUENCY_DELTA}
     * 候选因此几乎不被 N 截断，令正向断言充分被触达），又使 {@code |deltaAmount|} 远不足金额门控（不生成
     * {@code CATEGORY_DELTA} / {@code SAVINGS_TOTAL}）。断言（对 N 截断稳健——仅对<b>返回的</b>洞察做正向断言、
     * 对<b>上月笔数为 0</b> 的对象做永不生成断言，二者与是否截断无关）：</p>
     * <ol>
     *   <li><b>存在即正确</b>：每条返回的 {@code FREQUENCY_DELTA} 都满足 上月笔数 &gt; 0、{@code |countRate| ≥ 20.00}、
     *       {@code |deltaCount| ≥ 2}，且 {@code currentCount / previousCount / deltaCount} 与对应维度参照笔数逐值相等、
     *       {@code changeRate == countRate}、方向 {@code DOWN⟺cur<prev / UP⟺cur>prev}、{@code dimension / dimensionId}
     *       正确（需求 4.3、4.4、4.5）。</li>
     *   <li><b>上月为 0 不生成（需求 4.3）</b>：任一维度对象上月笔数为 0 时绝不产出 {@code FREQUENCY_DELTA}（无关截断）。</li>
     * </ol>
     *
     * <p>Validates: Requirements 4.3, 4.4, 4.5</p>
     */
    @Property(tries = 25)
    void property7_frequencyDeltaGatingCountArithmeticAndDirection(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = -1) int monthOffset,
            @ForAll("freqCountSpecs") List<FreqCountSpec> merchantSpecs,
            @ForAll("freqCountSpecs") List<FreqCountSpec> categorySpecs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset < 0 → final（已完结）
        YearMonth prev = target.minusMonths(1);

        final String unit = "0.01"; // 极小金额：让笔数打分主导排序、且金额远不足门控。

        // 商户维度：一个共享分类桶承载全部商户支出（其分类维度频次亦被参照覆盖，不影响正确性）。
        Long merchantBucketCat = saveCategory(ledgerId, CategoryKind.EXPENSE, "m" + ledgerId).getId();
        long mBase = 7_100L;
        // 确定性锚点（id, prevCount, curCount）覆盖各分支。
        int[][] merchantAnchors = { {1, 5, 2}, {2, 2, 6}, {3, 0, 3}, {4, 3, 3}, {5, 10, 11} };
        for (int[] a : merchantAnchors) {
            long mid = mBase + a[0];
            if (a[1] > 0) {
                seedMerchant(ledgerId, prev, mid, a[1], merchantBucketCat, unit);
            }
            if (a[2] > 0) {
                seedMerchant(ledgerId, target, mid, a[2], merchantBucketCat, unit);
            }
        }
        // 随机商户规格：从一个不与锚点重叠的 id 段起分配。
        long mRandBase = mBase + 100;
        for (int i = 0; i < merchantSpecs.size(); i++) {
            long mid = mRandBase + i;
            FreqCountSpec s = merchantSpecs.get(i);
            if (s.prevCount() > 0) {
                seedMerchant(ledgerId, prev, mid, s.prevCount(), merchantBucketCat, unit);
            }
            if (s.curCount() > 0) {
                seedMerchant(ledgerId, target, mid, s.curCount(), merchantBucketCat, unit);
            }
        }

        // 分类维度：每个对象一个专属分类（无商户），确定性锚点 + 随机规格。
        int[][] categoryAnchors = { {5, 2}, {2, 8}, {1, 1}, {0, 3}, {3, 3}, {10, 11} };
        List<Long> catFreqIds = new ArrayList<>();
        for (int i = 0; i < categoryAnchors.length; i++) {
            Long cid = saveCategory(ledgerId, CategoryKind.EXPENSE, "cf" + ledgerId + "-a" + i).getId();
            catFreqIds.add(cid);
            int p = categoryAnchors[i][0];
            int c = categoryAnchors[i][1];
            if (p > 0) {
                seedCategoryCount(ledgerId, prev, cid, p, unit);
            }
            if (c > 0) {
                seedCategoryCount(ledgerId, target, cid, c, unit);
            }
        }
        for (int i = 0; i < categorySpecs.size(); i++) {
            Long cid = saveCategory(ledgerId, CategoryKind.EXPENSE, "cf" + ledgerId + "-r" + i).getId();
            catFreqIds.add(cid);
            FreqCountSpec s = categorySpecs.get(i);
            if (s.prevCount() > 0) {
                seedCategoryCount(ledgerId, prev, cid, s.prevCount(), unit);
            }
            if (s.curCount() > 0) {
                seedCategoryCount(ledgerId, target, cid, s.curCount(), unit);
            }
        }

        // 参照模型：与生产同口径的分类笔数（categoryReport）与商户笔数（dimensionReport(dim=merchant)）。
        ReportService rs = reportService();
        CategoryReportResponse curCat = rs.categoryReport(ledgerId, target.atDay(1), target.atEndOfMonth());
        CategoryReportResponse prevCat = rs.categoryReport(ledgerId, prev.atDay(1), prev.atEndOfMonth());
        DimensionReportResponse curMer = rs.dimensionReport(
                ledgerId, target.atDay(1), target.atEndOfMonth(), TransactionType.EXPENSE, "merchant");
        DimensionReportResponse prevMer = rs.dimensionReport(
                ledgerId, prev.atDay(1), prev.atEndOfMonth(), TransactionType.EXPENSE, "merchant");

        Map<Long, Long> curCatCount = new HashMap<>();
        for (CategoryShare s : curCat.categories()) {
            curCatCount.put(s.categoryId(), s.count());
        }
        Map<Long, Long> prevCatCount = new HashMap<>();
        for (CategoryShare s : prevCat.categories()) {
            prevCatCount.put(s.categoryId(), s.count());
        }
        Map<Long, Long> curMerCount = new HashMap<>();
        for (DimensionShare s : curMer.items()) {
            curMerCount.put(s.id(), s.count());
        }
        Map<Long, Long> prevMerCount = new HashMap<>();
        for (DimensionShare s : prevMer.items()) {
            prevMerCount.put(s.id(), s.count());
        }

        BigDecimal rateMin = new AiInsightProperties().getFrequencyRatePctMin().abs(); // 20.00
        int countMin = Math.abs(new AiInsightProperties().getFrequencyCountMin());     // 2

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 收集返回的 FREQUENCY_DELTA 洞察，按 (dimension, dimensionId) 索引。
        Map<String, AiInsight> returnedByKey = new HashMap<>();
        for (AiInsight in : resp.insights()) {
            if (AiInsightService.TYPE_FREQUENCY_DELTA.equals(in.type())) {
                returnedByKey.put(in.dimension() + "#" + in.dimensionId(), in);
            }
        }

        // 断言 1：每条返回的 FREQUENCY_DELTA 都满足门控、笔数算术、变化率与方向（与对应维度参照逐值相等）。
        for (AiInsight in : returnedByKey.values()) {
            boolean isMerchant = AiInsightService.DIMENSION_MERCHANT.equals(in.dimension());
            long refCur = (isMerchant ? curMerCount : curCatCount).getOrDefault(in.dimensionId(), 0L);
            long refPrev = (isMerchant ? prevMerCount : prevCatCount).getOrDefault(in.dimensionId(), 0L);
            long delta = refCur - refPrev;
            String label = String.format("target=%s dim=%s id=%s cur=%d prev=%d",
                    target, in.dimension(), in.dimensionId(), refCur, refPrev);

            // 门控：上月笔数 > 0（需求 4.3）、|变化率| ≥ 下限、|变化量| ≥ 次数下限（需求 4.4）。
            assertThat(refPrev)
                    .as("%s 上月笔数 > 0（需求 4.3、4.4）", label)
                    .isGreaterThan(0L);
            BigDecimal countRate = pct(BigDecimal.valueOf(delta), BigDecimal.valueOf(refPrev));
            assertThat(countRate.abs())
                    .as("%s |笔数变化率| ≥ 变化率下限 20.00（需求 4.4）", label)
                    .isGreaterThanOrEqualTo(rateMin);
            assertThat(Math.abs(delta))
                    .as("%s |笔数变化量| ≥ 次数下限 2（需求 4.4）", label)
                    .isGreaterThanOrEqualTo((long) countMin);

            // 字段：维度、维度 id 与逐值笔数算术（需求 4.3、4.4）。
            assertThat(in.dimensionId())
                    .as("%s dimensionId 在场", label)
                    .isNotNull();
            assertThat(in.currentCount())
                    .as("%s currentCount == 报表笔数（M）", label)
                    .isEqualTo((int) refCur);
            assertThat(in.previousCount())
                    .as("%s previousCount == 报表笔数（prev）", label)
                    .isEqualTo((int) refPrev);
            assertThat(in.deltaCount())
                    .as("%s deltaCount == curCount − prevCount", label)
                    .isEqualTo((int) delta);
            assertThat(in.changeRate())
                    .as("%s changeRate == deltaCount ÷ prevCount × 100（2dp）（需求 4.3）", label)
                    .isEqualByComparingTo(countRate);

            // 方向：DOWN ⟺ cur < prev、UP ⟺ cur > prev（需求 4.5）。门控保证 |delta| ≥ 2 → cur ≠ prev。
            String expectedDir = refCur < refPrev
                    ? AiInsightService.DIRECTION_DOWN
                    : AiInsightService.DIRECTION_UP;
            assertThat(in.direction())
                    .as("%s 方向 DOWN⟺cur<prev / UP⟺cur>prev（需求 4.5）", label)
                    .isEqualTo(expectedDir);
        }

        // 断言 2：上月笔数为 0 的维度对象绝不产出 FREQUENCY_DELTA（需求 4.3）；与是否截断无关。
        for (Long id : curMerCount.keySet()) {
            if (prevMerCount.getOrDefault(id, 0L) == 0L) {
                assertThat(returnedByKey)
                        .as("target=%s 商户 id=%s 上月笔数为 0 → 无 FREQUENCY_DELTA（需求 4.3）", target, id)
                        .doesNotContainKey(AiInsightService.DIMENSION_MERCHANT + "#" + id);
            }
        }
        for (Long id : curCatCount.keySet()) {
            if (prevCatCount.getOrDefault(id, 0L) == 0L) {
                assertThat(returnedByKey)
                        .as("target=%s 分类 id=%s 上月笔数为 0 → 无 FREQUENCY_DELTA（需求 4.3）", target, id)
                        .doesNotContainKey(AiInsightService.DIMENSION_CATEGORY + "#" + id);
            }
        }
    }

    /** 在指定自然月为某分类落 {@code count} 笔支出（第 3..(3+count) 日，与商户播种日错开），金额固定。 */
    private void seedCategoryCount(long ledgerId, YearMonth ym, Long categoryId, int count, String amount) {
        for (int i = 0; i < count; i++) {
            int day = Math.min(3 + i, ym.lengthOfMonth());
            LocalDateTime when = ym.atDay(day).atTime(8, 15);
            persist(ledgerId, TransactionType.EXPENSE, new BigDecimal(amount), when, categoryId);
        }
    }

    // ---------------- Property 8 ----------------

    /**
     * Property 8 专用生成器：目标月 M 及其前 5 个自然月（M−5..M，升序，下标 0=M−5 … 5=M）的<b>按月支出档位</b>。
     * 每个月的档位取自小集合 {@code 0..6}（对应 {@code 0/3/6/9/12/15/18} 元），既频繁产生<b>相等</b>（同档位）与
     * <b>方向反转</b>（升降混合），也覆盖<b>严格单调</b>连续段；并以 {@link Arbitraries#oneOf} 掺入升序 / 降序排列的
     * 序列，稳定地制造较长的干净单调段，从而让连续段长度<b>恰好达到下限 3</b> 与<b>差一个月（2）</b>两侧都被充分触达。
     *
     * <p>档位上界 6（18 元）刻意保持极小：任意相邻两月支出之差 {@code < 20.00} 元故不触发 {@code CATEGORY_DELTA}
     * 门控（金额下限 20.00），单分类月总支出 {@code < 50.00} 元故不触发 {@code SAVINGS_TOTAL} 门控（金额下限 50.00），
     * 每月仅一笔支出故任意维度笔数变化 {@code |Δ| ≤ 1 < 2} 不触发 {@code FREQUENCY_DELTA}。因此单分类下候选总数至多为
     * {@code TREND_STREAK(≤1) + TOP_MOVER(≤1) = 2 ≪ N(默认 5)}——绝不发生 N 截断掩盖本应出现的 {@code TREND_STREAK}，
     * 得以对「达标必然出现」做正向断言，同时把对其它四类洞察的影响降到最低。</p>
     */
    @Provide
    Arbitrary<List<Integer>> monthlyLevels() {
        Arbitrary<Integer> level = Arbitraries.integers().between(0, 6);
        Arbitrary<List<Integer>> random6 = level.list().ofSize(6);
        Arbitrary<List<Integer>> ascending = level.list().ofSize(6).map(l -> {
            List<Integer> copy = new ArrayList<>(l);
            copy.sort(Integer::compareTo);
            return copy;
        });
        Arbitrary<List<Integer>> descending = level.list().ofSize(6).map(l -> {
            List<Integer> copy = new ArrayList<>(l);
            copy.sort((a, b) -> Integer.compare(b, a));
            return copy;
        });
        return Arbitraries.oneOf(random6, ascending, descending);
    }

    /**
     * Feature: ai-fun-analysis, Property 8: 连续涨跌段检测、门控与方向。
     *
     * <p>对<b>单个支出分类</b>在 M−5..M（6 个自然月）上的任意按月支出序列（含相等、含反转、含严格单调段），
     * 以<b>暴力参照实现</b>为对照：先用真实 {@link ReportService#categoryReport} 逐月重建该分类的按月支出序列
     * （无数据月计 {@code 0.00}、同口径），再以目标月 M 为锚点<b>倒序</b>逐一比较相邻两月，做严格单调延伸——遇相邻
     * 两月相等（含均为 {@code 0.00}）或方向反转即终止，连续月数<b>含两端计数</b>（需求 5.2、5.3）。断言：</p>
     * <ol>
     *   <li><b>达标必然出现且字段正确（需求 5.4、5.5）</b>：暴力参照连续月数 {@code ≥ streakMinMonths(3)} 时，
     *       该分类<b>必然</b>有一条 {@code TREND_STREAK}（候选总数 ≤ 2 ≪ N，绝无截断），且
     *       {@code streakMonths == 参照连续月数}、{@code direction} 递减→{@code DOWN}/递增→{@code UP}（需求 5.5）、
     *       {@code streakEndMonth == M}、{@code streakStartMonth == 参照连续段起始月}、
     *       {@code dimension == CATEGORY}、{@code dimensionId == 该分类}（需求 5.4）。</li>
     *   <li><b>不达标不生成（需求 5.6）</b>：暴力参照连续月数 {@code < 3}（含差一个月的 2）时，该分类<b>不得</b>
     *       出现任何 {@code TREND_STREAK}。</li>
     * </ol>
     *
     * <p>播种：单分类，每个档位 {@code > 0} 的自然月落<b>一笔</b>该档位金额的支出（档位 0 → 无交易 → 该月支出
     * {@code 0.00}）。目标月固定早于当前月（{@code monthOffset ∈ [−14,−1]}）以取 {@code final}（{@code partial} 会
     * 短路兜底、不产出洞察）且使 M−5 为真实自然月。对任一达标（{@code ≥3}）连续段，其必含 M−1 且 M−1 支出 {@code > 0}
     * （单调段两端严格有序），故上月总支出 {@code > 0}、可比基线检查天然通过、不会因无基线兜底而掩盖达标洞察。
     * 属性驱动 ≥100 次迭代。</p>
     *
     * <p>Validates: Requirements 5.2, 5.3, 5.4, 5.5, 5.6</p>
     */
    @Property(tries = 25)
    void property8_trendStreakDetectionGatingAndDirection(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = -1) int monthOffset,
            @ForAll("monthlyLevels") List<Integer> levels) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset < 0 → final（已完结）

        // 单个支出分类：档位 level 映射为 level*3 元（0/3/6/9/12/15/18），档位 0 表示该月无交易（→ 支出 0.00）。
        Long catId = saveCategory(ledgerId, CategoryKind.EXPENSE, "streak" + ledgerId).getId();
        for (int idx = 0; idx < 6; idx++) {
            int level = levels.get(idx);
            if (level > 0) {
                YearMonth ym = target.minusMonths(5 - idx); // idx 0 → M−5 … idx 5 → M
                BigDecimal amount = BigDecimal.valueOf((long) level * 3); // 元
                persist(ledgerId, TransactionType.EXPENSE, amount, ym.atDay(10).atTime(9, 30), catId);
            }
        }

        // 暴力参照：以真实 categoryReport 逐月重建 M−5..M 的该分类支出序列（无数据月 = 0.00，同口径）。
        ReportService rs = reportService();
        BigDecimal[] series = new BigDecimal[6];
        for (int idx = 0; idx < 6; idx++) {
            YearMonth ym = target.minusMonths(5 - idx);
            CategoryReportResponse rep = rs.categoryReport(ledgerId, ym.atDay(1), ym.atEndOfMonth());
            BigDecimal amt = scale2(BigDecimal.ZERO);
            for (CategoryShare cs : rep.categories()) {
                if (cs.categoryId().equals(catId)) {
                    amt = scale2(cs.amount());
                    break;
                }
            }
            series[idx] = amt;
        }

        // 暴力参照的连续段计算（与需求 5.2/5.3 的语义独立实现）：以锚点 M（idx 5）倒序延伸严格单调段。
        int anchor = series.length - 1;
        int expectedStreak = 1;      // 含锚点月 M（含两端计数）。
        int startIndex = anchor;
        Boolean decreasing = null;
        for (int i = anchor; i >= 1; i--) {
            int cmp = series[i].compareTo(series[i - 1]);
            if (cmp == 0) {
                break; // 相邻两月相等（含均为 0.00）即终止（需求 5.3）。
            }
            boolean stepDown = cmp < 0; // 较晚月严格小于较早月 → 一步递减。
            if (decreasing == null) {
                decreasing = stepDown;
            } else if (decreasing != stepDown) {
                break; // 方向反转即终止（需求 5.3）。
            }
            expectedStreak++;
            startIndex = i - 1;
        }

        int streakMin = new AiInsightProperties().getStreakMinMonths(); // 3
        boolean expectGenerate = decreasing != null && expectedStreak >= streakMin;
        String expectedDirection = (decreasing != null && decreasing)
                ? AiInsightService.DIRECTION_DOWN
                : AiInsightService.DIRECTION_UP;
        YearMonth expectedStartMonth = target.minusMonths(5 - startIndex);

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 收集该分类的 TREND_STREAK 洞察（至多一条）。
        AiInsight streakInsight = null;
        for (AiInsight in : resp.insights()) {
            if (AiInsightService.TYPE_TREND_STREAK.equals(in.type()) && catId.equals(in.dimensionId())) {
                assertThat(streakInsight)
                        .as("target=%s catId=%s TREND_STREAK 至多一条", target, catId)
                        .isNull();
                streakInsight = in;
            }
        }

        String label = String.format("target=%s catId=%s series=%s expectedStreak=%d expectGen=%b",
                target, catId, java.util.Arrays.toString(series), expectedStreak, expectGenerate);

        if (expectGenerate) {
            // 达标（≥3）→ 必然出现且字段与参照逐值一致（需求 5.4、5.5；候选总数 ≤ 2 ≪ N，无截断）。
            assertThat(streakInsight)
                    .as("%s 达标连续段应出现 TREND_STREAK（无截断，需求 5.4）", label)
                    .isNotNull();
            assertThat(streakInsight.streakMonths())
                    .as("%s streakMonths == 参照连续月数（含两端，需求 5.2、5.4）", label)
                    .isEqualTo(expectedStreak);
            assertThat(streakInsight.direction())
                    .as("%s 方向 DOWN⟺递减 / UP⟺递增（需求 5.5）", label)
                    .isEqualTo(expectedDirection);
            assertThat(streakInsight.streakEndMonth())
                    .as("%s streakEndMonth == 目标月 M（需求 5.4）", label)
                    .isEqualTo(target.toString());
            assertThat(streakInsight.streakStartMonth())
                    .as("%s streakStartMonth == 参照连续段起始月（需求 5.4）", label)
                    .isEqualTo(expectedStartMonth.toString());
            assertThat(streakInsight.dimension())
                    .as("%s 维度为 CATEGORY（需求 5.4）", label)
                    .isEqualTo(AiInsightService.DIMENSION_CATEGORY);
            assertThat(streakInsight.dimensionId())
                    .as("%s dimensionId == 该分类（需求 5.4）", label)
                    .isEqualTo(catId);
        } else {
            // 不达标（<3，含差一个月的 2）→ 该分类不得出现任何 TREND_STREAK（需求 5.6）。
            assertThat(streakInsight)
                    .as("%s 连续月数 < 下限 → 无 TREND_STREAK（需求 5.6）", label)
                    .isNull();
        }
    }

    // ---------------- Property 9 ----------------

    /** 单个分类的上月 / 目标月支出档位（0..5，映射为 {@code level*3} 元）；上月档位 0 表示上月无支出（→ 不入候选）。 */
    private record TopMoverLevelSpec(int prevLevel, int curLevel) { }

    /**
     * Property 9 专用生成器：1..5 个支出分类，各自的上月 / 目标月支出<b>档位</b>取自小集合 {@code 0..5}
     * （映射为 {@code 0/3/6/9/12/15} 元）。上月档位以 {@link Arbitraries#oneOf} 掺入 {@code just(0)}，使
     * 「上月支出为 0」（不入候选，覆盖需求 6.5 的空/部分候选）与「上月支出 &gt; 0」（入候选）两侧都获得充分覆盖。
     *
     * <p>档位空间刻意<b>离散且小</b>：变化量 {@code delta = (curLevel − prevLevel) × 3} 元落在
     * {@code [−15, +15]} 的 11 个离散值内，故多个分类<b>频繁共享同一最小/最大变化量</b>——从而稳定触达
     * 「并列以分类 id 升序决胜」（需求 6.4）与「单候选 / 全体并列塌缩到同一分类」的去重分支。同时 {@code |delta| ≤ 15
     * &lt; 20.00}（金额下限）故不触发 {@code CATEGORY_DELTA}；每（分类, 月）仅一笔支出故任意维度笔数变化
     * {@code |Δ| ≤ 1 &lt; 2} 不触发 {@code FREQUENCY_DELTA}；配合 M−2 阻断笔（见下）不触发 {@code TREND_STREAK}。
     * 因此可能出现的洞察仅 {@code TOP_MOVER}（≤2）与 {@code SAVINGS_TOTAL}（≤1），候选总数 {@code ≤ 3 ≪ N（默认 5）}
     * ——{@code TOP_MOVER}（类型优先级 1，仅次于 {@code SAVINGS_TOTAL}）<b>绝不被 N 截断</b>，得以对其选取结果做逐值断言。</p>
     */
    @Provide
    Arbitrary<List<TopMoverLevelSpec>> topMoverLevelSpecs() {
        Arbitrary<Integer> prevLevel = Arbitraries.oneOf(
                Arbitraries.just(0),
                Arbitraries.integers().between(1, 5));
        Arbitrary<Integer> curLevel = Arbitraries.integers().between(0, 5);
        return Combinators.combine(prevLevel, curLevel).as(TopMoverLevelSpec::new)
                .list().ofMinSize(1).ofMaxSize(5);
    }

    /**
     * Feature: ai-fun-analysis, Property 9: 最大改善/最超支选择与确定性决胜。
     *
     * <p>对任意（已完结）目标月与交易集合，以<b>暴力参照实现</b>为对照：用真实
     * {@link ReportService#categoryReport} 逐月重建目标月 M 与上月 prev 的每分类支出（同口径：排除 transfer、
     * {@code Asia/Shanghai} 半开区间、2dp HALF_UP），候选集合恰为「上月分类支出 &gt; 0」的分类（需求 6.1），
     * 每个候选的 {@code deltaAmount = 目标月支出 − 上月支出}（2dp）。参照选取（需求 6.2、6.4）：改善 = 变化量
     * <b>最小</b>者、超支 = 变化量<b>最大</b>者，<b>并列以分类 id 升序</b>决胜各选唯一一个；若改善与超支落在<b>同一分类</b>
     * （单候选，或全体变化量并列塌缩到同一 id）则依「同维度同类型至多一条」去重（需求 7.5）只保留一条，{@code role} 由
     * 该分类 {@code deltaAmount} 符号决定（{@code <0→IMPROVE}、{@code >0→OVERSPEND}、{@code ==0→不生成}）；候选集合为空
     * → 不生成任何 {@code TOP_MOVER}（需求 6.5）。断言：</p>
     * <ol>
     *   <li><b>选取集合正确</b>：返回的 {@code TOP_MOVER} 的 {@code (分类 id → role)} 映射与参照<b>完全相等</b>——含
     *       两分类各一条、同分类塌缩为一条（role 由符号决定）、{@code delta==0} 塌缩不生成、候选为空不生成（需求 6.2、6.4、6.5）。</li>
     *   <li><b>字段与 role 正确</b>：每条返回的 {@code TOP_MOVER} 维度为 {@code CATEGORY}、{@code currentValue / previousValue /
     *       deltaAmount / changeRate} 与参照逐值相等（{@code changeRate = deltaAmount ÷ prev × 100}，2dp），{@code role} 与参照一致，
     *       且 {@code direction} 为 {@code null}（方向语义由 role 表达）（需求 6.3）。</li>
     *   <li><b>确定性决胜（tie-break）</b>：离散小档位使多分类频繁并列最小/最大，参照与返回均以分类 id 升序各选唯一一个
     *       → 选中者恒为并列集合中的最小分类 id（需求 6.4）。</li>
     * </ol>
     *
     * <p>播种：每分类在 prev 落一笔（= 上月档位）、在 M 落一笔（= 目标月档位）；上月档位 &gt; 0 时在 M−2 放一笔<b>等于上月</b>
     * 的阻断笔，使 M−1 与 M−2 相等 → 任何连续单调段在 M−1 处终止（长度 ≤ 2 &lt; 3）不触发 {@code TREND_STREAK}；M−2 不落入
     * {@code categoryReport(M)/categoryReport(prev)} 的月范围，故不影响候选与派生指标。目标月固定早于当前月
     * （{@code monthOffset < 0}）以取 {@code final} 且具可比基线（{@code partial} 会短路兜底、不产出洞察）。属性驱动 ≥120 次迭代。</p>
     *
     * <p>Validates: Requirements 6.2, 6.3, 6.4, 6.5</p>
     */
    @Property(tries = 25)
    void property9_topMoverSelectionAndDeterministicTieBreak(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = -1) int monthOffset,
            @ForAll("topMoverLevelSpecs") List<TopMoverLevelSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset < 0 → final（已完结）
        YearMonth prev = target.minusMonths(1);
        YearMonth beforePrev = target.minusMonths(2);

        // 每个分类：prev 落一笔（= prevLevel×3 元）、M 落一笔（= curLevel×3 元）；prev>0 时 M−2 放等于 prev 的阻断笔（防 TREND_STREAK）。
        List<Long> catIds = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            Long cid = saveCategory(ledgerId, CategoryKind.EXPENSE, "tm" + ledgerId + "-" + i).getId();
            catIds.add(cid);
            TopMoverLevelSpec s = specs.get(i);
            if (s.prevLevel() > 0) {
                BigDecimal prevAmount = BigDecimal.valueOf((long) s.prevLevel() * 3);
                persist(ledgerId, TransactionType.EXPENSE, prevAmount, prev.atDay(10).atTime(9, 30), cid);
                // M−2 阻断笔（= prev），仅用于终止连续段（防 TREND_STREAK），不进入 categoryReport(M)/categoryReport(prev) 的月范围。
                persist(ledgerId, TransactionType.EXPENSE, prevAmount, beforePrev.atDay(10).atTime(9, 30), cid);
            }
            if (s.curLevel() > 0) {
                BigDecimal curAmount = BigDecimal.valueOf((long) s.curLevel() * 3);
                persist(ledgerId, TransactionType.EXPENSE, curAmount, target.atDay(10).atTime(9, 30), cid);
            }
        }

        // 参照模型：与生产同口径的 categoryReport(M) / categoryReport(prev)（默认 EXPENSE、Asia/Shanghai、2dp HALF_UP）。
        ReportService rs = reportService();
        CategoryReportResponse curCat = rs.categoryReport(ledgerId, target.atDay(1), target.atEndOfMonth());
        CategoryReportResponse prevCat = rs.categoryReport(ledgerId, prev.atDay(1), prev.atEndOfMonth());
        Map<Long, BigDecimal> curAmt = new HashMap<>();
        for (CategoryShare cs : curCat.categories()) {
            curAmt.put(cs.categoryId(), cs.amount());
        }
        Map<Long, BigDecimal> prevAmt = new HashMap<>();
        for (CategoryShare cs : prevCat.categories()) {
            prevAmt.put(cs.categoryId(), cs.amount());
        }

        // 候选集合 = 上月分类支出 > 0 的分类（需求 6.1）。
        List<Long> candidateIds = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : prevAmt.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) > 0) {
                candidateIds.add(e.getKey());
            }
        }

        // 参照选取：改善 = 变化量最小者（并列 id 升序）、超支 = 变化量最大者（并列 id 升序）（需求 6.2、6.4）。
        Long improveId = null;
        BigDecimal improveDelta = null;
        Long overspendId = null;
        BigDecimal overspendDelta = null;
        for (Long id : candidateIds) {
            BigDecimal cur = scale2(curAmt.getOrDefault(id, BigDecimal.ZERO));
            BigDecimal prevA = scale2(prevAmt.get(id));
            BigDecimal delta = scale2(cur.subtract(prevA));
            if (improveId == null
                    || delta.compareTo(improveDelta) < 0
                    || (delta.compareTo(improveDelta) == 0 && id < improveId)) {
                improveId = id;
                improveDelta = delta;
            }
            if (overspendId == null
                    || delta.compareTo(overspendDelta) > 0
                    || (delta.compareTo(overspendDelta) == 0 && id < overspendId)) {
                overspendId = id;
                overspendDelta = delta;
            }
        }

        // 参照期望的 (分类 id → role) 映射：含两分类各一条 / 同分类塌缩一条（role 由符号决定，delta==0 不生成）/ 候选为空不生成。
        Map<Long, String> expectedRoleById = new HashMap<>();
        if (!candidateIds.isEmpty()) {
            if (improveId.equals(overspendId)) {
                int sign = improveDelta.signum();
                if (sign < 0) {
                    expectedRoleById.put(improveId, AiInsightService.ROLE_IMPROVE);
                } else if (sign > 0) {
                    expectedRoleById.put(improveId, AiInsightService.ROLE_OVERSPEND);
                }
                // delta == 0 → 不生成（需求 6.4）。
            } else {
                expectedRoleById.put(improveId, AiInsightService.ROLE_IMPROVE);
                expectedRoleById.put(overspendId, AiInsightService.ROLE_OVERSPEND);
            }
        }

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 收集返回的 TOP_MOVER 洞察，按分类 id 索引（同键至多一条：去重，需求 7.5）。
        Map<Long, AiInsight> returnedById = new HashMap<>();
        for (AiInsight in : resp.insights()) {
            if (AiInsightService.TYPE_TOP_MOVER.equals(in.type())) {
                AiInsight dup = returnedById.put(in.dimensionId(), in);
                assertThat(dup)
                        .as("target=%s catId=%s TOP_MOVER 同分类至多一条（去重，需求 7.5）", target, in.dimensionId())
                        .isNull();
            }
        }

        String label = String.format("target=%s candidateIds=%s expected=%s",
                target, candidateIds, expectedRoleById);

        // 断言 1：返回的 (分类 id → role) 映射与参照完全相等（覆盖两分类 / 同分类塌缩 / delta==0 不生成 / 候选为空不生成）。
        Map<Long, String> returnedRoleById = new HashMap<>();
        for (Map.Entry<Long, AiInsight> e : returnedById.entrySet()) {
            returnedRoleById.put(e.getKey(), e.getValue().role());
        }
        assertThat(returnedRoleById)
                .as("%s 返回的 TOP_MOVER (分类 id → role) 与参照相等（需求 6.2、6.4、6.5）", label)
                .isEqualTo(expectedRoleById);

        // 断言 2：每条返回的 TOP_MOVER 维度 / 字段 / 变化率 / role / 方向均与参照逐值一致（需求 6.3）。
        for (Map.Entry<Long, String> e : expectedRoleById.entrySet()) {
            Long id = e.getKey();
            String role = e.getValue();
            AiInsight in = returnedById.get(id);
            BigDecimal cur = scale2(curAmt.getOrDefault(id, BigDecimal.ZERO));
            BigDecimal prevA = scale2(prevAmt.get(id));
            BigDecimal delta = scale2(cur.subtract(prevA));
            String fieldLabel = String.format("%s catId=%s cur=%s prev=%s delta=%s", label, id, cur, prevA, delta);

            assertThat(in.dimension())
                    .as("%s 维度为 CATEGORY（需求 6.3）", fieldLabel)
                    .isEqualTo(AiInsightService.DIMENSION_CATEGORY);
            assertThat(in.currentValue())
                    .as("%s currentValue == categoryReport(M) 分类支出（需求 6.3）", fieldLabel)
                    .isEqualByComparingTo(cur);
            assertThat(in.previousValue())
                    .as("%s previousValue == categoryReport(prev) 分类支出（需求 6.3）", fieldLabel)
                    .isEqualByComparingTo(prevA);
            assertThat(in.deltaAmount())
                    .as("%s deltaAmount == cur − prev（2dp，需求 6.1、6.3）", fieldLabel)
                    .isEqualByComparingTo(delta);
            assertThat(in.changeRate())
                    .as("%s changeRate == deltaAmount ÷ prev × 100（2dp，需求 6.3）", fieldLabel)
                    .isEqualByComparingTo(pct(delta, prevA));
            assertThat(in.role())
                    .as("%s role 与参照一致（需求 6.2）", fieldLabel)
                    .isEqualTo(role);
            assertThat(in.direction())
                    .as("%s direction 为 null（方向语义由 role 表达，需求 6.3）", fieldLabel)
                    .isNull();
        }
    }

    // ---------------- 落库辅助 ----------------

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

    /** 落一笔带商户归属的支出：既计入 categoryReport（分类口径），也计入 dimensionReport(dim=merchant)（商户口径）。 */
    private void persistMerchantExpense(long ledgerId, BigDecimal amount, LocalDateTime when,
            Long categoryId, Long merchantId) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(amount);
        t.setAccountId(1L);
        t.setCategoryId(categoryId);
        t.setMerchantId(merchantId);
        t.setOccurredAt(when);
        t.setCreatedAt(when);
        t.setUpdatedAt(when);
        transactionRepository.save(t);
    }

    /** 落一个（可能空白名的）商户行；已删除商户则不落任何 Merchant 行、仅在交易中引用其 id。 */
    private Merchant saveMerchant(long ledgerId, String name) {
        Merchant m = new Merchant();
        m.setUserId(1L);
        m.setLedgerId(ledgerId);
        m.setName(name);
        m.setSortOrder(0);
        m.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        m.setUpdatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        return merchantRepository.save(m);
    }

    // ---------------- Property 10 ----------------

    /**
     * Feature: ai-fun-analysis, Property 10: 删除/无名维度对象回退命名且不丢弃。
     *
     * <p>对任意（已完结）目标月与交易集合，任一维度对象（分类或商户）在当前账本中<b>已删除</b>（无对应
     * {@code Category}/{@code Merchant} 行、仅被交易以 id 引用）或<b>名称为空白</b>时，其对应洞察的
     * {@code dimensionName} 必取固定回退名（分类 → {@link InsightNarrator#DELETED_CATEGORY_NAME}
     * 「已删除分类」，商户 → {@link InsightNarrator#DELETED_MERCHANT_NAME}「已删除商户」，同一对象每次相同），
     * 且该洞察<b>不因名称缺失被丢弃</b>。</p>
     *
     * <p>验证手法：分类维度与商户维度各用一个<b>独立账本</b>承载最小可控场景，使门控恰好命中、候选总数远小于 N
     * （默认 5）故无截断掩盖达标洞察，从而可对「达标洞察必然在场」做正向断言：</p>
     * <ul>
     *   <li><b>分类</b>（{@code CATEGORY_DELTA / TOP_MOVER}）：对一个「删除态（无 Category 行，id=固定常量）」或
     *       「空白名（保存 name=&quot;\u3000&quot; 的 Category）」的支出分类，在上月落 100.00 元、目标月落 70.00 元
     *       （变化量 −30.00：{@code |Δ|≥20}、{@code |rate|=30%≥10} 命中 {@code CATEGORY_DELTA} 门控；上月 &gt; 0
     *       故亦为唯一 {@code TOP_MOVER} 候选，塌缩为一条 {@code IMPROVE}）。节省额 30.00 &lt; 50.00 故不生成
     *       {@code SAVINGS_TOTAL}，仅两月数据故不生成 {@code TREND_STREAK}——候选恰 2 条 ≤ N。</li>
     *   <li><b>商户</b>（{@code FREQUENCY_DELTA}，{@link AiInsightService#DIMENSION_MERCHANT}）：对一个「删除态
     *       （无 Merchant 行，id=固定常量）」或「空白名（保存 name=&quot;\u3000&quot; 的 Merchant）」的商户，用
     *       0.01 元极小额在上月落 10 笔、目标月落 2 笔（{@code deltaCount=−8}：{@code |Δ|=8≥2}、{@code rate=−80%≥20%}
     *       命中门控）。极小额使金额类门控（20.00/50.00）全不命中，候选仅 {@code FREQUENCY_DELTA}（商户+承载分类）与
     *       一条 {@code TOP_MOVER}（承载分类），总数 ≤ N。</li>
     * </ul>
     *
     * <p>目标月固定早于当前月（{@code monthOffset ∈ [−14,−1]}）以取 {@code final} 且具可比基线（{@code partial}
     * 会短路兜底、不产出洞察）。随机化「删除态 / 空白名」两条名称缺失路径以覆盖需求 2.7、4.6 的两种触发方式。
     * 并对同账本、同目标月的两次调用断言 {@code dimensionName} 一致（同一对象每次相同、可复现）。≥120 次迭代。</p>
     *
     * <p>Validates: Requirements 2.7, 4.6</p>
     */
    @Property(tries = 25)
    void property10_deletedOrUnnamedDimensionFallsBackAndIsNotDropped(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = -1) int monthOffset,
            @ForAll boolean categoryDeleted,
            @ForAll boolean merchantDeleted) {

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset < 0 → final（已完结）
        YearMonth prev = target.minusMonths(1);

        // ---------- 分类维度：删除态 / 空白名分类的 CATEGORY_DELTA / TOP_MOVER 回退命名且不丢弃 ----------
        long catLedger = LEDGER_BASE + SEQ.incrementAndGet();
        final Long deletedCatId = categoryDeleted
                ? 9_100_000L                                                   // 无对应 Category 行（已删除）
                : saveCategory(catLedger, CategoryKind.EXPENSE, "\u3000").getId(); // 空白名（全角空格）
        // 上月 100.00 元、目标月 70.00 元：变化量 −30.00 命中 CATEGORY_DELTA 门控，且为唯一 TOP_MOVER 候选。
        persist(catLedger, TransactionType.EXPENSE, new BigDecimal("100.00"),
                prev.atDay(10).atTime(9, 30), deletedCatId);
        persist(catLedger, TransactionType.EXPENSE, new BigDecimal("70.00"),
                target.atDay(10).atTime(9, 30), deletedCatId);

        AiInsightsResponse catResp = aiInsightService(clock).insights(catLedger, target);
        List<AiInsight> catInsights = new ArrayList<>();
        for (AiInsight in : catResp.insights()) {
            if (AiInsightService.DIMENSION_CATEGORY.equals(in.dimension())
                    && deletedCatId.equals(in.dimensionId())) {
                catInsights.add(in);
            }
        }
        String catLabel = String.format("target=%s catLedger=%d deletedCatId=%d categoryDeleted=%b",
                target, catLedger, deletedCatId, categoryDeleted);

        // 不丢弃：删除态/空白名分类的达标洞察必然在场（候选 ≤ N，无截断，需求 2.7）。
        assertThat(catInsights)
                .as("%s 删除态/空白名分类的洞察不因名称缺失被丢弃（需求 2.7）", catLabel)
                .isNotEmpty();
        // 回退命名：其每条洞察的 dimensionName 取固定回退名「已删除分类」（需求 2.7）。
        for (AiInsight in : catInsights) {
            assertThat(in.dimensionName())
                    .as("%s type=%s dimensionName 取固定回退名「已删除分类」（需求 2.7）", catLabel, in.type())
                    .isEqualTo(InsightNarrator.DELETED_CATEGORY_NAME);
        }
        // 每次相同（可复现）：重复调用后同一分类洞察的 dimensionName 不变（需求 2.7）。
        AiInsightsResponse catResp2 = aiInsightService(clock).insights(catLedger, target);
        for (AiInsight in : catResp2.insights()) {
            if (AiInsightService.DIMENSION_CATEGORY.equals(in.dimension())
                    && deletedCatId.equals(in.dimensionId())) {
                assertThat(in.dimensionName())
                        .as("%s 重复调用 dimensionName 恒为「已删除分类」（同一对象每次相同，需求 2.7）", catLabel)
                        .isEqualTo(InsightNarrator.DELETED_CATEGORY_NAME);
            }
        }

        // ---------- 商户维度：删除态 / 空白名商户的 FREQUENCY_DELTA 回退命名且不丢弃 ----------
        long merLedger = LEDGER_BASE + SEQ.incrementAndGet();
        Long bucketCat = saveCategory(merLedger, CategoryKind.EXPENSE, "bucket" + merLedger).getId();
        final Long deletedMerId = merchantDeleted
                ? 9_200_000L                                    // 无对应 Merchant 行（已删除）
                : saveMerchant(merLedger, "\u3000").getId();    // 空白名（全角空格）
        // 0.01 元极小额：上月 10 笔、目标月 2 笔 → deltaCount −8 命中 FREQUENCY_DELTA 门控；金额类门控全不命中。
        seedMerchant(merLedger, prev, deletedMerId, 10, bucketCat, "0.01");
        seedMerchant(merLedger, target, deletedMerId, 2, bucketCat, "0.01");

        AiInsightsResponse merResp = aiInsightService(clock).insights(merLedger, target);
        List<AiInsight> merInsights = new ArrayList<>();
        for (AiInsight in : merResp.insights()) {
            if (AiInsightService.DIMENSION_MERCHANT.equals(in.dimension())
                    && deletedMerId.equals(in.dimensionId())) {
                merInsights.add(in);
            }
        }
        String merLabel = String.format("target=%s merLedger=%d deletedMerId=%d merchantDeleted=%b",
                target, merLedger, deletedMerId, merchantDeleted);

        // 不丢弃：删除态/空白名商户的达标频次洞察必然在场（候选 ≤ N，无截断，需求 4.6）。
        assertThat(merInsights)
                .as("%s 删除态/空白名商户的洞察不因名称缺失被丢弃（需求 4.6）", merLabel)
                .isNotEmpty();
        // 回退命名：其每条洞察的 dimensionName 取固定回退名「已删除商户」（需求 4.6）。
        for (AiInsight in : merInsights) {
            assertThat(in.dimensionName())
                    .as("%s type=%s dimensionName 取固定回退名「已删除商户」（需求 4.6）", merLabel, in.type())
                    .isEqualTo(InsightNarrator.DELETED_MERCHANT_NAME);
        }
        // 每次相同（可复现）：重复调用后同一商户洞察的 dimensionName 不变（需求 4.6）。
        AiInsightsResponse merResp2 = aiInsightService(clock).insights(merLedger, target);
        for (AiInsight in : merResp2.insights()) {
            if (AiInsightService.DIMENSION_MERCHANT.equals(in.dimension())
                    && deletedMerId.equals(in.dimensionId())) {
                assertThat(in.dimensionName())
                        .as("%s 重复调用 dimensionName 恒为「已删除商户」（同一对象每次相同，需求 4.6）", merLabel)
                        .isEqualTo(InsightNarrator.DELETED_MERCHANT_NAME);
            }
        }
    }

    // ---------------- Property 11 ----------------

    /** 以固定注入的 {@link Clock} 与<b>自定义</b> {@link AiInsightProperties} 组装被测组合器；用于按迭代变化 N。 */
    private AiInsightService aiInsightService(Clock clock, AiInsightProperties props) {
        ReportService reportService = new ReportService(transactionRepository, categoryRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository);
        InsightNarrator narrator = new InsightNarrator();
        return new AiInsightService(reportService, categoryRepository, merchantRepository, clock, props, narrator);
    }

    /** 洞察类型全序（优先级由高到低）：{@code SAVINGS_TOTAL > TOP_MOVER > CATEGORY_DELTA > TREND_STREAK > FREQUENCY_DELTA}（需求 7.3）。 */
    private static int typeOrder(String type) {
        return switch (type) {
            case AiInsightService.TYPE_SAVINGS_TOTAL -> 0;
            case AiInsightService.TYPE_TOP_MOVER -> 1;
            case AiInsightService.TYPE_CATEGORY_DELTA -> 2;
            case AiInsightService.TYPE_TREND_STREAK -> 3;
            case AiInsightService.TYPE_FREQUENCY_DELTA -> 4;
            default -> 99;
        };
    }

    /** 维度 id 决胜键：{@code SAVINGS_TOTAL}（账本总额、无维度 id）视 id 为 {@code -1}（恒最前）；其余用 {@code dimensionId}（需求 7.3）。 */
    private static long dimIdForOrder(AiInsight in) {
        if (AiInsightService.TYPE_SAVINGS_TOTAL.equals(in.type()) || in.dimensionId() == null) {
            return -1L;
        }
        return in.dimensionId();
    }

    /**
     * Property 11 专用生成器：展示上限 N 的取值。覆盖<b>下界 1</b>、<b>默认 5</b>、<b>上界 20</b>、
     * <b>越界钳制到下界（0 → 1）</b>与<b>越界钳制到上界（25 → 20）</b>，并掺入 1..20 的随机整数，
     * 从而对「钳制到 [1,20]」「有界截断」「不足 N 不补足」三条语义在多个 N 上充分取样。
     */
    @Provide
    Arbitrary<Integer> maxCounts() {
        return Arbitraries.oneOf(
                Arbitraries.just(1),
                Arbitraries.just(5),
                Arbitraries.just(20),
                Arbitraries.just(0),
                Arbitraries.just(25),
                Arbitraries.integers().between(1, 20));
    }

    /**
     * Feature: ai-fun-analysis, Property 11: 确定性、幂等、有界与去重的洞察挑选。
     *
     * <p>对任意账本、目标月、交易集合与展示上限 N（含下界 1、默认 5、上界 20 及越界钳制 0→1 / 25→20），断言
     * 整份响应的<b>挑选不变量</b>——无论底层数据分布如何、无论兜底与否：</p>
     * <ol>
     *   <li><b>有界（需求 7.2）</b>：返回洞察数不超过钳制后的 N（{@code clamp(maxCount,1,20)}）；非兜底态时至少 1 条。</li>
     *   <li><b>确定性排序（需求 7.2、7.3）</b>：相邻两条洞察满足「打分降序；打分相等时先按洞察类型全序
     *       （{@code SAVINGS_TOTAL > TOP_MOVER > CATEGORY_DELTA > TREND_STREAK > FREQUENCY_DELTA}）、再按维度 id 升序
     *       （{@code SAVINGS_TOTAL} 视 id 为 −1 恒最前）」的全序——即前一条恒不排在后一条之后。</li>
     *   <li><b>打分非负（需求 7.1）</b>：每条洞察的 {@code score} 在场且为非负 {@link BigDecimal}。</li>
     *   <li><b>去重（需求 7.5）</b>：任意两条洞察的 {@code (type, dimension, dimensionId)} 三元组互不相同。</li>
     *   <li><b>幂等可复现（需求 7.4）</b>：同账本、同目标月、同底层数据、同 N 的两次调用返回<b>逐值相等</b>的
     *       {@link AiInsightsResponse}（{@code equals} 递归比较所有字段，含 {@code insights} 逐元素与顺序）。</li>
     *   <li><b>不补足（需求 7.6）</b>：兜底态 {@code insights} 恒为空；两次调用的条数完全一致且从不超过 N
     *       ——不足 N 时按同序返回全部候选、不做任何补足。</li>
     * </ol>
     *
     * <p>播种：沿用 {@link #txSpecs()} 的随机交易分布（目标月 / 上月 / 更早月，含转账噪声）并叠加确定性商户支出，
     * 使多类候选充分涌现（分类涨跌 / 节省总额 / 频次变化 / 连续趋势 / 最大改善·超支），从而覆盖排序、去重与截断。
     * 目标月取当前月或更早（{@code monthOffset ∈ [−14,0]}）以覆盖 {@code partial}（兜底短路）与 {@code final}
     * 两种月状态。展示上限 N 经 {@link AiInsightProperties#setMaxCount(int)} 注入，逐迭代变化。≥120 次迭代。</p>
     *
     * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5, 7.6</p>
     */
    @Property(tries = 25)
    void property11_selectionIsDeterministicIdempotentBoundedAndDeduplicated(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs,
            @ForAll("maxCounts") int maxCount) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset ≤ 0
        YearMonth prev = target.minusMonths(1);

        // 分类：3 个支出分类 + 1 个收入分类（与 Property 2/4 一致）。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 随机交易分布到目标月 / 上月 / 更早月（含转账噪声），获得可比基线与丰富候选。
        for (TxSpec s : specs) {
            YearMonth m = target.minusMonths(s.monthsBack());
            int day = Math.min(s.day(), m.lengthOfMonth());
            LocalDateTime when = m.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        // 确定性商户支出，触发 FREQUENCY_DELTA 的商户维度候选（与 Property 2/4 相同口径）。
        long merchantA = 7_001L;
        long merchantB = 7_002L;
        Long catForMerchant = expenseCats.get(0);
        seedMerchant(ledgerId, prev, merchantA, 5, catForMerchant, "31.50");
        seedMerchant(ledgerId, target, merchantA, 2, catForMerchant, "31.50");
        seedMerchant(ledgerId, prev, merchantB, 2, catForMerchant, "18.00");
        seedMerchant(ledgerId, target, merchantB, 6, catForMerchant, "18.00");

        // 逐迭代注入展示上限 N（含越界值，服务读取时钳制到 [1,20]）。
        AiInsightProperties props = new AiInsightProperties();
        props.setMaxCount(maxCount);
        int clampedN = Math.max(1, Math.min(20, maxCount));

        AiInsightService service = aiInsightService(clock, props);
        AiInsightsResponse resp = service.insights(ledgerId, target);
        AiInsightsResponse resp2 = service.insights(ledgerId, target);

        List<AiInsight> insights = resp.insights();
        String base = String.format("nowMonth=%s target=%s maxCount=%d clampedN=%d fallback=%b size=%d",
                nowMonth, target, maxCount, clampedN, resp.isFallback(), insights.size());

        // 不变量 5（幂等可复现，需求 7.4）：同 N、同数据两次调用逐值相等。
        assertThat(resp2)
                .as("%s 同账本/目标月/数据/N 两次调用逐值相等（幂等可复现，需求 7.4）", base)
                .isEqualTo(resp);

        // 不变量 1（有界，需求 7.2）：条数 ≤ 钳制后的 N。
        assertThat(insights.size())
                .as("%s 返回洞察数 ≤ 钳制后的 N（需求 7.2）", base)
                .isLessThanOrEqualTo(clampedN);

        // 不变量 6（不补足，需求 7.6）：兜底态 insights 恒为空；非兜底态至少 1 条。
        if (resp.isFallback()) {
            assertThat(insights)
                    .as("%s 兜底态 insights 为空（不补足，需求 7.6）", base)
                    .isEmpty();
        } else {
            assertThat(insights.size())
                    .as("%s 非兜底态 insights 至少 1 条（需求 7.2）", base)
                    .isBetween(1, clampedN);
        }

        // 不变量 4（去重，需求 7.5）：同一 (type, dimension, dimensionId) 至多一条。
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (AiInsight in : insights) {
            String key = in.type() + "#" + String.valueOf(in.dimension()) + "#" + String.valueOf(in.dimensionId());
            assertThat(keys.add(key))
                    .as("%s (type,dimension,dimensionId) 去重：%s 应唯一（需求 7.5）", base, key)
                    .isTrue();
        }

        // 不变量 3（打分非负，需求 7.1）：每条 score 在场且 ≥ 0。
        for (AiInsight in : insights) {
            assertThat(in.score())
                    .as("%s type=%s dimId=%s score 在场且非负（需求 7.1）", base, in.type(), in.dimensionId())
                    .isNotNull()
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        // 不变量 2（确定性排序，需求 7.2、7.3）：相邻两条满足「打分降序 → 类型全序 → 维度 id 升序」的全序。
        for (int i = 0; i + 1 < insights.size(); i++) {
            AiInsight a = insights.get(i);
            AiInsight b = insights.get(i + 1);
            String pair = String.format("%s pos=%d a(type=%s dimId=%s score=%s) b(type=%s dimId=%s score=%s)",
                    base, i, a.type(), a.dimensionId(), a.score(), b.type(), b.dimensionId(), b.score());

            // 第一键：打分降序。
            assertThat(a.score())
                    .as("%s 打分降序：a.score ≥ b.score（需求 7.2）", pair)
                    .isGreaterThanOrEqualTo(b.score());

            if (a.score().compareTo(b.score()) == 0) {
                // 第二键：洞察类型全序（a 的优先级不低于 b）。
                assertThat(typeOrder(a.type()))
                        .as("%s 打分相等 → 类型全序：typeOrder(a) ≤ typeOrder(b)（需求 7.3）", pair)
                        .isLessThanOrEqualTo(typeOrder(b.type()));

                if (typeOrder(a.type()) == typeOrder(b.type())) {
                    // 第三键：维度 id 升序（去重保证同键唯一 → 严格小于）。
                    assertThat(dimIdForOrder(a))
                            .as("%s 打分且类型相等 → 维度 id 升序：dimId(a) < dimId(b)（需求 7.3、7.5）", pair)
                            .isLessThan(dimIdForOrder(b));
                }
            }
        }
    }

    // ---------------- Property 12 ----------------

    /** 提醒/警示词集合：出现其一即为「提醒性措辞」（取自 {@link InsightNarrator} 模板：留意/关注/记得，需求 8.6、8.7）。 */
    private static final List<String> REMINDER_WORDS = List.of("留意", "关注", "记得");

    /** 金额绝对值 2dp HALF_UP 的纯文本（与 {@link InsightNarrator} 同口径），用于断言「文案数值 == 机器字段」。 */
    private static String absMoneyText(BigDecimal v) {
        return v.abs().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** 变化率绝对值百分比 2dp HALF_UP 的纯文本（与 {@link InsightNarrator} 同口径）。 */
    private static String absPctText(BigDecimal v) {
        return v.abs().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Feature: ai-fun-analysis, Property 12: 叙事文案正确性（含数值一致与措辞极性）。
     *
     * <p>对任意（已完结）目标月与交易集合，<b>非兜底</b>响应中的每一条洞察，其中文叙事文案
     * {@code narrativeText} 都满足需求 8 的正确性约束——要么<b>缺失（{@code null}）表示生成失败</b>
     * （缺全部关键数值，需求 8.8，直接跳过后续断言、不视为违约），要么<b>在场且逐项达标</b>：</p>
     * <ol>
     *   <li><b>长度上界（需求 8.5）</b>：{@code narrativeText.length() ≤ 100}（中文字符均为 BMP 单码元，
     *       {@code String.length()} 即字符数）。</li>
     *   <li><b>含维度名（需求 8.2）</b>：维度名在场（{@code dimensionName != null}，即
     *       {@code CATEGORY_DELTA / TOP_MOVER / TREND_STREAK / FREQUENCY_DELTA}）时，文案<b>包含</b>该维度名
     *       （可能是回退名「已删除分类 / 已删除商户」）；{@code SAVINGS_TOTAL} 为账本总额、无维度名，按需求 8.2
     *       由语境承载，跳过含名检查。</li>
     *   <li><b>数值一致（需求 8.4，含关键数值需求 8.2）</b>：文案中出现的每个数值都等于对应机器字段并同口径格式化——
     *       {@code deltaAmount != null → 文案包含 |deltaAmount| 的 2dp 文本}；
     *       {@code changeRate != null → 文案包含 |changeRate| 的百分比 2dp 文本}；
     *       {@code deltaCount != null → 文案包含 |deltaCount| 的整数文本}；
     *       {@code TREND_STREAK → 文案包含 streakMonths 的整数文本}。断言仅覆盖该类型模板实际渲染的字段
     *       （见 {@link InsightNarrator}），故文案必至少含一项关键数值（需求 8.2）。</li>
     *   <li><b>措辞极性（需求 8.6、8.7）</b>：正向/中性方向（{@code SAVINGS_TOTAL/TOP_MOVER} 的
     *       {@code role=IMPROVE}，或其余类型的 {@code direction=DOWN}）→ 文案<b>不含</b>任何提醒/警示词
     *       （{@link #REMINDER_WORDS}）；提醒方向（{@code role=OVERSPEND} 或 {@code direction=UP}）→ 文案
     *       <b>至少含一个</b>提醒/警示词。</li>
     * </ol>
     *
     * <p>播种沿用 Property 2/4/11 的口径：随机交易分布到目标月 / 上月 / 更早月（含转账噪声）获得可比基线，
     * 叠加确定性商户支出触发 {@code FREQUENCY_DELTA} 的商户维度，使五类洞察充分涌现、覆盖各方向分支。
     * 目标月固定早于当前月（{@code monthOffset ∈ [−14,−1]}）以取 {@code final} 且具可比基线（{@code partial}
     * 会短路兜底、不产出洞察），兜底态无洞察可断言则直接通过。≥120 次迭代。</p>
     *
     * <p>Validates: Requirements 8.1, 8.2, 8.4, 8.5, 8.6, 8.7, 8.8</p>
     */
    @Property(tries = 25)
    void property12_narrativeTextIsCorrectInNumbersAndPolarity(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = -1) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset < 0 → final（已完结、具可比基线）
        YearMonth prev = target.minusMonths(1);

        // 分类：3 个支出分类 + 1 个收入分类（与 Property 2/4/11 一致）。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 随机交易分布到目标月 / 上月 / 更早月（含转账噪声），获得可比基线与丰富候选。
        for (TxSpec s : specs) {
            YearMonth m = target.minusMonths(s.monthsBack());
            int day = Math.min(s.day(), m.lengthOfMonth());
            LocalDateTime when = m.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            switch (s.kind()) {
                case 0 -> persist(ledgerId, TransactionType.EXPENSE, amount, when,
                        expenseCats.get(s.categoryIndex() % expenseCats.size()));
                case 1 -> persist(ledgerId, TransactionType.INCOME, amount, when, incomeCat);
                default -> persist(ledgerId, TransactionType.TRANSFER, amount, when, null);
            }
        }

        // 确定性商户支出，触发 FREQUENCY_DELTA 的商户维度候选（与 Property 2/4/11 相同口径）。
        long merchantA = 7_001L;
        long merchantB = 7_002L;
        Long catForMerchant = expenseCats.get(0);
        seedMerchant(ledgerId, prev, merchantA, 5, catForMerchant, "31.50");
        seedMerchant(ledgerId, target, merchantA, 2, catForMerchant, "31.50");
        seedMerchant(ledgerId, prev, merchantB, 2, catForMerchant, "18.00");
        seedMerchant(ledgerId, target, merchantB, 6, catForMerchant, "18.00");

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 兜底态无洞察，无叙事文案可断言，直接通过。
        if (resp.isFallback()) {
            return;
        }

        for (AiInsight in : resp.insights()) {
            String label = String.format("target=%s type=%s dimId=%s dir=%s role=%s",
                    target, in.type(), in.dimensionId(), in.direction(), in.role());
            String text = in.narrativeText();

            // 需求 8.8：文案缺失（null）表示生成失败——保留机器字段、整体不报错；跳过后续文本级断言。
            if (text == null) {
                continue;
            }

            // 需求 8.5：长度 ≤ 100 个中文字符（BMP 单码元，String.length() 即字符数）。
            assertThat(text.length())
                    .as("%s narrativeText 长度 ≤ 100（需求 8.5）：<%s>", label, text)
                    .isLessThanOrEqualTo(100);

            // 需求 8.2：维度名在场时，文案必包含维度名（含回退名）；SAVINGS_TOTAL 无维度名，跳过含名检查。
            if (in.dimensionName() != null) {
                assertThat(text)
                        .as("%s narrativeText 含维度名「%s」（需求 8.2）：<%s>", label, in.dimensionName(), text)
                        .contains(in.dimensionName());
            }

            // 需求 8.4 + 8.2：文案数值 == 机器字段（同口径格式化），且至少含一项关键数值。
            if (in.deltaAmount() != null) {
                assertThat(text)
                        .as("%s narrativeText 含金额 |deltaAmount|=%s（2dp，需求 8.4）：<%s>",
                                label, absMoneyText(in.deltaAmount()), text)
                        .contains(absMoneyText(in.deltaAmount()));
            }
            if (in.changeRate() != null) {
                assertThat(text)
                        .as("%s narrativeText 含变化率 |changeRate|=%s（百分比 2dp，需求 8.4）：<%s>",
                                label, absPctText(in.changeRate()), text)
                        .contains(absPctText(in.changeRate()));
            }
            if (in.deltaCount() != null) {
                assertThat(text)
                        .as("%s narrativeText 含次数 |deltaCount|=%d（整数，需求 8.4）：<%s>",
                                label, Math.abs((long) in.deltaCount()), text)
                        .contains(String.valueOf(Math.abs((long) in.deltaCount())));
            }
            if (AiInsightService.TYPE_TREND_STREAK.equals(in.type()) && in.streakMonths() != null) {
                assertThat(text)
                        .as("%s narrativeText 含连续月数 streakMonths=%d（整数，需求 8.4）：<%s>",
                                label, in.streakMonths(), text)
                        .contains(String.valueOf(in.streakMonths()));
            }

            // 需求 8.6、8.7：措辞极性。SAVINGS_TOTAL / TOP_MOVER 以 role 判定，其余类型以 direction 判定
            //（与 InsightNarrator 模板分支完全一致）。
            boolean positive = switch (in.type()) {
                case AiInsightService.TYPE_SAVINGS_TOTAL, AiInsightService.TYPE_TOP_MOVER ->
                        "IMPROVE".equals(in.role());
                default -> "DOWN".equals(in.direction());
            };
            boolean hasReminderWord = REMINDER_WORDS.stream().anyMatch(text::contains);
            if (positive) {
                // 正向/中性（DOWN 或 IMPROVE）：不含任何提醒/警示词（需求 8.6）。
                assertThat(hasReminderWord)
                        .as("%s 正向/中性措辞不含提醒词%s（需求 8.6）：<%s>", label, REMINDER_WORDS, text)
                        .isFalse();
            } else {
                // 提醒（UP 或 OVERSPEND）：至少含一个提醒/警示词（需求 8.7）。
                assertThat(hasReminderWord)
                        .as("%s 提醒性措辞至少含一个提醒词%s（需求 8.7）：<%s>", label, REMINDER_WORDS, text)
                        .isTrue();
            }
        }
    }

    // ---------------- Property 13 ----------------

    /**
     * Feature: ai-fun-analysis, Property 13: 鼓励性兜底语义。
     *
     * <p>对任意账本与目标月，当上月无任何计入交易（无可比基线）、或无任何满足显著变化阈值的候选、或目标月为
     * {@code partial} 而依赖完整月对比的洞察被跳过后为空时，响应必为兜底态：{@code insights} 为空、
     * {@code isFallback == true}、{@code fallbackText} 恰为一条非空文案（长度 1..100）且不返回错误；当返回一条或
     * 多条洞察（非兜底态）时，{@code isFallback == false} 且 {@code fallbackText == null}、{@code insights}
     * 条数在 1..N 之间。无论兜底与否，{@code month}（{@code YYYY-MM}）与 {@code monthStatus} 均在场。</p>
     *
     * <p>验证策略：先以随机化的 {@code nowDayOffset}/{@code monthOffset ∈ [-14,0]}/随机交易广泛断言上述<b>不变式</b>
     * （兜底 ⟺ insights 空 ∧ fallbackText 非空 1..100；非兜底 ⟺ fallbackText 为 null ∧ insights 非空）；再以四个
     * 确定性子场景逐一坐实每条兜底路径与非兜底路径被真正走到：(a) partial 短路（目标月 == 当前月，有交易仍兜底）；
     * (b) 上月无基线（final 月，交易只落在 M、上月为空）；(c) 无候选（final 月，M 与上月同分类各一笔极小等额支出，
     * 全部低于阈值）；(d) 非兜底（final 月，某分类相对上月出现巨大涨幅 → 至少一条 CATEGORY_DELTA）。每次迭代使用
     * 独立 {@code ledgerId} 隔离。属性驱动 ≥120 次迭代。</p>
     *
     * <p>Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5</p>
     */
    @Property(tries = 25)
    void property13_encouragingFallbackSemantics(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset ≤ 0

        seedLedger(ledgerId, target, specs);
        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 兜底/非兜底的核心不变式（需求 9.1、9.2、9.3、9.4、9.5、9.6）。
        assertFallbackInvariant(resp, "随机场景 nowMonth=" + nowMonth + " target=" + target);

        // ---- 确定性子场景：逐一坐实三条兜底路径与非兜底路径均被真正走到 ----

        // (a) partial 短路：目标月 == 当前月，即便当月有交易，v1 五类全部跳过 → 兜底（需求 9.3、9.4）。
        long ledgerPartial = LEDGER_BASE + SEQ.incrementAndGet();
        LocalDate nowA = LocalDate.of(2024, 6, 15);
        Clock clockA = Clock.fixed(nowA.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth curMonth = YearMonth.of(2024, 6);
        Long catA = saveCategory(ledgerPartial, CategoryKind.EXPENSE, "eA" + ledgerPartial).getId();
        persist(ledgerPartial, TransactionType.EXPENSE, new BigDecimal("888.00"),
                curMonth.atDay(3).atTime(9, 30), catA);
        AiInsightsResponse respPartial = aiInsightService(clockA).insights(ledgerPartial, curMonth);
        assertThat(respPartial.monthStatus())
                .as("(a) partial 短路场景月状态应为 partial")
                .isEqualTo(AiInsightService.STATUS_PARTIAL);
        assertThat(respPartial.isFallback())
                .as("(a) partial 短路 → 依赖完整月对比的洞察全部跳过 → 兜底（需求 9.3）")
                .isTrue();
        assertFallbackInvariant(respPartial, "(a) partial 短路");

        // (b) 上月无基线：final 月，交易只落在 M、上月为空 → 无可比基线 → 兜底（需求 9.1、9.4）。
        long ledgerNoBaseline = LEDGER_BASE + SEQ.incrementAndGet();
        LocalDate nowB = LocalDate.of(2024, 6, 15);
        Clock clockB = Clock.fixed(nowB.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth finalMonth = YearMonth.of(2024, 5); // 早于当前月 → final
        Long catB = saveCategory(ledgerNoBaseline, CategoryKind.EXPENSE, "eB" + ledgerNoBaseline).getId();
        // 仅在 M 落库、上月（2024-04）完全为空。
        persist(ledgerNoBaseline, TransactionType.EXPENSE, new BigDecimal("500.00"),
                finalMonth.atDay(10).atTime(9, 30), catB);
        AiInsightsResponse respNoBaseline = aiInsightService(clockB).insights(ledgerNoBaseline, finalMonth);
        assertThat(respNoBaseline.monthStatus())
                .as("(b) 上月无基线场景月状态应为 final")
                .isEqualTo(AiInsightService.STATUS_FINAL);
        assertThat(respNoBaseline.isFallback())
                .as("(b) 上月无任何计入交易（无可比基线）→ 兜底（需求 9.1）")
                .isTrue();
        assertFallbackInvariant(respNoBaseline, "(b) 上月无基线");

        // (c) 无候选：final 月，M 与上月同分类各一笔极小等额支出（远低于所有阈值）→ 无候选 → 兜底（需求 9.2、9.4）。
        long ledgerNoCand = LEDGER_BASE + SEQ.incrementAndGet();
        LocalDate nowC = LocalDate.of(2024, 6, 15);
        Clock clockC = Clock.fixed(nowC.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        Long catC = saveCategory(ledgerNoCand, CategoryKind.EXPENSE, "eC" + ledgerNoCand).getId();
        // 上月（2024-04）与 M（2024-05）同分类各一笔 0.01 元支出：delta=0、savings=0、笔数差 0 → 全部低于阈值。
        persist(ledgerNoCand, TransactionType.EXPENSE, new BigDecimal("0.01"),
                finalMonth.minusMonths(1).atDay(10).atTime(9, 30), catC);
        persist(ledgerNoCand, TransactionType.EXPENSE, new BigDecimal("0.01"),
                finalMonth.atDay(10).atTime(9, 30), catC);
        AiInsightsResponse respNoCand = aiInsightService(clockC).insights(ledgerNoCand, finalMonth);
        assertThat(respNoCand.monthStatus())
                .as("(c) 无候选场景月状态应为 final")
                .isEqualTo(AiInsightService.STATUS_FINAL);
        assertThat(respNoCand.isFallback())
                .as("(c) 有可比基线但无任何满足阈值的候选 → 兜底（需求 9.2）")
                .isTrue();
        assertFallbackInvariant(respNoCand, "(c) 无候选");

        // (d) 非兜底：final 月，某分类相对上月出现巨大涨幅 → 至少一条 CATEGORY_DELTA（需求 9.5）。
        long ledgerNonFallback = LEDGER_BASE + SEQ.incrementAndGet();
        LocalDate nowD = LocalDate.of(2024, 6, 15);
        Clock clockD = Clock.fixed(nowD.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        Long catD = saveCategory(ledgerNonFallback, CategoryKind.EXPENSE, "eD" + ledgerNonFallback).getId();
        // 上月 100.00、M 500.00：deltaAmount=400.00 ≥ 20、changeRate=400% ≥ 10 → CATEGORY_DELTA 候选。
        persist(ledgerNonFallback, TransactionType.EXPENSE, new BigDecimal("100.00"),
                finalMonth.minusMonths(1).atDay(10).atTime(9, 30), catD);
        persist(ledgerNonFallback, TransactionType.EXPENSE, new BigDecimal("500.00"),
                finalMonth.atDay(10).atTime(9, 30), catD);
        AiInsightsResponse respNonFallback = aiInsightService(clockD).insights(ledgerNonFallback, finalMonth);
        assertThat(respNonFallback.monthStatus())
                .as("(d) 非兜底场景月状态应为 final")
                .isEqualTo(AiInsightService.STATUS_FINAL);
        assertThat(respNonFallback.isFallback())
                .as("(d) 存在显著分类涨幅 → 非兜底（需求 9.5）")
                .isFalse();
        assertFallbackInvariant(respNonFallback, "(d) 非兜底");
    }

    /**
     * 断言鼓励性兜底语义的核心不变式（需求 9.1–9.6）：
     * {@code month} 合法且在场、{@code monthStatus} 在场且取值合法、{@code insights} 列表在场；
     * 兜底态 ⟺ {@code insights} 为空 ∧ {@code fallbackText} 非空（长度 1..100）；
     * 非兜底态 ⟺ {@code fallbackText} 为 null ∧ {@code insights} 条数在 1..N。
     */
    private static void assertFallbackInvariant(AiInsightsResponse resp, String scenario) {
        int maxCount = new AiInsightProperties().maxCountClamped();

        // month / monthStatus 无论兜底与否均在场（需求 9.6）。
        assertThat(resp.month())
                .as("%s：month 在场且形如 YYYY-MM", scenario)
                .isNotNull()
                .matches("\\d{4}-\\d{2}");
        assertThat(resp.monthStatus())
                .as("%s：monthStatus 在场且取值合法", scenario)
                .isIn(AiInsightService.STATUS_FINAL, AiInsightService.STATUS_PARTIAL);
        assertThat(resp.insights())
                .as("%s：insights 列表在场", scenario)
                .isNotNull();

        if (resp.isFallback()) {
            // 兜底态：insights 为空 ∧ fallbackText 非空、长度 1..100（需求 9.1、9.2、9.3、9.4）。
            assertThat(resp.insights())
                    .as("%s：兜底态 insights 为空（需求 9.4）", scenario)
                    .isEmpty();
            assertThat(resp.fallbackText())
                    .as("%s：兜底态 fallbackText 非空（需求 9.1、9.2、9.3）", scenario)
                    .isNotNull()
                    .isNotBlank();
            assertThat(resp.fallbackText().length())
                    .as("%s：兜底文案长度 1..100（需求 9.1、9.2、9.3）", scenario)
                    .isBetween(1, 100);
        } else {
            // 非兜底态：fallbackText 为 null ∧ insights 条数 1..N（需求 9.5）。
            assertThat(resp.fallbackText())
                    .as("%s：非兜底态 fallbackText 为 null（需求 9.5）", scenario)
                    .isNull();
            assertThat(resp.insights().size())
                    .as("%s：非兜底态 insights 条数在 1..N（N=%d，需求 9.5）", scenario, maxCount)
                    .isBetween(1, maxCount);
        }
    }

    // ---------------- Property 14 ----------------

    /** 隐私白名单：允许出现在 AI 趣味分析响应 JSON 中的全部字段名（含 Jackson 对 record boolean 的两种命名）。 */
    private static final java.util.Set<String> PRIVACY_WHITELIST = java.util.Set.of(
            "month", "monthStatus", "isFallback", "fallback", "fallbackText", "insights",
            "type", "dimension", "dimensionId", "dimensionName",
            "currentValue", "previousValue", "currentCount", "previousCount",
            "deltaAmount", "deltaCount", "changeRate",
            "streakMonths", "streakStartMonth", "streakEndMonth",
            "direction", "role", "score", "narrativeText");

    /** 被明确禁止出现在响应任意层级的字段名（邮箱/令牌/外部标识/原始备注/商户原始标识/附件/跨请求归属键）。 */
    private static final java.util.List<String> PRIVACY_FORBIDDEN_NAMES = java.util.List.of(
            "email", "token", "refreshToken", "accessToken", "externalId", "external_id",
            "note", "rawMerchantId", "attachment", "userId", "ledgerId", "occurredAt");

    /**
     * Feature: ai-fun-analysis, Property 14: 隐私白名单（响应不含被禁字段）。
     *
     * <p>把 {@link AiInsightsResponse} 序列化为 JSON 后，其中出现的<b>全部字段名</b>（递归遍历对象/数组的
     * 每一层）必构成隐私白名单的子集——白名单仅含聚合派生统计（金额、笔数、变化率、连续月数、打分、维度
     * id/名称、月份/月状态）与叙事文案字段；因此结构上不可能出现用户邮箱、访问/刷新令牌、{@code external_id}、
     * 原始备注、商户原始标识、附件、或 {@code userId/ledgerId/occurredAt} 等逐笔敏感字段（需求 12.3、12.4、12.5）。
     * 进一步，即便交易被播种了哨兵备注与哨兵外部标识，最终 JSON 文本也<b>不得包含</b>任何哨兵取值，坐实原始
     * 逐笔敏感<b>取值</b>不外泄；无论兜底与否该不变式均成立（对全部返回态断言）。</p>
     *
     * <p>播种：目标月固定早于当前月（{@code monthOffset ∈ [−14,−1]} → {@code final}）并铺陈跨目标月/上月/更早月
     * 的随机交易（含转账噪声）以尽量产出非兜底洞察；每笔交易都写入哨兵 {@code note} 与（唯一化的）哨兵
     * {@code externalId}，以检验这些原始字段的取值绝不进入响应 JSON。≥120 次迭代。</p>
     *
     * <p>Validates: Requirements 12.3, 12.4, 12.5</p>
     */
    @Property(tries = 25)
    void property14_privacyWhitelist(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = -1) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) throws Exception {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset ≤ −1 → final（早于当前月）

        // 哨兵：写入逐笔原始 note / external_id，最终响应 JSON 绝不得包含这些取值。
        final String sentinelNote = "SENTINEL_PRIVATE_abc123";
        final String sentinelExternalPrefix = "SENTINEL_EXT_xyz789";

        // 分类：3 个支出分类 + 1 个收入分类（与其它属性一致）。
        List<Long> expenseCats = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expenseCats.add(saveCategory(ledgerId, CategoryKind.EXPENSE, "e" + ledgerId + "-" + i).getId());
        }
        Long incomeCat = saveCategory(ledgerId, CategoryKind.INCOME, "i" + ledgerId).getId();

        // 随机交易分布到目标月/上月/更早月（含转账噪声），并逐笔写入哨兵 note / external_id。
        // external_id 逐笔唯一化以避开 (user_id, external_id) 唯一索引，但保留哨兵前缀供文本检测。
        int seq = 0;
        for (TxSpec s : specs) {
            YearMonth m = target.minusMonths(s.monthsBack());
            int day = Math.min(s.day(), m.lengthOfMonth());
            LocalDateTime when = m.atDay(day).atTime(9, 30);
            BigDecimal amount = BigDecimal.valueOf(s.cents()).movePointLeft(2);
            Transaction t = new Transaction();
            t.setLedgerId(ledgerId);
            switch (s.kind()) {
                case 0 -> {
                    t.setType(TransactionType.EXPENSE);
                    t.setAccountId(1L);
                    t.setCategoryId(expenseCats.get(s.categoryIndex() % expenseCats.size()));
                }
                case 1 -> {
                    t.setType(TransactionType.INCOME);
                    t.setAccountId(1L);
                    t.setCategoryId(incomeCat);
                }
                default -> {
                    t.setType(TransactionType.TRANSFER);
                    t.setSourceAccountId(10L);
                    t.setDestinationAccountId(11L);
                }
            }
            t.setAmount(amount);
            t.setNote(sentinelNote);
            t.setExternalId(sentinelExternalPrefix + "-" + (seq++));
            t.setOccurredAt(when);
            t.setCreatedAt(when);
            t.setUpdatedAt(when);
            transactionRepository.save(t);
        }

        AiInsightsResponse resp = aiInsightService(clock).insights(ledgerId, target);

        // 序列化为 JSON（DTO 字段均为 String/BigDecimal/boolean/Integer/Long/List，无需 JavaTimeModule）。
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(resp);
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

        // 递归收集响应 JSON 的所有字段名。
        java.util.Set<String> fieldNames = new java.util.HashSet<>();
        collectJsonFieldNames(root, fieldNames);

        // 字段名集合 ⊆ 隐私白名单（需求 12.3、12.4）。
        assertThat(PRIVACY_WHITELIST)
                .as("nowMonth=%s target=%s isFallback=%s 响应字段名应为隐私白名单子集，实际字段=%s",
                        nowMonth, target, resp.isFallback(), fieldNames)
                .containsAll(fieldNames);

        // 明确的被禁字段名不得出现在任意层级（需求 12.3、12.4、12.5）。
        for (String forbidden : PRIVACY_FORBIDDEN_NAMES) {
            assertThat(fieldNames)
                    .as("nowMonth=%s target=%s 被禁字段名 '%s' 不得出现在响应 JSON 任意层级", nowMonth, target, forbidden)
                    .doesNotContain(forbidden);
        }

        // 逐笔原始 note / external_id 取值绝不外泄（需求 12.4、12.5）。
        assertThat(json)
                .as("nowMonth=%s target=%s 响应 JSON 不得包含逐笔原始备注取值", nowMonth, target)
                .doesNotContain(sentinelNote);
        assertThat(json)
                .as("nowMonth=%s target=%s 响应 JSON 不得包含逐笔原始外部标识取值", nowMonth, target)
                .doesNotContain(sentinelExternalPrefix);
    }

    /** 递归收集一个 JSON 节点（对象/数组）各层级出现的全部字段名。 */
    private static void collectJsonFieldNames(com.fasterxml.jackson.databind.JsonNode node,
            java.util.Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it =
                    node.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = it.next();
                out.add(entry.getKey());
                collectJsonFieldNames(entry.getValue(), out);
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                collectJsonFieldNames(child, out);
            }
        }
    }

    // ---------------- Property 15 ----------------

    /**
     * 对 {@code transactions} / {@code categories} / {@code merchants} 三张领域表拍一个可比较的<b>内容快照</b>：
     * 每张表分别取「总行数」与「逐行内容签名的升序列表」。行内容签名把每行的稳定标识与关键列拼成字符串
     * （交易含 id / ledgerId / type / amount / 账户 / 分类 / 商户 / 时间 / 备注 / 外部标识等全部业务列；分类含
     * id / ledgerId / kind / name；商户含 id / ledgerId / name），从而任何一行的<b>新增、删除或任一列取值改动</b>
     * 都会改变对应的行数或内容列表，被前后快照的相等断言捕获。
     */
    private record DbSnapshot(long txCount, long catCount, long merCount,
            List<String> txRows, List<String> catRows, List<String> merRows) { }

    /** 读取全表内容拍快照（在快照前 flush，使既有持久化上下文里的挂起写入落定，保证快照反映真实库状态）。 */
    private DbSnapshot snapshotDb() {
        transactionRepository.flush();
        categoryRepository.flush();
        merchantRepository.flush();

        List<String> txRows = new ArrayList<>();
        for (Transaction t : transactionRepository.findAll()) {
            txRows.add(String.join("|",
                    String.valueOf(t.getId()),
                    String.valueOf(t.getUserId()),
                    String.valueOf(t.getLedgerId()),
                    String.valueOf(t.getType()),
                    t.getAmount() == null ? "null" : t.getAmount().toPlainString(),
                    String.valueOf(t.getAccountId()),
                    String.valueOf(t.getSourceAccountId()),
                    String.valueOf(t.getDestinationAccountId()),
                    String.valueOf(t.getCategoryId()),
                    String.valueOf(t.getProjectId()),
                    String.valueOf(t.getMerchantId()),
                    String.valueOf(t.getOccurredAt()),
                    String.valueOf(t.getNote()),
                    String.valueOf(t.getExternalId()),
                    String.valueOf(t.getCreatedAt()),
                    String.valueOf(t.getUpdatedAt()),
                    String.valueOf(t.getDeletedAt())));
        }
        List<String> catRows = new ArrayList<>();
        for (Category c : categoryRepository.findAll()) {
            catRows.add(String.join("|",
                    String.valueOf(c.getId()),
                    String.valueOf(c.getLedgerId()),
                    String.valueOf(c.getKind()),
                    String.valueOf(c.getName())));
        }
        List<String> merRows = new ArrayList<>();
        for (Merchant m : merchantRepository.findAll()) {
            merRows.add(String.join("|",
                    String.valueOf(m.getId()),
                    String.valueOf(m.getLedgerId()),
                    String.valueOf(m.getName())));
        }
        java.util.Collections.sort(txRows);
        java.util.Collections.sort(catRows);
        java.util.Collections.sort(merRows);

        return new DbSnapshot(
                transactionRepository.count(), categoryRepository.count(), merchantRepository.count(),
                txRows, catRows, merRows);
    }

    /**
     * Feature: ai-fun-analysis, Property 15: 纯只读不写库。
     *
     * <p>对任意账本、目标月与初始数据库状态，调用 AI 趣味分析接口（一次或多次）后，{@code transactions}、
     * {@code categories}、{@code merchants} 三张领域表的<b>行数与逐行内容</b>均保持不变——零写入副作用、零 DDL。
     * 这直接坐实设计的「单次事务只读」（{@code AiInsightService.insights} 标注 {@code @Transactional(readOnly = true)}、
     * 全过程无任何写语句、不新增任何 repository 方法 / SQL）。</p>
     *
     * <p>验证手法：先播种随机数据（复用 {@link #seedLedger}）并叠加确定性商户支出（复用 {@link #seedMerchant}）
     * 以触发多类候选；随后对三张表拍<b>调用前</b>内容快照（{@link #snapshotDb()}，快照前 flush 使播种写入落定）；
     * 接着<b>连续两次</b>调用 {@code insights(ledgerId, target)}（既覆盖单次只读、也覆盖多次调用的幂等无写）；
     * 最后拍<b>调用后</b>快照并断言与调用前<b>逐字段完全相等</b>（行数一致 + 内容列表逐元素相等）。目标月取当前月
     * 或更早（{@code monthOffset ∈ [−14,0]}）以覆盖 {@code partial}（兜底短路）与 {@code final} 两种路径——两者都
     * 不得写库。每次迭代使用独立 {@code ledgerId} 隔离随机数据。属性驱动 ≥120 次迭代。</p>
     *
     * <p>Validates: Requirements 13.1</p>
     */
    @Property(tries = 25)
    void property15_readOnlyNoDbWrites(
            @ForAll("nowDayOffsets") int nowDayOffset,
            @ForAll @IntRange(min = -14, max = 0) int monthOffset,
            @ForAll("txSpecs") List<TxSpec> specs) {

        long ledgerId = LEDGER_BASE + SEQ.incrementAndGet();

        LocalDate nowDate = LocalDate.of(2024, 1, 1).plusDays(nowDayOffset);
        Clock clock = Clock.fixed(nowDate.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
        YearMonth nowMonth = YearMonth.from(nowDate);
        YearMonth target = nowMonth.plusMonths(monthOffset); // monthOffset ≤ 0 → partial / final 两条路径
        YearMonth prev = target.minusMonths(1);

        // 播种随机交易（含收入/转账噪声，分布到目标月/上月/更早月）与确定性商户支出，触发多类候选。
        seedLedger(ledgerId, target, specs);
        Long merchantCat = saveCategory(ledgerId, CategoryKind.EXPENSE, "mcat" + ledgerId).getId();
        seedMerchant(ledgerId, prev, 7_101L, 5, merchantCat, "31.50");
        seedMerchant(ledgerId, target, 7_101L, 2, merchantCat, "31.50");
        seedMerchant(ledgerId, prev, 7_102L, 2, merchantCat, "18.00");
        seedMerchant(ledgerId, target, 7_102L, 6, merchantCat, "18.00");

        // 调用前快照（flush 使播种写入落定后拍照）。
        DbSnapshot before = snapshotDb();

        // 连续两次调用：覆盖单次只读，也覆盖多次调用仍无写入。
        AiInsightsResponse resp1 = aiInsightService(clock).insights(ledgerId, target);
        AiInsightsResponse resp2 = aiInsightService(clock).insights(ledgerId, target);
        // 触碰响应以确保不被优化省略（并非断言重点）。
        assertThat(resp1).as("insights 返回非空对象").isNotNull();
        assertThat(resp2).as("insights 返回非空对象").isNotNull();

        // 调用后快照。
        DbSnapshot after = snapshotDb();

        String label = String.format("nowMonth=%s target=%s monthStatus=%s isFallback=%s",
                nowMonth, target, resp1.monthStatus(), resp1.isFallback());

        // 行数完全一致（零写入：无新增/删除行）。
        assertThat(after.txCount())
                .as("%s transactions 行数在调用 insights 前后不变（零写入，需求 13.1）", label)
                .isEqualTo(before.txCount());
        assertThat(after.catCount())
                .as("%s categories 行数在调用 insights 前后不变（零写入，需求 13.1）", label)
                .isEqualTo(before.catCount());
        assertThat(after.merCount())
                .as("%s merchants 行数在调用 insights 前后不变（零写入，需求 13.1）", label)
                .isEqualTo(before.merCount());

        // 逐行内容完全一致（零列改动、零 DDL）。
        assertThat(after.txRows())
                .as("%s transactions 逐行内容在调用 insights 前后完全一致（零改动，需求 13.1）", label)
                .isEqualTo(before.txRows());
        assertThat(after.catRows())
                .as("%s categories 逐行内容在调用 insights 前后完全一致（零改动，需求 13.1）", label)
                .isEqualTo(before.catRows());
        assertThat(after.merRows())
                .as("%s merchants 逐行内容在调用 insights 前后完全一致（零改动，需求 13.1）", label)
                .isEqualTo(before.merRows());
    }
}
