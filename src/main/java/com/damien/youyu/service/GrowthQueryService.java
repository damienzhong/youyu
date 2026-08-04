package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;

/**
 * 成长概览与经验明细的组装（需求 10）。
 *
 * <p>本服务处在结算的<b>事务边界之外</b>：概览路径会主动触发一次结算，但结算的任何异常都在这里
 * 就地吞掉只记日志（需求 9.10、9.11、9.7）——{@link GrowthSettlementService#settle} 自身刻意不 catch，
 * 靠异常穿出回滚其 {@code REQUIRES_NEW} 事务，吞异常必须发生在这一层，否则会把一个已标记回滚的事务
 * 照常提交。</p>
 *
 * <p>概览路径的固定顺序（需求 10.1、10.14）：</p>
 * <ol>
 *   <li>尝试结算（{@code OVERVIEW} 来源自带 10 秒进程内节流；异常吞掉只记 WARN）；</li>
 *   <li>读成长档案（<b>可能为空</b>：新用户或结算失败且从未建档）；</li>
 *   <li>实时聚合三项累计统计（笔数 / 支出 / 收入，需求 7.9 不物化）；</li>
 *   <li>按<b>判定日</b>校正当前连续天数（需求 4.11、4.15，读取侧实时判定、不写库）；</li>
 *   <li>组装 9 枚徽章（需求 8.5、8.6、8.12、8.13）。</li>
 * </ol>
 *
 * <p><b>降级</b>（需求 9.11）：结算失败且无档案时返回等级 1 / 经验 0 / 三项天数 0 / 9 枚未点亮，
 * 但累计笔数与金额仍是<b>真实值</b>（它们来自交易事实源的实时聚合，与档案无关）。结算成败
 * <b>不改变响应字段集</b>（需求 9.10）。</p>
 */
@Service
public class GrowthQueryService {

    private static final Logger log = LoggerFactory.getLogger(GrowthQueryService.class);

    /** {@code DECIMAL(18,2)} 可表示的最大值，累计金额的上界（需求 7.14）。 */
    private static final BigDecimal AMOUNT_UPPER_BOUND = new BigDecimal("9999999999999999.99");

    /** 三项累计聚合的服务端耗时上界；超过只记 WARN、不使请求失败（需求 7.13）。 */
    private static final long STATS_BUDGET_MILLIS = 500L;

    /** 交易类型字面量，与 {@link TransactionRepository} 的原生查询口径一致（需求 7.3）。 */
    private static final String TYPE_EXPENSE = "expense";
    private static final String TYPE_INCOME = "income";

    /** 预算达成经验事件键前缀（需求 3.7）；用于判定 {@code BUDGET_MET} 徽章的点亮条件（需求 8.11）。 */
    private static final String BUDGET_MET_PREFIX = "BUDGET_MET:";

    /** 首次邀请经验事件键（需求 6.1）；用于判定 {@code INVITE_1} 徽章的点亮条件（需求 8.11）。 */
    private static final String FIRST_INVITE_KEY = "FIRST_INVITE";

    /** 经验明细分页参数的缺省值与取值范围（需求 10.2、10.9）。 */
    private static final int DEFAULT_PAGE = 0;
    private static final int PAGE_MIN = 0;
    private static final int PAGE_MAX = 100000;
    private static final int DEFAULT_SIZE = 20;
    private static final int SIZE_MIN = 1;
    private static final int SIZE_MAX = 50;

    private final GrowthSettlementService settlementService;
    private final UserGrowthRepository userGrowthRepository;
    private final GrowthEventRepository growthEventRepository;
    private final TransactionRepository transactionRepository;
    private final GrowthBadgeCatalog badgeCatalog;
    private final GrowthLevelCurve levelCurve;
    private final Clock clock;

