package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.BudgetOverviewResponse;
import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.DimensionReportResponse;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.api.dto.PersonalityTagsResponse;
import com.damien.youyu.api.dto.PersonalityTagsResponse.PersonalityTag;
import com.damien.youyu.config.PersonalityTagProperties;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.Merchant;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 趣味人格标签只读组合器（read-only composer，design.md「Architecture」「Components and Interfaces」）。
 *
 * <p>针对某一自然月，编排既有 {@link ReportService}（{@code monthlyReport / categoryReport /
 * dimensionReport}）、{@link BudgetService}（{@code overview}）与既有「按账本 + {@code occurredAt} 半开
 * 区间」交易查询，把目标月 M（及省钱达人所需的上一自然月 M−1）的派生指标算成一组<b>标签达标候选</b>，
 * 确定性打分挑选 → 交给 {@link TagNarrator} 用中文模板渲染 → 打包成 {@link PersonalityTagsResponse}。
 * 全过程<b>只读、无任何写语句</b>（需求 14.1、14.6）：仅编排既有服务与仓库查询，<b>不新增任何 repository
 * 查询、不新增任何 SQL</b>（需求 14.1、14.2）。</p>
 *
 * <p><b>口径</b>：金额一律 {@link java.math.BigDecimal} 保留 2 位小数（HALF_UP），占比/变化率（百分比）保留
 * 2 位小数（HALF_UP）；自然月边界按 {@code Asia/Shanghai}（由注入的 {@link Clock} 决定）；所有金额/笔数统计
 * 排除 {@code type=transfer}，与既有 {@code /api/reports/*}、{@code /api/budgets} 逐值同口径（需求 1.6、1.7、
 * 14.5）。</p>
 *
 * <p><b>月状态与短路</b>（需求 1.3、1.4、1.10、1.11、10.2）：</p>
 * <ul>
 *   <li>{@code status = month.isBefore(YearMonth.now(clock)) ? "final" : "partial"}。</li>
 *   <li>{@code partial}（含缺省的当前自然月<b>与目标月晚于当前月的未来月</b>）：v1 全部标签均依赖完整自然月
 *       数据判定，故全部跳过 → 候选为空 → 返回一条鼓励性兜底文案（{@code isFallback=true}），仍携带
 *       {@code month} 与 {@code monthStatus}（需求 1.10、1.11、10.2、10.5）。</li>
 *   <li>{@code final}（目标月早于当前自然月）：正常评估全部标签。</li>
 * </ul>
 *
 * <p><b>本类当前进度（骨架，任务 4）</b>：已实现月状态计算、partial/未来月短路兜底与 {@code final} 月的取数
 * 编排（把全部源数据取入 {@link SourceData} 持有者）。逐枚标签评估器（任务 5.1–5.5）、强度打分/挑选/去重/截断
 * （任务 6）、响应组装与隐私净化（任务 7）、{@link TagNarrator} 接线（任务 8）为后续任务，已在下方以清晰的
 * 扩展点（{@link #evaluateCandidates}、{@link #scoreDedupSortTruncate}、{@link #renderNarratives}）预留；
 * {@code final} 月在评估器落地前返回空候选兜底。</p>
 */
@Service
public class PersonalityTagService {

    /** 月状态：已完结（目标月早于当前自然月，需求 1.4）。 */
    static final String STATUS_FINAL = "final";

    /** 月状态：进行中（目标月为当前自然月且当月未结束，或目标月晚于当前月，需求 1.3、1.11）。 */
    static final String STATUS_PARTIAL = "partial";

    /** {@code Asia/Shanghai} 时区（自然月边界与夜宵本地小时派生，需求 1.2、7.1）。 */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 系统内置默认鼓励性兜底文案（1..60 字符，非空，需求 10.1、10.2、10.6）。
     * 任务 7 完善兜底语义时可从此内置默认扩展为可配置来源，来源为空/不可用时回退本文案。
     */
    static final String FALLBACK_TEXT = "才刚开始记账，专属标签正在路上啦～";

    /** 鼓励性兜底文案长度下限（含，需求 10.1、10.2、10.6）。 */
    static final int FALLBACK_TEXT_MIN_LEN = 1;

    /** 鼓励性兜底文案长度上限（含，需求 10.1、10.2、10.6）。 */
    static final int FALLBACK_TEXT_MAX_LEN = 60;

    /**
     * 隐私白名单（需求 13.3、13.4、13.5）：{@link PersonalityTag} 允许出现的<b>全部</b>字段名集合，
     * 仅含聚合派生统计 + 标题/表情/维度名 + 标签文案与用于排序/展示的辅助字段。任何不在此集合中的字段
     * 均视为被禁字段（如 {@code email}、访问/刷新令牌、{@code externalId}、原始备注全文、商户原始标识、
     * 附件内容/链接、以及任何不属于当前请求账本的数据），组装响应前的净化过程（{@link #assembleResponse}
     * → {@link #assertWhitelist(PersonalityTag)}）据此逐字段核验并剔除，保证响应从结构与运行时双重杜绝
     * 隐私外泄。
     */
    static final Set<String> WHITELIST_FIELDS = Set.of(
            "tagKey", "title", "emoji",
            "dimension", "dimensionId", "dimensionName",
            "currentValue", "previousValue", "income", "savings", "saveRate",
            "budget", "used", "usedRate",
            "matchCount", "matchAmount", "matchPercent",
            "lateNightCount", "lateNightWindow",
            "threshold", "strengthScore", "narrativeText");

    /** 省钱达人标签键（需求 3、2.2）。 */
    static final String TAG_SAVINGS_MASTER = "SAVINGS_MASTER";

    /** 省钱达人标签标题（正向/中性，禁用词零命中，需求 8.3、8.4）。 */
    static final String TITLE_SAVINGS_MASTER = "省钱达人";

    /** 省钱达人标签表情符号（需求 2.1）。 */
    static final String EMOJI_SAVINGS_MASTER = "🏆";

    /** 理财新星标签键（需求 4、2.2）。 */
    static final String TAG_FINANCE_STAR = "FINANCE_STAR";

    /** 理财新星标签标题（正向/中性，禁用词零命中，需求 8.3、8.4）。 */
    static final String TITLE_FINANCE_STAR = "理财新星";

    /** 理财新星标签表情符号（需求 2.1）。 */
    static final String EMOJI_FINANCE_STAR = "🌟";

    /** 预算大师标签键（需求 5、2.2）。 */
    static final String TAG_BUDGET_MASTER = "BUDGET_MASTER";

    /** 预算大师标签标题（正向/中性，禁用词零命中，需求 8.3、8.4）。 */
    static final String TITLE_BUDGET_MASTER = "预算大师";

    /** 预算大师标签表情符号（需求 2.1）。 */
    static final String EMOJI_BUDGET_MASTER = "🎯";

    /** 外卖探索家标签键（需求 6、2.2）。 */
    static final String TAG_TAKEOUT_EXPLORER = "TAKEOUT_EXPLORER";

    /** 外卖探索家标签标题（正向/中性，禁用词零命中，需求 8.3、8.4）。 */
    static final String TITLE_TAKEOUT_EXPLORER = "外卖探索家";

    /** 外卖探索家标签表情符号（需求 2.1）。 */
    static final String EMOJI_TAKEOUT_EXPLORER = "🍱";

    /** 咖啡收藏家标签键（需求 6、2.2）。 */
    static final String TAG_COFFEE_COLLECTOR = "COFFEE_COLLECTOR";

    /** 咖啡收藏家标签标题（正向/中性，禁用词零命中，需求 8.3、8.4）。 */
    static final String TITLE_COFFEE_COLLECTOR = "咖啡收藏家";

    /** 咖啡收藏家标签表情符号（需求 2.1）。 */
    static final String EMOJI_COFFEE_COLLECTOR = "☕";

    /** 旅行狂人标签键（需求 6、2.2）。 */
    static final String TAG_TRAVEL_ENTHUSIAST = "TRAVEL_ENTHUSIAST";

    /** 旅行狂人标签标题（正向/中性，禁用词零命中，需求 8.3、8.4）。 */
    static final String TITLE_TRAVEL_ENTHUSIAST = "旅行狂人";

    /** 旅行狂人标签表情符号（需求 2.1）。 */
    static final String EMOJI_TRAVEL_ENTHUSIAST = "✈️";

    /** 购物生活家标签键（需求 6、2.2）。 */
    static final String TAG_SHOPPING_LIFER = "SHOPPING_LIFER";

    /** 购物生活家标签标题（正向/中性，「冲动购物」的正向包装，禁用词零命中，需求 8.3、8.4、8.5）。 */
    static final String TITLE_SHOPPING_LIFER = "购物生活家";

    /** 购物生活家标签表情符号（需求 2.1）。 */
    static final String EMOJI_SHOPPING_LIFER = "🛍️";

    /** 夜宵王标签键（需求 7、2.2）。 */
    static final String TAG_LATE_NIGHT_KING = "LATE_NIGHT_KING";

    /** 夜宵王标签标题（正向/中性，禁用词零命中，需求 8.3、8.4）。 */
    static final String TITLE_LATE_NIGHT_KING = "夜宵王";

    /** 夜宵王标签表情符号（需求 2.1）。 */
    static final String EMOJI_LATE_NIGHT_KING = "🌙";

    /** 行为类标签判定维度：按分类匹配为主（需求 6.6、2.7）。 */
    static final String DIMENSION_CATEGORY = "CATEGORY";

    /** 行为类标签判定维度：按商户匹配为主（需求 6.6、2.7）。 */
    static final String DIMENSION_MERCHANT = "MERCHANT";

    /** 金额与占比/比率一律保留 2 位小数（HALF_UP），与既有报表口径一致（需求 1.7、3.1、3.2）。 */
    static final int MONEY_SCALE = 2;

    /** 强度分保留 6 位小数（HALF_UP），保证「有限、非负、6 位小数」的确定性分值（需求 9.1）。 */
    static final int STRENGTH_SCALE = 6;

    /**
     * 预算大师强度分下限 ε（需求 9.1、9.2）：使用率越低分越高，取 {@code budgetUsedPctMax ÷ max(usedRate, ε)}；
     * 当 {@code usedRate=0} 时以 ε 兜底，保证分值<b>有限有界</b>而非除零无穷。
     */
    static final BigDecimal STRENGTH_EPSILON = new BigDecimal("0.000001");

    /**
     * 固定标签优先级全序决胜键（需求 9.5、9.6）：强度分相等时按此 {@code tagKey → rank}（rank 越小优先级越高）
     * 排序，使挑选与排序结果唯一确定、与判定顺序无关。全序（由高到低）：
     * {@code SAVINGS_MASTER > FINANCE_STAR > BUDGET_MASTER > TAKEOUT_EXPLORER > COFFEE_COLLECTOR >
     * LATE_NIGHT_KING > TRAVEL_ENTHUSIAST > SHOPPING_LIFER}。
     */
    static final Map<String, Integer> TAG_PRIORITY = Map.of(
            TAG_SAVINGS_MASTER, 0,
            TAG_FINANCE_STAR, 1,
            TAG_BUDGET_MASTER, 2,
            TAG_TAKEOUT_EXPLORER, 3,
            TAG_COFFEE_COLLECTOR, 4,
            TAG_LATE_NIGHT_KING, 5,
            TAG_TRAVEL_ENTHUSIAST, 6,
            TAG_SHOPPING_LIFER, 7);

    private final ReportService reportService;
    private final BudgetService budgetService;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;
    private final Clock clock;
    private final PersonalityTagProperties props;
    private final TagNarrator narrator;

    public PersonalityTagService(
            ReportService reportService,
            BudgetService budgetService,
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            MerchantRepository merchantRepository,
            Clock clock,
            PersonalityTagProperties props,
            TagNarrator narrator) {
        this.reportService = reportService;
        this.budgetService = budgetService;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.clock = clock;
        this.props = props;
        this.narrator = narrator;
    }

    /**
     * 生成目标月的趣味人格标签数据包（需求 1、8、9、10）。纯只读派生，不落库。
     *
     * <p><b>编排步骤</b>：月状态计算（需求 1.3、1.4）→ partial/未来月短路兜底（需求 1.10、1.11、10.2）→
     * {@code final} 月取数编排（复用既有服务、同口径、{@code Asia/Shanghai} 半开区间、排除 transfer，需求
     * 1.5、1.6、14.5）→ 逐枚标签达标判定（任务 5，扩展点 {@link #evaluateCandidates}）→ 强度打分/去重/确定性
     * 排序/截断至 N（任务 6，扩展点 {@link #scoreDedupSortTruncate}）→ 文案渲染（任务 8，扩展点
     * {@link #renderNarratives}）→ 兜底语义与响应组装（任务 7）。</p>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月（按 {@code Asia/Shanghai} 边界）
     * @return 目标月挑选后不超过 N 枚人格标签，或一条鼓励性兜底文案
     */
    @Transactional(readOnly = true)
    public PersonalityTagsResponse tags(Long ledgerId, YearMonth month) {
        // 月状态：目标月早于当前自然月为已完结，否则（当前月或未来月）进行中（需求 1.3、1.4、1.11）。
        String monthStatus = month.isBefore(YearMonth.now(clock)) ? STATUS_FINAL : STATUS_PARTIAL;

        // partial/未来月短路（需求 1.10、1.11、10.2）：v1 全部标签均依赖完整月 → 候选为空 → 鼓励兜底，
        // 仍携带 month、monthStatus（需求 10.5）。
        if (STATUS_PARTIAL.equals(monthStatus)) {
            return fallback(month, monthStatus);
        }

        // 阈值净化：非法/未配置项逐项回退默认值继续评估（需求 2.4、2.5），供后续评估器（任务 5）与打分（任务 6）消费。
        PersonalityTagProperties config = props.sanitize();

        // final 月取数编排（复用既有服务，同口径、Asia/Shanghai 半开区间、排除 transfer，需求 1.5、1.6、14.5）。
        SourceData data = fetchSourceData(ledgerId, month);

        // 扩展点（任务 5.1–5.5）：逐枚标签达标判定，从 data 派生候选标签；当前返回空候选（骨架）。
        List<PersonalityTag> candidates = evaluateCandidates(ledgerId, month, config, data);

        // 扩展点（任务 6）：强度打分 → 去重 → 确定性排序 → 截断至 N；当前透传（骨架）。
        List<PersonalityTag> selected = scoreDedupSortTruncate(candidates, config);

        // 扩展点（任务 8）：对每枚挑选后的标签调用 narrator.render 生成 narrativeText；当前透传（骨架）。
        List<PersonalityTag> rendered = renderNarratives(selected);

        // 兜底语义（需求 10.1、10.3、10.4）：挑选后为空 → 鼓励兜底；否则非兜底态返回 1..N 枚。
        if (rendered.isEmpty()) {
            return fallback(month, monthStatus);
        }
        // 非兜底态组装 + 隐私白名单净化（需求 10.3、10.4、10.5、13.3、13.4、13.5）。
        return assembleResponse(month, monthStatus, rendered);
    }

    /**
     * 组装非兜底态响应并执行隐私白名单净化（需求 10.3、10.4、10.5、13.3、13.4、13.5）。
     *
     * <p>非兜底态语义：{@code isFallback=false}、{@code fallbackText=null}、{@code tags} 为 1..N 枚挑选后
     * 标签（本方法由 {@code tags()} 在 {@code rendered} 非空时调用，故枚数 ≥1，需求 10.4）；无论兜底态或
     * 非兜底态均携带 {@code month}（{@code YYYY-MM}）与 {@code monthStatus}（{@code partial}/{@code final}，
     * 需求 10.5）。</p>
     *
     * <p><b>隐私净化（需求 13.5）</b>：{@link PersonalityTag} 的 record 字段集合本身即隐私白名单
     * （{@link #WHITELIST_FIELDS}），从结构上就不含 email/令牌/其它账本数据/{@code external_id}/原始备注
     * 全文/商户原始标识/附件内容或链接（需求 13.3、13.4）。本方法在返回前对每枚标签再做一次<b>运行时
     * 防御性核验</b>（{@link #assertWhitelist(PersonalityTag)}）：逐 record 组件核对字段名均落在白名单内，
     * 若检测到任一被禁字段则视为契约被破坏并快速失败（fail-fast），从而在返回给调用方前把该字段挡在响应
     * 之外、其余合法字段照常返回，不改变其余字段取值。因 DTO 为不可变 record、字段集固定，正常路径下该核验
     * 恒真、零开销地兜住「日后误加被禁字段」的回归。</p>
     *
     * @param month       目标自然月
     * @param monthStatus 月状态（{@code partial}/{@code final}）
     * @param tags        挑选并渲染后的标签（非空、1..N 枚）
     * @return 非兜底态响应（已通过白名单净化）
     */
    private PersonalityTagsResponse assembleResponse(
            YearMonth month, String monthStatus, List<PersonalityTag> tags) {
        for (PersonalityTag tag : tags) {
            assertWhitelist(tag);
        }
        return new PersonalityTagsResponse(month.toString(), monthStatus, false, null, tags);
    }

    /**
     * 隐私白名单运行时核验（需求 13.3、13.4、13.5）：反射逐一核对 {@link PersonalityTag} 的全部 record
     * 组件名均落在 {@link #WHITELIST_FIELDS} 内。检测到任一被禁字段（不在白名单）→ 抛
     * {@link IllegalStateException} 快速失败，避免把 email/令牌/{@code external_id}/原始备注/商户原始标识
     * 等隐私字段随响应外泄；正常路径（DTO 字段集固定）下恒真。
     *
     * @param tag 待核验标签
     */
    private static void assertWhitelist(PersonalityTag tag) {
        for (var component : PersonalityTag.class.getRecordComponents()) {
            if (!WHITELIST_FIELDS.contains(component.getName())) {
                throw new IllegalStateException(
                        "PersonalityTag 含非白名单字段（隐私边界被破坏，需求 13.5）：" + component.getName());
            }
        }
    }

    /**
     * {@code final} 月取数编排（需求 1.5、1.6、3.1、4.1、5.1、6.3、7.1、14.5）：复用既有服务，把 8 枚标签所需的
     * 全部源数据一次性取入 {@link SourceData} 持有者，供后续评估器（任务 5）消费。全部为只读查询，不新增任何
     * repository 方法（需求 14.1、14.2）。
     *
     * <ul>
     *   <li>{@code monthlyReport(M)}、{@code monthlyReport(M−1)}：月度总收入/总支出（省钱达人、理财新星）。</li>
     *   <li>{@code budgetService.overview(M)}：本月预算 / 已用支出（预算大师）。</li>
     *   <li>{@code categoryReport(M 全月, EXPENSE)}：每分类支出金额、笔数、占比 + 当月总支出（行为类分类维度）。</li>
     *   <li>{@code dimensionReport(M 全月, EXPENSE, "merchant")}：每商户支出金额、笔数（行为类商户维度）。</li>
     *   <li>既有半开区间交易查询（M 全月）→ 内存过滤 {@code type=EXPENSE}：夜宵王在内存派生本地小时（任务 5.5）
     *       与行为类标签的交易级去重（任务 5.4）所需的逐笔数据。</li>
     * </ul>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月 M
     * @return 目标月的全部源数据持有者
     */
    private SourceData fetchSourceData(Long ledgerId, YearMonth month) {
        YearMonth prev = month.minusMonths(1);

        // 月度总收入/总支出（省钱达人、理财新星，需求 3.1、4.1）。
        MonthlyReportResponse currentMonthly = reportService.monthlyReport(ledgerId, month);
        MonthlyReportResponse previousMonthly = reportService.monthlyReport(ledgerId, prev);

        // 本月预算 / 已用支出（预算大师，需求 5.1）。
        BudgetOverviewResponse budget = budgetService.overview(ledgerId, month);

        // 行为类标签分类维度：每分类支出金额/笔数/占比 + 当月总支出（需求 6.3），全月范围（含起止边界）。
        CategoryReportResponse categoryExpense = reportService.categoryReport(
                ledgerId, month.atDay(1), month.atEndOfMonth(), TransactionType.EXPENSE);

        // 行为类标签商户维度：每商户支出金额/笔数（需求 6.3），全月范围（含起止边界）。
        DimensionReportResponse merchantExpense = reportService.dimensionReport(
                ledgerId, month.atDay(1), month.atEndOfMonth(), TransactionType.EXPENSE, "merchant");

        // 夜宵王 + 行为类交易级去重所需逐笔数据（需求 7.1、6.2）：复用既有半开区间查询后内存过滤 EXPENSE。
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        List<Transaction> monthExpenseTransactions = transactionRepository
                .findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(ledgerId, from, to)
                .stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .toList();

        return new SourceData(
                currentMonthly, previousMonthly, budget, categoryExpense, merchantExpense,
                monthExpenseTransactions);
    }

    /**
     * 扩展点（任务 5.1–5.5）：对内置标签目录逐枚做确定性达标判定，从 {@link SourceData} 派生候选标签。
     * 每枚标签独立判定、互不影响（需求 2.3）；达标者携带完整机器字段。
     *
     * <p>骨架实现返回空候选列表；后续任务将在此填充 8 枚评估器：{@code SAVINGS_MASTER}（5.1）、
     * {@code FINANCE_STAR}（5.2）、{@code BUDGET_MASTER}（5.3）、行为类
     * {@code TAKEOUT_EXPLORER/COFFEE_COLLECTOR/TRAVEL_ENTHUSIAST/SHOPPING_LIFER}（5.4）、
     * {@code LATE_NIGHT_KING}（5.5）。行为类标签的分类/商户名称解析与回退名可用注入的
     * {@link #categoryRepository}、{@link #merchantRepository}，夜宵本地小时派生可用 {@link #localHour}。</p>
     *
     * @param ledgerId 当前账本
     * @param month    目标自然月 M
     * @param config   已净化的可配置阈值/匹配集合（需求 2.5）
     * @param data     目标月源数据持有者
     * @return 达标候选标签列表（骨架：空列表）
     */
    private List<PersonalityTag> evaluateCandidates(
            Long ledgerId, YearMonth month, PersonalityTagProperties config, SourceData data) {
        List<PersonalityTag> candidates = new ArrayList<>();
        // 任务 5.1：省钱达人（SAVINGS_MASTER，需求 3）。每枚标签独立判定、互不影响（需求 2.3）。
        PersonalityTag savingsMaster = evaluateSavingsMaster(config, data);
        if (savingsMaster != null) {
            candidates.add(savingsMaster);
        }
        // 任务 5.2：理财新星（FINANCE_STAR，需求 4）。每枚标签独立判定、互不影响（需求 2.3）。
        PersonalityTag financeStar = evaluateFinanceStar(config, data);
        if (financeStar != null) {
            candidates.add(financeStar);
        }
        // 任务 5.3：预算大师（BUDGET_MASTER，需求 5）。每枚标签独立判定、互不影响（需求 2.3）。
        PersonalityTag budgetMaster = evaluateBudgetMaster(config, data);
        if (budgetMaster != null) {
            candidates.add(budgetMaster);
        }
        // 任务 5.4：行为类标签（TAKEOUT_EXPLORER / COFFEE_COLLECTOR / TRAVEL_ENTHUSIAST / SHOPPING_LIFER，
        // 需求 6）。逐笔按分类名称/商户名称匹配、交易 id 去重、占比按当月总支出计（需求 6.1–6.8）。
        // 先构建 categoryId->name、merchantId->name 映射（复用既有只读查询，需求 6.8、14.2），供逐笔判定复用。
        Map<Long, String> categoryNames = new HashMap<>();
        for (Category c : categoryRepository.findByLedgerId(ledgerId)) {
            categoryNames.put(c.getId(), c.getName());
        }
        Map<Long, String> merchantNames = new HashMap<>();
        for (Merchant m : merchantRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId)) {
            merchantNames.put(m.getId(), m.getName());
        }

        // 外卖探索家：已配置下限 = 笔数下限 + 占比下限（无金额下限）（需求 6.6）。
        addIfPresent(candidates, evaluateBehaviorTag(
                TAG_TAKEOUT_EXPLORER, TITLE_TAKEOUT_EXPLORER, EMOJI_TAKEOUT_EXPLORER,
                config.getTakeoutCategories(), config.getTakeoutMerchants(),
                config.getTakeoutCountMin(), config.getTakeoutPctMin(), null,
                data, categoryNames, merchantNames));
        // 咖啡收藏家：已配置下限 = 仅笔数下限（无占比、无金额下限）（需求 6.6）。
        addIfPresent(candidates, evaluateBehaviorTag(
                TAG_COFFEE_COLLECTOR, TITLE_COFFEE_COLLECTOR, EMOJI_COFFEE_COLLECTOR,
                config.getCoffeeCategories(), config.getCoffeeMerchants(),
                config.getCoffeeCountMin(), null, null,
                data, categoryNames, merchantNames));
        // 旅行狂人：已配置下限 = 金额下限 + 笔数下限（无占比下限）（需求 6.6）。
        addIfPresent(candidates, evaluateBehaviorTag(
                TAG_TRAVEL_ENTHUSIAST, TITLE_TRAVEL_ENTHUSIAST, EMOJI_TRAVEL_ENTHUSIAST,
                config.getTravelCategories(), config.getTravelMerchants(),
                config.getTravelCountMin(), null, config.getTravelAmountMin(),
                data, categoryNames, merchantNames));
        // 购物生活家：已配置下限 = 笔数下限 + 金额下限（无占比下限）（需求 6.6）。
        addIfPresent(candidates, evaluateBehaviorTag(
                TAG_SHOPPING_LIFER, TITLE_SHOPPING_LIFER, EMOJI_SHOPPING_LIFER,
                config.getShoppingCategories(), config.getShoppingMerchants(),
                config.getShoppingCountMin(), null, config.getShoppingAmountMin(),
                data, categoryNames, merchantNames));

        // 任务 5.5：夜宵王（LATE_NIGHT_KING，需求 7）。每枚标签独立判定、互不影响（需求 2.3）。
        addIfPresent(candidates, evaluateLateNightKing(config, data));
        return candidates;
    }

    /** 达标（非 {@code null}）时加入候选列表，否则忽略（每枚标签独立判定，需求 2.3）。 */
    private static void addIfPresent(List<PersonalityTag> candidates, PersonalityTag tag) {
        if (tag != null) {
            candidates.add(tag);
        }
    }

    /**
     * 行为类标签共享评估器（{@code TAKEOUT_EXPLORER}/{@code COFFEE_COLLECTOR}/{@code TRAVEL_ENTHUSIAST}/
     * {@code SHOPPING_LIFER}，需求 6.1–6.8、2.3）。以标签键/标题/表情、分类名称集合与商户名称集合、以及三类
     * 下限（笔数下限、占比下限、金额下限，均可为 {@code null} 表示该项<b>未配置、不参与判定</b>）参数化，
     * 供 {@link #evaluateCandidates} 用不同配置调用 4 次，避免重复代码。
     *
     * <p><b>匹配（需求 6.1、6.2、6.5）</b>：逐笔遍历目标月当前账本、未删除、{@code type=expense} 的交易
     * （{@link SourceData#monthExpenseTransactions()}，已在取数阶段过滤）；某笔交易的<b>分类名称</b>落在
     * {@code categorySet} 内 <b>或</b> 其<b>商户名称</b>落在 {@code merchantSet} 内即命中该标签。名称按精确
     * 字符串匹配（分类/商户名称集合已由 {@link PersonalityTagProperties#sanitize()} 处理），<b>不基于
     * {@code note} 备注</b>。同一笔交易即便同时命中分类集合与商户集合，也以交易 id 集合合并<b>只计一次</b>
     * （去重，需求 6.2）。</p>
     *
     * <p><b>统计（需求 6.3、6.4）</b>：{@code matchCount}=命中交易数（去重后，整数 ≥0）；{@code matchAmount}
     * =命中交易金额合计（2dp HALF_UP，≥0.00）；{@code matchPercent}={@code matchAmount ÷ 当月总支出 × 100}
     * （2dp HALF_UP）。当月总支出取 {@code currentMonthly.totalExpense()}（与 {@code categoryReport} 一致）；
     * <b>当月总支出为 0 → 占比记 0.00 且不授予任何行为类标签</b>（直接返回 {@code null}）。</p>
     *
     * <p><b>达标（需求 6.6、6.7）</b>：达标当且仅当「{@code matchCount ≥ 笔数下限} 或 {@code matchPercent ≥
     * 占比下限} 或 {@code matchAmount ≥ 金额下限}」，其中<b>仅已配置（非 {@code null}）的下限参与判定</b>；
     * 对每个已配置下限均严格不达标则不授予（返回 {@code null}）。</p>
     *
     * <p><b>维度与回退名（需求 6.8、2.7）</b>：{@code dimension} 取判定所依据的<b>主维度</b>——分类维度与
     * 商户维度各自命中金额合计，取合计更大者（并列取分类），并携带该主维度中命中金额最高的对象
     * （{@code dimensionId}/{@code dimensionName}，金额并列取 id 较小者以保证确定性）。维度对象已删除或名称
     * 为空 → 固定回退名（分类 {@code 已删除分类}、商户 {@code 已删除商户}，复用
     * {@link TagNarrator#categoryDisplayName}/{@link TagNarrator#merchantDisplayName}）；<b>不因缺名丢弃标签
     * 或漏计交易</b>。{@code threshold} 取主判定阈值（笔数下限对应的 {@link BigDecimal}）。其余不适用字段为
     * {@code null}，{@code strengthScore}/{@code narrativeText} 留待任务 6/8（此处置 {@code null}）。</p>
     *
     * @param tagKey        标签键
     * @param title         标签标题
     * @param emoji         标签表情符号
     * @param categorySet   分类名称匹配集合
     * @param merchantSet   商户名称匹配集合
     * @param countMin      笔数下限（{@code null} 表示未配置、不参与判定）
     * @param pctMin        占比下限（{@code null} 表示未配置、不参与判定）
     * @param amountMin     金额下限（{@code null} 表示未配置、不参与判定）
     * @param data          目标月源数据持有者
     * @param categoryNames 当前账本 categoryId→name 映射（供逐笔匹配与回退名）
     * @param merchantNames 当前账本 merchantId→name 映射（供逐笔匹配与回退名）
     * @return 达标时返回该行为类标签，否则 {@code null}
     */
    private PersonalityTag evaluateBehaviorTag(
            String tagKey, String title, String emoji,
            Set<String> categorySet, Set<String> merchantSet,
            Integer countMin, BigDecimal pctMin, BigDecimal amountMin,
            SourceData data, Map<Long, String> categoryNames, Map<Long, String> merchantNames) {

        // 当月总支出（与 categoryReport 同源，需求 6.3、6.4）。为 0 → 不授予任何行为类标签（需求 6.4）。
        BigDecimal currentExpense = data.currentMonthly().totalExpense().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (currentExpense.signum() <= 0) {
            return null;
        }

        // 逐笔匹配 + 交易 id 去重（需求 6.1、6.2、6.5）：分类名称或商户名称落在集合内即命中，一笔至多计一次。
        Set<Long> matchedTxIds = new HashSet<>();
        BigDecimal matchAmount = BigDecimal.ZERO;
        Map<Long, BigDecimal> categoryAmounts = new HashMap<>();
        Map<Long, BigDecimal> merchantAmounts = new HashMap<>();

        for (Transaction tx : data.monthExpenseTransactions()) {
            Long catId = tx.getCategoryId();
            Long merId = tx.getMerchantId();
            String catName = catId == null ? null : categoryNames.get(catId);
            String merName = merId == null ? null : merchantNames.get(merId);
            boolean catHit = catName != null && categorySet != null && categorySet.contains(catName);
            boolean merHit = merName != null && merchantSet != null && merchantSet.contains(merName);
            if (!catHit && !merHit) {
                continue;
            }
            // 命中：交易 id 去重（同时命中分类与商户也只计一次，需求 6.2）。
            if (!matchedTxIds.add(tx.getId())) {
                continue;
            }
            BigDecimal amt = tx.getAmount();
            matchAmount = matchAmount.add(amt);
            if (catHit) {
                categoryAmounts.merge(catId, amt, BigDecimal::add);
            }
            if (merHit) {
                merchantAmounts.merge(merId, amt, BigDecimal::add);
            }
        }

        int matchCount = matchedTxIds.size();
        BigDecimal matchAmt = matchAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        // 占比 = 匹配金额 ÷ 当月总支出 × 100（2dp HALF_UP，此处当月总支出必 > 0，需求 6.3）。
        BigDecimal matchPercent = matchAmt
                .multiply(BigDecimal.valueOf(100))
                .divide(currentExpense, MONEY_SCALE, RoundingMode.HALF_UP);

        // 达标判定（需求 6.6、6.7）：仅已配置下限参与，任一已配置下限达标即授予；均严格不达标则不授予。
        boolean countReached = countMin != null && matchCount >= countMin;
        boolean pctReached = pctMin != null && matchPercent.compareTo(pctMin) >= 0;
        boolean amountReached = amountMin != null && matchAmt.compareTo(amountMin) >= 0;
        if (!countReached && !pctReached && !amountReached) {
            return null;
        }

        // 主维度与维度对象（需求 6.8、2.7）：分类/商户命中金额合计取更大者（并列取分类），携带其中金额最高的对象。
        BigDecimal categoryTotal = sumValues(categoryAmounts);
        BigDecimal merchantTotal = sumValues(merchantAmounts);
        String dimension = null;
        Long dimensionId = null;
        String dimensionName = null;
        if (!categoryAmounts.isEmpty() && categoryTotal.compareTo(merchantTotal) >= 0) {
            dimension = DIMENSION_CATEGORY;
            dimensionId = topId(categoryAmounts);
            dimensionName = TagNarrator.categoryDisplayName(categoryNames.get(dimensionId));
        } else if (!merchantAmounts.isEmpty()) {
            dimension = DIMENSION_MERCHANT;
            dimensionId = topId(merchantAmounts);
            dimensionName = TagNarrator.merchantDisplayName(merchantNames.get(dimensionId));
        }

        // 主判定阈值（需求 2.7）：笔数下限对应的 BigDecimal（4 枚行为类标签均配置笔数下限）。
        BigDecimal threshold = countMin != null
                ? BigDecimal.valueOf(countMin)
                : (pctMin != null ? pctMin : amountMin);

        return new PersonalityTag(
                tagKey,          // tagKey
                title,           // title
                emoji,           // emoji
                dimension,       // dimension（主维度：CATEGORY / MERCHANT）
                dimensionId,     // dimensionId（主维度命中金额最高的对象）
                dimensionName,   // dimensionName（回退：已删除分类 / 已删除商户）
                null,            // currentValue（仅 SAVINGS_MASTER/FINANCE_STAR 在场）
                null,            // previousValue
                null,            // income
                null,            // savings
                null,            // saveRate
                null,            // budget
                null,            // used
                null,            // usedRate
                matchCount,      // matchCount = 匹配笔数
                matchAmt,        // matchAmount = 匹配金额（2dp）
                matchPercent,    // matchPercent = 匹配占比（2dp）
                null,            // lateNightCount
                null,            // lateNightWindow
                threshold,       // threshold = 主判定阈值（笔数下限）
                null,            // strengthScore（任务 6）
                null);           // narrativeText（任务 8）
    }

    /** 累加映射中的全部金额值（2dp 语义由调用方保证）。 */
    private static BigDecimal sumValues(Map<Long, BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : amounts.values()) {
            total = total.add(v);
        }
        return total;
    }

    /**
     * 取命中金额最高的对象 id；金额并列时取 id 较小者，保证确定性（需求 9.6、6.8）。
     *
     * @param amounts 非空的 id→命中金额映射
     * @return 金额最高（并列取最小 id）的对象 id
     */
    private static Long topId(Map<Long, BigDecimal> amounts) {
        Long bestId = null;
        BigDecimal bestAmount = null;
        for (Map.Entry<Long, BigDecimal> e : amounts.entrySet()) {
            Long id = e.getKey();
            BigDecimal amt = e.getValue();
            if (bestId == null
                    || amt.compareTo(bestAmount) > 0
                    || (amt.compareTo(bestAmount) == 0 && id < bestId)) {
                bestId = id;
                bestAmount = amt;
            }
        }
        return bestId;
    }

    /**
     * 省钱达人（{@code SAVINGS_MASTER}）评估器（需求 3.1、3.2、3.4、3.5、3.6、2.3）。
     *
     * <p>取目标月与上一自然月的月度总支出（复用 {@code monthlyReport} 口径，各 2dp HALF_UP），
     * 计算节省额 {@code savings = 上月总支出 − 目标月总支出}（2dp，可负）；节省率仅在上月总支出 &gt; 0
     * 时有定义 {@code = savings ÷ 上月总支出 × 100}（2dp HALF_UP），否则为 {@code null}（需求 3.2）。</p>
     *
     * <p>达标当且仅当「上月总支出 &gt; 0 且 savings &gt; 0 且（savings ≥ 节省金额下限 或 节省率 ≥ 节省率
     * 下限）」（需求 3.4）；上月总支出为 0（需求 3.5）或 savings ≤ 0（需求 3.6）时不授予、不报错、不
     * 中断其余标签评估（返回 {@code null}）。</p>
     *
     * <p>达标时构造一枚标签，携带目标月总支出（{@code currentValue}）、上月总支出（{@code previousValue}）、
     * 节省额（{@code savings}）与节省率（{@code saveRate}），{@code threshold} 取主判定阈值节省金额下限；
     * 其余不适用字段为 {@code null}，{@code strengthScore}/{@code narrativeText} 留待任务 6/8（此处置
     * {@code null}）。</p>
     *
     * @param config 已净化的可配置阈值（{@code savingsAmountMin}、{@code savingsRatePctMin}）
     * @param data   目标月源数据持有者
     * @return 达标时返回省钱达人标签，否则 {@code null}
     */
    private PersonalityTag evaluateSavingsMaster(PersonalityTagProperties config, SourceData data) {
        BigDecimal expense = data.currentMonthly().totalExpense().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal prevExpense = data.previousMonthly().totalExpense().setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 节省额 = 上月总支出 − 目标月总支出（2dp，可负，需求 3.1）。
        BigDecimal savings = prevExpense.subtract(expense).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 节省率仅在上月总支出 > 0 时有定义（需求 3.2）。
        BigDecimal savingsRate = null;
        if (prevExpense.signum() > 0) {
            savingsRate = savings
                    .multiply(BigDecimal.valueOf(100))
                    .divide(prevExpense, MONEY_SCALE, RoundingMode.HALF_UP);
        }

        // 达标判定（需求 3.4、3.5、3.6）：上月总支出为 0 或 savings ≤ 0 直接不授予。
        if (prevExpense.signum() <= 0 || savings.signum() <= 0) {
            return null;
        }
        boolean amountReached = savings.compareTo(config.getSavingsAmountMin()) >= 0;
        boolean rateReached = savingsRate != null
                && savingsRate.compareTo(config.getSavingsRatePctMin()) >= 0;
        if (!amountReached && !rateReached) {
            return null;
        }

        // 达标：携带目标月总支出、上月总支出、节省额、节省率；threshold 取主判定阈值节省金额下限（需求 3.4、2.7）。
        return new PersonalityTag(
                TAG_SAVINGS_MASTER,          // tagKey
                TITLE_SAVINGS_MASTER,        // title
                EMOJI_SAVINGS_MASTER,        // emoji
                null,                        // dimension（聚合类标签无维度）
                null,                        // dimensionId
                null,                        // dimensionName
                expense,                     // currentValue = 目标月总支出
                prevExpense,                 // previousValue = 上月总支出
                null,                        // income（仅 FINANCE_STAR 在场）
                savings,                     // savings = 节省额
                savingsRate,                 // saveRate = 节省率
                null,                        // budget
                null,                        // used
                null,                        // usedRate
                null,                        // matchCount
                null,                        // matchAmount
                null,                        // matchPercent
                null,                        // lateNightCount
                null,                        // lateNightWindow
                config.getSavingsAmountMin(), // threshold = 主判定阈值（节省金额下限）
                null,                        // strengthScore（任务 6）
                null);                       // narrativeText（任务 8）
    }

    /**
     * 理财新星（{@code FINANCE_STAR}）评估器（需求 4.1、4.2、4.3、4.5、4.6、2.3）。
     *
     * <p>取目标月的月度总收入与总支出（复用 {@code monthlyReport} 口径，各 2dp HALF_UP），计算结余
     * {@code balance = 总收入 − 总支出}（2dp，可负）；无任何计入统计的非 transfer 交易时，{@code monthlyReport}
     * 已返回 0.00，故三者均取 0.00（需求 4.2）。结余率仅在总收入 &gt; 0 时有定义
     * {@code = balance ÷ 总收入 × 100}（2dp HALF_UP），否则为 {@code null}（需求 4.3）。</p>
     *
     * <p>达标当且仅当「总收入 &gt; 0 且 balance &gt; 0 且 结余率 ≥ 结余率下限」（需求 4.5）；总收入为 0、
     * 或 balance ≤ 0、或结余率 &lt; 下限时不授予、不报错（需求 4.6），返回 {@code null}。</p>
     *
     * <p>达标时构造一枚标签，携带目标月总收入（{@code income}）、总支出（{@code currentValue}）与结余率
     * （{@code saveRate}）。注意结余额本身无独立 DTO 字段，故 {@code saveRate} 存结余率；{@code threshold}
     * 取主判定阈值结余率下限；其余不适用字段为 {@code null}，{@code strengthScore}/{@code narrativeText}
     * 留待任务 6/8（此处置 {@code null}）。</p>
     *
     * @param config 已净化的可配置阈值（{@code financeSaveRatePctMin}）
     * @param data   目标月源数据持有者
     * @return 达标时返回理财新星标签，否则 {@code null}
     */
    private PersonalityTag evaluateFinanceStar(PersonalityTagProperties config, SourceData data) {
        BigDecimal income = data.currentMonthly().totalIncome().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal expense = data.currentMonthly().totalExpense().setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 结余 = 总收入 − 总支出（2dp，可负，需求 4.1）。无任何计入交易时 monthlyReport 已返回 0.00（需求 4.2）。
        BigDecimal balance = income.subtract(expense).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 结余率仅在总收入 > 0 时有定义（需求 4.3）。
        BigDecimal saveRate = null;
        if (income.signum() > 0) {
            saveRate = balance
                    .multiply(BigDecimal.valueOf(100))
                    .divide(income, MONEY_SCALE, RoundingMode.HALF_UP);
        }

        // 达标判定（需求 4.5、4.6）：总收入为 0 或 balance ≤ 0 直接不授予。
        if (income.signum() <= 0 || balance.signum() <= 0) {
            return null;
        }
        // saveRate 在此必非空（income > 0）；结余率 < 下限不授予。
        if (saveRate.compareTo(config.getFinanceSaveRatePctMin()) < 0) {
            return null;
        }

        // 达标：携带总收入（income）、总支出（currentValue）、结余率（saveRate）；threshold 取结余率下限（需求 4.5、2.7）。
        return new PersonalityTag(
                TAG_FINANCE_STAR,            // tagKey
                TITLE_FINANCE_STAR,          // title
                EMOJI_FINANCE_STAR,          // emoji
                null,                        // dimension（聚合类标签无维度）
                null,                        // dimensionId
                null,                        // dimensionName
                expense,                     // currentValue = 目标月总支出
                null,                        // previousValue（仅 SAVINGS_MASTER 在场）
                income,                      // income = 目标月总收入
                null,                        // savings（仅 SAVINGS_MASTER 在场）
                saveRate,                    // saveRate = 结余率（DTO 无 balance 字段，结余率存此）
                null,                        // budget
                null,                        // used
                null,                        // usedRate
                null,                        // matchCount
                null,                        // matchAmount
                null,                        // matchPercent
                null,                        // lateNightCount
                null,                        // lateNightWindow
                config.getFinanceSaveRatePctMin(), // threshold = 主判定阈值（结余率下限）
                null,                        // strengthScore（任务 6）
                null);                       // narrativeText（任务 8）
    }

    /**
     * 预算大师（{@code BUDGET_MASTER}）评估器（需求 5.1、5.2、5.3、5.4、5.5、2.3）。
     *
     * <p>从 {@link BudgetService#overview} 取 {@code hasBudget}、{@code totalBudget}、{@code spent}
     * （与既有预算聚合口径同源，需求 5.1）。仅在「已设预算且本月预算 &gt; 0.00」时计算 2 位小数预算使用率
     * {@code usedRate = 已用支出 ÷ 本月预算 × 100}（{@link BigDecimal}，HALF_UP，与 {@code BudgetService}
     * 的 {@code spent}/{@code totalBudget} 同源、同口径，需求 5.2）。</p>
     *
     * <p>达标当且仅当「已设预算 且 本月预算 &gt; 0.00 且 已用支出 ≤ 本月预算 且 usedRate ≤ 预算使用率上限」
     * （需求 5.3）；超支（{@code spent > totalBudget}）或 {@code usedRate > 上限} 不授予（需求 5.4）；
     * 未设预算或预算 ≤ 0（{@code totalBudget} 可能为 null）不计算使用率、不授予、不报错，返回 {@code null}
     * （需求 5.5）。</p>
     *
     * <p>达标时构造一枚标签，携带本月预算（{@code budget}）、已用支出（{@code used}）与预算使用率
     * （{@code usedRate}，均 2dp）；{@code threshold} 取主判定阈值预算使用率上限；其余不适用字段为
     * {@code null}，{@code strengthScore}/{@code narrativeText} 留待任务 6/8（此处置 {@code null}）。</p>
     *
     * @param config 已净化的可配置阈值（{@code budgetUsedPctMax}）
     * @param data   目标月源数据持有者
     * @return 达标时返回预算大师标签，否则 {@code null}
     */
    private PersonalityTag evaluateBudgetMaster(PersonalityTagProperties config, SourceData data) {
        BudgetOverviewResponse budget = data.budget();

        // 未设预算 → 不计算使用率、不授予、不报错（需求 5.5）。此时 totalBudget 可能为 null，需先短路以避免 NPE。
        if (!budget.hasBudget() || budget.totalBudget() == null) {
            return null;
        }

        BigDecimal totalBudget = budget.totalBudget().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal spent = budget.spent().setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 预算 ≤ 0 → 不计算使用率、不授予、不报错（需求 5.5）。
        if (totalBudget.signum() <= 0) {
            return null;
        }

        // 预算使用率 = 已用支出 ÷ 本月预算 × 100（2dp HALF_UP，与 BudgetService 的 spent/totalBudget 同源，需求 5.2）。
        BigDecimal usedRate = spent
                .multiply(BigDecimal.valueOf(100))
                .divide(totalBudget, MONEY_SCALE, RoundingMode.HALF_UP);

        // 达标判定（需求 5.3、5.4）：超支（spent > totalBudget）或使用率超上限不授予。
        if (spent.compareTo(totalBudget) > 0) {
            return null;
        }
        if (usedRate.compareTo(config.getBudgetUsedPctMax()) > 0) {
            return null;
        }

        // 达标：携带本月预算、已用支出、预算使用率；threshold 取主判定阈值预算使用率上限（需求 5.3、2.7）。
        return new PersonalityTag(
                TAG_BUDGET_MASTER,           // tagKey
                TITLE_BUDGET_MASTER,         // title
                EMOJI_BUDGET_MASTER,         // emoji
                null,                        // dimension（聚合类标签无维度）
                null,                        // dimensionId
                null,                        // dimensionName
                null,                        // currentValue（仅 SAVINGS_MASTER/FINANCE_STAR 在场）
                null,                        // previousValue
                null,                        // income
                null,                        // savings
                null,                        // saveRate
                totalBudget,                 // budget = 本月预算
                spent,                       // used = 已用支出
                usedRate,                    // usedRate = 预算使用率
                null,                        // matchCount
                null,                        // matchAmount
                null,                        // matchPercent
                null,                        // lateNightCount
                null,                        // lateNightWindow
                config.getBudgetUsedPctMax(), // threshold = 主判定阈值（预算使用率上限）
                null,                        // strengthScore（任务 6）
                null);                       // narrativeText（任务 8）
    }

    /**
     * 夜宵王（{@code LATE_NIGHT_KING}）评估器（需求 7.1、7.2、7.4、7.5、2.3）。
     *
     * <p>取目标月半开区间内当前账本、未删除、{@code type=expense} 的逐笔交易
     * （{@link SourceData#monthExpenseTransactions()}，已在取数阶段过滤），按 {@code Asia/Shanghai} 在内存
     * 派生每笔本地小时（{@link #localHour(Transaction)}），<b>不新增任何数据库查询</b>（需求 7.1）。依可配置
     * 夜宵时段（{@link PersonalityTagProperties#lateNightWindow()}，默认半开
     * {@code [22:00, 24:00) ∪ [00:00, 04:00)}，跨零点感知，非法回退默认）统计夜宵笔数 {@code lateNightCount}
     * （用 {@link PersonalityTagProperties.LateNightWindow#contains(int)}，需求 7.2）。</p>
     *
     * <p>达标当且仅当 {@code lateNightCount ≥ lateNightCountMin}（默认 5，需求 7.4）；小于下限（含 0）不授予、
     * 不报错（需求 7.5）。达标时携带夜宵笔数（{@code lateNightCount}）、夜宵时段字符串描述
     * （{@code lateNightWindow}，形如 {@code "22:00-04:00"}）与笔数下限（{@code threshold}，
     * {@code lateNightCountMin} 的 {@link BigDecimal}）；其余不适用字段为 {@code null}，
     * {@code strengthScore}/{@code narrativeText} 留待任务 6/8（此处置 {@code null}）。</p>
     *
     * @param config 已净化的可配置阈值（{@code lateNightCountMin}、夜宵时段）
     * @param data   目标月源数据持有者
     * @return 达标时返回夜宵王标签，否则 {@code null}
     */
    private PersonalityTag evaluateLateNightKing(PersonalityTagProperties config, SourceData data) {
        PersonalityTagProperties.LateNightWindow window = config.lateNightWindow();

        // 逐笔内存派生本地小时（Asia/Shanghai），落在夜宵时段（半开、跨零点感知）的计入夜宵笔数（需求 7.1、7.2）。
        int lateNightCount = 0;
        for (Transaction tx : data.monthExpenseTransactions()) {
            if (window.contains(localHour(tx))) {
                lateNightCount++;
            }
        }

        // 达标判定（需求 7.4、7.5）：夜宵笔数 < 下限（含 0）不授予。
        if (lateNightCount < config.getLateNightCountMin()) {
            return null;
        }

        // 夜宵时段字符串描述（如 "22:00-04:00"），由起止小时格式化为 "HH:00-HH:00"（需求 7.4）。
        String windowText = String.format("%02d:00-%02d:00", window.startHour(), window.endHour());

        // 达标：携带夜宵笔数、夜宵时段、笔数下限；threshold 取主判定阈值笔数下限（需求 7.4、2.7）。
        return new PersonalityTag(
                TAG_LATE_NIGHT_KING,         // tagKey
                TITLE_LATE_NIGHT_KING,       // title
                EMOJI_LATE_NIGHT_KING,       // emoji
                null,                        // dimension（聚合类标签无维度）
                null,                        // dimensionId
                null,                        // dimensionName
                null,                        // currentValue
                null,                        // previousValue
                null,                        // income
                null,                        // savings
                null,                        // saveRate
                null,                        // budget
                null,                        // used
                null,                        // usedRate
                null,                        // matchCount
                null,                        // matchAmount
                null,                        // matchPercent
                lateNightCount,              // lateNightCount = 夜宵时段支出笔数
                windowText,                  // lateNightWindow = 夜宵时段描述
                BigDecimal.valueOf(config.getLateNightCountMin()), // threshold = 主判定阈值（夜宵笔数下限）
                null,                        // strengthScore（任务 6）
                null);                       // narrativeText（任务 8）
    }

    /**
     * 扩展点（任务 6）：为每枚达标标签计算确定性强度分（6dp，有限非负），按强度分降序 + 固定标签优先级全序
     * 决胜，去重（同一 {@code tagKey} 至多一枚）后截断至 N（{@code config.maxCountClamped()}，需求 9）。
     *
     * <p>骨架实现直接透传候选列表；后续任务将在此实现打分、排序、去重与截断。</p>
     *
     * @param candidates 达标候选标签
     * @param config     已净化的可配置阈值/展示上限
     * @return 挑选后的标签列表（骨架：原样透传）
     */
    private List<PersonalityTag> scoreDedupSortTruncate(
            List<PersonalityTag> candidates, PersonalityTagProperties config) {
        // 1. 强度打分（需求 9.1、9.2）：为每枚达标候选算 6dp、有限、非负的确定性强度分，重建带分值的记录
        //    （record 不可变，故复制全字段仅填入 strengthScore）。阈值为 0/无法按比值计算时记 0，仍参与挑选。
        List<PersonalityTag> scored = new ArrayList<>(candidates.size());
        for (PersonalityTag tag : candidates) {
            scored.add(withStrength(tag, computeStrength(tag, config)));
        }

        // 2. 去重（需求 9.7）：同一 tagKey 至多保留一枚——保留「更优」者（强度分更高，相等按固定优先级决胜键）。
        //    LinkedHashMap 仅用于稳定收敛，最终顺序由第 3 步全序排序决定，与插入/判定顺序无关。
        Map<String, PersonalityTag> byKey = new LinkedHashMap<>();
        for (PersonalityTag tag : scored) {
            PersonalityTag existing = byKey.get(tag.tagKey());
            if (existing == null || compareForSelection(tag, existing) < 0) {
                byKey.put(tag.tagKey(), tag);
            }
        }

        // 3. 排序（需求 9.3、9.5、9.6）：强度分降序，相等按固定标签优先级全序决胜 → 结果唯一确定、可复现。
        List<PersonalityTag> sorted = new ArrayList<>(byKey.values());
        sorted.sort(PersonalityTagService::compareForSelection);

        // 4. 截断至 N（需求 9.3、9.8）：N = maxCountClamped()（钳制 1–8，默认 4）；不足 N 按同序返回全部、不补足。
        int limit = config.maxCountClamped();
        if (sorted.size() > limit) {
            return new ArrayList<>(sorted.subList(0, limit));
        }
        return sorted;
    }

    /**
     * 计算一枚达标标签的确定性强度分（需求 9.1、9.2）：判定指标相对其阈值的归一化比值，取<b>有限、非负、
     * 6 位小数</b>。多下限标签（省钱达人、行为类）取<b>已配置</b>各项归一化比值的最大者；预算大师使用率越低
     * 分越高，取 {@code budgetUsedPctMax ÷ max(usedRate, ε)} 保证有界。阈值为 0 或无法按比值计算（分母 ≤ 0、
     * 指标缺失）时记 {@code 0}，该标签<b>仍参与</b>排序与挑选。结果一律 {@code setScale(6, HALF_UP)}。
     *
     * @param tag    达标候选标签（携带判定指标机器字段与主判定阈值）
     * @param config 已净化的可配置阈值（各项归一化下限）
     * @return 6dp、有限、非负的强度分
     */
    private static BigDecimal computeStrength(PersonalityTag tag, PersonalityTagProperties config) {
        BigDecimal score = switch (tag.tagKey()) {
            // 省钱达人：max(savings/savingsAmountMin, savingsRate/savingsRatePctMin)；saveRate 可能 null → 该项跳过。
            case TAG_SAVINGS_MASTER -> maxRatio(
                    ratio(tag.savings(), config.getSavingsAmountMin()),
                    ratio(tag.saveRate(), config.getSavingsRatePctMin()));
            // 理财新星：saveRate / financeSaveRatePctMin。
            case TAG_FINANCE_STAR -> ratio(tag.saveRate(), config.getFinanceSaveRatePctMin());
            // 预算大师：使用率越低分越高，budgetUsedPctMax / max(usedRate, ε)（保证有限有界）。
            case TAG_BUDGET_MASTER -> budgetStrength(tag, config);
            // 外卖探索家：max(matchCount/countMin, matchPercent/pctMin)（仅已配置下限参与）。
            case TAG_TAKEOUT_EXPLORER -> maxRatio(
                    ratio(intVal(tag.matchCount()), BigDecimal.valueOf(config.getTakeoutCountMin())),
                    ratio(tag.matchPercent(), config.getTakeoutPctMin()));
            // 咖啡收藏家：仅 matchCount / countMin。
            case TAG_COFFEE_COLLECTOR -> ratio(
                    intVal(tag.matchCount()), BigDecimal.valueOf(config.getCoffeeCountMin()));
            // 旅行狂人：max(matchCount/countMin, matchAmount/amountMin)。
            case TAG_TRAVEL_ENTHUSIAST -> maxRatio(
                    ratio(intVal(tag.matchCount()), BigDecimal.valueOf(config.getTravelCountMin())),
                    ratio(tag.matchAmount(), config.getTravelAmountMin()));
            // 购物生活家：max(matchCount/countMin, matchAmount/amountMin)。
            case TAG_SHOPPING_LIFER -> maxRatio(
                    ratio(intVal(tag.matchCount()), BigDecimal.valueOf(config.getShoppingCountMin())),
                    ratio(tag.matchAmount(), config.getShoppingAmountMin()));
            // 夜宵王：lateNightCount / lateNightCountMin。
            case TAG_LATE_NIGHT_KING -> ratio(
                    intVal(tag.lateNightCount()), BigDecimal.valueOf(config.getLateNightCountMin()));
            default -> null;
        };
        // 阈值为 0/无法按比值计算 → 记 0；负值同样兜底为 0，保证非负（需求 9.2）。一律 6dp HALF_UP（需求 9.1）。
        if (score == null || score.signum() < 0) {
            score = BigDecimal.ZERO;
        }
        return score.setScale(STRENGTH_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 预算大师强度分（需求 9.1、9.2）：{@code budgetUsedPctMax ÷ max(usedRate, ε)}。使用率越低分越高；
     * {@code usedRate=0} 时以 ε 兜底保证<b>有限有界</b>。上限缺失时返回 {@code null}（记 0）。
     */
    private static BigDecimal budgetStrength(PersonalityTag tag, PersonalityTagProperties config) {
        BigDecimal cap = config.getBudgetUsedPctMax();
        if (cap == null) {
            return null;
        }
        BigDecimal usedRate = tag.usedRate() == null ? BigDecimal.ZERO : tag.usedRate();
        BigDecimal denom = usedRate.max(STRENGTH_EPSILON);
        return cap.divide(denom, STRENGTH_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 归一化比值 {@code numerator ÷ denominator}（6dp HALF_UP）；分子/分母缺失或分母 ≤ 0（含阈值为 0，
     * 需求 9.2）时返回 {@code null}，表示该项不参与（未配置或无法按比值计算）。
     */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, STRENGTH_SCALE, RoundingMode.HALF_UP);
    }

    /** 整数指标转 {@link BigDecimal}（{@code null} 透传），供 {@link #ratio} 归一化。 */
    private static BigDecimal intVal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** 取多项归一化比值中的最大者，忽略 {@code null}（未配置项）；全为 {@code null} 时返回 {@code null}。 */
    private static BigDecimal maxRatio(BigDecimal... ratios) {
        BigDecimal max = null;
        for (BigDecimal r : ratios) {
            if (r == null) {
                continue;
            }
            if (max == null || r.compareTo(max) > 0) {
                max = r;
            }
        }
        return max;
    }

    /**
     * 挑选/排序决胜比较器（需求 9.5、9.6）：强度分降序为主；强度分逐位相等时按固定标签优先级
     * （{@link #TAG_PRIORITY}，rank 越小越优先）升序决胜，构成全序 → 结果唯一确定、与判定顺序无关。
     * 返回值 &lt; 0 表示 {@code a} 更优（应排在前）。
     */
    private static int compareForSelection(PersonalityTag a, PersonalityTag b) {
        int byScore = b.strengthScore().compareTo(a.strengthScore());
        if (byScore != 0) {
            return byScore;
        }
        return Integer.compare(priorityRank(a.tagKey()), priorityRank(b.tagKey()));
    }

    /** 标签优先级 rank（未知标签置末位，保证全序确定）。 */
    private static int priorityRank(String tagKey) {
        return TAG_PRIORITY.getOrDefault(tagKey, Integer.MAX_VALUE);
    }

    /**
     * 复制一枚标签并填入 {@code strengthScore}（record 不可变，需求 9.1）。其余机器字段原样保留。
     *
     * @param t        原标签（{@code strengthScore} 为 {@code null}）
     * @param strength 计算所得强度分（6dp、有限、非负）
     * @return 填充了 {@code strengthScore} 的新标签
     */
    private static PersonalityTag withStrength(PersonalityTag t, BigDecimal strength) {
        return new PersonalityTag(
                t.tagKey(), t.title(), t.emoji(), t.dimension(), t.dimensionId(), t.dimensionName(),
                t.currentValue(), t.previousValue(), t.income(), t.savings(), t.saveRate(),
                t.budget(), t.used(), t.usedRate(), t.matchCount(), t.matchAmount(), t.matchPercent(),
                t.lateNightCount(), t.lateNightWindow(), t.threshold(), strength, t.narrativeText());
    }

    /**
     * 对每枚挑选后的标签调用 {@link TagNarrator#render(PersonalityTag)} 生成 {@code narrativeText}
     * （需求 8.1、8.9）。
     *
     * <p><b>成功</b>：{@code render} 返回一段中文文案（已由 {@link TagNarrator} 保证仅正向/中性措辞、至少
     * 含标题与一类关键数值、长度 1..60、且数值与机器字段一致，需求 8.3–8.8），据此重建携带
     * {@code narrativeText} 的标签记录（record 不可变）。</p>
     *
     * <p><b>渲染失败</b>（缺标题或缺全部关键数值，{@code render} 返回 {@code null}，需求 8.9）：
     * {@code narrativeText} 保持为 {@code null} 以标记该条文案生成失败，<b>保留其余机器字段</b>，
     * 且<b>不使整体请求返回错误</b>——逐枚独立渲染，一枚失败不影响其余标签。</p>
     *
     * @param selected 挑选后的标签列表（{@code narrativeText} 尚为 {@code null}）
     * @return 已逐枚填充 {@code narrativeText}（成功）或保持 {@code null}（失败）的标签列表
     */
    private List<PersonalityTag> renderNarratives(List<PersonalityTag> selected) {
        List<PersonalityTag> rendered = new ArrayList<>(selected.size());
        for (PersonalityTag tag : selected) {
            // render 成功返回文案、失败返回 null；null 原样写入 narrativeText 以标记生成失败（需求 8.9）。
            rendered.add(withNarrative(tag, narrator.render(tag)));
        }
        return rendered;
    }

    /**
     * 复制一枚标签并填入 {@code narrativeText}（record 不可变，需求 8.1、8.9）。其余机器字段原样保留；
     * {@code narrativeText} 为 {@code null} 时即表示该条文案生成失败（缺标题或缺全部关键数值，需求 8.9）。
     *
     * @param t             原标签（{@code narrativeText} 为 {@code null}）
     * @param narrativeText 渲染所得中文文案；{@code null} 表示渲染失败
     * @return 填充了 {@code narrativeText} 的新标签
     */
    private static PersonalityTag withNarrative(PersonalityTag t, String narrativeText) {
        return new PersonalityTag(
                t.tagKey(), t.title(), t.emoji(), t.dimension(), t.dimensionId(), t.dimensionName(),
                t.currentValue(), t.previousValue(), t.income(), t.savings(), t.saveRate(),
                t.budget(), t.used(), t.usedRate(), t.matchCount(), t.matchAmount(), t.matchPercent(),
                t.lateNightCount(), t.lateNightWindow(), t.threshold(), t.strengthScore(), narrativeText);
    }

    /**
     * 组装鼓励性兜底响应（需求 10.1、10.2、10.3、10.6）：{@code isFallback=true}、一条非空鼓励文案
     * （1..60 字符，来源为空时用系统内置默认 {@link #FALLBACK_TEXT}）、{@code tags} 为空列表，仍携带
     * {@code month} 与 {@code monthStatus}（需求 10.5）。任务 7 将在此完善兜底语义（来源可配置等）。
     *
     * @param month       目标自然月
     * @param monthStatus 月状态（{@code partial} / {@code final}）
     * @return 兜底态响应
     */
    private PersonalityTagsResponse fallback(YearMonth month, String monthStatus) {
        return new PersonalityTagsResponse(
                month.toString(), monthStatus, true, sanitizeFallbackText(FALLBACK_TEXT), List.of());
    }

    /**
     * 兜底文案长度不变式守卫（需求 10.1、10.2、10.6）：保证返回的鼓励性兜底文案<b>非空且长度落在
     * {@code [1, 60]} 个字符内</b>。来源为空/空白（{@code null} 或去空白后为空）→ 回退系统内置默认
     * {@link #FALLBACK_TEXT}；来源超过 60 字符 → 截断至 60；本方法保证任何来源都产出一条合法的非空文案，
     * 且 SHALL 不返回错误。
     *
     * @param source 兜底文案来源（可能为 {@code null}/空白/超长）
     * @return 非空、长度 1..60 的合法兜底文案
     */
    static String sanitizeFallbackText(String source) {
        // 来源为空/空白 → 内置默认（需求 10.6）。
        String text = (source == null || source.strip().isEmpty()) ? FALLBACK_TEXT : source;
        // 超长 → 截断至上限（需求 10.1、10.2）。截断后仍非空（上限 ≥ 下限 ≥ 1）。
        if (text.length() > FALLBACK_TEXT_MAX_LEN) {
            text = text.substring(0, FALLBACK_TEXT_MAX_LEN);
        }
        // 兜底：内置默认自身即满足 1..60，此判定为防御性守卫（需求 10.6）。
        if (text.length() < FALLBACK_TEXT_MIN_LEN) {
            text = FALLBACK_TEXT;
        }
        return text;
    }

    /**
     * 派生某笔交易在 {@code Asia/Shanghai} 的本地小时（0–23，需求 7.1）。交易 {@code occurredAt} 以
     * {@code Asia/Shanghai} 墙钟时间存储（与既有报表/预算取数口径一致），故本地小时即
     * {@code occurredAt.getHour()}；本方法显式表意供夜宵王评估器（任务 5.5）复用。
     *
     * @param tx 一笔交易
     * @return 本地小时（0–23）
     */
    static int localHour(Transaction tx) {
        return tx.getOccurredAt().getHour();
    }

    /**
     * {@code final} 月的全部源数据持有者（需求 1.6、14.5）：把 8 枚标签所需的既有报表/预算聚合结果与逐笔支出
     * 交易一次性取入，供后续评估器（任务 5）以同口径消费，避免重复取数。纯只读、无副作用。
     *
     * @param currentMonthly          目标月月度报表（总收入/总支出/结余，需求 3.1、4.1）
     * @param previousMonthly         上一自然月月度报表（月环比基线，需求 3.1）
     * @param budget                  目标月预算总览（本月预算 / 已用支出，需求 5.1）
     * @param categoryExpense         目标月全月支出分类占比报表（每分类金额/笔数/占比 + 当月总支出，需求 6.3）
     * @param merchantExpense         目标月全月支出商户维度报表（每商户金额/笔数，需求 6.3）
     * @param monthExpenseTransactions 目标月全月 {@code type=EXPENSE} 逐笔交易（夜宵本地小时派生、行为类交易级去重，需求 7.1、6.2）
     */
    private record SourceData(
            MonthlyReportResponse currentMonthly,
            MonthlyReportResponse previousMonthly,
            BudgetOverviewResponse budget,
            CategoryReportResponse categoryExpense,
            DimensionReportResponse merchantExpense,
            List<Transaction> monthExpenseTransactions) {
    }
}
