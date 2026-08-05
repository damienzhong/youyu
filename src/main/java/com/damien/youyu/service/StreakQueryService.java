package com.damien.youyu.service;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.StreakSegment;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.StreakSegmentRepository;
import com.damien.youyu.repository.UserGrowthRepository;

/**
 * 连续记账两个查询接口的组装：连续记账概览与历史连续区间分页
 * （需求 1.4、1.7、1.11、1.12；2.5、2.6、2.7；3.1～3.3；6.1～6.7、6.12、6.17；7.8～7.11）。
 *
 * <p>本服务与 {@link GrowthQueryService} 同构、同处结算的<b>事务边界之外</b>：概览路径会主动触发
 * 一次结算，但结算的任何异常都在这里就地吞掉只记 WARN（需求 6.7）——
 * {@link GrowthSettlementService#settle} 自身刻意不 catch，靠异常穿出回滚其 {@code REQUIRES_NEW}
 * 事务，吞异常必须发生在这一层，否则会把一个已标记回滚的事务照常提交。</p>
 *
 * <h2>概览恰好 3 条读查询（需求 7.10）</h2>
 * <p>Q1 读成长档案（{@link UserGrowthRepository#findById}）、Q2 段聚合
 * （{@link StreakSegmentRepository#aggregateRaw}：{@code COUNT / SUM(days) / MAX(days)}）、
 * Q3 当前段与最长段（{@link StreakSegmentRepository#endpointsRaw}：一条 {@code UNION ALL}）。
 * 三条为常量上界，不随段总数与交易笔数增长。「今日已完成 / 当前连续天数 / 是否中断」三项一律委托
 * {@link StreakJudgment}（需求 2.3、10.5 的相等性由此构造性成立），里程碑换算委托
 * {@link StreakMilestones}。</p>
 *
 * <h2>「上次连续」= 当前段本身的投影（需求 2.5、2.6）</h2>
 * <p>中断状态下当前段的结束日即最近记账日，它的天数正是「用户最近一次坚持了多少天」，因此不需要
 * 第 4 条读查询去找「倒数第二段」——{@code broken} 为真时把当前段的 {@code days} / {@code end_date}
 * 投影成 {@code lastStreakDays} / {@code lastStreakEnd} 即可；{@code broken} 为假时两项一律为
 * {@code null}（连续未中断时不存在「上一次」）。</p>
 *
 * <h2>无档案 / 空日历降级（需求 1.4、1.7）</h2>
 * <p>{@code profile} 为 {@code null} 或日历为空时：{@code todayDone=false}、三个天数 0、
 * 四个端点日期为空值、{@code broken=false}、{@code segmentCount=0}，<b>不返回错误、不写任何表</b>，
 * 字段集与正常路径完全相同。</p>
 */
@Service
public class StreakQueryService {

    private static final Logger log = LoggerFactory.getLogger(StreakQueryService.class);

    /** 分页参数缺省值与取值范围（需求 6.2、6.12），与成长域逐项相同。 */
    private static final int DEFAULT_PAGE = 0;
    private static final int PAGE_MIN = 0;
    private static final int PAGE_MAX = 100000;
    private static final int DEFAULT_SIZE = 20;
    private static final int SIZE_MIN = 1;
    private static final int SIZE_MAX = 50;

    private final GrowthSettlementService settlementService;
    private final UserGrowthRepository userGrowthRepository;
    private final StreakSegmentRepository repository;
    private final StreakMilestones milestones;
    private final Clock clock;

    public StreakQueryService(GrowthSettlementService settlementService,
                              UserGrowthRepository userGrowthRepository,
                              StreakSegmentRepository repository,
                              StreakMilestones milestones,
                              Clock clock) {
        this.settlementService = settlementService;
        this.userGrowthRepository = userGrowthRepository;
        this.repository = repository;
        this.milestones = milestones;
        this.clock = clock;
    }