    public GrowthQueryService(GrowthSettlementService settlementService,
                              UserGrowthRepository userGrowthRepository,
                              GrowthEventRepository growthEventRepository,
                              TransactionRepository transactionRepository,
                              GrowthBadgeCatalog badgeCatalog,
                              GrowthLevelCurve levelCurve,
                              Clock clock) {
        this.settlementService = settlementService;
        this.userGrowthRepository = userGrowthRepository;
        this.growthEventRepository = growthEventRepository;
        this.transactionRepository = transactionRepository;
        this.badgeCatalog = badgeCatalog;
        this.levelCurve = levelCurve;
        this.clock = clock;
    }

    /**
     * 成长概览：先尝试结算（异常就地吞掉），再读档案、实时聚合、校正连续天数、组装徽章。
     *
     * <p><b>本方法不加 {@code @Transactional}</b>：它处在结算事务边界之外，结算走
     * {@code settle} 自己的 {@code REQUIRES_NEW}，读档案与聚合都是独立的只读查询，无需外层事务；
     * 加事务反而会把「吞异常」挪进事务上下文、破坏需求 9.7 的隔离。</p>
     *
     * @param userId 令牌所标识的用户 id（调用方已确认其在 {@code users} 表中仍存在）
     * @return 成长概览响应，恰好 15 项字段（需求 10.1、10.13）
     */
    public GrowthOverviewResponse getOverview(Long userId) {
        // ── ① 尝试结算：OVERVIEW 来源自带 10 秒进程内节流；异常吞掉只记 WARN（需求 9.10、10.14）──
        // 结算成败与是否被节流都不改变下面的响应字段集：无论如何都照常读档案、聚合、组装。
        try {
            settlementService.settle(userId, TriggerSource.OVERVIEW);
        } catch (Exception e) {
            // 事务边界之外吞异常（需求 9.7）：settle 的 REQUIRES_NEW 事务已因异常穿出而回滚，
            // 这里只负责不把异常继续抛给控制器，从而让概览照常返回略旧的档案取值（需求 9.10）。
            log.warn("[GROWTH_SETTLE_FAILED] 概览触发的结算失败，返回档案当前取值 userId={}", userId, e);
        }

        // ── ② 读档案（可能为空：新用户，或结算失败且从未建档）───────────────────────────────
        Optional<UserGrowth> profileOpt = userGrowthRepository.findById(userId);

        // ── ③ 实时聚合三项累计统计（需求 7.9 不物化；7.13 合计 >500ms 记 WARN 但不失败）──────
        CumulativeStats stats = aggregateStats(userId);

        // ── ④ 按判定日校正当前连续天数（需求 4.11、4.15；读取侧实时判定，不写库）──────────────
        LocalDate judgmentDay = LocalDate.now(clock);
        int currentStreakDays = correctedCurrentStreak(profileOpt.orElse(null), judgmentDay);

        // ── ⑤ 组装 9 枚徽章（需求 8.5、8.6、8.12、8.13）──────────────────────────────────
        List<BadgeView> badges = assembleBadges(userId, profileOpt.orElse(null), stats.recordCount());

        // ── 等级换算的六个派生字段（需求 2.8、2.9、2.10）；无档案降级为 Lv1 / 0 经验（需求 9.11）──
        int level = profileOpt.map(UserGrowth::getLevel).orElse(1);
        long exp = profileOpt.map(UserGrowth::getExp).orElse(0L);
        boolean maxLevelReached = level >= GrowthLevelCurve.MAX_LEVEL;

        long currentLevelExp = levelCurve.threshold(level);
        Long nextLevelExp = maxLevelReached ? null : levelCurve.threshold(level + 1);
        long expInCurrentLevel = exp - currentLevelExp;                 // 需求 2.10：≥ 0
        Long expToNextLevel = maxLevelReached ? null : nextLevelExp - exp; // 需求 2.10：未满级 ≥ 1

        int totalRecordDays = profileOpt.map(UserGrowth::getTotalRecordDays).orElse(0);
        int maxStreakDays = profileOpt.map(UserGrowth::getMaxStreakDays).orElse(0);

        return new GrowthOverviewResponse(
                level, exp, currentLevelExp, nextLevelExp,
                expInCurrentLevel, expToNextLevel, GrowthLevelCurve.MAX_LEVEL,
                maxLevelReached, stats.recordCount(),
                stats.totalExpense(), stats.totalIncome(),
                totalRecordDays, currentStreakDays, maxStreakDays,
                badges);
    }

