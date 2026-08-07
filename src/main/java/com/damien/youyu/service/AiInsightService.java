package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.AiInsightsResponse;
import com.damien.youyu.api.dto.AiInsightsResponse.AiInsight;
import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.CategoryReportResponse.CategoryShare;
import com.damien.youyu.api.dto.DimensionReportResponse;
import com.damien.youyu.api.dto.DimensionReportResponse.DimensionShare;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.config.AiInsightProperties;
import com.damien.youyu.domain.Merchant;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;

/**
 * AI 趣味分析只读组合器（read-only composer，design.md「Architecture」「Components and Interfaces」）。
 *
 * <p>针对某一自然月，编排既有 {@link ReportService}（{@code monthlyReport / categoryReport /
 * dimensionReport}）把目标月 M 与上一自然月 M−1（及 streak 的至多 6 个月）的派生指标算成一组<b>候选洞察</b>，
 * 确定性打分挑选 → 交给 {@link InsightNarrator} 用中文模板渲染 → 打包成 {@link AiInsightsResponse}。
 * 全过程<b>只读、无任何写语句</b>（需求 13.1）：仅编排既有服务与仓库查询，<b>不新增任何 repository 查询、
 * 不新增任何 SQL</b>（需求 13.1、13.2）。</p>
 *
 * <p><b>口径</b>：金额一律 {@link BigDecimal} 保留 2 位小数（HALF_UP），变化率（百分比）保留 2 位小数
 * （HALF_UP）；自然月边界按 {@code Asia/Shanghai}（由注入的 {@link Clock} 决定）；所有金额/笔数统计排除
 * {@code type=transfer}，与既有 {@code /api/reports/*} 逐值同口径（需求 1.6、1.7、13.5）。</p>
 *
 * <p><b>月状态与短路</b>（需求 1.3、1.4、9.3）：</p>
 * <ul>
 *   <li>{@code status = month.isBefore(YearMonth.now(clock)) ? "final" : "partial"}。</li>
 *   <li>{@code partial}（含缺省的当前自然月）：v1 五类洞察全部依赖完整月对比，全部跳过 → 候选为空 →
 *       返回一条鼓励性兜底文案（需求 9.3、9.6）。</li>
 *   <li>可比基线检查：{@code prev = month.minusMonths(1)}；若上月总收入与总支出均为 {@code 0.00}
 *       （上月无任何计入交易 = 无可比基线）→ 候选为空 → 鼓励兜底（需求 9.1、1.10）。</li>
 * </ul>
 */
@Service
public class AiInsightService {

    /** 月状态：已完结（目标月早于当前自然月，需求 1.4）。 */
    static final String STATUS_FINAL = "final";

    /** 月状态：进行中（目标月为当前自然月且当月未结束，需求 1.3）。 */
    static final String STATUS_PARTIAL = "partial";

    /** 鼓励性兜底文案（1..100 字符，非空，需求 9.1、9.2、9.3）。 */
    static final String FALLBACK_TEXT = "才刚开始记账，下个月就能看到你的变化啦～";

    /** 金额/变化率统一保留的小数位（HALF_UP），与既有报表口径一致（需求 1.7）。 */
    private static final int SCALE = 2;

    /** 百分比换算常量。 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 洞察类型：分类消费涨跌（需求 2）。 */
    static final String TYPE_CATEGORY_DELTA = "CATEGORY_DELTA";

    /** 洞察类型：比上月节省/多花总额（需求 3）。 */
    static final String TYPE_SAVINGS_TOTAL = "SAVINGS_TOTAL";

    /** 洞察类型：商户或分类频次变化（需求 4）。 */
    static final String TYPE_FREQUENCY_DELTA = "FREQUENCY_DELTA";

    /** 洞察类型：连续涨跌趋势（需求 5）。 */
    static final String TYPE_TREND_STREAK = "TREND_STREAK";

    /** 洞察类型：最大改善/最超支分类（需求 6）。 */
    static final String TYPE_TOP_MOVER = "TOP_MOVER";

    /** 连续涨跌趋势向前回看的自然月总数（含目标月 M，即 M−5..M，至多 6 个月，需求 5.1）。 */
    private static final int STREAK_WINDOW_MONTHS = 6;

    /** 角色：改善/节省（需求 3.6、6.2）。 */
    static final String ROLE_IMPROVE = "IMPROVE";

    /** 角色：超支/多花（需求 3.7、6.2）。 */
    static final String ROLE_OVERSPEND = "OVERSPEND";

    /** 维度：分类。 */
    static final String DIMENSION_CATEGORY = "CATEGORY";

    /** 维度：商户（需求 4.2）。 */
    static final String DIMENSION_MERCHANT = "MERCHANT";

    /** {@link ReportService#dimensionReport} 的商户维度标识（需求 4.2）。 */
    private static final String DIM_MERCHANT = "merchant";

    /** 方向：下降/减少（需求 2.5）。 */
    static final String DIRECTION_DOWN = "DOWN";

    /** 方向：上升/增加（需求 2.5）。 */
    static final String DIRECTION_UP = "UP";

    private final ReportService reportService;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;
    private final Clock clock;
    private final AiInsightProperties props;
    private final InsightNarrator narrator;

    public AiInsightService(
            ReportService reportService,
            CategoryRepository categoryRepository,
            MerchantRepository merchantRepository,
            Clock clock,
            AiInsightProperties props,
            InsightNarrator narrator) {
        this.reportService = reportService;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.clock = clock;
        this.props = props;
        this.narrator = narrator;
    }

