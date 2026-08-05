package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AchievementNoticeRepository;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.service.AchievementSnapshotService.AchievementSnapshot;

/**
 * 成就清单、待播报成就与播报游标推进三条读取侧路径的组装（需求 5、需求 6）。
 *
 * <h2>三条路径的分工</h2>
 *
 * <ul>
 *   <li>{@link #getAchievements(Long)}：<b>写入型 GET</b>——返回前先尝试一次结算（复用概览侧
 *       {@code OVERVIEW} 来源自带的 10 秒进程内节流），再把
 *       {@link AchievementSnapshotService#snapshot(Long)} 的同一份快照投影成 16 项成就视图。</li>
 *   <li>{@link #getPending(Long)}：<b>纯只读</b>——读游标 + 两条 {@code growth_events} 查询，
 *       <b>不触发结算</b>、不向 {@code growth_events} 与 {@code user_growth} 执行任何写语句，
 *       也不推进游标（需求 5.14、5.17）。</li>
 *   <li>{@link #ack(Long, String)}：解析并校验入参后执行<b>唯一一条</b>
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE} 配 {@code GREATEST} 的游标推进语句。</li>
 * </ul>
 *
 * <h2>为什么本服务不加 {@code @Transactional}</h2>
 *
 * <p>与 {@link GrowthQueryService#getOverview(Long)} 完全同一个理由：本服务处在结算的<b>事务边界
 * 之外</b>。{@link GrowthSettlementService#settle} 刻意不 catch 任何异常，靠异常穿出回滚它自己的
 * {@code REQUIRES_NEW} 事务；「吞掉结算异常」这件事因此只能发生在事务边界外的这一层。若给本服务
 * （或它的任一方法）加上 {@code @Transactional}，那次 catch 就被挪进了一个事务上下文里——
 * Spring 会把那个外层事务标记为 rollback-only，于是「结算失败但成就清单照常返回」在提交那一刻
 * 仍然会失败，需求 6.7 的隔离当场破掉。{@link AchievementSnapshotService} 出于同样的理由也不加。</p>
 *
 * <h2>结算失败 / 被节流时的形状</h2>
 *
 * <p>第 ① 步的结算异常在这里就地吞掉只记一条 {@code [GROWTH_SETTLE_FAILED]} WARN，②③④ 三步
 * <b>照常执行</b>：返回已持久化的解锁状态 + 实时聚合的当前值，字段集与结算成功时<b>完全相同</b>、
 * 不对外暴露错误码，且 {@code growth_events} / {@code user_growth} / {@code achievement_notices}
 * 三表的行数与全部列取值不变（需求 6.7、1.11）。被节流与失败走的是同一条形状——两者对响应的
 * 可观察差别只有「档案可能略旧」。</p>
 */
@Service
public class AchievementQueryService {

    private static final Logger log = LoggerFactory.getLogger(AchievementQueryService.class);

    /** 单次待播报请求的返回上限（需求 5.4）：按成就事件 id 升序取前 10 项，先解锁的先播报。 */
    static final int PENDING_PAGE_SIZE = 10;

    /** 无游标行时的游标取值（需求 5.3）：按 0 计，等价于「全部 {@code BADGE} 行都待播报」。 */
    private static final long CURSOR_ABSENT = 0L;