    /**
     * 连续记账概览：先尝试结算（异常就地吞掉），再读档案与段聚合、委托判定与里程碑换算，组装 14 项。
     *
     * <p><b>本方法不加 {@code @Transactional}</b>：它处在结算事务边界之外，结算走 {@code settle}
     * 自己的 {@code REQUIRES_NEW}，Q1/Q2/Q3 都是独立只读查询，无需外层事务；加事务反而会把「吞异常」
     * 挪进事务上下文、破坏需求 6.7 的隔离。</p>
     *
     * @param userId 令牌所标识的用户 id（调用方已确认其在 {@code users} 表中仍存在）
     * @return 连续记账概览响应，恰好 14 项字段（需求 6.1）
     */
    public StreakOverviewResponse getOverview(Long userId) {
        // ── ① 尝试结算：复用 OVERVIEW 来源的 10 秒进程内节流器，不新增节流器（需求 6.6）──────────
        // 结算成败与是否被节流都不改变响应字段集（需求 6.7）：无论如何都照常读档案、聚合、组装。
        try {
            settlementService.settle(userId, TriggerSource.OVERVIEW);
        } catch (Exception e) {
            // 事务边界之外吞异常（需求 6.7）：settle 的 REQUIRES_NEW 事务已因异常穿出而回滚，
            // 这里只负责不把异常继续抛给控制器，从而让概览照常返回已持久化的取值。
            log.warn("[STREAK_SETTLE_FAILED] 连续记账概览触发的结算失败，返回已持久化取值 userId={}", userId, e);
        }

        // ── ② 只读一次时钟：判定日一律由注入的 Clock（固定 Asia/Shanghai）折算（需求 1.5）──────────
        LocalDate judgmentDay = LocalDate.now(clock);

        // ── ③ Q1 读成长档案（可能为空：新用户，或结算失败且从未建档，需求 1.4）────────────────────
        UserGrowth profile = userGrowthRepository.findById(userId).orElse(null);

        // ── ④ Q2 段聚合（COUNT / SUM(days) / MAX(days)）─────────────────────────────────────────
        StreakAggregate agg = readAggregate(userId);

        // ── ⑤ Q3 当前段 + 最长段（一条 UNION ALL）───────────────────────────────────────────────
        StreakEndpoints ends = readEndpoints(userId);

        // ── ⑥ 三项判定全部委托 StreakJudgment（需求 2.3、10.5 相等性构造性成立）──────────────────
        LocalDate lastRecordDate = (profile == null) ? null : profile.getLastRecordDate();
        int currentSegmentDays = (profile == null) ? 0 : profile.getCurrentStreakDays();
        int currentStreak = StreakJudgment.currentStreakDays(lastRecordDate, currentSegmentDays, judgmentDay);
        boolean broken = StreakJudgment.broken(lastRecordDate, judgmentDay);
        boolean todayDone = StreakJudgment.todayDone(lastRecordDate, judgmentDay);

        // ── ⑦ 最近记账日晚于判定日（时钟偏移或数据异常）记 WARN（需求 1.12）──────────────────────
        if (lastRecordDate != null && lastRecordDate.isAfter(judgmentDay)) {
            log.warn("[STREAK_CLOCK_SKEW] 最近记账日晚于判定日 userId={}", userId);
        }

        // ── ⑧ 不变式在线校验（需求 4.17）：只告警、不使概览请求失败────────────────────────────────
        assertInvariants(userId, profile, agg);

        // ── ⑨ 里程碑换算（需求 3.6、3.7）：按当前连续天数算进度──────────────────────────────────
        Integer next = milestones.nextAfter(currentStreak);
        Integer daysToNext = (next == null) ? null : next - currentStreak;

        // ── 组装 14 项（顺序与 design.md「7. 接口设计」的字段表逐项对齐）──────────────────────────
        return new StreakOverviewResponse(
                todayDone,
                currentStreak,
                broken,
                ends.currentStart(),
                ends.currentEnd(),
                broken ? ends.currentDays() : null,              // 上次连续天数（需求 2.5、2.6）
                broken ? ends.currentEnd() : null,               // 上次连续结束日
                (profile == null) ? 0 : profile.getMaxStreakDays(),
                ends.longestStart(),
                ends.longestEnd(),
                (profile == null) ? 0 : profile.getTotalRecordDays(),
                agg.segmentCount(),
                next,
                daysToNext);
    }