    /**
     * 生成目标月的 AI 趣味分析数据包（需求 1、8、9、10）。纯只读派生，不落库。
     *
     * <p><b>编排步骤</b>：月状态与结束边界计算 → partial 短路（需求 9.3）→ 可比基线检查（需求 9.1、1.10）
     * → 五类候选洞察构建（{@code buildCategoryDelta / buildSavingsTotal / buildFrequencyDelta /
     * buildTrendStreak / buildTopMover}）→ 显著度打分/去重/确定性排序/截断至 N（{@link #scoreDedupSortTruncate}）
     * → 兜底语义与响应组装（任务 7）。</p>
     *
     * <p><b>兜底语义</b>（需求 9.1–9.6）：partial 跳空、上月无可比基线、或挑选后无候选三种情形均走
     * {@link #fallback}（{@code isFallback=true}、一条非空鼓励文案、{@code insights} 为空列表）；否则返回
     * 非兜底态（{@code isFallback=false}、{@code fallbackText=null}、{@code insights} 为 1..N 条）。两态均携带
     * {@code month}（{@code YYYY-MM}）与 {@code monthStatus}（{@code partial}/{@code final}）。</p>
     *
     * <p><b>隐私白名单</b>（需求 12.3、12.4、12.5）：响应 DTO 字段集本身即白名单——仅派生统计 + 维度名 + 叙事
     * 文案，结构上不含 email/令牌/其它账本数据/{@code external_id}/原始备注/商户原始标识。装配时对每条洞察的
     * {@code dimensionName} 做回退规整（{@link #normalizeNames}），确保名称缺失/空白时取固定回退名而非泄漏
     * 空值（需求 2.7、4.6）。{@code narrativeText} 的填充由任务 8 接入 {@link InsightNarrator} 完成。</p>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月（按 {@code Asia/Shanghai} 边界）
     * @return 目标月挑选后不超过 N 条趣味洞察，或一条鼓励性兜底文案
     */
    @Transactional(readOnly = true)
    public AiInsightsResponse insights(Long ledgerId, YearMonth month) {
        // 月状态：目标月早于当前自然月为已完结，否则进行中（需求 1.3、1.4）。
        String monthStatus = month.isBefore(YearMonth.now(clock)) ? STATUS_FINAL : STATUS_PARTIAL;

        // partial 短路（需求 9.3）：v1 五类均依赖完整月对比 → 候选为空 → 鼓励兜底，仍携带 month、monthStatus。
        if (STATUS_PARTIAL.equals(monthStatus)) {
            return fallback(month, monthStatus);
        }

        // 可比基线检查（需求 9.1、1.10）：上月无任何计入交易（总收入与总支出均为 0.00）→ 无可比基线 → 鼓励兜底。
        YearMonth prev = month.minusMonths(1);
        MonthlyReportResponse prevMonthly = reportService.monthlyReport(ledgerId, prev);
        if (isZero(prevMonthly.totalIncome()) && isZero(prevMonthly.totalExpense())) {
            return fallback(month, monthStatus);
        }

        // final 月且有可比基线：构建五类候选洞察（纯派生）。
        List<AiInsight> candidates = new ArrayList<>();
        candidates.addAll(buildCategoryDelta(ledgerId, month, prev));
        candidates.addAll(buildSavingsTotal(ledgerId, month, prev));
        candidates.addAll(buildFrequencyDelta(ledgerId, month, prev));
        candidates.addAll(buildTrendStreak(ledgerId, month));
        candidates.addAll(buildTopMover(ledgerId, month, prev));

        // 显著度打分 → 去重 →（type 全序 + dimensionId 升序决胜的）确定性排序 → 截断至 N（需求 7）。
        // 返回的每条洞察已填充 score，narrativeText 仍为 null（任务 8 接入 narrator.render 时填充）。
        List<AiInsight> selected = scoreDedupSortTruncate(candidates);

        // 挑选后无候选（需求 9.2）→ 鼓励兜底。
        if (selected.isEmpty()) {
            return fallback(month, monthStatus);
        }

        // 隐私净化 + 维度名回退（需求 2.7、4.6、12.3、12.4、12.5）：DTO 字段集即白名单，此处仅规整 dimensionName。
        List<AiInsight> normalized = normalizeNames(selected);

        // 叙事渲染（任务 8，需求 8.1、8.8）：对每条洞察调用 narrator.render 生成 narrativeText；
        // render 缺全部关键数值时返回 null，据此标记生成失败、保留机器字段、整体不报错。
        List<AiInsight> rendered = renderNarratives(normalized);

        // 非兜底态（需求 9.4、9.5、9.6）：isFallback=false、fallbackText=null、insights 为 1..N 条。
        return new AiInsightsResponse(month.toString(), monthStatus, false, null, rendered);
    }

    /**
     * {@code CATEGORY_DELTA}（分类消费涨跌）候选构建（需求 2）。
     *
     * <p>复用 {@link ReportService#categoryReport(Long, java.time.LocalDate, java.time.LocalDate)}
     * （默认 EXPENSE 口径、{@code Asia/Shanghai} 半开区间、金额 2dp HALF_UP、排除 {@code transfer}）分别取目标月 M
     * 与上一自然月 prev 的每分类支出，逐值同口径（需求 1.6、1.7、2.1、13.5）。</p>
     *
     * <p>对 M 的每个支出分类：{@code deltaAmount = cur − prev}（2dp，HALF_UP，可负，需求 2.1）；
     * {@code changeRate} 仅在 {@code prev > 0} 时有定义 = {@code deltaAmount ÷ prev × 100}（2dp，HALF_UP），
     * 否则为 {@code null}（需求 2.2、2.8）。<b>门控</b>：仅当 {@code prev > 0} 且
     * {@code |changeRate| ≥ categoryRatePctMin} 且 {@code |deltaAmount| ≥ categoryAmountMin} 三项全满足才生成候选；
     * {@code prev = 0}（新增消费，变化率无定义）不生成（需求 2.3、2.8）。</p>
     *
     * <p><b>方向</b>：{@code cur < prev → DOWN}（下降）、{@code cur > prev → UP}（上升，需求 2.5）。每条候选携带
     * 分类 id/名称、cur、prev、{@code deltaAmount}、{@code changeRate}（需求 2.4）。{@code score} 与 {@code narrativeText}
     * 分别由任务 6（打分）、任务 8（叙事）填充，此处留空。</p>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月 M
     * @param prev     上一自然月 M−1
     * @return 满足门控的 {@code CATEGORY_DELTA} 候选洞察（顺序由后续打分/排序统一决定）
     */
    private List<AiInsight> buildCategoryDelta(Long ledgerId, YearMonth month, YearMonth prev) {
        // 全月范围（含起止边界，Asia/Shanghai 半开区间由 categoryReport 内部处理）。
        CategoryReportResponse curReport =
                reportService.categoryReport(ledgerId, month.atDay(1), month.atEndOfMonth());
        CategoryReportResponse prevReport =
                reportService.categoryReport(ledgerId, prev.atDay(1), prev.atEndOfMonth());

        // 上月每分类支出映射（用于查基线），保持稳定顺序。
        Map<Long, BigDecimal> prevAmountByCategory = new LinkedHashMap<>();
        for (CategoryShare s : prevReport.categories()) {
            prevAmountByCategory.put(s.categoryId(), s.amount());
        }

        BigDecimal rateMin = props.getCategoryRatePctMin().abs();
        BigDecimal amountMin = props.getCategoryAmountMin().abs();

        List<AiInsight> out = new ArrayList<>();
        for (CategoryShare s : curReport.categories()) {
            BigDecimal cur = scale(s.amount());
            BigDecimal prevAmount = scale(prevAmountByCategory.getOrDefault(s.categoryId(), BigDecimal.ZERO));

            // prev = 0（新增消费，变化率无定义）→ 不生成（需求 2.8）。
            if (prevAmount.signum() <= 0) {
                continue;
            }

            BigDecimal deltaAmount = scale(cur.subtract(prevAmount));
            BigDecimal changeRate = deltaAmount
                    .multiply(HUNDRED)
                    .divide(prevAmount, SCALE, RoundingMode.HALF_UP);

            // 门控：|changeRate| ≥ 变化率下限 且 |deltaAmount| ≥ 金额下限（需求 2.3）。
            if (changeRate.abs().compareTo(rateMin) < 0 || deltaAmount.abs().compareTo(amountMin) < 0) {
                continue;
            }

            // 方向：cur < prev → DOWN、cur > prev → UP（需求 2.5）。
            String direction = cur.compareTo(prevAmount) < 0 ? DIRECTION_DOWN : DIRECTION_UP;

            out.add(new AiInsight(
                    TYPE_CATEGORY_DELTA,
                    DIMENSION_CATEGORY,
                    s.categoryId(),
                    s.categoryName(),
                    cur,
                    prevAmount,
                    null,
                    null,
                    deltaAmount,
                    null,
                    changeRate,
                    null,
                    null,
                    null,
                    direction,
                    null,
                    null,
                    null));
        }
        return out;
    }

