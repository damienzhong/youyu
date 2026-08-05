package com.damien.youyu.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;

/**
 * 读取侧的<b>统一事实快照</b>：一处求值八个统计口径 + 一份「已解锁成就编码 → {@code BADGE} 事件行」映射
 * （需求 3.1~3.14、3.16、1.12）。
 *
 * <h2>为什么必须只有这一个入口</h2>
 *
 * <p><b>本服务是需求 12.3「成长概览的徽章列表与成就清单逐项相等」构造性成立的唯一基础。</b>
 * 需求 12.3 要求两条读取路径（{@code GrowthQueryService.assembleBadges} 与
 * {@code AchievementQueryService.getAchievements}）返回的第 N 项在编码、名称、是否已解锁、
 * 解锁时刻、门槛与当前值六项上逐项相等。做法不是「写两份实现再靠测试比对」，而是让两条路径
 * <b>都调用本服务的同一个 {@link #snapshot(Long)}、再配同一份 {@link GrowthBadgeCatalog}</b>，
 * 两个 DTO（{@code BadgeView} 6 字段 / {@code AchievementView} 9 字段）只是同一份快照的两种投影。</p>
 *
 * <p>因此<b>任何绕过本服务自行组装 {@link GrowthFacts} 或自行读 {@code BADGE} 行的代码都是缺陷</b>：
 * 那等于把一条构造性不变式退化成「靠测试凑巧对上」——两份实现只要有一处过滤条件、一处钳制规则、
 * 一次取值时刻不同，两个接口就会在某个用户身上给出不同的进度，而这种漂移只在解锁那一刻才暴露。</p>
 *
 * <h2>每个口径只求值一次</h2>
 *
 * <p>{@link GrowthFacts} 的八个分量在本方法内一次求全（需求 3.16），随后本次请求内全部依赖这些口径的
 * 成就共用同一份取值。八个口径合计只发 <b>5 条</b>读查询：</p>
 *
 * <ol>
 *   <li>{@code countValidRecordsByCreatedBy} → {@link BadgeMetric#RECORD_COUNT}（需求 3.1）；</li>
 *   <li>{@code user_growth} 单行 → {@link BadgeMetric#MAX_STREAK} 与 {@link BadgeMetric#TOTAL_DAYS}
 *       两个口径直接取物化列，<b>不读 {@code transactions} 重算</b>（需求 3.1、3.2）；无档案时取 0（需求 3.13）；</li>
 *   <li>{@code findEventKeysByUserId} → {@link BadgeMetric#BUDGET_MET_COUNT}、
 *       {@link BadgeMetric#SAVING_MONTH_COUNT}、{@link BadgeMetric#FIRST_INVITE_EVENT}
 *       三个口径由<b>前缀计数</b>得出，零新增查询（需求 3.6、3.7、3.8）——结算侧的既有查询已把该用户
 *       全部事件键一次读完，这里沿用同一条；</li>
 *   <li>{@code countEditorsOfOwnedLedgers} → {@link BadgeMetric#COLLAB_MEMBER_COUNT}（需求 3.3、3.4）；</li>
 *   <li>{@code countTravelExpenses} → {@link BadgeMetric#TRAVEL_RECORD_COUNT}（需求 3.9、3.10、3.11）。</li>
 * </ol>
 *
 * <p>三个基于事件键的口径一律<b>只数经验事件行、不数 {@code BADGE} 行</b>（需求 3.6、3.7、3.8 的反向隔离）：
 * 前缀 {@code BUDGET_MET:} / {@code SAVING_MONTH:} 与裸键 {@code FIRST_INVITE} 都与 {@code BADGE:} 前缀
 * 天然互斥，故前缀计数不会把 {@code BADGE:BUDGET_MET}、{@code BADGE:SAVING_MASTER}、
 * {@code BADGE:INVITE_1} 误计进去。</p>
 *
 * <h2>降级只属于查询路径</h2>
 *
 * <p>某个口径的查询抛异常时：该口径本次取 0、记一条含用户 id 与该口径枚举取值的 WARN、
 * 其余口径照常返回、<b>不向上抛</b>（需求 3.14、6.7）。<b>这条降级只用于查询路径。</b>
 * 结算路径（{@code GrowthSettlementService.settle}）刻意<b>不</b>调用本服务、也不做此降级：
 * 那里异常必须穿出以回滚它的 {@code REQUIRES_NEW} 事务（需求 4.14），
 * 若在结算里吞掉异常，Spring 会照常提交一个可能已部分写入或已被标记 rollback-only 的事务。
 * 一句话分工：<b>读可以给出略残缺的取值，写必须要么全成要么全滚。</b></p>
 *
 * <p>本服务因此<b>刻意不加 {@code @Transactional}</b>：逐查询 catch 必须发生在事务边界之外——
 * 在同一个事务里 catch 掉一条已失败的语句，事务照样会被标记为 rollback-only，
 * 于是「其余口径照常返回」在提交那一刻仍会失败。</p>
 *
 * <h2>未知 {@code BADGE} 行</h2>
 *
 * <p>清单是权威（需求 1.12）：库里若出现 {@code BADGE:<不在 16 项清单内的编码>} 的行
 * （例如清单历史上误删过某个编码），本服务一律<b>忽略该行</b>、记一条含用户 id 与该 {@code event_key}
 * 的 WARN、不报错、<b>不改动该行</b>（{@code growth_events} 是只追加表，需求 12.7）。
 * 投影出的列表项数因此恒为清单项数，不会因库里的意外行多出一项。</p>
 */