    /**
     * 历史连续区间分页：自行解析原文分页参数 → 校验 → 按 {@code start_date} 倒序翻页并返回真实总条数。
     *
     * <p><b>本方法不触发结算</b>（需求 6.6）：历史区间只读段表，允许比概览旧，不因该差异报错、不写任何表。
     * 标注 {@code @Transactional(readOnly = true)} 只为让「取当页 + 取总条数」两次查询处在同一只读事务里，
     * 读到一致的快照。恰好 2 条读查询（需求 7.11）。</p>
     *
     * <p>参数以原文 {@link String} 接收、在方法体内自行解析（需求 6.12）：无法解析为整数、{@code page < 0}、
     * {@code page > 100000}、{@code size < 1}、{@code size > 50} 一律抛 {@code STREAK_PAGE_PARAM_INVALID}
     * 并置 {@code field} 为越界的参数名；{@code page} / {@code size} 缺省（{@code null} 或空白）时分别取
     * 0 与 20。页码越界时返回空列表 + 真实总条数，不报错（需求 6.17）。</p>
     *
     * @param userId  令牌所标识的用户 id（调用方已确认其在 {@code users} 表中仍存在）
     * @param rawPage 原文页码，可为 {@code null} / 空白（取缺省 0）
     * @param rawSize 原文每页条数，可为 {@code null} / 空白（取缺省 20）
     * @return 历史连续区间分页响应，顶层恰好 2 项（列表项 + 总条数，需求 6.2）
     */
    @Transactional(readOnly = true)
    public StreakSegmentPageResponse listSegments(Long userId, String rawPage, String rawSize) {
        int page = parseInRange(rawPage, DEFAULT_PAGE, PAGE_MIN, PAGE_MAX, "page");
        int size = parseInRange(rawSize, DEFAULT_SIZE, SIZE_MIN, SIZE_MAX, "size");

        // 排序键固定在仓库方法名里（start_date 倒序），Pageable 只承载页码与每页条数（需求 6.3、6.4）。
        Page<StreakSegment> rows =
                repository.findByUserIdOrderByStartDateDesc(userId, PageRequest.of(page, size));

        // 页码越界时 rows.getContent() 为空列表，getTotalElements() 仍是真实总条数（需求 6.5、6.17）。
        List<StreakSegmentItem> items = new ArrayList<>(rows.getNumberOfElements());
        for (StreakSegment s : rows.getContent()) {
            items.add(new StreakSegmentItem(s.getStartDate(), s.getEndDate(), s.getDays()));
        }
        return new StreakSegmentPageResponse(items, rows.getTotalElements());
    }

    /**
     * 不变式在线校验（需求 4.17）：用 Q2 已取的聚合值与 Q1 的物化列比对，<b>零额外查询</b>。
     *
     * <p>不变式③（{@code sumDays == totalRecordDays}）与④（{@code maxDays == maxStreakDays}）任一
     * 不成立记一条 {@code [STREAK_INVARIANT_VIOLATED]} WARN（含 userId 与首个被违反的不变式序号，
     * 用 {@code else if} 只报首个）。<b>只告警、不使概览请求失败</b>：段是派生数据，不一致只导致历史区间墙
     * 少展示或多展示一段，修复只需下一次结算的全量对账。</p>
     *
     * <p>存量用户在段建立之前的第一次概览请求（且该请求内结算被节流跳过）会命中一次
     * {@code sumDays=0 != totalRecordDays} 的告警，属预期的一次性噪声，<b>该日志不应配置为告警上报项</b>。</p>
     */
    private void assertInvariants(Long userId, UserGrowth profile, StreakAggregate agg) {
        if (profile == null) {
            return;                                 // 无档案：段序列必为空，无可校验的物化列
        }
        long totalRecordDays = profile.getTotalRecordDays();
        int maxStreakDays = profile.getMaxStreakDays();
        if (agg.sumDays() != totalRecordDays) {
            log.warn("[STREAK_INVARIANT_VIOLATED] userId={} 首个被违反的不变式=3（Σdays={} != totalRecordDays={}）",
                    userId, agg.sumDays(), totalRecordDays);
        } else if (agg.maxDays() != maxStreakDays) {
            log.warn("[STREAK_INVARIANT_VIOLATED] userId={} 首个被违反的不变式=4（maxDays={} != maxStreakDays={}）",
                    userId, agg.maxDays(), maxStreakDays);
        }
    }

