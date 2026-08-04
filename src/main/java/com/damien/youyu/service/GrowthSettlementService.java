package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;

/**
 * 成长结算核心服务：全 spec <b>唯一</b>写 {@code growth_events} / {@code user_growth} 的地方。
 *
 * <p>{@link #settle(Long, TriggerSource)} 编排一次幂等结算：节流判定（事务外）→ 建档并加行级写锁 →
 * 读事实源 → 批量补写缺失事件 → 全量重算并写回物化列。整个写入路径在
 * {@code @Transactional(REQUIRES_NEW)} 的独立事务里完成。</p>
 *
 * <h2>两条不可动的事务约束</h2>
 *
 * <ol>
 *   <li><b>本方法刻意不捕获任何异常。</b>{@code REQUIRES_NEW} 的语义是——只有当异常<b>穿出</b>被通知
 *       方法时，Spring 事务切面才会回滚这次独立事务。若在方法体内 {@code catch} 掉数据库异常并正常
 *       返回，Spring 会照常提交，而底层连接可能已被标记为 rollback-only、或已产生部分写入，于是
 *       需求 9.7「结算失败不产生部分写入」被破坏。因此「吞异常只记 WARN」这件事必须发生在事务边界
 *       <b>之外</b>：{@code GrowthSettlementTrigger.settleQuietly}（记账/导入路径）与
 *       {@code GrowthQueryService.getOverview}（概览路径）。本方法内出现任何 {@code catch} 都是缺陷。</li>
 *   <li><b>禁止把 {@code REQUIRES_NEW} 改成 {@code REQUIRED}。</b>结算必须与业务事务相互独立：记账
 *       事务提交后才在 {@code afterCommit} 回调里开这次结算事务，结算若回滚也只回滚成长数据。改成
 *       {@code REQUIRED} 会让结算并入记账事务，结算的任何回滚将<b>连坐已提交的记账与余额变更</b>
 *       （需求 9.3、9.7）。</li>
 * </ol>
 *
 * <h2>只忽略重复键，绝不吞 CHECK 违例</h2>
 *
 * <p>批量插入用 {@code INSERT ... ON DUPLICATE KEY UPDATE id = id}（见 {@link #INSERT_EVENT_SQL}）：
 * 唯一键冲突时退化为无副作用的自更新（幂等由 {@code uk_growth_events_user_key} 承担，需求 1.5），
 * 但 CHECK 违例、非空违例、超长截断照样抛异常使整个结算回滚。<b>不得改用 {@code INSERT IGNORE}</b>
 * ——它会把这些错误一并静默降级为警告，让脏数据落库。同理结算路径<b>不应存在任何
 * {@code catch DataIntegrityViolationException}</b>：ODKU 之下重复键根本不抛异常，出现这个 catch
 * 就说明有人想吞掉 CHECK 违例。也<b>不采用</b> invite-system 的 JDBC 保存点方案——那里的插入在登录
 * 事务内、冲突绝不允许连坐已提交的注册数据，故用保存点把单条冲突局部化；这里的插入在结算<b>自己的
 * 独立事务</b>内，整体回滚 + 下次结算自愈是完全可接受的失败模式，无需保存点这层复杂度。</p>
 *
 * <p><b>时钟统一</b>：一律用注入的 {@link Clock}（{@code TimeConfig} 提供，固定 {@code Asia/Shanghai}），
 * <b>不得</b>改用 {@code System.currentTimeMillis()} 或 {@code LocalDateTime.now()} 无参重载。单次结算
 * <b>只读一次时钟</b>，同一个 {@code now} 同时用于事件 {@code created_at}、档案 {@code updated_at} 与
 * {@code last_settled_at}。</p>
 */
@Service
public class GrowthSettlementService {