    /**
     * {@code SAVINGS_TOTAL}（比上月节省/多花总额）候选构建（需求 3）。
     *
     * <p>复用 {@link ReportService#monthlyReport(Long, YearMonth)}（{@code Asia/Shanghai} 自然月边界、金额 2dp
     * HALF_UP、排除 {@code transfer}）分别取目标月 M 与上一自然月 prev 的<b>月度总支出</b>，逐值同口径
     * （需求 1.6、1.7、3.1、13.5）。</p>
     *
     * <p>节省额 {@code savings = prevTotalExpense − curTotalExpense}（2dp，HALF_UP，可负，需求 3.2）；
     * {@code changeRate} 仅在 {@code prevTotalExpense > 0} 时有定义 = {@code savings ÷ prevTotalExpense × 100}
     * （2dp，HALF_UP），否则为 {@code null}（需求 3.3、3.8）。<b>门控</b>：仅当 {@code prevTotalExpense > 0}
     * 且 {@code |savings| ≥ savingsAmountMin} 才生成候选；{@code prevTotalExpense = 0} 不生成且不报错
     * （需求 3.4、3.5、3.8）。</p>
     *
     * <p><b>角色</b>：{@code savings > 0 → IMPROVE}（节省）、{@code savings < 0 → OVERSPEND}（多花，需求 3.6、3.7）。
     * 维度为账本总额，故 {@code dimension = null、dimensionId = null、dimensionName = null}；{@code direction}
     * 亦为 {@code null}（方向语义由 {@code role} 表达）。{@code currentValue = curTotalExpense}、
     * {@code previousValue = prevTotalExpense}、{@code deltaAmount = savings}（需求 3.4）。{@code score} 与
     * {@code narrativeText} 分别由任务 6、任务 8 填充，此处留空。</p>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月 M
     * @param prev     上一自然月 M−1
     * @return 满足门控的 {@code SAVINGS_TOTAL} 候选（0 或 1 条，顺序由后续打分/排序统一决定）
     */
    private List<AiInsight> buildSavingsTotal(Long ledgerId, YearMonth month, YearMonth prev) {
        BigDecimal curTotalExpense = scale(reportService.monthlyReport(ledgerId, month).totalExpense());
        BigDecimal prevTotalExpense = scale(reportService.monthlyReport(ledgerId, prev).totalExpense());

        // prevTotalExpense = 0 → 变化率无定义、无可比基线 → 不生成且不报错（需求 3.8）。
        if (prevTotalExpense.signum() <= 0) {
            return List.of();
        }

        BigDecimal savings = scale(prevTotalExpense.subtract(curTotalExpense));

        // 门控：|savings| ≥ 金额下限（需求 3.4、3.5）。savings=0（收支持平）不构成节省/多花，不生成。
        if (savings.signum() == 0
                || savings.abs().compareTo(props.getSavingsAmountMin().abs()) < 0) {
            return List.of();
        }

        // changeRate 仅在 prevTotalExpense > 0 时有定义（需求 3.3），此处已保证成立。
        BigDecimal changeRate = savings
                .multiply(HUNDRED)
                .divide(prevTotalExpense, SCALE, RoundingMode.HALF_UP);

        // 角色：savings > 0 → IMPROVE（节省）、savings < 0 → OVERSPEND（多花）（需求 3.6、3.7）。
        String role = savings.signum() > 0 ? ROLE_IMPROVE : ROLE_OVERSPEND;

        AiInsight insight = new AiInsight(
                TYPE_SAVINGS_TOTAL,
                null,
                null,
                null,
                curTotalExpense,
                prevTotalExpense,
                null,
                null,
                savings,
                null,
                changeRate,
                null,
                null,
                null,
                null,
                role,
                null,
                null);
        return List.of(insight);
    }

    /**
     * {@code FREQUENCY_DELTA}（商户或分类频次变化）候选构建（需求 4）。
     *
     * <p>覆盖两个维度：<b>分类维度</b>（{@link #DIMENSION_CATEGORY}）复用
     * {@link ReportService#categoryReport(Long, java.time.LocalDate, java.time.LocalDate)} 的每分类支出笔数；
     * <b>商户维度</b>（{@link #DIMENSION_MERCHANT}）复用
     * {@link ReportService#dimensionReport(Long, java.time.LocalDate, java.time.LocalDate,
     * TransactionType, String)}（{@code dim=merchant}、{@code kind=EXPENSE}）的每商户支出笔数。二者均按
     * {@code Asia/Shanghai} 半开区间、排除 {@code type=transfer}，逐值同口径（需求 4.1、4.2、13.5）。仅按分类
     * 与商户维度识别，<b>不使用 {@code note} 备注关键词匹配</b>（需求 4.7）。</p>
     *
     * <p>对每个维度对象：{@code deltaCount = curCount − prevCount}（整数，可负，需求 4.3）；{@code countRate}
     * 仅在 {@code prevCount > 0} 时有定义 = {@code deltaCount ÷ prevCount × 100}（2dp，HALF_UP），否则无定义
     * （不生成，需求 4.3）。<b>门控</b>：仅当 {@code prevCount > 0} 且 {@code |countRate| ≥ frequencyRatePctMin}
     * 且 {@code |deltaCount| ≥ frequencyCountMin} 三项全满足才生成候选（需求 4.4）。<b>方向</b>：
     * {@code curCount < prevCount → DOWN}（减少）、{@code curCount > prevCount → UP}（增加，需求 4.5）。每条候选
     * 携带维度、维度 id/名称、{@code currentCount}、{@code previousCount}、{@code deltaCount} 及存于
     * {@code changeRate} 字段的 {@code countRate}（需求 4.4）；金额相关字段（{@code currentValue}/
     * {@code previousValue}/{@code deltaAmount}）为 {@code null}（纯频次洞察）。{@code score} 与
     * {@code narrativeText} 分别由任务 6、任务 8 填充，此处留空。</p>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月 M
     * @param prev     上一自然月 M−1
     * @return 满足门控的 {@code FREQUENCY_DELTA} 候选（含分类与商户两个维度）
     */
    private List<AiInsight> buildFrequencyDelta(Long ledgerId, YearMonth month, YearMonth prev) {
        List<AiInsight> out = new ArrayList<>();
        out.addAll(buildFrequencyForCategory(ledgerId, month, prev));
        out.addAll(buildFrequencyForMerchant(ledgerId, month, prev));
        return out;
    }