    /**
     * 解析原文分页参数并做范围校验（需求 6.12）。与成长域 {@code GrowthQueryService#parseInRange} 逐字同构，
     * 只把错误码换成 {@link ApiException#streakPageParamInvalid(String)}——<b>不复用</b>
     * {@code growthPageParamInvalid}：跨域复用会让客户端在连续记账页收到带 {@code GROWTH} 前缀的错误码，
     * 既误导排查也让前端无法按域分派提示文案。
     *
     * <p>{@code null} 或空白取 {@code defaultValue}；否则去空白后以 {@link Integer#parseInt} 解析，
     * 无法解析或落在 {@code [min, max]} 之外一律抛 {@code STREAK_PAGE_PARAM_INVALID} 并置 {@code field}。</p>
     */
    private static int parseInRange(String raw, int defaultValue, int min, int max, String field) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.streakPageParamInvalid(field);
        }
        if (value < min || value > max) {
            throw ApiException.streakPageParamInvalid(field);
        }
        return value;
    }

    /**
     * Q2：把 {@link StreakSegmentRepository#aggregateRaw} 的 {@code Object[]} 包成不可变载体。
     *
     * <p>{@code aggregateRaw} 声明返回 {@code Object[]}，而 Spring Data 对「单行多列」聚合查询会把
     * 那一行的列数组<b>再包一层</b>——即返回 {@code Object[]{ Object[]{COUNT, SUM, MAX} }}，
     * {@code row[0]} 本身是列数组。这里先 {@link #unwrapSingleRow} 拆平这层嵌套再取列，
     * 否则 {@code row[0]} 会是一个 {@code Object[]} 而不是数值（与
     * {@code StreakSegmentRepositoryTest.aggregateColumns} 的处理一致）。扁平列数组（如单元测试桩
     * 返回的 {@code new Object[]{0L, 0L, 0}}）原样透传。</p>
     */
    private StreakAggregate readAggregate(Long userId) {
        Object[] row = unwrapSingleRow(repository.aggregateRaw(userId));
        long count = toLong(row[0]);
        long sumDays = toLong(row[1]);
        int maxDays = (int) toLong(row[2]);
        return new StreakAggregate(count, sumDays, maxDays);
    }

    /**
     * 拆平 Spring Data 对「单行多列」聚合查询多包的一层：{@code [ [c, s, m] ] → [c, s, m]}。
     * 已是扁平列数组时原样返回。
     */
    private static Object[] unwrapSingleRow(Object[] agg) {
        if (agg.length == 1 && agg[0] instanceof Object[] inner) {
            return inner;
        }
        return agg;
    }

    /**
     * Q3：把 {@link StreakSegmentRepository#endpointsRaw} 的 {@code List<Object[]>} 包成不可变载体。
     * 每行 {@code [kind, start_date, end_date, days]}：{@code kind=0} 为当前段、{@code kind=1} 为最长段。
     * 该用户无任何段时返回全空端点。
     */
    private StreakEndpoints readEndpoints(Long userId) {
        LocalDate currentStart = null;
        LocalDate currentEnd = null;
        Integer currentDays = null;
        LocalDate longestStart = null;
        LocalDate longestEnd = null;
        for (Object[] row : repository.endpointsRaw(userId)) {
            int kind = (int) toLong(row[0]);
            LocalDate start = toLocalDate(row[1]);
            LocalDate end = toLocalDate(row[2]);
            int days = (int) toLong(row[3]);
            if (kind == 0) {                        // 当前段：start_date 最大者
                currentStart = start;
                currentEnd = end;
                currentDays = days;
            } else {                                // 最长段：days 最大、并列时 start_date 最晚者
                longestStart = start;
                longestEnd = end;
            }
        }
        return new StreakEndpoints(currentStart, currentEnd, currentDays, longestStart, longestEnd);
    }

    /** 数值列稳妥转 {@code long}（聚合列可能是 {@link Long} / {@link Integer} 等）。 */
    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }

    /**
     * 日期列稳妥转 {@link LocalDate}，逐字回读、<b>不经默认时区换算</b>（需求 1.5）：
     * Hibernate 6 通常把 DATE 列直接读成 {@link LocalDate}；若某驱动仍读成 {@link Date}，
     * 用 {@link Date#toLocalDate()} 逐字取年月日（不走 {@code Calendar}、不做时区平移）。
     */
    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }

    /** Q2 的不可变载体：段总数、天数合计、最大段天数。后两项亦作不变式③④的在线校验材料。 */
    private record StreakAggregate(long segmentCount, long sumDays, int maxDays) {
    }

    /** Q3 的不可变载体：当前段（起止日 + 天数）与最长段（起止日）；无段时各项为空。 */
    private record StreakEndpoints(LocalDate currentStart, LocalDate currentEnd, Integer currentDays,
                                   LocalDate longestStart, LocalDate longestEnd) {
    }
}