    /**
     * 单次结算获取行级写锁的墙钟预算：500 毫秒（需求 9.16）。
     *
     * <p>为什么是「应用层墙钟」而不是交给数据库：见 {@link #lockProfileWithBudget} 的 Javadoc
     * 与 {@link UserGrowthRepository#findForUpdateById} 的说明——MySQL 的
     * {@code innodb_lock_wait_timeout} 最小粒度是 1 秒，无法表达 500ms。</p>
     */
    static final long LOCK_BUDGET_MILLIS = 500L;

    /** 退避重试的最大次数（首次尝试之外再试至多 3 次，退避 20 / 40 / 80ms）。 */
    static final int MAX_LOCK_RETRIES = 3;

    /** 首次退避基准毫秒数（后续按 2 的幂递增：20 / 40 / 80）。 */
    static final long BACKOFF_BASE_MILLIS = 20L;

    /** 记账侧结算节流窗口：60 秒（需求 9.15）。判定条件另含「{@code last_record_date} 已等于结算日」。 */
    static final long RECORD_THROTTLE_SECONDS = 60L;

    /** {@code DAILY_RECORD} 事件键前缀（需求 3.7）。 */
    private static final String DAILY_RECORD_PREFIX = "DAILY_RECORD:";

    /** {@code BUDGET_MET} 事件键前缀（需求 3.7）。 */
    private static final String BUDGET_MET_PREFIX = "BUDGET_MET:";

    /** {@code FIRST_RECORD} / {@code FIRST_INVITE} 事件键（即其类型名）。 */
    private static final String FIRST_RECORD_KEY = "FIRST_RECORD";
    private static final String FIRST_INVITE_KEY = "FIRST_INVITE";
    private static final String STREAK_7_KEY = "STREAK_7";
    private static final String STREAK_30_KEY = "STREAK_30";

    /**
     * 单次结算写入事件的硬上界（需求 3.10）：≤1000 条 {@code DAILY_RECORD} + 1 {@code FIRST_RECORD}
     * + 2 {@code STREAK} + 3 {@code BUDGET_MET} + 1 {@code FIRST_INVITE} + 9 {@code BADGE} = 1016。
     */
    static final int MAX_PENDING_EVENTS = 1016;

    /**
     * 建档语句：并发安全地确保档案行存在（需求 1.10）。
     *
     * <p>走 {@code ON DUPLICATE KEY UPDATE user_id = user_id} 而不是 {@code save()}：{@link UserGrowth}
     * 的 {@code @Id} 由应用赋值且刻意不带 {@code @GeneratedValue}，{@code save()} 会走 merge 语义先发一次
     * 探测 {@code SELECT}；而两个请求同时给同一用户建档时，ODKU 让后者退化为无副作用的自更新，
     * 天然免疫唯一键冲突。{@code exp/level/...} 全给初始值，冲突时不改任何列（自更新 {@code user_id}）。</p>
     */
    private static final String INSERT_PROFILE_SQL =
            "INSERT INTO user_growth "
                    + "(user_id, exp, level, total_record_days, current_streak_days, max_streak_days, "
                    + "last_record_date, last_settled_at, created_at, updated_at) "
                    + "VALUES (?, 0, 1, 0, 0, 0, NULL, NULL, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE user_id = user_id";