    /** 分类维度频次候选：由 {@code categoryReport} 的每分类笔数派生（需求 4.2）。 */
    private List<AiInsight> buildFrequencyForCategory(Long ledgerId, YearMonth month, YearMonth prev) {
        CategoryReportResponse curReport =
                reportService.categoryReport(ledgerId, month.atDay(1), month.atEndOfMonth());
        CategoryReportResponse prevReport =
                reportService.categoryReport(ledgerId, prev.atDay(1), prev.atEndOfMonth());

        Map<Long, Long> curCounts = new LinkedHashMap<>();
        Map<Long, Long> prevCounts = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (CategoryShare s : curReport.categories()) {
            curCounts.put(s.categoryId(), s.count());
            names.put(s.categoryId(), s.categoryName());
        }
        for (CategoryShare s : prevReport.categories()) {
            prevCounts.put(s.categoryId(), s.count());
            names.putIfAbsent(s.categoryId(), s.categoryName());
        }
        return frequencyCandidates(DIMENSION_CATEGORY, curCounts, prevCounts, names);
    }

    /** 商户维度频次候选：由 {@code dimensionReport(dim=merchant)} 的每商户笔数派生（需求 4.2）。 */
    private List<AiInsight> buildFrequencyForMerchant(Long ledgerId, YearMonth month, YearMonth prev) {
        DimensionReportResponse curReport = reportService.dimensionReport(
                ledgerId, month.atDay(1), month.atEndOfMonth(), TransactionType.EXPENSE, DIM_MERCHANT);
        DimensionReportResponse prevReport = reportService.dimensionReport(
                ledgerId, prev.atDay(1), prev.atEndOfMonth(), TransactionType.EXPENSE, DIM_MERCHANT);

        // 商户显示名权威来源（需求 4.6）：从 merchants 表按账本一次性构建 id→name 映射。
        // 已删除商户（无 merchants 行）在此映射中缺席 → name 为 null，交由 normalizeNames/fallbackName
        // 统一回退为固定名 DELETED_MERCHANT_NAME「已删除商户」，而非沿用 dimensionReport 的占位名「已删除」。
        // 计数/笔数仍取自 dimensionReport，口径不变；现存商户仍显示其真实名称。
        Map<Long, String> merchantNames = new LinkedHashMap<>();
        for (Merchant m : merchantRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId)) {
            merchantNames.put(m.getId(), m.getName());
        }

