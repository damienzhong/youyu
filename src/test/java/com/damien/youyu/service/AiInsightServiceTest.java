package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.AiInsightsResponse;
import com.damien.youyu.api.dto.AiInsightsResponse.AiInsight;
import com.damien.youyu.config.AiInsightProperties;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;

/**
 * {@link AiInsightService} 的<b>服务层边界单元测试</b>（任务 11）。
 *
 * <p>沿用仓库内 DB 支撑型测试范式（见 {@code AiInsightServicePropertyTest}）：{@code @DataJpaTest} +
 * 真实 H2（{@code MODE=MySQL}）+ 真实 {@link TransactionRepository}/{@link CategoryRepository} 等仓储，
 * 被测的 {@link AiInsightService}（连同其编排的真实 {@link ReportService}/{@link InsightNarrator}）业务
 * 逻辑全部真实执行，不使用任何 mock。区别于属性测试的随机驱动，本类用<b>确定性最小场景</b>逐一压中门控
 * 两侧、无定义分支、去重选取、兜底与叙事等边界。</p>
 *
 * <p>时区口径固定注入 {@code Asia/Shanghai} 的固定 {@link Clock}（当前日 2024-06-15），目标月固定取
 * 2024-05（早于当前月 → {@code final}），上一自然月为 2024-04；每个用例使用<b>独立 ledgerId</b> 以隔离数据。</p>
 *
 * <p>覆盖需求：1.2、2.8、3.8、4.3、5.6、6.5、8.5、8.6、8.7、8.8、10.1、10.2、10.3、10.4、10.7、10.8
 * 中属于服务层可验证的部分（门控、无定义分支、去重选取、N 边界、叙事长度/极性、生成失败、兜底）。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AiInsightServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定注入时钟的「当前日期」：2024-06-15（当前自然月 2024-06 未完结）。 */
    private final Clock clock = Clock.fixed(
            java.time.LocalDate.of(2024, 6, 15).atTime(12, 0).atZone(ZONE).toInstant(), ZONE);

    /** 目标月（早于当前月 → {@code final}）。 */
    private static final YearMonth TARGET = YearMonth.of(2024, 5);
    /** 上一自然月（月环比基线）。 */
    private static final YearMonth PREV = YearMonth.of(2024, 4);
    /** 当前自然月（进行中 → {@code partial}）。 */
    private static final YearMonth CURRENT = YearMonth.of(2024, 6);

    /** 本类专属 ledgerId 段，避免与其它测试共用同一内存 H2 时相互串味。 */
    private static final long LEDGER_BASE = 6_100_000_000L;
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

    private long nextLedger() {
        return LEDGER_BASE + SEQ.incrementAndGet();
    }

    /** 以固定注入的 {@link Clock} 与给定阈值组装真实的 AI 趣味分析组合器（编排真实 ReportService / InsightNarrator）。 */
    private AiInsightService service(AiInsightProperties props) {
        ReportService reportService = new ReportService(transactionRepository, categoryRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository);
        return new AiInsightService(reportService, categoryRepository, merchantRepository, clock, props,
                new InsightNarrator());
    }

    private AiInsightService service() {
        return service(new AiInsightProperties());
    }

    // ============================================================
    // CATEGORY_DELTA 门控：恰好达标 / 恰好未达标（需求 2.3、2.8 相邻）
    // ============================================================

    @Test
    void categoryDelta_thresholdsExactlyMet_generatesInsight() {
        long ledger = nextLedger();
        Long catId = saveCategory(ledger, CategoryKind.EXPENSE, "餐饮").getId();
        // prev=200.00，cur=220.00 → delta=20.00（==金额下限 20）、rate=10.00%（==变化率下限 10）：两项恰好达标。
        expense(ledger, "200.00", PREV, catId);
        expense(ledger, "220.00", TARGET, catId);

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        Optional<AiInsight> delta = byTypeAndDim(resp, "CATEGORY_DELTA", catId);
        assertThat(delta).as("恰好达标（|Δ|=20 且 |rate|=10%）应生成 CATEGORY_DELTA").isPresent();
        assertThat(delta.get().deltaAmount()).isEqualByComparingTo("20.00");
        assertThat(delta.get().changeRate()).isEqualByComparingTo("10.00");
        assertThat(delta.get().direction()).isEqualTo("UP");
    }

    @Test
    void categoryDelta_amountJustBelowThreshold_notGenerated() {
        long ledger = nextLedger();
        Long catId = saveCategory(ledger, CategoryKind.EXPENSE, "餐饮").getId();
        // prev=200.00，cur=219.99 → delta=19.99 < 20（金额下限），虽 rate 达标仍不生成。
        expense(ledger, "200.00", PREV, catId);
        expense(ledger, "219.99", TARGET, catId);

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        assertThat(byTypeAndDim(resp, "CATEGORY_DELTA", catId))
                .as("金额变化量恰好低于下限（19.99<20）不应生成 CATEGORY_DELTA").isEmpty();
    }

    @Test
    void categoryDelta_rateJustBelowThreshold_notGenerated() {
        long ledger = nextLedger();
        Long catId = saveCategory(ledger, CategoryKind.EXPENSE, "餐饮").getId();
        // prev=1000.00，cur=1050.00 → delta=50.00≥20，但 rate=5.00% < 10%（变化率下限），不生成。
        expense(ledger, "1000.00", PREV, catId);
        expense(ledger, "1050.00", TARGET, catId);

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        assertThat(byTypeAndDim(resp, "CATEGORY_DELTA", catId))
                .as("变化率恰好低于下限（5%<10%）不应生成 CATEGORY_DELTA").isEmpty();
    }

    // ============================================================
    // 上月基线 = 0 的无定义分支（需求 2.8、3.8、4.3、6.5）
    // ============================================================

    @Test
    void categoryDelta_previousZero_isNewSpending_notGenerated() {
        // 需求 2.8：某分类上月支出 = 0（新增消费，变化率无定义）→ 不生成 CATEGORY_DELTA。
        long ledger = nextLedger();
        Long newCat = saveCategory(ledger, CategoryKind.EXPENSE, "新增分类").getId();
        Long baseCat = saveCategory(ledger, CategoryKind.EXPENSE, "基线分类").getId();
        // 新增分类：上月 0、目标月 500（prev=0 → 无定义）。
        expense(ledger, "500.00", TARGET, newCat);
        // 基线分类：上月 100、目标月 300（保证有可比基线且非兜底：Δ=200、rate=200% 生成 CATEGORY_DELTA）。
        expense(ledger, "100.00", PREV, baseCat);
        expense(ledger, "300.00", TARGET, baseCat);

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        assertThat(resp.isFallback()).as("存在可比基线，应为非兜底态").isFalse();
        assertThat(byTypeAndDim(resp, "CATEGORY_DELTA", newCat))
                .as("上月支出为 0 的新增分类不应生成 CATEGORY_DELTA（需求 2.8）").isEmpty();
        assertThat(byTypeAndDim(resp, "CATEGORY_DELTA", baseCat))
                .as("基线分类应生成 CATEGORY_DELTA").isPresent();
    }

    @Test
    void savingsAndTopMover_previousExpenseZero_notGeneratedAndNoError() {
        // 需求 3.8：上月总支出 = 0 → 不生成 SAVINGS_TOTAL 且不报错。
        // 需求 6.5：候选集合（上月分类支出>0）为空 → 不生成任何 TOP_MOVER。
        long ledger = nextLedger();
        Long incomeCat = saveCategory(ledger, CategoryKind.INCOME, "工资").getId();
        Long expCat = saveCategory(ledger, CategoryKind.EXPENSE, "餐饮").getId();
        // 上月仅有收入、无任何支出（prevTotalExpense=0），故基线检查通过（收入非 0），但支出侧无可比基线。
        income(ledger, "5000.00", PREV, incomeCat);
        // 目标月有支出（不会因此报错）。
        expense(ledger, "300.00", TARGET, expCat);

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        // 不报错：正常返回响应对象。
        assertThat(resp).isNotNull();
        assertThat(ofType(resp, "SAVINGS_TOTAL"))
                .as("上月总支出为 0 时不应生成 SAVINGS_TOTAL（需求 3.8）").isEmpty();
        assertThat(ofType(resp, "TOP_MOVER"))
                .as("上月无支出分类（候选集合为空）时不应生成 TOP_MOVER（需求 6.5）").isEmpty();
    }

    @Test
    void frequencyDelta_previousCountZero_notGenerated() {
        // 需求 4.3：上月笔数 = 0 → 笔数变化率无定义 → 不生成 FREQUENCY_DELTA。
        long ledger = nextLedger();
        Long newCat = saveCategory(ledger, CategoryKind.EXPENSE, "新频次分类").getId();
        Long baseCat = saveCategory(ledger, CategoryKind.EXPENSE, "基线分类").getId();
        // 新频次分类：上月 0 笔、目标月 5 笔（小额，避免触发金额类洞察）。
        for (int i = 0; i < 5; i++) {
            expense(ledger, "1.00", TARGET, newCat);
        }
        // 基线分类：上月 1 笔 100、目标月 1 笔 300 → CATEGORY_DELTA 保证非兜底；笔数 1→1 不触发 FREQUENCY。
        expense(ledger, "100.00", PREV, baseCat);
        expense(ledger, "300.00", TARGET, baseCat);

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        assertThat(resp.isFallback()).as("存在可比基线，应为非兜底态").isFalse();
        assertThat(byTypeAndDim(resp, "FREQUENCY_DELTA", newCat))
                .as("上月笔数为 0 的分类不应生成 FREQUENCY_DELTA（需求 4.3）").isEmpty();
    }

    // ============================================================
    // TREND_STREAK 门控：连续月数恰好达标 / 差一个月（需求 5.6）
    // ============================================================

    @Test
    void trendStreak_exactlyMeetsMinMonths_generatesInsight() {
        // 默认连续月数下限 3：M-2>M-1>M 严格递减，连续月数=3（含两端）→ 生成 TREND_STREAK。
        long ledger = nextLedger();
        Long catId = saveCategory(ledger, CategoryKind.EXPENSE, "外卖").getId();
        expense(ledger, "300.00", YearMonth.of(2024, 3), catId); // M-2
        expense(ledger, "200.00", PREV, catId);                  // M-1
        expense(ledger, "100.00", TARGET, catId);                // M

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        Optional<AiInsight> streak = byTypeAndDim(resp, "TREND_STREAK", catId);
        assertThat(streak).as("连续 3 个月严格递减（恰好达标）应生成 TREND_STREAK").isPresent();
        assertThat(streak.get().streakMonths()).isEqualTo(3);
        assertThat(streak.get().direction()).isEqualTo("DOWN");
        assertThat(streak.get().streakStartMonth()).isEqualTo("2024-03");
        assertThat(streak.get().streakEndMonth()).isEqualTo("2024-05");
    }

    @Test
    void trendStreak_oneMonthShort_notGenerated() {
        // 仅连续 2 个月递减（M-1>M），M-2 与 M-1 相等 → 连续段在 2 处终止，2 < 3 → 不生成 TREND_STREAK。
        long ledger = nextLedger();
        Long catId = saveCategory(ledger, CategoryKind.EXPENSE, "外卖").getId();
        expense(ledger, "200.00", YearMonth.of(2024, 3), catId); // M-2（与 M-1 相等 → 终止延伸）
        expense(ledger, "200.00", PREV, catId);                  // M-1
        expense(ledger, "100.00", TARGET, catId);                // M

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        assertThat(byTypeAndDim(resp, "TREND_STREAK", catId))
                .as("仅连续 2 个月递减（差一个月）不应生成 TREND_STREAK（需求 5.6）").isEmpty();
    }

    // ============================================================
    // TOP_MOVER 同分类去重选取（需求 6.4、7.5）
    // ============================================================

    @Test
    void topMover_sameCategoryImproveAndOverspend_dedupToSingleInsight() {
        // 候选集合仅 1 个分类（唯一上月支出>0 的分类）→ 改善与超支落在同一分类 → 去重只保留一条，role 由符号决定。
        long ledger = nextLedger();
        Long catId = saveCategory(ledger, CategoryKind.EXPENSE, "购物").getId();
        expense(ledger, "300.00", PREV, catId);
        expense(ledger, "100.00", TARGET, catId); // delta=-200 → 符号<0 → IMPROVE

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        List<AiInsight> movers = ofType(resp, "TOP_MOVER");
        assertThat(movers).as("同一分类的改善与超支应去重为一条 TOP_MOVER（需求 6.4、7.5）").hasSize(1);
        assertThat(movers.get(0).role()).isEqualTo("IMPROVE");
        assertThat(movers.get(0).dimensionId()).isEqualTo(catId);
        assertThat(movers.get(0).deltaAmount()).isEqualByComparingTo("-200.00");
    }

    // ============================================================
    // partial 月 → 全部跳过 → 兜底（需求 9.3；目标月缺省语义见需求 1.2）
    // ============================================================

    @Test
    void partialMonth_allSkipped_returnsFallback() {
        long ledger = nextLedger();
        Long catId = saveCategory(ledger, CategoryKind.EXPENSE, "餐饮").getId();
        expense(ledger, "300.00", CURRENT.minusMonths(1), catId);
        expense(ledger, "100.00", CURRENT, catId);

        AiInsightsResponse resp = service().insights(ledger, CURRENT);

        assertThat(resp.monthStatus()).as("当前自然月为进行中").isEqualTo("partial");
        assertThat(resp.isFallback()).as("partial 月全部洞察跳过 → 兜底态").isTrue();
        assertThat(resp.insights()).isEmpty();
        assertThat(resp.fallbackText()).isNotNull().isNotBlank();
        assertThat(resp.month()).isEqualTo(CURRENT.toString());
    }

    // ============================================================
    // N 边界（需求 7.2、10.1）：1、20、越界钳制
    // ============================================================

    @Test
    void maxCount_nEquals1_returnsAtMostOne() {
        long ledger = seedManyCategories(25);
        AiInsightProperties props = new AiInsightProperties();
        props.setMaxCount(1);

        AiInsightsResponse resp = service(props).insights(ledger, TARGET);

        assertThat(resp.isFallback()).isFalse();
        assertThat(resp.insights()).as("N=1 时最多返回 1 条").hasSize(1);
    }

    @Test
    void maxCount_nEquals20_returnsAtMost20() {
        long ledger = seedManyCategories(25); // 候选总数 > 20
        AiInsightProperties props = new AiInsightProperties();
        props.setMaxCount(20);

        AiInsightsResponse resp = service(props).insights(ledger, TARGET);

        assertThat(resp.insights()).as("N=20 且候选>20 时恰好返回 20 条").hasSize(20);
    }

    @Test
    void maxCount_belowRange_clampsToOne() {
        long ledger = seedManyCategories(25);
        AiInsightProperties props = new AiInsightProperties();
        props.setMaxCount(0); // 越界（<1）→ 钳制到 1

        AiInsightsResponse resp = service(props).insights(ledger, TARGET);

        assertThat(resp.insights()).as("N=0 越界钳制到 1").hasSize(1);
    }

    @Test
    void maxCount_aboveRange_clampsTo20() {
        long ledger = seedManyCategories(25);
        AiInsightProperties props = new AiInsightProperties();
        props.setMaxCount(999); // 越界（>20）→ 钳制到 20

        AiInsightsResponse resp = service(props).insights(ledger, TARGET);

        assertThat(resp.insights()).as("N=999 越界钳制到 20").hasSize(20);
    }

    // ============================================================
    // 叙事长度上界与措辞极性（需求 8.5、8.6、8.7）
    // ============================================================

    @Test
    void narrative_lengthWithinUpperBound_forAllInsights() {
        long ledger = seedManyCategories(25);
        AiInsightProperties props = new AiInsightProperties();
        props.setMaxCount(20);

        AiInsightsResponse resp = service(props).insights(ledger, TARGET);

        assertThat(resp.insights()).isNotEmpty();
        for (AiInsight in : resp.insights()) {
            if (in.narrativeText() != null) {
                assertThat(in.narrativeText().length())
                        .as("叙事文案长度不超过 100 个字符（需求 8.5）：%s", in.narrativeText())
                        .isLessThanOrEqualTo(100);
            }
        }
    }

    @Test
    void narrative_wordingPolarity_downIsPositive_upIsCautionary() {
        // 下降方向（IMPROVE/DOWN）→ 正向或中性、不含提醒词；上升方向（UP/OVERSPEND）→ 提醒性措辞。
        long ledger = nextLedger();
        Long downCat = saveCategory(ledger, CategoryKind.EXPENSE, "餐饮").getId();
        Long upCat = saveCategory(ledger, CategoryKind.EXPENSE, "购物").getId();
        // 下降分类：prev=300、cur=100（Δ=-200、rate=-66.67%）→ DOWN。
        expense(ledger, "300.00", PREV, downCat);
        expense(ledger, "100.00", TARGET, downCat);
        // 上升分类：prev=100、cur=300（Δ=+200、rate=200%）→ UP。
        expense(ledger, "100.00", PREV, upCat);
        expense(ledger, "300.00", TARGET, upCat);

        AiInsightsResponse resp = service().insights(ledger, TARGET);

        AiInsight down = byTypeAndDim(resp, "CATEGORY_DELTA", downCat)
                .orElseThrow(() -> new AssertionError("缺少下降分类的 CATEGORY_DELTA"));
        AiInsight up = byTypeAndDim(resp, "CATEGORY_DELTA", upCat)
                .orElseThrow(() -> new AssertionError("缺少上升分类的 CATEGORY_DELTA"));

        assertThat(down.narrativeText())
                .as("下降方向应为正向/中性措辞，不含提醒/警示词（需求 8.6）：%s", down.narrativeText())
                .isNotNull()
                .doesNotContain("留意")
                .doesNotContain("关注")
                .doesNotContain("注意");
        assertThat(up.narrativeText())
                .as("上升方向应采用提醒性措辞（需求 8.7）：%s", up.narrativeText())
                .isNotNull()
                .containsAnyOf("留意", "关注");
    }

    // ============================================================
    // 叙事生成失败分支（需求 8.8）：缺全部关键数值 → 返回 null（保留机器字段、整体不报错）
    // ============================================================

    @Test
    void narrator_missingAllKeyNumerics_returnsNull() {
        InsightNarrator narrator = new InsightNarrator();
        // CATEGORY_DELTA 但 deltaAmount 与 changeRate 均缺失 → 无关键数值 → 生成失败返回 null（需求 8.8）。
        AiInsight broken = new AiInsight(
                "CATEGORY_DELTA", "CATEGORY", 1L, "餐饮",
                null, null, null, null,
                null, null, null,
                null, null, null,
                "DOWN", null, null, null);

        assertThat(narrator.render(broken))
                .as("缺全部关键数值时叙事应生成失败返回 null（需求 8.8）").isNull();
    }

    // ---------------------------------- 数据准备辅助 ----------------------------------

    /**
     * 为一个新账本创建 {@code count} 个支出分类，每个分类上月/目标月支出均满足 CATEGORY_DELTA 门控，
     * 使候选总数（CATEGORY_DELTA×count + TOP_MOVER×2 + SAVINGS_TOTAL×1）远超 N，用于 N 边界测试。
     *
     * @return 新账本 id
     */
    private long seedManyCategories(int count) {
        long ledger = nextLedger();
        for (int i = 0; i < count; i++) {
            Long catId = saveCategory(ledger, CategoryKind.EXPENSE, "cat-" + i).getId();
            BigDecimal prev = new BigDecimal(100 + i * 10);
            BigDecimal cur = prev.add(new BigDecimal(50 + i)); // Δ≥50≥20，rate≥~15%≥10
            expense(ledger, prev.setScale(2).toPlainString(), PREV, catId);
            expense(ledger, cur.setScale(2).toPlainString(), TARGET, catId);
        }
        return ledger;
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

    /** 在给定自然月第 10 日落一笔支出。 */
    private void expense(long ledgerId, String amount, YearMonth month, Long categoryId) {
        persist(ledgerId, TransactionType.EXPENSE, new BigDecimal(amount),
                month.atDay(Math.min(10, month.lengthOfMonth())).atTime(9, 30), categoryId);
    }

    /** 在给定自然月第 10 日落一笔收入。 */
    private void income(long ledgerId, String amount, YearMonth month, Long categoryId) {
        persist(ledgerId, TransactionType.INCOME, new BigDecimal(amount),
                month.atDay(Math.min(10, month.lengthOfMonth())).atTime(9, 30), categoryId);
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

    // ---------------------------------- 断言辅助 ----------------------------------

    private static List<AiInsight> ofType(AiInsightsResponse resp, String type) {
        return resp.insights().stream().filter(i -> type.equals(i.type())).toList();
    }

    private static Optional<AiInsight> byTypeAndDim(AiInsightsResponse resp, String type, Long dimensionId) {
        return resp.insights().stream()
                .filter(i -> type.equals(i.type()) && dimensionId.equals(i.dimensionId()))
                .findFirst();
    }
}