    /**
     * 事件批量插入语句：只忽略重复键（见类级 Javadoc「只忽略重复键，绝不吞 CHECK 违例」）。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id = id} 是无副作用的自更新，唯一键冲突时不新增行、不改
     * {@code id}、不抛异常（需求 1.6）；但 CHECK / 非空 / 超长等违例照样抛异常使结算整体回滚。
     * <b>绝不能改成 {@code INSERT IGNORE}</b>。</p>
     */
    private static final String INSERT_EVENT_SQL =
            "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                    + "VALUES (?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE id = id";

    private final UserGrowthRepository userGrowthRepository;
    private final GrowthEventRepository growthEventRepository;
    private final TransactionRepository transactionRepository;
    private final InviteRelationRepository inviteRelationRepository;
    private final GrowthCalendarService calendarService;
    private final GrowthBudgetEvaluator budgetEvaluator;
    private final GrowthBadgeCatalog badgeCatalog;
    private final GrowthLevelCurve levelCurve;
    private final GrowthSettlementThrottle throttle;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public GrowthSettlementService(UserGrowthRepository userGrowthRepository,
                                   GrowthEventRepository growthEventRepository,
                                   TransactionRepository transactionRepository,
                                   InviteRelationRepository inviteRelationRepository,
                                   GrowthCalendarService calendarService,
                                   GrowthBudgetEvaluator budgetEvaluator,
                                   GrowthBadgeCatalog badgeCatalog,
                                   GrowthLevelCurve levelCurve,
                                   GrowthSettlementThrottle throttle,
                                   JdbcTemplate jdbcTemplate,
                                   Clock clock) {
        this.userGrowthRepository = userGrowthRepository;
        this.growthEventRepository = growthEventRepository;
        this.transactionRepository = transactionRepository;
        this.inviteRelationRepository = inviteRelationRepository;
        this.calendarService = calendarService;
        this.budgetEvaluator = budgetEvaluator;
        this.badgeCatalog = badgeCatalog;
        this.levelCurve = levelCurve;
        this.throttle = throttle;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * 执行一次幂等结算（需求 1、3、4、5、6、8 的写入侧，需求 9.15、10.14 的节流）。
     *
     * <p>流程：① 节流判定（事务外，命中即 {@link SettleOutcome#SKIPPED_THROTTLED} 直接返回，
     * 不写任何行）→ ② 建档 + 加行级写锁 → ③ 读事实源（全部只读）→ ④ 固定顺序组装待写事件 →
     * ⑤ 批量插入（只忽略重复键）→ ⑥ 全量重算写回物化列与经验/等级。</p>
     *
     * <p>本方法带 {@code @Transactional(REQUIRES_NEW)}，两条禁令见类级 Javadoc（不 catch、不改 REQUIRED）。</p>
     *
     * @param userId 结算用户 id（等于令牌用户 id / {@code users.id}）
     * @param source 触发来源，决定走哪套节流（见 {@link TriggerSource}）
     * @return {@link SettleOutcome#SETTLED} 或 {@link SettleOutcome#SKIPPED_THROTTLED}
     * @throws GrowthLockAbandonedException 500ms 内未取得行级写锁（穿出以回滚事务、边界外吞掉）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettleOutcome settle(Long userId, TriggerSource source) {
        // 单次结算只读一次时钟：now 同时用于事件 created_at、档案 updated_at 与 last_settled_at。
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate settleDate = now.toLocalDate();

        // ── ① 节流判定（在写入任何行之前，命中即返回，不建档、不加锁、不写事件）─────────────
        // 需求 9.15 / 10.14 要求「跳过时不写入任何成长事件与成长档案列」：本分支在这一切之前返回，
        // 因此观察到的效果就是零写入、零加锁。判定刻意放在方法最前，不读写档案的任何列取值。
        if (isThrottled(userId, source, now, settleDate)) {
            return SettleOutcome.SKIPPED_THROTTLED;
        }

        // ── ② 建档并加行级写锁（需求 1.9、1.10）──────────────────────────────────────
        // 先 ODKU 保证行存在（并发建档竞态天然免疫），再走 500ms 墙钟预算的加锁读。
        jdbcTemplate.update(INSERT_PROFILE_SQL, userId, now, now);
        UserGrowth profile = lockProfileWithBudget(userId, LOCK_BUDGET_MILLIS);

        // ── ③ 读事实源（全部只读）─────────────────────────────────────────────────
        Set<String> existingKeys = new HashSet<>(growthEventRepository.findEventKeysByUserId(userId));
        long recordCount = transactionRepository.countValidRecordsByCreatedBy(userId);
        long inviteCount = inviteRelationRepository.countByInviterIdAndStatus(userId, InviteStatus.REGISTERED);
        BackfillResult backfill = calendarService.backfillDates(userId, profile.getLastRecordDate(), settleDate);
        List<String> budgetMonths = budgetEvaluator.metMonths(userId, settleDate, existingKeys);

        // 连续里程碑用「已有日历 ∪ 本次补发日期」的 maxStreak 判定（需求 3.6：跨门槛不漏发低门槛）。
        // 已有日历从 existingKeys 里筛 DAILY_RECORD: 前缀解析得出，无需额外查库。
        List<LocalDate> calendarAfterBackfill = unionDailyRecordDates(existingKeys, backfill.dates());
        CalendarScan scanForStreak = GrowthCalendarService.scan(calendarAfterBackfill);

        boolean hasBudgetMetEvent = anyKeyStartsWith(existingKeys, BUDGET_MET_PREFIX) || !budgetMonths.isEmpty();
        boolean hasFirstInviteEvent = existingKeys.contains(FIRST_INVITE_KEY) || inviteCount >= 1;
        GrowthFacts facts = new GrowthFacts(recordCount, scanForStreak.maxStreak(),
                scanForStreak.totalDays(), hasBudgetMetEvent, hasFirstInviteEvent);

        // ── ④ 固定顺序组装待写事件（便于逐条断言）：
        //     DAILY_RECORD(升序) → FIRST_RECORD → STREAK_7 → STREAK_30 → BUDGET_MET → FIRST_INVITE → BADGE
        List<Object[]> pending = new ArrayList<>();
        for (LocalDate date : backfill.dates()) {                       // ≤1000 条，日期升序（需求 4.6）
            add(pending, existingKeys, userId, GrowthEventType.DAILY_RECORD, DAILY_RECORD_PREFIX + date, 5, now);
        }
        if (recordCount >= 1) {
            add(pending, existingKeys, userId, GrowthEventType.FIRST_RECORD, FIRST_RECORD_KEY, 10, now);
        }
        // ≥30 时同次写入 STREAK_7 与 STREAK_30，两个判定各自独立、不用 else（需求 3.6）。
        if (scanForStreak.maxStreak() >= 7) {
            add(pending, existingKeys, userId, GrowthEventType.STREAK, STREAK_7_KEY, 30, now);
        }
        if (scanForStreak.maxStreak() >= 30) {
            add(pending, existingKeys, userId, GrowthEventType.STREAK, STREAK_30_KEY, 100, now);
        }
        for (String month : budgetMonths) {                             // ≤3 条（需求 5.15）
            add(pending, existingKeys, userId, GrowthEventType.BUDGET_MET, BUDGET_MET_PREFIX + month, 50, now);
        }
        if (inviteCount >= 1) {
            add(pending, existingKeys, userId, GrowthEventType.FIRST_INVITE, FIRST_INVITE_KEY, 80, now);
        }
        for (String code : badgeCatalog.qualified(facts)) {             // ≤9 条，exp 恒 0（需求 8.3）
            add(pending, existingKeys, userId, GrowthEventType.BADGE, GrowthBadgeCatalog.eventKeyOf(code), 0, now);
        }
        // 有界性断言（需求 3.10）：越界说明追补窗口或组装逻辑有缺陷，宁可炸响也不静默写超量。
        if (pending.size() > MAX_PENDING_EVENTS) {
            throw new IllegalStateException("单次结算待写事件 " + pending.size()
                    + " 超过上界 " + MAX_PENDING_EVENTS + "，userId=" + userId);
        }

        // ── ⑤ 批量插入（只忽略重复键；绝不 INSERT IGNORE、绝不 catch CHECK 违例，见类级 Javadoc）──
        if (!pending.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_EVENT_SQL, pending);
        }

        // ── ⑥ 全量重算写回（唯一路径；与 recalculateOnly 共用同一套重算，需求 1.12）─────────────
        recalculateAndWriteBack(profile, userId, now);
        userGrowthRepository.save(profile);

        // 概览侧 10 秒窗口记账时刻：提交前记录即可，节流是降级机制，宁可多跳过（需求 10.14）。
        throttle.markSettled(userId);
        return SettleOutcome.SETTLED;
    }

    /**
     * 全量重算：从事件表把该用户的成长档案物化列（经验 / 等级 / 三个天数列 / {@code last_record_date}）
     * <b>整体重新推导并写回</b>，但<b>不组装、不插入任何 {@code growth_events}</b>（需求 1.12、4.13）。
     *
     * <p><b>「全量重算」不是第二条实现路径。</b>它与 {@link #settle} 走的是<b>同一条</b>第 ⑥ 步重算代码
     * （{@link #recalculateAndWriteBack}）——{@code settle} 只是在这条路径<b>之前</b>额外做了「读事实源 →
     * 固定顺序组装待写事件 → 批量插入」这些步骤；把这些步骤去掉，剩下的重算与写回逐字节相同。因此
     * 「增量维护的结果 == 全量重算的结果」（Property 5、需求 1.7 / 1.12）不是靠两份独立实现凑巧对上，
     * 而是<b>构造性</b>成立：两者本就调用同一个 {@code recalculateAndWriteBack}。Property 5 只负责把这条
     * 事实<b>锁住</b>——一旦有人把重算逻辑拆成两份、让它们产生分歧，属性测试立刻变红。</p>
     *
     * <p>与 {@code settle} 共享的另外两点：① 单次调用<b>只读一次时钟</b>，同一个 {@code now} 同时用于
     * {@code updated_at} 与 {@code last_settled_at}；② 走 ODKU 建档 + 500ms 墙钟预算加行级写锁，语义与
     * {@code settle} 的第 ② 步一致（需求 1.9、1.10、9.16）。不做的只有「组装与插入事件」这一段。</p>
     *
     * <p>带 {@code @Transactional(REQUIRES_NEW)}，两条禁令与 {@code settle} 相同（见类级 Javadoc）：
     * 本方法刻意不捕获任何异常、禁止把 {@code REQUIRES_NEW} 改成 {@code REQUIRED}。</p>
     *
     * @param userId 重算用户 id（等于令牌用户 id / {@code users.id}）
     * @throws GrowthLockAbandonedException 500ms 内未取得行级写锁（穿出以回滚事务、边界外吞掉）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recalculateOnly(Long userId) {
        // 与 settle 一致：只读一次时钟，now 同时用于 updated_at 与 last_settled_at。
        LocalDateTime now = LocalDateTime.now(clock);

        // 建档 + 加行级写锁（与 settle 第 ② 步同一套：ODKU 免疫并发建档竞态，再走 500ms 墙钟预算加锁读）。
        jdbcTemplate.update(INSERT_PROFILE_SQL, userId, now, now);
        UserGrowth profile = lockProfileWithBudget(userId, LOCK_BUDGET_MILLIS);

        // 跳过 settle 的第 ③④⑤ 步（读事实源 / 组装 / 插入），直接走同一条第 ⑥ 步重算写回。
        recalculateAndWriteBack(profile, userId, now);
        userGrowthRepository.save(profile);
    }

    /**
     * 判定本次结算是否应被节流跳过（需求 9.15、10.14）。
     *
     * <ul>
     *   <li>{@link TriggerSource#OVERVIEW}：查进程内内存窗口，10 秒内已由概览驱动过结算则跳过。</li>
     *   <li>{@link TriggerSource#RECORD}：无锁读一次档案行（只为节流判定，不加锁、不写），
     *       {@code last_settled_at} 距今 &lt;60s <b>且</b> {@code last_record_date} 已等于结算日则跳过。
     *       两个条件缺一不可：没到 60s 但今天还没记过账仍要结算（补今天的 {@code DAILY_RECORD}）。</li>
     * </ul>
     */
    private boolean isThrottled(Long userId, TriggerSource source, LocalDateTime now, LocalDate settleDate) {
        if (source == TriggerSource.OVERVIEW) {
            return throttle.overviewRecentlySettled(userId);
        }
        // RECORD：无锁读档案，仅用于节流判定。
        Optional<UserGrowth> existing = userGrowthRepository.findById(userId);
        if (existing.isEmpty()) {
            return false;
        }
        UserGrowth profile = existing.get();
        LocalDateTime lastSettledAt = profile.getLastSettledAt();
        if (lastSettledAt == null) {
            return false;
        }
        boolean withinWindow = lastSettledAt.isAfter(now.minusSeconds(RECORD_THROTTLE_SECONDS));
        boolean todayAlreadyRecorded = settleDate.equals(profile.getLastRecordDate());
        return withinWindow && todayAlreadyRecorded;
    }

    /**
     * 第 ⑥ 步：从库重读完整 {@code DAILY_RECORD} 日历 → 纯函数扫描 → 写回四个物化列 + 经验 + 等级
     * （需求 1.11、4.7、4.9、4.13）。
     *
     * <p>{@code exp} 一律取 {@code SUM(exp_amount)} 数据库聚合（需求 1.2），<b>不用「旧 exp + 本次新增」的
     * 内存累加</b>——只有每次从事件表重新聚合才能让「档案 exp 恒等于事件之和」这条等式构造性成立。
     * {@code level = levelOf(exp)}。写回时<b>不动 {@code user_id} 与 {@code created_at}</b>，只更新六个
     * 物化列（exp/level/三个天数列/last_record_date）加 {@code updated_at} 与 {@code last_settled_at}。</p>
     */
    private void recalculateAndWriteBack(UserGrowth profile, Long userId, LocalDateTime now) {
        List<LocalDate> calendar = parseDailyRecordDates(growthEventRepository.findDailyRecordKeys(userId));
        CalendarScan scan = GrowthCalendarService.scan(calendar);
        long exp = growthEventRepository.sumExpByUserId(userId);        // 数据库聚合，不用内存累加（需求 1.2）
        int level = levelCurve.levelOf(exp);

        profile.setExp(exp);
        profile.setLevel(level);
        profile.setTotalRecordDays(scan.totalDays());
        profile.setCurrentStreakDays(scan.currentSegment());
        profile.setMaxStreakDays(scan.maxStreak());
        profile.setLastRecordDate(scan.lastDate());
        profile.setUpdatedAt(now);
        profile.setLastSettledAt(now);
        // user_id 与 created_at 刻意不动（需求 1.11）。
    }

    /**
     * 把一批已存在的事件键筛出 {@code DAILY_RECORD:} 前缀并解析为日期，与本次追补日期取并集
     * （用于第 ④ 步的 {@code STREAK} 门槛判定）。
     */
    private static List<LocalDate> unionDailyRecordDates(Set<String> existingKeys, List<LocalDate> backfillDates) {
        List<LocalDate> union = new ArrayList<>();
        for (String key : existingKeys) {
            if (key.startsWith(DAILY_RECORD_PREFIX)) {
                union.add(parseDailyRecordKey(key));
            }
        }
        union.addAll(backfillDates);
        // scan 内部会去重并排序，这里无需先处理。
        return union;
    }

    /** 把 {@code DAILY_RECORD:yyyy-MM-dd} 事件键列表解析为日期列表；解析失败抛异常，不静默跳过。 */
    private static List<LocalDate> parseDailyRecordDates(List<String> keys) {
        List<LocalDate> dates = new ArrayList<>(keys.size());
        for (String key : keys) {
            dates.add(parseDailyRecordKey(key));
        }
        return dates;
    }

    /**
     * 解析单个 {@code DAILY_RECORD:yyyy-MM-dd} 键（需求 4.1）。
     *
     * <p>解析失败必须抛异常而非静默跳过：库里出现畸形键说明写入路径有缺陷，跳过会让累计天数、连续天数
     * 悄悄少算且每次结算重复少算、永不自愈。让它抛出使这次结算整体回滚（{@code REQUIRES_NEW}），
     * 记账本身不受影响，问题因而可见可查。</p>
     */
    private static LocalDate parseDailyRecordKey(String key) {
        // LocalDate.parse 对畸形日期会抛 DateTimeParseException（RuntimeException），穿出使事务回滚。
        return LocalDate.parse(key.substring(DAILY_RECORD_PREFIX.length()));
    }

    /**
     * 把一条待写事件加入 {@code pending}，<b>先按 {@code existingKeys} 过滤</b>（减少无效写入）。
     *
     * <p>过滤只是优化，唯一性最终仍由数据库的 {@code uk_growth_events_user_key} 兜底（需求 1.5）：即便
     * 并发结算在过滤之后、插入之前抢先写入了同一键，ODKU 也会把它变成无副作用的自更新。
     * 参数顺序与 {@link #INSERT_EVENT_SQL} 的占位符一致：user_id, event_type, event_key, exp_amount, created_at。</p>
     */
    private void add(List<Object[]> pending, Set<String> existingKeys, Long userId,
                     String eventType, String eventKey, int expAmount, LocalDateTime now) {
        if (existingKeys.contains(eventKey)) {
            return;
        }
        // 加进本地已知集合，避免同一次结算内因逻辑缺陷重复组装同一键（正常路径不会发生）。
        existingKeys.add(eventKey);
        pending.add(new Object[] {userId, eventType, eventKey, expAmount, now});
    }

    /**
     * 在 {@code budgetMillis} 毫秒的墙钟预算内，加行级写锁读取该用户的成长档案行（需求 1.9、9.16）。
     *
     * <p><b>数据库事实（决定了 500ms 只能是应用层墙钟预算，不能交给数据库）</b>：</p>
     * <ul>
     *   <li>MySQL 的 {@code innodb_lock_wait_timeout} <b>最小粒度是 1 秒</b>——
     *       {@code SET innodb_lock_wait_timeout = 0} 被钳到 1、{@code = 0.5} 直接报
     *       {@code ERROR 1232}，因此无法用它表达「500 毫秒」这个粒度；</li>
     *   <li>{@code SELECT ... FOR UPDATE} 在 MySQL 8 上只有两种<b>非阻塞</b>修饰：{@code NOWAIT}
     *       （0 等待，取不到立即失败）与 {@code SKIP LOCKED}（跳过已锁行），<b>没有「等 N 毫秒」的语法</b>；</li>
     *   <li>故分工固定：{@link UserGrowthRepository#findForUpdateById} 靠 {@code lock.timeout = 0}
     *       渲染成 {@code FOR UPDATE NOWAIT} 让每次尝试<b>立即返回</b>（取不到锁抛
     *       {@link PessimisticLockingFailureException}），本方法用注入的 {@link Clock} 读墙钟、
     *       在预算内做有限次退避重试，由应用层决定「还要不要再试」。</li>
     * </ul>
     *
     * <p><b>放弃是安全的降级</b>：锁等待的对手只有「同一用户的另一次结算」，而并发结算幂等
     * （事实源都在库里，谁先拿到锁谁把终态算对），故预算耗尽时抛
     * {@link GrowthLockAbandonedException} 直接放弃即可，下一次结算会自然补齐。该异常必须<b>穿出</b>
     * 本方法与 {@code settle}（{@code REQUIRES_NEW}）使这次独立事务回滚，再由事务边界<b>之外</b>吞掉。</p>
     *
     * <p><b>H2（{@code MODE=MySQL}）兼容性决策（任务 4.5 决策点，已实测）</b>：H2 1.4.200
     * （Spring Boot 3.x 传递依赖，本项目测试用的内存库）<b>接受</b>
     * {@code jakarta.persistence.lock.timeout = 0} 这个提示、也<b>接受</b> {@code FOR UPDATE} 语法，
     * 因此 {@link UserGrowthRepository#findForUpdateById} 在 H2 上能正常执行、返回档案行，
     * 无需为测试期改写成不带 hint 的 {@code PESSIMISTIC_WRITE} + {@code SET LOCK_TIMEOUT 500} 近似方案。
     * 但要点明 H2 与 MySQL 的<b>语义差异</b>：H2 的 {@code MODE=MySQL} 并不真正实现 InnoDB 那种
     * 「另一会话持锁时 NOWAIT 立即抛错」的行锁竞争——H2 的行锁模型与超时行为和 MySQL 不同，
     * 且测试用的内存库单连接下也复现不出真实的并发争锁。因此
     * <b>「500ms 预算耗尽 → 抛 {@link GrowthLockAbandonedException}」这条分支无法在 H2 上真实触发</b>，
     * 它的<b>最终确认属于真实 MySQL 的手工验证清单</b>（任务 1.5 已完成：会话 A 持锁时会话 B 的
     * {@code FOR UPDATE NOWAIT} 以 {@code ERROR 3572 ... NOWAIT is set} 立即返回，见 design.md
     * 「迁移脚本」小节实测结论 ④）。本方法在 H2 上可覆盖的是「正常取到锁返回档案行」与
     * 「注入的时钟/退避逻辑本身」，放弃分支用注入 {@link Clock} 与桩仓储在单元测试里驱动。</p>
     *
     * @param userId       用户 id（即 {@code user_growth} 主键；调用方应已用 ODKU 建档，正常路径上行必存在）
     * @param budgetMillis 墙钟预算毫秒数（结算路径固定传 {@link #LOCK_BUDGET_MILLIS} = 500）
     * @return 已加行级写锁的成长档案行
     * @throws GrowthLockAbandonedException 预算耗尽或退避次数用尽仍未取得锁（穿出以回滚事务）
     */
    UserGrowth lockProfileWithBudget(Long userId, long budgetMillis) {
        long deadline = clock.millis() + budgetMillis;
        int attempt = 0;
        while (true) {
            try {
                // findForUpdateById 带 lock.timeout=0 → MySQLDialect 渲染为 SELECT ... FOR UPDATE NOWAIT，
                // 取不到锁立即抛 PessimisticLockingFailureException 而非阻塞等待。
                return userGrowthRepository.findForUpdateById(userId)
                        .orElseThrow(() -> new IllegalStateException(
                                "成长档案行不存在，加锁前应已通过 ODKU 建档：userId=" + userId));
            } catch (PessimisticLockingFailureException e) {
                long remaining = deadline - clock.millis();
                if (remaining <= 0 || ++attempt > MAX_LOCK_RETRIES) {
                    // 预算耗尽 / 退避次数用尽：放弃本次结算，异常穿出使事务回滚、边界外吞掉、下次自愈。
                    throw new GrowthLockAbandonedException(userId, e);
                }
                // 退避 20 / 40 / 80ms（attempt = 1/2/3），但不超过剩余预算，避免睡过头。
                long backoff = Math.min(remaining, BACKOFF_BASE_MILLIS << (attempt - 1));
                sleepQuietly(backoff);
            }
        }
    }

    /**
     * 退避睡眠：被中断时恢复中断标志并立即返回（不吞掉中断状态）。
     *
     * <p>返回后由 {@link #lockProfileWithBudget} 的循环重新评估剩余预算与退避次数上限，
     * 因此中断不会导致空转——重试次数由 {@link #MAX_LOCK_RETRIES} 硬性封顶。</p>
     */
    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供内部工具判断某集合内是否存在带指定前缀的键。 */
    private static boolean anyKeyStartsWith(Set<String> keys, String prefix) {
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