        Map<Long, Long> curCounts = new LinkedHashMap<>();
        Map<Long, Long> prevCounts = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (DimensionShare s : curReport.items()) {
            curCounts.put(s.id(), s.count());
            names.put(s.id(), merchantNames.get(s.id()));
        }
        for (DimensionShare s : prevReport.items()) {
            prevCounts.put(s.id(), s.count());
            names.putIfAbsent(s.id(), merchantNames.get(s.id()));
        }
        return frequencyCandidates(DIMENSION_MERCHANT, curCounts, prevCounts, names);
    }

    /**
     * 频次候选的共用派生逻辑：对分类/商户两维度对象按门控生成 {@code FREQUENCY_DELTA} 候选（需求 4.3、4.4、4.5）。
     *
     * <p>遍历目标月与上月对象的并集（按 id 升序保证确定性）：{@code prevCount = 0} 时变化率无定义 → 不生成
     * （需求 4.3）；否则计算 {@code deltaCount} 与 {@code countRate}，按「{@code |countRate| ≥ 下限} 且
     * {@code |deltaCount| ≥ 下限}」门控（需求 4.4）。</p>
     */
    private List<AiInsight> frequencyCandidates(
            String dimension, Map<Long, Long> curCounts, Map<Long, Long> prevCounts, Map<Long, String> names) {
        BigDecimal rateMin = props.getFrequencyRatePctMin().abs();
        int countMin = Math.abs(props.getFrequencyCountMin());

        Set<Long> keys = new TreeSet<>();
        keys.addAll(curCounts.keySet());
        keys.addAll(prevCounts.keySet());

        List<AiInsight> out = new ArrayList<>();
        for (Long id : keys) {
            long curCount = curCounts.getOrDefault(id, 0L);
            long prevCount = prevCounts.getOrDefault(id, 0L);

            // prevCount = 0 → 变化率无定义（新增），不生成（需求 4.3、4.4）。
            if (prevCount <= 0) {
                continue;
            }

            long deltaCount = curCount - prevCount;
            BigDecimal countRate = BigDecimal.valueOf(deltaCount)
                    .multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(prevCount), SCALE, RoundingMode.HALF_UP);

            // 门控：|countRate| ≥ 变化率下限 且 |deltaCount| ≥ 次数下限（需求 4.4）。
            if (countRate.abs().compareTo(rateMin) < 0 || Math.abs(deltaCount) < countMin) {
                continue;
            }

            // 方向：curCount < prevCount → DOWN（减少）、curCount > prevCount → UP（增加）（需求 4.5）。
            String direction = curCount < prevCount ? DIRECTION_DOWN : DIRECTION_UP;

            out.add(new AiInsight(
                    TYPE_FREQUENCY_DELTA,
                    dimension,
                    id,
                    names.get(id),
                    null,
                    null,
                    (int) curCount,
                    (int) prevCount,
                    null,
                    (int) deltaCount,
                    countRate,
                    null,
                    null,
                    null,
                    direction,
                    null,
                    null,
                    null));
        }
        return out;
    }

    /**
     * {@code TREND_STREAK}（连续涨跌趋势）候选构建（需求 5）。
     *
     * <p><b>按月序列</b>：对每个 {@code CATEGORY} 支出分类，复用
     * {@link ReportService#categoryReport(Long, java.time.LocalDate, java.time.LocalDate)}
     * 逐月取 M−5..M（{@link #STREAK_WINDOW_MONTHS} 个自然月，至多 6 次调用，k=0..5）的分类支出，构建<b>按自然月
     * 升序</b>（下标 0=M−5 … 5=M）、<b>无数据月计 {@code 0.00}</b> 的每分类支出序列。每次 {@code categoryReport}
     * 均按 {@code Asia/Shanghai} 半开区间、金额 2dp HALF_UP、排除 {@code transfer}，逐值同口径（需求 5.1、13.5）。</p>
     *
     * <p><b>连续段检测</b>（需求 5.2、5.3）：以目标月 M（序列末端 {@code anchor = n−1}）为锚点<b>倒序</b>逐一比较
     * 相邻两月的分类支出——把「较晚月与其前一（较早）月」比较：较晚月严格<b>小于</b>较早月即一步递减、严格<b>大于</b>
     * 即一步递增；由首个非零方向确定整段方向，之后每一步须与该方向一致方可延伸。遇相邻两月<b>相等</b>（含两月均为
     * {@code 0.00}）或<b>方向反转</b>即终止延伸。连续月数 {@code streakMonths}<b>含两端计数</b>（锚点月本身计为 1，
     * 每成功延伸一步 +1）。</p>
     *
     * <p><b>门控与产出</b>：仅当 {@code streakMonths ≥ streakMinMonths} 才生成候选（需求 5.4、5.6）。方向为递减 →
     * {@link #DIRECTION_DOWN}（连续下降）、递增 → {@link #DIRECTION_UP}（连续上升，需求 5.5）。每条候选携带维度
     * （{@link #DIMENSION_CATEGORY}）、分类 id/名称、{@code direction}、{@code streakMonths}、
     * {@code streakStartMonth}（连续段最早自然月 {@code YYYY-MM}）、{@code streakEndMonth}（= M，{@code YYYY-MM}，
     * 需求 5.4）。</p>
     *
     * <p><b>任务 6 打分衔接（重要）</b>：设计「打分模型」规定 {@code TREND_STREAK} 的显著度
     * {@code score = |M值 − 段起始月值|}。为使任务 6 无需重算整段按月序列即可打分，本方法将<b>连续段两端的分类
     * 支出金额顺带存入 DTO 现有字段</b>：{@code currentValue = M 值}（锚点月分类支出，2dp）、
     * {@code previousValue = 段起始月值}（{@code streakStartMonth} 当月分类支出，2dp）。二者均为 {@link BigDecimal}
     * 2dp，满足金额字段 2dp 不变式；任务 6 直接以 {@code |currentValue − previousValue|} 计算 {@code score}。
     * {@code deltaAmount}/{@code changeRate}（趋势段无单一环比变化率）与 {@code currentCount}/{@code previousCount}/
     * {@code deltaCount}、{@code role} 均为 {@code null}；{@code score} 与 {@code narrativeText} 分别由任务 6、任务 8 填充。
     * 叙事模板对 {@code TREND_STREAK} 仅使用 {@code dimensionName} 与 {@code streakMonths}，故 {@code currentValue}/
     * {@code previousValue} 的暂存不会泄漏到文案中。</p>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月 M（连续段结束月）
     * @return 满足门控的 {@code TREND_STREAK} 候选（每个符合条件的分类一条，按分类 id 升序，顺序最终由任务 6 统一决定）
     */
    private List<AiInsight> buildTrendStreak(Long ledgerId, YearMonth month) {
        // M−5..M 升序（下标 0=M−5 … 5=M）。
        List<YearMonth> months = new ArrayList<>(STREAK_WINDOW_MONTHS);
        for (int k = STREAK_WINDOW_MONTHS - 1; k >= 0; k--) {
            months.add(month.minusMonths(k));
        }

        // 逐月取每分类支出，构建 categoryId → 6 个月支出序列（无数据月计 0.00）。
        Map<Long, BigDecimal[]> seriesByCategory = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (int idx = 0; idx < months.size(); idx++) {
            YearMonth ym = months.get(idx);
            CategoryReportResponse report =
                    reportService.categoryReport(ledgerId, ym.atDay(1), ym.atEndOfMonth());
            for (CategoryShare s : report.categories()) {
                BigDecimal[] series = seriesByCategory.computeIfAbsent(s.categoryId(), id -> zeroSeries());
                series[idx] = scale(s.amount());
                names.putIfAbsent(s.categoryId(), s.categoryName());
            }
        }

        int streakMin = props.getStreakMinMonths();

        // 按分类 id 升序遍历，保证确定性输出。
        List<AiInsight> out = new ArrayList<>();
        for (Long categoryId : new TreeSet<>(seriesByCategory.keySet())) {
            BigDecimal[] series = seriesByCategory.get(categoryId);
            int anchor = series.length - 1; // M

            int streakMonths = 1;   // 含锚点月 M（需求 5.2「含两端计数」）。
            int startIndex = anchor;
            Boolean decreasing = null;
            // 倒序逐一比较相邻两月：series[i]（较晚月）vs series[i-1]（较早月）。
            for (int i = anchor; i >= 1; i--) {
                int cmp = series[i].compareTo(series[i - 1]);
                if (cmp == 0) {
                    break; // 相等（含均为 0.00）即终止（需求 5.3）。
                }
                boolean stepDown = cmp < 0; // 较晚月严格小于较早月 → 一步递减。
                if (decreasing == null) {
                    decreasing = stepDown; // 首个非零方向确定整段方向。
                } else if (decreasing != stepDown) {
                    break; // 方向反转即终止（需求 5.3）。
                }
                streakMonths++;
                startIndex = i - 1;
            }

            // 门控：连续月数 ≥ 下限；decreasing==null 说明无方向（锚点即遇相等），必然不达标（需求 5.4、5.6）。
            if (decreasing == null || streakMonths < streakMin) {
                continue;
            }

            String direction = decreasing ? DIRECTION_DOWN : DIRECTION_UP;
            YearMonth startMonth = months.get(startIndex);

            out.add(new AiInsight(
                    TYPE_TREND_STREAK,
                    DIMENSION_CATEGORY,
                    categoryId,
                    names.get(categoryId),
                    series[anchor],      // currentValue = M 值（供任务 6 打分，见 Javadoc）。
                    series[startIndex],  // previousValue = 段起始月值（供任务 6 打分）。
                    null,
                    null,
                    null,
                    null,
                    null,
                    streakMonths,
                    startMonth.toString(),
                    month.toString(),
                    direction,
                    null,
                    null,
                    null));
        }
        return out;
    }

    /** 返回长度为 {@link #STREAK_WINDOW_MONTHS}、全部初始化为 {@code 0.00} 的按月支出序列（无数据月默认 0.00）。 */
    private static BigDecimal[] zeroSeries() {
        BigDecimal[] series = new BigDecimal[STREAK_WINDOW_MONTHS];
        for (int i = 0; i < series.length; i++) {
            series[i] = scale(BigDecimal.ZERO);
        }
        return series;
    }

    /**
     * {@code TOP_MOVER}（最大改善/最超支分类）候选构建（需求 6）。
     *
     * <p><b>候选集合</b>：复用 {@link ReportService#categoryReport(Long, java.time.LocalDate, java.time.LocalDate)}
     * （默认 EXPENSE 口径、{@code Asia/Shanghai} 半开区间、金额 2dp HALF_UP、排除 {@code transfer}）分别取目标月 M
     * 与上一自然月 prev 的每分类支出。候选集合 = 「<b>上月分类支出 &gt; 0</b>」的分类（需求 6.1）——因此遍历 prev
     * 报表中支出 &gt; 0 的分类（在 M 缺席即 {@code cur = 0.00}，属最大改善候选，必须纳入）。每个候选
     * {@code deltaAmount = cur − prev}（2dp，HALF_UP，可负）、{@code changeRate = deltaAmount ÷ prev × 100}
     * （2dp，HALF_UP；{@code prev > 0} 由候选前提保证，恒有定义，需求 6.1、6.3）。</p>
     *
     * <p><b>选取</b>（需求 6.2、6.4）：候选集合非空时，选 {@code deltaAmount} <b>最小</b>者（下降最多）为「改善」
     * （{@link #ROLE_IMPROVE}）、<b>最大</b>者（增加最多）为「超支」（{@link #ROLE_OVERSPEND}），各生成一条；并列最小/
     * 最大时分别以<b>分类 id 升序</b>决胜，各选唯一一个。改善与超支通常是<b>两个不同分类</b>，此时两条都产出。</p>
     *
     * <p><b>同分类去重</b>（需求 6.4、7.5）：仅当改善与超支落在<b>同一分类</b>（即 {@code min == max}——候选集合仅 1 个
     * 分类，或所有候选 {@code deltaAmount} 相等而并列决胜到同一 id）时，依「同维度同类型至多一条」只保留一条，
     * {@code role} 由该分类 {@code deltaAmount} 符号决定：{@code < 0 → IMPROVE}、{@code > 0 → OVERSPEND}、
     * {@code == 0 → 不生成}。符号规则<b>仅</b>作用于同分类塌缩场景；不同分类场景一律各产出一条（需求 6.2）。</p>
     *
     * <p>每条候选携带分类 id/名称、{@code currentValue = cur}、{@code previousValue = prev}、{@code deltaAmount}、
     * {@code changeRate} 与 {@code role}（需求 6.3）；{@code direction} 为 {@code null}（方向语义由 {@code role} 表达）。
     * 候选集合为空 → 不生成任何 {@code TOP_MOVER}（需求 6.5）。{@code score} 与 {@code narrativeText} 分别由任务 6、
     * 任务 8 填充，此处留空。</p>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月 M
     * @param prev     上一自然月 M−1
     * @return {@code TOP_MOVER} 候选（0、1 或 2 条），顺序由后续打分/排序统一决定
     */
    private List<AiInsight> buildTopMover(Long ledgerId, YearMonth month, YearMonth prev) {
        CategoryReportResponse curReport =
                reportService.categoryReport(ledgerId, month.atDay(1), month.atEndOfMonth());
        CategoryReportResponse prevReport =
                reportService.categoryReport(ledgerId, prev.atDay(1), prev.atEndOfMonth());

        Map<Long, BigDecimal> curAmountByCategory = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (CategoryShare s : curReport.categories()) {
            curAmountByCategory.put(s.categoryId(), s.amount());
            names.put(s.categoryId(), s.categoryName());
        }
        for (CategoryShare s : prevReport.categories()) {
            names.putIfAbsent(s.categoryId(), s.categoryName());
        }

        // 候选集合 = 上月分类支出 > 0 的分类（需求 6.1）：遍历 prev 报表，在 M 缺席则 cur = 0.00。
        List<TopMoverCandidate> candidates = new ArrayList<>();
        for (CategoryShare s : prevReport.categories()) {
            BigDecimal prevAmount = scale(s.amount());
            if (prevAmount.signum() <= 0) {
                continue;
            }
            BigDecimal cur = scale(curAmountByCategory.getOrDefault(s.categoryId(), BigDecimal.ZERO));
            BigDecimal deltaAmount = scale(cur.subtract(prevAmount));
            // changeRate 恒有定义（prev > 0 由候选前提保证，需求 6.3）。
            BigDecimal changeRate = deltaAmount
                    .multiply(HUNDRED)
                    .divide(prevAmount, SCALE, RoundingMode.HALF_UP);
            candidates.add(new TopMoverCandidate(
                    s.categoryId(), names.get(s.categoryId()), cur, prevAmount, deltaAmount, changeRate));
        }

        // 候选集合为空 → 不生成任何 TOP_MOVER（需求 6.5）。
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 选 deltaAmount 最小者为改善、最大者为超支；并列以分类 id 升序决胜（需求 6.2、6.4）。
        TopMoverCandidate improve = null;   // deltaAmount 最小（下降最多）。
        TopMoverCandidate overspend = null; // deltaAmount 最大（增加最多）。
        for (TopMoverCandidate c : candidates) {
            if (improve == null
                    || c.deltaAmount().compareTo(improve.deltaAmount()) < 0
                    || (c.deltaAmount().compareTo(improve.deltaAmount()) == 0
                            && c.categoryId() < improve.categoryId())) {
                improve = c;
            }
            if (overspend == null
                    || c.deltaAmount().compareTo(overspend.deltaAmount()) > 0
                    || (c.deltaAmount().compareTo(overspend.deltaAmount()) == 0
                            && c.categoryId() < overspend.categoryId())) {
                overspend = c;
            }
        }

        List<AiInsight> out = new ArrayList<>();
        if (improve.categoryId() != overspend.categoryId()) {
            // 改善与超支分属不同分类 → 各生成一条（需求 6.2）。
            out.add(topMoverInsight(improve, ROLE_IMPROVE));
            out.add(topMoverInsight(overspend, ROLE_OVERSPEND));
        } else {
            // 改善与超支落在同一分类（min == max）→ 去重只保留一条，role 由符号决定（需求 6.4、7.5）。
            int sign = improve.deltaAmount().signum();
            if (sign < 0) {
                out.add(topMoverInsight(improve, ROLE_IMPROVE));
            } else if (sign > 0) {
                out.add(topMoverInsight(improve, ROLE_OVERSPEND));
            }
            // deltaAmount == 0 → 不生成（需求 6.4）。
        }
        return out;
    }

    /** 由一个 {@link TopMoverCandidate} 与角色装配一条 {@code TOP_MOVER} 洞察（需求 6.3）。 */
    private static AiInsight topMoverInsight(TopMoverCandidate c, String role) {
        return new AiInsight(
                TYPE_TOP_MOVER,
                DIMENSION_CATEGORY,
                c.categoryId(),
                c.categoryName(),
                c.cur(),
                c.prev(),
                null,
                null,
                c.deltaAmount(),
                null,
                c.changeRate(),
                null,
                null,
                null,
                null,   // direction = null（方向语义由 role 表达）。
                role,
                null,   // score（任务 6 填充）。
                null);  // narrativeText（任务 8 填充）。
    }

    /**
     * {@code TOP_MOVER} 候选的内部载体：承载单个候选分类派生出的机器字段（需求 6.1、6.3），
     * 用于 min/max 选取与决胜后装配为 {@link AiInsight}。
     */
    private record TopMoverCandidate(
            long categoryId,
            String categoryName,
            BigDecimal cur,
            BigDecimal prev,
            BigDecimal deltaAmount,
            BigDecimal changeRate) {
    }

    /**
     * 洞察类型全序（优先级由高到低，固定且预定义，需求 7.3、design.md「洞察类型全序与打分模型」）：
     * {@code SAVINGS_TOTAL > TOP_MOVER > CATEGORY_DELTA > TREND_STREAK > FREQUENCY_DELTA}。
     * 数值越小优先级越高（升序即优先级由高到低），作为 {@code score} 相等时的第一决胜键。
     */
    private static final Map<String, Integer> TYPE_ORDER = Map.of(
            TYPE_SAVINGS_TOTAL, 0,
            TYPE_TOP_MOVER, 1,
            TYPE_CATEGORY_DELTA, 2,
            TYPE_TREND_STREAK, 3,
            TYPE_FREQUENCY_DELTA, 4);

    /** {@code SAVINGS_TOTAL}（账本总额、无维度 id）在维度 id 决胜时视为的 id，恒最前（需求 7.3）。 */
    private static final long SAVINGS_TIE_ID = -1L;

    /**
     * 显著度打分、去重、确定性排序与数量上限截断（任务 6，需求 7.1–7.7）。
     *
     * <p><b>纯函数式</b>：仅依据入参候选的机器字段推导，无 I/O、无随机、无隐藏状态；配合下述全序决胜键，
     * 同一候选集合 + 同一 N 的多次调用返回<b>完全一致</b>的洞察集合与顺序（幂等可复现，需求 7.4）。</p>
     *
     * <p><b>1. 打分（需求 7.1）</b>：为每条候选计算非负确定性 {@code score}（{@link BigDecimal}，2dp），
     * 并<b>重建</b>该 {@link AiInsight}（record 不可变）填入 {@code score}（{@code narrativeText} 仍为
     * {@code null}，由任务 8 接入 {@link InsightNarrator} 时填充）：</p>
     * <ul>
     *   <li>金额类（{@link #TYPE_CATEGORY_DELTA}/{@link #TYPE_SAVINGS_TOTAL}/{@link #TYPE_TOP_MOVER}）：
     *       {@code score = |deltaAmount|}。</li>
     *   <li>{@link #TYPE_FREQUENCY_DELTA}：{@code score = |deltaCount|}（提升为 {@link BigDecimal}）。</li>
     *   <li>{@link #TYPE_TREND_STREAK}：{@code score = |currentValue − previousValue|}
     *       （builder 已把 M 值存入 {@code currentValue}、段起始月值存入 {@code previousValue}）。</li>
     * </ul>
     *
     * <p><b>2. 去重（需求 7.5）</b>：同一 {@code (type, dimension, dimensionId)} 至多保留一条——保留同键中
     * {@code score} 更高者，{@code score} 相等时再按下述决胜键取唯一。</p>
     *
     * <p><b>3. 排序（需求 7.2、7.3）</b>：按 {@code score} 降序；{@code score} 相等时先按洞察类型全序
     * （{@link #TYPE_ORDER}）、再按维度 id 升序（{@code SAVINGS_TOTAL} 视 id 为 {@code -1} 恒最前）决胜。
     * 三段构成全序 → 结果唯一确定、与输入顺序无关。</p>
     *
     * <p><b>4. 截断（需求 7.2、7.6）</b>：取前 {@code N}（{@link AiInsightProperties#maxCountClamped()}，
     * 钳制 1–20）条；候选少于 N 时按同序返回全部、不补足。</p>
     *
     * @param candidates 五类 builder 产出的候选洞察（{@code score}/{@code narrativeText} 尚未填充）
     * @return 打分、去重后按全序决胜键排序并截断至 N 的洞察列表；每条 {@code score} 已填充、
     *         {@code narrativeText} 仍为 {@code null}（供任务 8 渲染）
     */
    private List<AiInsight> scoreDedupSortTruncate(List<AiInsight> candidates) {
        // 全序比较器：score 降序 → 类型全序（升序）→ 维度 id 升序。
        Comparator<AiInsight> byOrder = Comparator
                .comparing(AiInsight::score, Comparator.reverseOrder())
                .thenComparingInt(i -> TYPE_ORDER.getOrDefault(i.type(), Integer.MAX_VALUE))
                .thenComparingLong(AiInsightService::tieBreakDimensionId);

        // 1. 打分并重建（record 不可变）；2. 去重：同 (type, dimension, dimensionId) 仅保留决胜键最前者。
        Map<String, AiInsight> deduped = new LinkedHashMap<>();
        for (AiInsight candidate : candidates) {
            AiInsight scored = withScore(candidate, computeScore(candidate));
            String key = dedupKey(scored);
            AiInsight existing = deduped.get(key);
            // byOrder 升序 = 决胜键最前（score 更高，或同分时全序更靠前）者胜出，保留该条（需求 7.5）。
            if (existing == null || byOrder.compare(scored, existing) < 0) {
                deduped.put(key, scored);
            }
        }

        // 3. 排序 + 4. 截断至 N（钳制 1–20；不足 N 返回全部、不补足，需求 7.2、7.6）。
        int limit = props.maxCountClamped();
        return deduped.values().stream()
                .sorted(byOrder)
                .limit(limit)
                .toList();
    }

    /** 去重键：{@code (type, dimension, dimensionId)}；{@code dimension}/{@code dimensionId} 允许为 null（需求 7.5）。 */
    private static String dedupKey(AiInsight insight) {
        return insight.type() + "|" + insight.dimension() + "|" + insight.dimensionId();
    }

    /** 维度 id 决胜值：{@code SAVINGS_TOTAL}（无维度 id）视为 {@code -1} 恒最前，其余用 {@code dimensionId}（需求 7.3）。 */
    private static long tieBreakDimensionId(AiInsight insight) {
        return insight.dimensionId() == null ? SAVINGS_TIE_ID : insight.dimensionId();
    }

    /**
     * 计算候选的非负确定性显著度打分（{@link BigDecimal}，2dp，需求 7.1、design.md「打分模型」）。
     *
     * <p>金额类 = {@code |deltaAmount|}；{@code FREQUENCY_DELTA} = {@code |deltaCount|}；
     * {@code TREND_STREAK} = {@code |currentValue − previousValue|}（M 值与段起始月值之差的绝对值）。
     * 缺失字段按 0 处理，保证打分恒非负、恒有定义。</p>
     */
    private static BigDecimal computeScore(AiInsight c) {
        BigDecimal raw;
        if (TYPE_FREQUENCY_DELTA.equals(c.type())) {
            long delta = c.deltaCount() == null ? 0L : Math.abs((long) c.deltaCount());
            raw = BigDecimal.valueOf(delta);
        } else if (TYPE_TREND_STREAK.equals(c.type())) {
            BigDecimal end = c.currentValue() == null ? BigDecimal.ZERO : c.currentValue();
            BigDecimal start = c.previousValue() == null ? BigDecimal.ZERO : c.previousValue();
            raw = end.subtract(start).abs();
        } else {
            // CATEGORY_DELTA / SAVINGS_TOTAL / TOP_MOVER：|deltaAmount|。
            raw = c.deltaAmount() == null ? BigDecimal.ZERO : c.deltaAmount().abs();
        }
        return scale(raw);
    }

    /** 以给定 {@code score} 重建一条 {@link AiInsight}（record 不可变），其余机器字段与 {@code narrativeText} 原样保留。 */
    private static AiInsight withScore(AiInsight c, BigDecimal score) {
        return new AiInsight(
                c.type(),
                c.dimension(),
                c.dimensionId(),
                c.dimensionName(),
                c.currentValue(),
                c.previousValue(),
                c.currentCount(),
                c.previousCount(),
                c.deltaAmount(),
                c.deltaCount(),
                c.changeRate(),
                c.streakMonths(),
                c.streakStartMonth(),
                c.streakEndMonth(),
                c.direction(),
                c.role(),
                score,
                c.narrativeText());
    }

    /**
     * 隐私净化与维度名回退（需求 2.7、4.6、12.3、12.4、12.5）：对挑选后的每条洞察，将缺失/空白的
     * {@code dimensionName} 规整为固定回退名（分类 → {@link InsightNarrator#DELETED_CATEGORY_NAME}、
     * 商户 → {@link InsightNarrator#DELETED_MERCHANT_NAME}），使响应的 {@code dimensionName} 不为
     * {@code null}/空白且每次相同、并与叙事回退一致（需求 2.7、4.6，Property 10）。{@code SAVINGS_TOTAL}
     * 为账本总额、无维度（{@code dimension=null}）→ {@code dimensionName} 保持 {@code null}。
     *
     * <p>DTO 字段集本身即隐私白名单，天然不含 email/令牌/其它账本数据/{@code external_id}/原始备注/商户
     * 原始标识，故净化在结构上已成立，本方法只负责名称回退这一项确定性规整（需求 12.3、12.4、12.5）。</p>
     */
    private List<AiInsight> normalizeNames(List<AiInsight> insights) {
        List<AiInsight> out = new ArrayList<>(insights.size());
        for (AiInsight in : insights) {
            String name = fallbackName(in.dimension(), in.dimensionName());
            out.add(withDimensionName(in, name));
        }
        return out;
    }

    /**
     * 维度名回退（需求 2.7、4.6）：{@code dimension} 为 {@code null}（{@code SAVINGS_TOTAL} 账本总额）时保持
     * 原值（{@code null}）；名称非空白时原样返回；否则按维度取固定回退名（商户 → 已删除商户，其余 → 已删除分类）。
     */
    private static String fallbackName(String dimension, String rawName) {
        if (dimension == null) {
            return rawName;
        }
        if (rawName != null && !rawName.isBlank()) {
            return rawName;
        }
        return DIMENSION_MERCHANT.equals(dimension)
                ? InsightNarrator.DELETED_MERCHANT_NAME
                : InsightNarrator.DELETED_CATEGORY_NAME;
    }

    /**
     * 叙事渲染（任务 8，需求 8.1、8.8）：对挑选并规整名称后的每条洞察调用
     * {@link InsightNarrator#render(AiInsight)} 生成中文叙事文案，并<b>重建</b>该 {@link AiInsight}
     * （record 不可变）填入 {@code narrativeText}。
     *
     * <p>{@code render} 为纯函数，缺全部关键数值等无法生成时返回 {@code null}、从不抛错（需求 8.3、8.8）；
     * 因此此处直接以其返回值填充：渲染成功 → 一段中文文案；渲染失败 → {@code narrativeText=null}
     * 标记生成失败、保留全部机器字段、整体不报错（需求 8.8）。</p>
     *
     * @param insights 挑选并规整名称后的洞察（{@code narrativeText} 尚为 {@code null}）
     * @return 每条已填充 {@code narrativeText}（渲染失败为 {@code null}）的洞察列表，其余字段原样保留
     */
    private List<AiInsight> renderNarratives(List<AiInsight> insights) {
        List<AiInsight> out = new ArrayList<>(insights.size());
        for (AiInsight in : insights) {
            out.add(withNarrativeText(in, narrator.render(in)));
        }
        return out;
    }

    /** 以给定 {@code narrativeText} 重建一条 {@link AiInsight}（record 不可变），其余字段原样保留（任务 8）。 */
    private static AiInsight withNarrativeText(AiInsight c, String narrativeText) {
        return new AiInsight(
                c.type(),
                c.dimension(),
                c.dimensionId(),
                c.dimensionName(),
                c.currentValue(),
                c.previousValue(),
                c.currentCount(),
                c.previousCount(),
                c.deltaAmount(),
                c.deltaCount(),
                c.changeRate(),
                c.streakMonths(),
                c.streakStartMonth(),
                c.streakEndMonth(),
                c.direction(),
                c.role(),
                c.score(),
                narrativeText);
    }

    /** 以给定 {@code dimensionName} 重建一条 {@link AiInsight}（record 不可变），其余字段原样保留。 */
    private static AiInsight withDimensionName(AiInsight c, String dimensionName) {
        return new AiInsight(
                c.type(),
                c.dimension(),
                c.dimensionId(),
                dimensionName,
                c.currentValue(),
                c.previousValue(),
                c.currentCount(),
                c.previousCount(),
                c.deltaAmount(),
                c.deltaCount(),
                c.changeRate(),
                c.streakMonths(),
                c.streakStartMonth(),
                c.streakEndMonth(),
                c.direction(),
                c.role(),
                c.score(),
                c.narrativeText());
    }

    /**
     * 鼓励性兜底响应（需求 9.1、9.2、9.3、9.4、9.6）：{@code isFallback=true}、一条非空鼓励文案
     * （1..100 字符）、{@code insights} 为空列表，仍携带目标月标识（{@code YYYY-MM}）与月状态。
     */
    private AiInsightsResponse fallback(YearMonth month, String monthStatus) {
        return new AiInsightsResponse(month.toString(), monthStatus, true, FALLBACK_TEXT, List.of());
    }

    /** 金额是否为 0（按数值比较，忽略 scale 差异）。 */
    private static boolean isZero(BigDecimal v) {
        return v == null || v.signum() == 0;
    }

    /** 金额保留 2 位小数（HALF_UP），与既有报表口径一致（需求 1.7）；{@code null} 视为 0.00。 */
    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