    /**
     * 实时聚合累计记账笔数、累计支出金额与累计收入金额（需求 7.1、7.2、7.3、7.9）。
     *
     * <p>三项都直接从交易事实源查，<b>不读档案、不物化</b>，因此在结算失败或无档案的降级路径下
     * 依然是真实值（需求 9.11）。金额一律 {@link BigDecimal} 参与，无浮点（需求 7.11）。</p>
     *
     * <p>三步处理逐项写死（需求 7.3、7.14、7.15）：{@code setScale(2, HALF_UP)} → 负值以 {@code 0.00}
     * 返回 → 超上界钳到 {@code 9999999999999999.99} 并记 WARN；无匹配行时为 {@code 0.00}。
     * 合计耗时 &gt;500ms 记一条 WARN，但三种情形都<b>不使本次请求失败</b>（需求 7.13）。</p>
     */
    private CumulativeStats aggregateStats(Long userId) {
        long startNanos = System.nanoTime();

        long recordCount = transactionRepository.countValidRecordsByCreatedBy(userId);
        List<Object[]> rows = transactionRepository.sumValidAmountsByCreatedByGroupByType(userId);

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMillis > STATS_BUDGET_MILLIS) {
            // 需求 7.13：只告警、不失败。耗时上界针对 10 万笔量级的三项聚合合计。
            log.warn("[GROWTH_STATS_SLOW] 累计统计聚合耗时 {}ms 超过上界 {}ms userId={}",
                    elapsedMillis, STATS_BUDGET_MILLIS, userId);
        }

        BigDecimal rawExpense = BigDecimal.ZERO;
        BigDecimal rawIncome = BigDecimal.ZERO;
        for (Object[] row : rows) {                                     // 至多两行：[type, sum]
            String type = String.valueOf(row[0]);
            BigDecimal sum = toBigDecimal(row[1]);
            if (TYPE_EXPENSE.equals(type)) {
                rawExpense = sum;
            } else if (TYPE_INCOME.equals(type)) {
                rawIncome = sum;
            }
        }