@Service
public class AchievementSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AchievementSnapshotService.class);

    /** {@code BUDGET_MET} 经验事件键前缀（需求 3.6）：{@code BUDGET_MET:yyyy-MM}。 */
    private static final String BUDGET_MET_PREFIX = "BUDGET_MET:";

    /** {@code SAVING_MONTH} 事件键前缀（需求 3.7）：{@code SAVING_MONTH:yyyy-MM}。 */
    private static final String SAVING_MONTH_PREFIX = "SAVING_MONTH:";

    /** 首次邀请事件键（需求 3.8）：存在型口径，取值只有 0 / 1。 */
    private static final String FIRST_INVITE_KEY = "FIRST_INVITE";

    private final GrowthEventRepository growthEventRepository;
    private final UserGrowthRepository userGrowthRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerMemberRepository ledgerMemberRepository;
    private final GrowthBadgeCatalog badgeCatalog;

    public AchievementSnapshotService(GrowthEventRepository growthEventRepository,
                                     UserGrowthRepository userGrowthRepository,
                                     TransactionRepository transactionRepository,
                                     LedgerMemberRepository ledgerMemberRepository,
                                     GrowthBadgeCatalog badgeCatalog) {
        this.growthEventRepository = growthEventRepository;
        this.userGrowthRepository = userGrowthRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerMemberRepository = ledgerMemberRepository;
        this.badgeCatalog = badgeCatalog;
    }

    /**
     * 求值该用户的八个统计口径与已解锁成就映射，一次求全（需求 3.16）。
     *
     * <p>每个口径只求值一次；任一口径的查询抛异常时该口径取 0 并记 WARN，其余口径照常返回、不抛出
     * （需求 3.14；<b>仅查询路径</b>，见类级说明）。全部数量型口径以 64 位整型承载、取值落在
     * {@code [0, Long.MAX_VALUE]}，查询无结果或该用户尚无 {@code user_growth} 行时按 0 计（需求 3.13）。</p>
     *
     * <p><b>本方法只读</b>：不写 {@code growth_events}、{@code user_growth} 与
     * {@code ledger_members} / {@code ledgers} / {@code categories} / {@code transactions} 任何一表
     * （需求 3.5）。是否触发结算由调用方决定（概览与成就清单在调用本方法<b>之前</b>各自尝试结算）。</p>
     *
     * @param userId 令牌所标识的用户 id（调用方已确认其在 {@code users} 表中仍存在）
     * @return 事实快照 + 已解锁映射；调用方据此配 {@link GrowthBadgeCatalog#badges()} 投影成响应
     */
    public AchievementSnapshot snapshot(Long userId) {
        // ── 事件键：一条查询喂三个口径（需求 3.6、3.7、3.8），零新增查询 ───────────────────
        Set<String> eventKeys = readOrDegrade(userId, Set.<String>of(),
                () -> new HashSet<>(growthEventRepository.findEventKeysByUserId(userId)),
                BadgeMetric.BUDGET_MET_COUNT, BadgeMetric.SAVING_MONTH_COUNT, BadgeMetric.FIRST_INVITE_EVENT);

        long budgetMetCount = countPrefix(eventKeys, BUDGET_MET_PREFIX);
        long savingMonthCount = countPrefix(eventKeys, SAVING_MONTH_PREFIX);
        boolean firstInviteEvent = eventKeys.contains(FIRST_INVITE_KEY);

        // ── 累计记账笔数：与成长概览同一条既有查询，故两处口径逐例相等（需求 3.1）─────────────
        long recordCount = readOrDegrade(userId, 0L,
                () -> transactionRepository.countValidRecordsByCreatedBy(userId),
                BadgeMetric.RECORD_COUNT);

        // ── 连续 / 累计天数：取 user_growth 的物化列，不读 transactions 重算（需求 3.1、3.2）──
        // 无档案（新用户、或结算失败且从未建档）时两个口径均取 0（需求 3.13）。
        Optional<UserGrowth> profile = readOrDegrade(userId, Optional.<UserGrowth>empty(),
                () -> userGrowthRepository.findById(userId),
                BadgeMetric.MAX_STREAK, BadgeMetric.TOTAL_DAYS);
        int maxStreakDays = profile.map(UserGrowth::getMaxStreakDays).orElse(0);
        int totalRecordDays = profile.map(UserGrowth::getTotalRecordDays).orElse(0);

        // ── 两条新增聚合（需求 3.3、3.4 / 3.9、3.10、3.11）：各自独立降级，互不牵连 ──────────
        long collabMemberCount = readOrDegrade(userId, 0L,
                () -> ledgerMemberRepository.countEditorsOfOwnedLedgers(userId),
                BadgeMetric.COLLAB_MEMBER_COUNT);
        long travelRecordCount = readOrDegrade(userId, 0L,
                () -> transactionRepository.countTravelExpenses(userId),
                BadgeMetric.TRAVEL_RECORD_COUNT);

        GrowthFacts facts = new GrowthFacts(recordCount, maxStreakDays, totalRecordDays,
                budgetMetCount, firstInviteEvent, savingMonthCount, collabMemberCount, travelRecordCount);

        return new AchievementSnapshot(facts, unlockedByCode(userId));
    }

    /**
     * 已解锁映射：{@code BADGE} 行的 {@code event_key} 去掉 {@code BADGE:} 前缀即成就编码，
     * value 是<b>整行</b>（同时承载解锁时刻 {@code created_at} 与成就事件 {@code id}，需求 6.3、5.4）。
     *
     * <p>解锁与否的唯一依据是「存在对应的 {@code BADGE:<编码>} 行」（需求 2.3、2.10）：
     * 裸键的经验事件（{@code FIRST_RECORD} / {@code STREAK_7} / {@code STREAK_30} / {@code BUDGET_MET}）
     * 不参与该判定，查询条件本身已把它们排除在外。</p>
     *
     * <p>不在清单内的编码（含缺失前缀的畸形键）一律忽略并记 WARN，不报错、不改动该行（需求 1.12）。</p>
     */
    private Map<String, GrowthEvent> unlockedByCode(Long userId) {
        List<GrowthEvent> badgeEvents;
        try {
            badgeEvents = growthEventRepository.findBadgeEvents(userId);
        } catch (Exception e) {
            // 与口径降级同一取舍（需求 6.7）：查询路径下不对外暴露服务端错误码，本次按「尚无已解锁行」
            // 返回——16 项照常返回、全部未解锁、解锁时刻与事件 id 为空值，与需求 1.11 的间隙态形状一致。
            log.warn("[ACHIEVEMENT_UNLOCKED_DEGRADED] 已解锁成就查询失败，本次按无已解锁行返回 userId={}", userId, e);
            return Map.of();
        }
        if (badgeEvents == null || badgeEvents.isEmpty()) {
            return Map.of();
        }

        Set<String> knownCodes = new LinkedHashSet<>();
        for (BadgeDef def : badgeCatalog.badges()) {
            knownCodes.add(def.code());
        }

        Map<String, GrowthEvent> unlocked = new HashMap<>();
        for (GrowthEvent event : badgeEvents) {
            String key = event.getEventKey();
            String code = (key == null || !key.startsWith(GrowthBadgeCatalog.BADGE_KEY_PREFIX))
                    ? null
                    : key.substring(GrowthBadgeCatalog.BADGE_KEY_PREFIX.length());
            if (code == null || !knownCodes.contains(code)) {
                // 需求 1.12：清单是权威。忽略该行、记一条含用户 id 与 event_key 的 WARN、不报错、不改行。
                log.warn("[ACHIEVEMENT_UNKNOWN_BADGE] 成就事件的编码不在清单内，已忽略该行 userId={} eventKey={}",
                        userId, key);
                continue;
            }
            // uk_growth_events_user_key 保证同一编码至多一行；万一有历史重复，固定取 id 较小者，
            // 使解锁时刻与成就事件 id 的取值不随查询返回顺序漂移。
            unlocked.merge(code, event, (kept, other) -> idOf(kept) <= idOf(other) ? kept : other);
        }
        return Map.copyOf(unlocked);
    }

    /** {@code null} 安全的事件 id 取值，只用于上面的择一比较（未落库的行不应出现在这里）。 */
    private static long idOf(GrowthEvent event) {
        return event.getId() == null ? Long.MAX_VALUE : event.getId();
    }

    /**
     * 前缀计数（需求 3.6、3.7）：事件键集合里以 {@code prefix} 开头的键个数。
     *
     * <p>{@code BUDGET_MET:} / {@code SAVING_MONTH:} 与 {@code BADGE:} 前缀互斥，因此按前缀计数
     * 天然把 {@code BADGE} 行排除在外，无需额外过滤（需求 3.6、3.7 的反向隔离）。</p>
     */
    private static long countPrefix(Set<String> keys, String prefix) {
        long count = 0L;
        for (String key : keys) {
            if (key != null && key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 单个查询的降级包装（需求 3.14）：抛异常时返回 {@code fallback}、记一条含用户 id 与受影响口径
     * 枚举取值的 WARN、不向上抛，其余口径照常求值。
     *
     * <p><b>只用于查询路径</b>（见类级说明）；结算路径不经过本服务，那里异常必须穿出以回滚事务。
     * 捕获 {@link Exception} 而不是只捕获 {@code DataAccessException}：这里要保证的是
     * 「成就清单接口在任何单点故障下都仍返回完整字段集」，把范围收窄只会让某类异常仍旧穿到控制器
     * 变成一个服务端错误码（需求 6.7 明确禁止）。{@link Error} 不在捕获范围内，
     * 那类问题不该被降级掩盖。</p>
     *
     * @param fallback 降级取值：数量型口径传 {@code 0L}，集合型传空集合（对应需求 3.13 的「按 0 计」）
     * @param metrics  受该查询影响的口径，日志里逐一列出；一条查询可同时喂多个口径
     */
    private <T> T readOrDegrade(Long userId, T fallback, Supplier<T> reader, BadgeMetric... metrics) {
        try {
            T value = reader.get();
            return (value == null) ? fallback : value;
        } catch (Exception e) {
            List<BadgeMetric> affected = new ArrayList<>(List.of(metrics));
            log.warn("[ACHIEVEMENT_METRIC_DEGRADED] 统计口径查询失败，本次以 0 计 userId={} metrics={}",
                    userId, affected, e);
            return fallback;
        }
    }

    /**
     * 一次读取所得的完整事实快照：八个统计口径 + 已解锁成就映射。
     *
     * <p>纯值载体，两条读取路径（概览徽章、成就清单）各自把它投影成自己的 DTO，
     * 因此需求 12.3 的逐项相等是<b>构造性</b>成立的，见 {@link AchievementSnapshotService} 类级说明。</p>
     *
     * @param facts           八个统计口径的取值，每个口径本次只求值一次（需求 3.16）
     * @param unlockedByCode  已解锁成就编码 → 对应 {@code BADGE} 事件行（承载解锁时刻与事件 id）；
     *                        不可修改，只含清单内的编码（需求 1.12）
     */
    public record AchievementSnapshot(GrowthFacts facts, Map<String, GrowthEvent> unlockedByCode) {

        /** 该编码是否已解锁：唯一依据是存在对应的 {@code BADGE:<编码>} 行（需求 2.3、2.10）。 */
        public boolean unlocked(String code) {
            return unlockedByCode.containsKey(code);
        }

        /** 该编码的 {@code BADGE} 事件行；未解锁时为 {@code null}（需求 2.13：解锁时刻与事件 id 均为空值）。 */
        public GrowthEvent eventOf(String code) {
            return unlockedByCode.get(code);
        }
    }
}