    /**
     * 游标推进：<b>唯一一条</b>写语句，把单调性、幂等性与并发安全三条不变式压进一条 SQL
     * （需求 5.7~5.11，见 design.md「5. 播报游标」）。
     *
     * <p><b>两句赋值的先后顺序不能调换。</b>MySQL 的 {@code ON DUPLICATE KEY UPDATE} 赋值列表按
     * <b>书写顺序从左到右求值</b>，右侧表达式读到的是左侧已经赋过的<b>新值</b>。因此
     * {@code updated_at} 的 {@code CASE WHEN ? > last_notified_event_id} 必须写在
     * {@code last_notified_event_id = GREATEST(...)} <b>之前</b>：一旦调换，{@code GREATEST} 先落地，
     * {@code CASE} 读到的 {@code last_notified_event_id} 已是新值，而 {@code GREATEST(旧, 新) >= 新}
     * 使 {@code ? > last_notified_event_id} 对<b>任何</b>入参都不成立，{@code updated_at} 就永远
     * 停在首次写入的时刻——游标照常推进，MySQL 既不报错也不告警，是一个纯静默的错误。</p>
     *
     * <p>这条依赖是<b>实测结论、不是推断</b>：任务 1.5 在 MySQL {@code 8.0.46} 上建了两张同构表，
     * 一张按本写法、一张把两句调换，对同一组数据（5 → 9 → 10）各跑一遍——前者终态
     * {@code 10 / created_at=T1 / updated_at=T6}，后者终态 {@code 10 / T1 /} <b>{@code T1}</b>
     * （{@code updated_at} 一次都没动）。测得的版本号与逐项结论记在 design.md「5. 播报游标」的
     * 实测结论块里，任务 8.7 的反向断言以同一组数据为依据。</p>
     *
     * <p>用 {@code GREATEST(旧值, 新值)} 而不是 {@code = ?}：这一句同时满足「单调不减」（需求 5.9）、
     * 「重复确认幂等」（需求 5.8）与「并发终态取最大值」（需求 5.10）三条，且<b>不需要行锁、
     * 不需要先读后写</b>——竞态无从产生。同一实测还确认了重复确认（传入 ≤ 当前值）时
     * {@code last_notified_event_id} 与 {@code updated_at} 两列均不变、且不报错。</p>
     *
     * <p>参数 7 个，顺序为
     * {@code userId, lastEventId, now, now, lastEventId, now, lastEventId}。</p>
     */
    private static final String ADVANCE_CURSOR_SQL =
            "INSERT INTO achievement_notices "
                    + "(user_id, last_notified_event_id, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE "
                    + "    updated_at             = CASE WHEN ? > last_notified_event_id THEN ? ELSE updated_at END, "
                    + "    last_notified_event_id = GREATEST(last_notified_event_id, ?)";