        return new CumulativeStats(recordCount, clampAmount(rawExpense, userId, TYPE_EXPENSE),
                clampAmount(rawIncome, userId, TYPE_INCOME));
    }

    /**
     * 金额归一化：保留 2 位（{@code HALF_UP}）、负值以 {@code 0.00} 返回、超上界钳到上界并记 WARN
     * （需求 7.14、7.15）。任何一步都不使请求失败。
     */
    private BigDecimal clampAmount(BigDecimal amount, Long userId, String type) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        if (scaled.signum() < 0) {
            // 需求 7.15：历史脏数据（amount < 0.01 等）导致某项合计为负时，以 0.00 返回，不取绝对值。
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (scaled.compareTo(AMOUNT_UPPER_BOUND) > 0) {
            // 需求 7.14：超过 DECIMAL(18,2) 上界时钳到上界并告警，不返回回绕值、不失败。
            log.warn("[GROWTH_AMOUNT_CLAMPED] 累计{}金额 {} 超过上界 {}，已钳到上界 userId={}",
                    type, scaled.toPlainString(), AMOUNT_UPPER_BOUND.toPlainString(), userId);
            return AMOUNT_UPPER_BOUND;
        }
        return scaled;
    }

    /**
     * 按判定日校正当前连续天数（需求 4.11、4.15）。
     *
     * <p>{@code last_record_date} 等于判定日或判定日前一日时返回物化的 {@code current_streak_days}，
     * 否则返回 0（连续已中断）。物化列只承载连续段长度，「连续是否已中断」一律在读取侧按判定日实时判定，
     * 因此跨日后或结算失败时都不会返回过期的非零连续天数。<b>本方法只读，不写档案。</b></p>
     *
     * @param profile     成长档案，可为 {@code null}（无档案时返回 0）
     * @param judgmentDay 判定日（生成响应时刻所在的 {@code Asia/Shanghai} 自然日）
     * @return 校正后的当前连续天数，≥ 0
     */
    private int correctedCurrentStreak(UserGrowth profile, LocalDate judgmentDay) {
        if (profile == null) {
            return 0;
        }
        LocalDate lastRecordDate = profile.getLastRecordDate();
        if (lastRecordDate == null) {
            return 0;
        }
        if (lastRecordDate.equals(judgmentDay) || lastRecordDate.equals(judgmentDay.minusDays(1))) {
            return profile.getCurrentStreakDays();
        }
        return 0;
    }

    /**
     * 组装 9 枚徽章视图，顺序与 {@link GrowthBadgeCatalog#badges()} 一致（需求 8.5、8.8）。
     *
     * <p>已点亮的唯一依据是存在对应的 {@code BADGE:<编码>} 行（需求 8.4、8.11）：解锁时刻取该行的
     * {@code created_at}（需求 8.6），当前值恒等于目标值（需求 8.12）。未点亮时解锁时刻为空、当前值取
     * 「统计量当前取值」与目标值的较小者，落在 {@code [0, target]}（需求 8.12）。<b>条件已成立但徽章事件
     * 尚未写入</b>（结算被节流或失败留下的间隙）时仍返回未点亮 + 空解锁时刻，而当前值因统计量已达门槛而
     * 等于目标值，且不报错，由下一次成功结算自愈（需求 8.13）。</p>
     */
    private List<BadgeView> assembleBadges(Long userId, UserGrowth profile, long recordCount) {
        // 已点亮徽章：BADGE 行的 event_key 去掉前缀即编码，created_at 即解锁时刻（需求 8.6）。
        Map<String, LocalDateTime> unlockedAtByCode = new HashMap<>();
        for (GrowthEvent badgeEvent : growthEventRepository.findBadgeEvents(userId)) {
            String code = badgeEvent.getEventKey().substring(GrowthBadgeCatalog.BADGE_KEY_PREFIX.length());
            unlockedAtByCode.put(code, badgeEvent.getCreatedAt());
        }

        // 徽章「当前值」所需的统计事实：笔数取实时聚合值；连续/累计天数取档案物化列；
        // 两个存在型口径看经验事件键（BUDGET_MET: 前缀行、FIRST_INVITE 行），与 BADGE 行双向隔离（需求 8.11）。
        Set<String> eventKeys = new HashSet<>(growthEventRepository.findEventKeysByUserId(userId));
        boolean budgetMetEvent = eventKeys.stream().anyMatch(key -> key.startsWith(BUDGET_MET_PREFIX));
        boolean firstInviteEvent = eventKeys.contains(FIRST_INVITE_KEY);
        int maxStreakDays = (profile == null) ? 0 : profile.getMaxStreakDays();
        int totalRecordDays = (profile == null) ? 0 : profile.getTotalRecordDays();
        GrowthFacts facts = new GrowthFacts(recordCount, maxStreakDays, totalRecordDays,
                budgetMetEvent, firstInviteEvent);

        List<BadgeDef> defs = badgeCatalog.badges();
        List<BadgeView> views = new ArrayList<>(defs.size());
        for (BadgeDef def : defs) {
            boolean unlocked = unlockedAtByCode.containsKey(def.code());
            LocalDateTime unlockedAt = unlocked ? unlockedAtByCode.get(def.code()) : null;
            int current = badgeCatalog.currentOf(def, facts, unlocked);
            views.add(new BadgeView(def.code(), def.name(), unlocked, unlockedAt, def.target(), current));
        }
        return views;
    }

    /** 把原生查询返回的数值列稳妥转成 {@link BigDecimal}（{@code SUM(DECIMAL)} 通常已是 {@code BigDecimal}）。 */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(value.toString());
    }

    /**
     * 经验明细分页：自行解析原文分页参数 → 校验 → 按 {@code id} 倒序翻页并返回真实总条数。
     *
     * <p><b>本方法不触发结算</b>（需求 10.11）：明细只读事件表，允许比概览旧（两接口经验值合计可不相等），
     * 不因该差异报错、不写任何表。标注 {@code @Transactional(readOnly = true)} 只为让「取当页 +
     * 取总条数」两次查询处在同一只读事务里，读到一致的快照。</p>
     *
     * <p>参数以原文 {@link String} 接收、在方法体内自行解析（需求 10.9）：无法解析为整数、{@code page < 0}、
     * {@code page > 100000}、{@code size < 1}、{@code size > 50} 一律抛 {@code GROWTH_PAGE_PARAM_INVALID}
     * 并置 {@code field} 为越界的参数名；{@code page} / {@code size} 缺省（{@code null} 或空白）时分别取
     * 0 与 20。控制器刻意用 {@code String} 接收而非交给框架做类型转换，避免非数字取值在进入方法体前
     * 抛出另一套错误码。</p>
     *
     * <p>页码越界（{@code page × size} 已越过全部数据）时返回空列表 + 真实总条数，不报错（需求 10.10）。
     * 总条数由 {@link org.springframework.data.domain.Page#getTotalElements()} 提供，不受分页影响
     * （需求 10.5）。</p>
     *
     * @param userId  令牌所标识的用户 id（调用方已确认其在 {@code users} 表中仍存在）
     * @param rawPage 原文页码，可为 {@code null} / 空白（取缺省 0）
     * @param rawSize 原文每页条数，可为 {@code null} / 空白（取缺省 20）
     * @return 经验明细分页响应，顶层恰好 2 项（列表项 + 总条数，需求 10.13）
     */
    @Transactional(readOnly = true)
    public GrowthEventPageResponse listEvents(Long userId, String rawPage, String rawSize) {
        int page = parseInRange(rawPage, DEFAULT_PAGE, PAGE_MIN, PAGE_MAX, "page");
        int size = parseInRange(rawSize, DEFAULT_SIZE, SIZE_MIN, SIZE_MAX, "size");

        // 排序键固定在仓库方法名里（id 倒序），Pageable 只承载页码与每页条数（需求 10.3、10.5）。
        Page<GrowthEvent> events =
                growthEventRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(page, size));

        // 页码越界时 events.getContent() 为空列表，getTotalElements() 仍是真实总条数（需求 10.10）。
        List<GrowthEventItem> items = new ArrayList<>(events.getNumberOfElements());
        for (GrowthEvent e : events.getContent()) {
            items.add(new GrowthEventItem(e.getId(), e.getEventType(), e.getEventKey(),
                    e.getExpAmount(), e.getCreatedAt()));
        }
        return new GrowthEventPageResponse(items, events.getTotalElements());
    }

    /**
     * 解析原文分页参数并做范围校验（需求 10.9）。
     *
     * <p>{@code null} 或空白取 {@code defaultValue}；否则去空白后以 {@link Integer#parseInt} 解析，
     * 无法解析或落在 {@code [min, max]} 之外一律抛 {@code GROWTH_PAGE_PARAM_INVALID} 并置 {@code field}。</p>
     */
    private static int parseInRange(String raw, int defaultValue, int min, int max, String field) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.growthPageParamInvalid(field);
        }
        if (value < min || value > max) {
            throw ApiException.growthPageParamInvalid(field);
        }
        return value;
    }

    /** 三项累计统计的载体（需求 7.1、7.2、7.3）：笔数与两项已归一化的金额。 */
    private record CumulativeStats(long recordCount, BigDecimal totalExpense, BigDecimal totalIncome) {
    }
}