    private final GrowthSettlementService settlementService;
    private final AchievementSnapshotService snapshotService;
    private final GrowthBadgeCatalog badgeCatalog;
    private final GrowthEventRepository growthEventRepository;
    private final AchievementNoticeRepository noticeRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AchievementQueryService(GrowthSettlementService settlementService,
                                   AchievementSnapshotService snapshotService,
                                   GrowthBadgeCatalog badgeCatalog,
                                   GrowthEventRepository growthEventRepository,
                                   AchievementNoticeRepository noticeRepository,
                                   JdbcTemplate jdbcTemplate,
                                   Clock clock) {
        this.settlementService = settlementService;
        this.snapshotService = snapshotService;
        this.badgeCatalog = badgeCatalog;
        this.growthEventRepository = growthEventRepository;
        this.noticeRepository = noticeRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * 成就清单：先尝试结算（异常就地吞掉），再取一份事实快照投影成 16 项成就视图（需求 6.1~6.7）。
     *
     * <p>固定四步（见 design.md「成就清单的组装顺序」）：</p>
     * <ol>
     *   <li>{@code settle(userId, OVERVIEW)}——复用概览侧的 10 秒进程内节流器
     *       （{@link GrowthSettlementThrottle}），<b>不新增第二个节流器</b>（需求 6.6）；
     *       异常 try/catch 吞掉只记 {@code [GROWTH_SETTLE_FAILED]}；</li>
     *   <li>{@link AchievementSnapshotService#snapshot(Long)}——八个统计口径与已解锁映射各只求值一次
     *       （需求 3.16），且与成长概览走的是<b>同一个</b>快照方法，需求 12.3 的逐项相等因此是
     *       构造性成立的；</li>
     *   <li>遍历 {@link GrowthBadgeCatalog#badges()} 投影成 16 个 {@link AchievementView}，
     *       顺序即清单顺序（需求 1.7）；</li>
     *   <li>{@code unlockedCount} 取列表中已解锁项的个数（需求 6.5）。</li>
     * </ol>
     *
     * <p>结算失败或被节流时 ②③④ 照常执行，响应字段集与成功时相同、不返回服务端错误码，三表行数与
     * 全部列取值不变（需求 6.7）。<b>本方法不加 {@code @Transactional}</b>，理由见类级 Javadoc。</p>
     *
     * @param userId 令牌所标识的用户 id（调用方已确认其在 {@code users} 表中仍存在）
     * @return 成就清单响应：顶层恰好 3 项，列表恒 16 项、{@code total} 恒 16（需求 6.1）
     */
    public AchievementListResponse getAchievements(Long userId) {
        // ── ① 尝试结算：OVERVIEW 来源自带 10 秒进程内节流；异常吞掉只记 WARN（需求 6.6、6.7）──
        try {
            settlementService.settle(userId, TriggerSource.OVERVIEW);
        } catch (Exception e) {
            // 事务边界之外吞异常：settle 的 REQUIRES_NEW 事务已因异常穿出而回滚，这里只负责不把异常
            // 继续抛给控制器，从而让成就清单照常返回已持久化的解锁状态（需求 6.7）。
            log.warn("[GROWTH_SETTLE_FAILED] 成就清单触发的结算失败，返回已持久化的解锁状态 userId={}", userId, e);
        }

        // ── ② 一份快照喂全部 16 项（需求 3.16、12.3）─────────────────────────────────
        AchievementSnapshot snapshot = snapshotService.snapshot(userId);

        // ── ③ 投影成 16 个成就视图，顺序即清单顺序（需求 1.7、6.2）──────────────────────
        List<BadgeDef> defs = badgeCatalog.badges();
        List<AchievementView> views = new ArrayList<>(defs.size());
        int unlockedCount = 0;
        for (BadgeDef def : defs) {
            boolean unlocked = snapshot.unlocked(def.code());
            GrowthEvent event = snapshot.eventOf(def.code());
            // 未解锁时解锁时刻与事件 id 一律为空值，不以 0 / 空字符串 / 当前时刻替代（需求 6.3、2.13）。
            LocalDateTime unlockedAt = (event == null) ? null : event.getCreatedAt();
            Long eventId = (event == null) ? null : event.getId();
            int current = badgeCatalog.currentOf(def, snapshot.facts(), unlocked);
            if (unlocked) {
                unlockedCount++;
            }
            views.add(new AchievementView(def.code(), def.name(), def.description(),
                    def.category().label(), def.target(), current, unlocked, unlockedAt, eventId));
        }

        // ── ④ 已解锁成就数即列表中已解锁项的个数；total 即列表项数（恒 16，需求 6.1、6.5）───────
        return new AchievementListResponse(views, unlockedCount, views.size());
    }

    /**
     * 待播报成就：读游标（无行按 0）后按成就事件 id 升序取前 10 项 + 截断前总条数（需求 5.2~5.5）。
     *
     * <p><b>纯只读路径</b>：不触发结算、不推进游标、不向 {@code growth_events} 与 {@code user_growth}
     * 执行任何插入 / 更新 / 删除语句（需求 5.14、5.17）。因此在期间无新解锁且未推进游标时，连续两次
     * 请求返回相同的项、相同顺序与相同的 {@code total}。</p>
     *
     * <p>{@code total} 取 {@link GrowthEventRepository#countPendingBadgeEvents} 的
     * <b>截断前</b>条数，而不是 {@code items.size()}（需求 5.5）：待播报 30 项时列表只给 10 项，
     * 但客户端必须知道还有后续，否则会把「还有 30 项」显示成「还有 10 项」。</p>
     *
     * <p>清单是权威（需求 1.12）：{@code event_key} 去掉 {@code BADGE:} 前缀后不在 16 项清单内的行
     * 一律<b>忽略</b>并记一条含用户 id 与该 {@code event_key} 的 WARN，不报错、不改动该行
     * （{@code growth_events} 是只追加表）。此时 {@code total} 仍是库里的真实待播报条数——它按
     * {@code event_type} 计数，与编码是否在清单内无关。</p>
     *
     * <p>无待播报成就时返回空列表 + {@code total} 0，且不返回错误（需求 5.16）。数据库访问抛异常时
     * 记一条含用户 id 的 WARN 并按「本次无待播报」返回，游标表保持不变、异常不向上传播（需求 5.19）。</p>
     *
     * @param userId 令牌所标识的用户 id（调用方已确认其在 {@code users} 表中仍存在）
     * @return 待播报成就响应：顶层恰好 2 项，{@code items} ≤ 10 项且按事件 id 升序（需求 5.4）
     */
    public PendingAchievementResponse getPending(Long userId) {
        try {
            long cursor = readCursor(userId);

            // 两条只读查询走既有索引 idx_growth_events_user_type，过滤条件逐字相同、只差分页截断。
            List<GrowthEvent> events = growthEventRepository.findPendingBadgeEvents(
                    userId, cursor, PageRequest.of(0, PENDING_PAGE_SIZE));
            long total = growthEventRepository.countPendingBadgeEvents(userId, cursor);

            Map<String, BadgeDef> defByCode = defByCode();
            List<PendingAchievementItem> items = new ArrayList<>(events.size());
            for (GrowthEvent event : events) {
                String key = event.getEventKey();
                String code = (key == null || !key.startsWith(GrowthBadgeCatalog.BADGE_KEY_PREFIX))
                        ? null
                        : key.substring(GrowthBadgeCatalog.BADGE_KEY_PREFIX.length());
                BadgeDef def = (code == null) ? null : defByCode.get(code);
                if (def == null) {
                    // 需求 1.12：清单是权威。忽略该行、记 WARN、不报错、不改行；total 不受影响。
                    log.warn("[ACHIEVEMENT_UNKNOWN_BADGE] 待播报成就的编码不在清单内，已忽略该行 "
                            + "userId={} eventKey={}", userId, key);
                    continue;
                }
                // 六个字段与 AchievementView 的同名字段逐项相等：同一份清单常量 + 同一行 BADGE 事件。
                items.add(new PendingAchievementItem(def.code(), def.name(), def.description(),
                        def.category().label(), event.getCreatedAt(), event.getId()));
            }
            return new PendingAchievementResponse(items, total);
        } catch (Exception e) {
            // 需求 5.19：记一条含用户 id 的 WARN、游标表零改动、不向记账 / 登录 / 注销路径传播。
            log.warn("[ACHIEVEMENT_PENDING_DEGRADED] 待播报成就查询失败，本次按无待播报返回 userId={}", userId, e);
            return new PendingAchievementResponse(List.of(), 0L);
        }
    }

    /**
     * 推进播报游标：解析 → 校验 → 一条 ODKU + {@code GREATEST} → 重读该行返回推进后取值（需求 5.6~5.13）。
     *
     * <p><b>校验（需求 5.12）</b>：{@code null}、空白、无法解析为整数、小于 0、或大于该用户当前最大
     * {@code BADGE} 事件 id（无 {@code BADGE} 行时上界按 0 计，需求 5.6、5.13）——五种情形一律抛
     * {@link ApiException#achievementAckParamInvalid()}（{@code field} 为 {@code lastEventId}），
     * 且此时<b>一条写语句都不发</b>，{@code achievement_notices} 的行数与全部列取值不变。</p>
     *
     * <p><b>返回值必须重读一次游标行</b>：{@code GREATEST} 的结果只有数据库知道，不能拿请求入参充当
     * 返回值——重复确认（传入 ≤ 当前游标）时正确答案是<b>当前</b>游标而不是入参（需求 5.8）。
     * 也不能用受影响行数判断「是否推进」：任务 1.5 实测该实例上 ODKU 的 {@code ROW_COUNT()} 是
     * 0（空更新）/ 1（插入）/ 2（真实更新）三态，且还受客户端 {@code CLIENT_FOUND_ROWS} 标志影响。</p>
     *
     * <p><b>数据库访问异常</b>（需求 5.19）：记一条含用户 id 的 WARN，游标表保持不变，按当前游标取值
     * 返回，异常<b>不向记账、登录与注销路径传播</b>。入参非法的 {@link ApiException} 不在此列——
     * 它是对客户端的正常答复，故上界校验刻意放在 try 之外，不会被降级分支吞掉。</p>
     *
     * @param userId         令牌所标识的用户 id（调用方已确认其在 {@code users} 表中仍存在）
     * @param rawLastEventId 本次已播报到的最大成就事件 id 的原文（见 {@link AchievementAckRequest}）
     * @return 推进后的游标取值，顶层恰好 1 项（需求 5.7）
     * @throws ApiException {@code ACHIEVEMENT_ACK_PARAM_INVALID}，入参缺失 / 不可解析 / 越界（需求 5.12）
     */
    public AchievementAckResponse ack(Long userId, String rawLastEventId) {
        long lastEventId = parseNonNegative(rawLastEventId);

        long upperBound;
        try {
            // 上界的唯一依据（需求 5.6、5.13）：COALESCE(MAX(id), 0)，无 BADGE 行时为 0。
            upperBound = growthEventRepository.maxBadgeEventId(userId);
        } catch (Exception e) {
            // 读不到上界就无法判定合法性，此时一律不写：游标保持不变（需求 5.19），也不误判成 400。
            return cursorDegraded(userId, e);
        }
        if (lastEventId > upperBound) {
            throw ApiException.achievementAckParamInvalid();
        }

        try {
            // 单次请求只读一次时钟：now 同时作为插入路径的 created_at / updated_at 与推进时的 updated_at，
            // 使首次创建时两列为「同一服务端时刻」（需求 5.11）。
            LocalDateTime now = LocalDateTime.now(clock);
            jdbcTemplate.update(ADVANCE_CURSOR_SQL,
                    userId, lastEventId, now, now,      // VALUES(...)
                    lastEventId, now,                   // updated_at 的 CASE WHEN ? > 旧值 THEN ?
                    lastEventId);                       // GREATEST(旧值, ?)
            return new AchievementAckResponse(readCursor(userId));
        } catch (Exception e) {
            return cursorDegraded(userId, e);
        }
    }

    /**
     * 读回当前游标取值，无行时按 0 计（需求 5.1、5.3）。
     *
     * <p>只走继承来的 {@code findById}——{@link AchievementNoticeRepository} 刻意不暴露任何单行写入
     * 方法，读回来的取值只用于「算待播报」与「作为响应取值」，绝不用于「比一比再 save」那条读改写
     * 竞态路径。</p>
     */
    private long readCursor(Long userId) {
        return noticeRepository.findById(userId)
                .map(notice -> notice.getLastNotifiedEventId())
                .orElse(CURSOR_ABSENT);
    }

    /**
     * 游标路径的数据库访问降级（需求 5.19）：记一条<b>含用户 id</b> 的 WARN，游标表保持不变，
     * 按当前游标取值返回（连重读都失败时按 0 计），异常不向上传播。
     */
    private AchievementAckResponse cursorDegraded(Long userId, Exception cause) {
        log.warn("[ACHIEVEMENT_CURSOR_DEGRADED] 播报游标访问失败，游标保持不变 userId={}", userId, cause);
        long current;
        try {
            current = readCursor(userId);
        } catch (Exception ignored) {
            current = CURSOR_ABSENT;
        }
        return new AchievementAckResponse(current);
    }

    /**
     * 解析 {@code lastEventId} 原文并校验非负（需求 5.12 的前四种情形）。
     *
     * <p>{@code null} / 空白 / 无法解析为整数（含 {@code "1.5"} 这类小数写法）/ 小于 0 一律抛
     * {@code ACHIEVEMENT_ACK_PARAM_INVALID}。先 {@code trim} 再解析，故 {@code " 12 "} 按 12 处理
     * ——客户端多带的空白不该变成一个 400。上界校验需要一次库查询，放在调用方（见 {@link #ack}）。</p>
     */
    private static long parseNonNegative(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.achievementAckParamInvalid();
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.achievementAckParamInvalid();
        }
        if (value < 0) {
            throw ApiException.achievementAckParamInvalid();
        }
        return value;
    }

    /** 编码 → 成就定义的索引，用于待播报项的投影与未知编码的识别（需求 1.12）。 */
    private Map<String, BadgeDef> defByCode() {
        Map<String, BadgeDef> byCode = new LinkedHashMap<>();
        for (BadgeDef def : badgeCatalog.badges()) {
            byCode.put(def.code(), def);
        }
        return byCode;
    }
}
