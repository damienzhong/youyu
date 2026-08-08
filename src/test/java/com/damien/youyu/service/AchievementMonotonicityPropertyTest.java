package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.LedgerMember;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 2：已解锁成就数单调不减（删账不收回成就）</b>的属性测试（任务 8.1）。
 *
 * <p><i>对任意</i>由「软删某笔 / 清空回收站 / 把某笔改到别的分类 / 把『旅行』分类改名 / 删除『旅行』分类 /
 * 下调总预算 / 删除总预算 / 移除 {@code EDITOR} 成员 / 把邀请关系置 {@code INVALID} / 直接触发结算 /
 * 请求成就清单」组成的<b>回撤序列</b>（长度 1–40，用户池 2–5）：</p>
 *
 * <ul>
 *   <li>该用户的已解锁成就数在时间上<b>单调不减</b>（需求 2.4）；</li>
 *   <li>全部 {@code BADGE} 行的<b>行数与每一列取值逐列相等</b>于回撤前的基线快照
 *       （{@code id} / {@code event_type} / {@code event_key} / {@code exp_amount} / {@code created_at}，
 *        需求 2.3、2.4）；</li>
 *   <li>成就清单里已解锁项<b>保持已解锁</b>，且其<b>当前值恒等于门槛</b>——即便统计量已经因回撤跌回 0
 *       （需求 3.12：「旅行」改名 / 删除 / 改分类使 {@code TRAVEL_RECORD_COUNT} 下降后，
 *        {@code TRAVEL_MASTER} 的当前值取「下降后的取值」与门槛 10 的较小者，而已解锁项的当前值恒为门槛）；</li>
 *   <li>整个过程<b>不报错</b>：回撤本身不是异常，成就清单照常返回 16 项。</li>
 * </ul>
 *
 * <p>末尾另加一段<b>增长方向</b>的收尾（见 {@code property2} 的第 ③ 步）：回撤序列跑完后补一笔有效记账
 * 再结算，断言已解锁数只增不减、基线的每一行仍逐列不变——「单调不减」不能靠「什么都没变」来满足。</p>
 *
 * <h2>成立方式：构造性（本测试只负责锁住它，防回归）</h2>
 *
 * <p>{@code growth_events} 是<b>只追加</b>表（除注销时的硬删），结算的批量语句是
 * {@code INSERT ... ON DUPLICATE KEY UPDATE id = id}，对既有行既不 {@code UPDATE} 也不 {@code DELETE}；
 * 读取侧「是否已解锁」的唯一依据是「存在 {@code BADGE:<编码>} 行」（需求 2.3），与当前统计量无关。
 * 两点合起来使删账、删分类、下调预算这些<b>只缩减事实源</b>的操作动不了已经记下的成就。
 * 一旦有人让结算在统计量回落时删行 / 改行，或把「已解锁」改成「当前统计量 ≥ 门槛」，本测试立刻变红。</p>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 *
 * <p>与 {@code AchievementIdempotencyPropertyTest} 同一套约定：{@code settle} 带
 * {@code @Transactional(REQUIRES_NEW)}，只有真实提交才能在库里观察到终态，故<b>不用测试级事务包裹</b>；
 * 清理由 {@link #resetState()} 每次迭代前显式清表 + 全局自增序号 {@link #SEQ} 双重隔离。时钟是
 * {@code @Primary} 的可推进 {@link MutableClock}（{@code Asia/Shanghai} 的 {@code 2025-06-15 08:00}），
 * 每次结算前推进 61 秒越过记账侧 60 秒与概览侧 10 秒两个节流窗口，且不跨自然日。
 * jqwik 属性方法不经 {@code SpringExtension}，注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 手工完成。</p>
 *
 * <h2>基线为什么要造得这么满</h2>
 *
 * <p>基线播种 7 个连续记账日 + 12 笔「旅行」支出 + 1 个 {@code EDITOR} 成员 + 1 条 {@code REGISTERED}
 * 邀请 + 前一月的宽松总预算 + 3 个储蓄月，一次结算解锁 <b>8 枚</b>成就：
 * {@code FIRST_RECORD} / {@code STREAK_7} / {@code RECORD_10} / {@code INVITE_1} / {@code COLLAB_1} /
 * {@code BUDGET_MET} / {@code SAVING_MASTER} / {@code TRAVEL_MASTER}。这样每一类回撤操作都<b>确有</b>
 * 一枚成就可供它去撤：删账撤笔数与连续、改分类 / 改名 / 删分类撤旅行、下调 / 删预算撤预算达成、
 * 移除成员撤协作、邀请置 {@code INVALID} 撤邀请。基线若只解锁一枚，多数回撤操作就打在空处，
 * 属性会以「什么都没发生」的方式恒真。{@link #assertBaselineIsRich} 把这条前提也断言起来。</p>
 *
 * <p>Feature: achievement-system, Property 2: 已解锁成就数单调不减（删账不收回成就）</p>
 *
 * <p>Validates: Requirements 2.3, 2.4, 3.12</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-mono-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(AchievementMonotonicityPropertyTest.ClockConfig.class)
class AchievementMonotonicityPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：结算日恒为 2025-06-15，回看月为 2025-03 / 04 / 05。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 越过记账侧 60 秒节流窗口（也顺带越过概览侧 10 秒窗口）的推进量。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);

    /** 全局自增序号：保证跨迭代 userId / ledgerId / 分类 id / 成员 id 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(720_000_000L);

    /** 基线必然解锁的 8 枚成就（每一类回撤操作都有的放矢，见类级 Javadoc）。 */
    private static final List<String> BASELINE_CODES = List.of(
            "FIRST_RECORD", "STREAK_7", "RECORD_10", "INVITE_1", "COLLAB_1",
            "BUDGET_MET", "SAVING_MASTER", "TRAVEL_MASTER");

    /** 清单项数（需求 1.1）：成就清单响应恒 16 项，回撤过程中一项不少。 */
    private static final int CATALOG_SIZE = 16;

    /** 交易直插语句：列顺序与 {@link #insertTransaction} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private AchievementQueryService achievementQueryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(AchievementMonotonicityPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删事实源与三张成长 / 成就表（各表间无外键）。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM budgets");
        jdbcTemplate.update("DELETE FROM ledger_members");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM categories");
        jdbcTemplate.update("DELETE FROM invite_relations");
    }

    // ---------------- 生成器 ----------------

    /**
     * 一次回撤操作。<b>每一种都只缩减事实源</b>，没有任何一种会让某个统计口径上升，
     * 因此「已解锁成就数不变」是这段序列的预期终态，「单调不减」在这里的强形式就是「逐列相等」。
     */
    enum RollbackOp {
        /** 软删该用户当前最早一笔未删交易（移入回收站）。 */
        SOFT_DELETE_ONE_TX,
        /** 清空回收站：硬删该用户全部已软删交易。 */
        PURGE_RECYCLE_BIN,
        /** 把一笔「旅行」交易改到别的分类（需求 3.12 的三种触发之一）。 */
        MOVE_TX_TO_OTHER_CATEGORY,
        /** 把「旅行」分类改名（改成 {@code 旅行保险}，逐字符相等的判定随即不再命中）。 */
        RENAME_TRAVEL_CATEGORY,
        /** 删除「旅行」分类行。 */
        DELETE_TRAVEL_CATEGORY,
        /** 把总预算下调到 0.01（原本达成的月份不再达成）。 */
        LOWER_BUDGET_TO_MIN,
        /** 删除总预算行。 */
        DELETE_BUDGET,
        /** 移除自有账本上的全部 {@code EDITOR} 成员行。 */
        REMOVE_EDITOR_MEMBER,
        /** 把全部邀请关系置 {@code INVALID}（被邀请人注销的效果）。 */
        INVALIDATE_INVITES,
        /** 直接串行结算一次（回撤之后的结算是最可能「顺手收回」的时刻）。 */
        SETTLE,
        /** 请求成就清单（写入型 GET，内含一次 {@code OVERVIEW} 结算），并逐项复核视图不变式。 */
        LIST
    }

    /** 回撤序列：长度 1–40，各类型随机（含重复，考验幂等）。 */
    @Provide
    Arbitrary<List<RollbackOp>> rollbackSequences() {
        return Arbitraries.of(RollbackOp.class).list().ofMinSize(1).ofMaxSize(40);
    }

    // ---------------- Property 2 ----------------

    /**
     * Feature: achievement-system, Property 2: 已解锁成就数单调不减（删账不收回成就）
     *
     * <p>三步：① 为 2–5 个用户各播种一份「满」基线并结算，记下基线快照与已解锁数；
     * ② 轮流施加 1–40 个回撤操作，<b>每个操作之后</b>对<b>全部</b>用户复核：已解锁数单调不减、
     * 全部 {@code BADGE} 行与基线<b>逐列相等</b>（行数也相等）；③ 收尾：补一笔有效记账再结算，
     * 断言已解锁数只增不减、基线各行仍逐列不变，并用成就清单接口逐项复核
     * 「已解锁项保持已解锁 + 当前值恒等于门槛」（需求 3.12）。</p>
     *
     * <p>用户池 ≥2 让「跨用户不串行为」一并被覆盖：某个用户的回撤绝不能动到另一个用户的成就。</p>
     *
     * <p>Validates: Requirements 2.3, 2.4, 3.12</p>
     */
    @Property(tries = 10)
    void property2_unlockedCountNeverDecreasesAndBadgeRowsStayIdentical(
            @ForAll("rollbackSequences") List<RollbackOp> ops,
            @ForAll @IntRange(min = 2, max = 5) int userCount) {

        // ── ① 基线：每个用户播一份「满」事实源并结算 ────────────────────────────────────
        List<Ctx> users = new ArrayList<>(userCount);
        Map<Long, Map<String, List<Object>>> baseline = new LinkedHashMap<>();
        Map<Long, Long> previousUnlocked = new LinkedHashMap<>();
        for (int i = 0; i < userCount; i++) {
            Ctx ctx = seedBaselineUser();
            users.add(ctx);
            Map<String, List<Object>> snapshot = badgeSnapshot(ctx.userId());
            assertBaselineIsRich(ctx.userId(), snapshot);
            baseline.put(ctx.userId(), snapshot);
            previousUnlocked.put(ctx.userId(), (long) snapshot.size());
        }

        // ── ② 回撤序列：每个操作之后复核全部用户 ───────────────────────────────────────
        for (int i = 0; i < ops.size(); i++) {
            Ctx ctx = users.get(i % users.size());
            applyRollback(ops.get(i), ctx);
            String stage = "第 " + (i + 1) + " 个回撤操作 " + ops.get(i);
            for (Ctx each : users) {
                assertNoAchievementLost(each, baseline, previousUnlocked, stage);
            }
        }

        // ── ③ 收尾：增长方向也不许丢失既有成就（单调不减不能靠「什么都没变」来满足）───────────
        for (Ctx ctx : users) {
            insertTransaction(ctx, "expense", "3.30", LocalDate.now(CLOCK).atTime(9, 30),
                    ctx.decoyCategoryId());
            CLOCK.advance(BEYOND_THROTTLE);
            settlementService.settle(ctx.userId(), TriggerSource.RECORD);
        }
        for (Ctx ctx : users) {
            assertNoAchievementLost(ctx, baseline, previousUnlocked, "收尾的新增记账 + 结算");
            assertAchievementListKeepsUnlocked(ctx, baseline.get(ctx.userId()), "收尾的新增记账 + 结算");
        }
    }

    // ---------------- 不变式断言 ----------------

    /**
     * 复核某用户的两条不变式：已解锁成就数单调不减（需求 2.4）、全部 {@code BADGE} 行与基线逐列相等
     * （需求 2.3、2.4）。
     *
     * <p>「逐列相等」用 {@code containsAllEntriesOf(基线)} 而不是 {@code isEqualTo(基线)}：回撤序列本身不会
     * 让任何口径上升，但结算<b>本来就该</b>把「基线时条件已成立却还没写入」的成就补齐（例如某次结算恰好
     * 是首次覆盖到某个口径），把这种补齐算成缺陷会让属性错误变红。「不许丢」由包含式判定 + 已解锁数单调
     * 不减两条合起来锁死：任何一行被删或被改写，包含式判定立刻变红；任何一枚成就被收回，数量断言变红。</p>
     */
    private void assertNoAchievementLost(Ctx ctx, Map<Long, Map<String, List<Object>>> baseline,
                                         Map<Long, Long> previousUnlocked, String stage) {
        long userId = ctx.userId();
        Map<String, List<Object>> current = badgeSnapshot(userId);
        Map<String, List<Object>> base = baseline.get(userId);

        // 已解锁成就数单调不减（需求 2.4）。
        long previous = previousUnlocked.get(userId);
        assertThat((long) current.size())
                .as("%s 之后用户 %s 的已解锁成就数不得下降（需求 2.4）", stage, userId)
                .isGreaterThanOrEqualTo(previous);

        // 基线的每一行仍在，且 id / event_type / event_key / exp_amount / created_at 逐列相等（需求 2.3、2.4）。
        assertThat(current)
                .as("%s 之后用户 %s 的 BADGE 行必须逐列相等于基线（需求 2.3、2.4）", stage, userId)
                .containsAllEntriesOf(base);

        previousUnlocked.put(userId, (long) current.size());
    }

    /**
     * 用成就清单接口逐项复核：16 项一项不少；已解锁项保持已解锁、当前值恒等于门槛、解锁时刻与成就事件 id
     * 等于库里那一行的取值（需求 2.3、3.12）。
     *
     * <p>{@code TRAVEL_MASTER} 是这条断言的主角：回撤序列可能已经把「旅行」分类改名或删掉、把交易改到
     * 别的分类，于是 {@code TRAVEL_RECORD_COUNT} 跌回 0；需求 3.12 要求此时该成就仍是已解锁、当前值仍是
     * 门槛 10（已解锁项的当前值不随统计量回落而回退），且不报错。</p>
     */
    private void assertAchievementListKeepsUnlocked(Ctx ctx, Map<String, List<Object>> base, String stage) {
        AchievementListResponse response = achievementQueryService.getAchievements(ctx.userId());

        assertThat(response.achievements())
                .as("%s 之后成就清单仍恒 %d 项", stage, CATALOG_SIZE).hasSize(CATALOG_SIZE);
        assertThat(response.total()).isEqualTo(CATALOG_SIZE);
        assertThat((long) response.unlockedCount())
                .as("%s 之后已解锁项数不得少于基线（需求 2.4）", stage)
                .isGreaterThanOrEqualTo(base.size());

        Map<String, AchievementView> byCode = new LinkedHashMap<>();
        for (AchievementView view : response.achievements()) {
            byCode.put(view.code(), view);
        }
        for (Map.Entry<String, List<Object>> entry : base.entrySet()) {
            String code = entry.getKey();
            AchievementView view = byCode.get(code);
            assertThat(view).as("成就 %s 应仍在清单内", code).isNotNull();
            assertThat(view.unlocked())
                    .as("%s 之后成就 %s 必须保持已解锁（需求 2.3、3.12）", stage, code).isTrue();
            assertThat(view.current())
                    .as("%s 之后已解锁成就 %s 的当前值恒等于门槛，不随统计量回落而回退（需求 3.12）",
                            stage, code)
                    .isEqualTo(view.target());
            // 解锁时刻与成就事件 id 取库里那一行的取值（基线快照的第 1 / 5 列）。
            assertThat(view.eventId()).as("成就 %s 的成就事件 id", code).isEqualTo(entry.getValue().get(0));
            assertThat(Timestamp.valueOf(view.unlockedAt()))
                    .as("成就 %s 的解锁时刻等于该 BADGE 行的 created_at", code)
                    .isEqualTo(entry.getValue().get(4));
        }
    }

    /** 基线必须「满」：8 枚成就都已解锁，否则多数回撤操作打在空处，属性会以「什么都没发生」恒真。 */
    private void assertBaselineIsRich(long userId, Map<String, List<Object>> snapshot) {
        assertThat(snapshot.keySet())
                .as("用户 %s 的基线应解锁 8 枚成就，否则回撤操作无从考验单调性", userId)
                .containsAll(BASELINE_CODES);
        snapshot.forEach((code, row) -> {
            assertThat(row.get(1)).as("成就 %s 的 event_type", code).isEqualTo(GrowthEventType.BADGE);
            assertThat(row.get(3)).as("成就 %s 的 exp_amount 恒为 0", code).isEqualTo(0);
        });
    }

    /**
     * 该用户全部 {@code BADGE} 行的五列快照：编码 -&gt;
     * {@code [id, event_type, event_key, exp_amount, created_at]}。
     *
     * <p>一律<b>从库读回</b>再比对，不缓存服务层返回的对象：接口投影出来的解锁时刻若被实现改成「当前
     * 时刻」，内存比对照样自我一致地相等，只有比库里的原值才能发现行被覆写。每行用
     * {@code List<Object>} 而非 {@code Object[]} 承载（{@code Object[].equals} 是引用相等，
     * 会让「逐列相等」的断言恒真）。</p>
     */
    private Map<String, List<Object>> badgeSnapshot(long userId) {
        Map<String, List<Object>> snapshot = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, event_type, event_key, exp_amount, created_at FROM growth_events "
                        + "WHERE user_id = ? AND event_type = 'BADGE' ORDER BY id",
                userId);
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("event_key");
            snapshot.put(key.substring(GrowthBadgeCatalog.BADGE_KEY_PREFIX.length()),
                    List.of(((Number) row.get("id")).longValue(),
                            row.get("event_type"),
                            key,
                            ((Number) row.get("exp_amount")).intValue(),
                            row.get("created_at")));
        }
        return snapshot;
    }

    // ---------------- 回撤操作 ----------------

    private void applyRollback(RollbackOp op, Ctx ctx) {
        long userId = ctx.userId();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        switch (op) {
            case SOFT_DELETE_ONE_TX -> {
                Long id = jdbcTemplate.queryForObject(
                        "SELECT MIN(id) FROM transactions WHERE created_by = ? AND deleted_at IS NULL",
                        Long.class, userId);
                if (id != null) {
                    jdbcTemplate.update("UPDATE transactions SET deleted_at = ? WHERE id = ?", now, id);
                }
            }
            case PURGE_RECYCLE_BIN -> jdbcTemplate.update(
                    "DELETE FROM transactions WHERE created_by = ? AND deleted_at IS NOT NULL", userId);
            case MOVE_TX_TO_OTHER_CATEGORY -> {
                Long id = jdbcTemplate.queryForObject(
                        "SELECT MIN(id) FROM transactions WHERE created_by = ? AND category_id = ?",
                        Long.class, userId, ctx.travelCategoryId());
                if (id != null) {
                    jdbcTemplate.update("UPDATE transactions SET category_id = ?, updated_at = ? WHERE id = ?",
                            ctx.decoyCategoryId(), now, id);
                }
            }
            case RENAME_TRAVEL_CATEGORY -> jdbcTemplate.update(
                    "UPDATE categories SET name = '旅行保险', updated_at = ? WHERE id = ?",
                    now, ctx.travelCategoryId());
            case DELETE_TRAVEL_CATEGORY -> jdbcTemplate.update(
                    "DELETE FROM categories WHERE id = ?", ctx.travelCategoryId());
            case LOWER_BUDGET_TO_MIN -> jdbcTemplate.update(
                    "UPDATE budgets SET amount = 0.01, updated_at = ? WHERE user_id = ?", now, userId);
            case DELETE_BUDGET -> jdbcTemplate.update(
                    "DELETE FROM budgets WHERE user_id = ?", userId);
            case REMOVE_EDITOR_MEMBER -> jdbcTemplate.update(
                    "DELETE FROM ledger_members WHERE ledger_id = ? AND role = ?",
                    ctx.ledgerId(), LedgerMember.ROLE_EDITOR);
            case INVALIDATE_INVITES -> jdbcTemplate.update(
                    "UPDATE invite_relations SET status = 'INVALID', updated_at = ? WHERE inviter_id = ?",
                    now, userId);
            case SETTLE -> {
                CLOCK.advance(BEYOND_THROTTLE);
                settlementService.settle(userId, TriggerSource.RECORD);
            }
            case LIST -> {
                CLOCK.advance(BEYOND_THROTTLE);
                assertAchievementListKeepsUnlocked(ctx, badgeSnapshot(userId), "请求成就清单");
            }
            default -> throw new IllegalStateException("未覆盖的回撤操作: " + op);
        }
    }

    // ---------------- 基线播种 ----------------

    /** 一个用户的固定上下文：自有账本、「旅行」父分类、一个用于「改分类」的诱饵分类。 */
    private record Ctx(long userId, long ledgerId, long travelCategoryId, long decoyCategoryId) {
    }

    /**
     * 播种一份「满」基线并结算一次，返回该用户的上下文。
     *
     * <p>构造要点：全部交易的<b>记账日</b>（{@code created_at}）一律 ≤ 昨天，故
     * {@code last_record_date != 结算日}，记账侧 60 秒节流的两个条件不会同时成立；三个回看月的收支只改
     * {@code occurred_at}（预算与储蓄月按 {@code occurred_at} 聚合），因此不额外增加记账日。</p>
     */
    private Ctx seedBaselineUser() {
        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        LocalDate settleDate = LocalDate.now(CLOCK);
        LocalDate yesterday = settleDate.minusDays(1);

        jdbcTemplate.update(
                "INSERT INTO ledgers (id, user_id, name, type, sort_order, is_default, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PERSONAL', 0, FALSE, ?, ?)",
                ledgerId, userId, "mono-" + userId, now, now);
        long travelId = insertCategory(userId, ledgerId, "旅行", now);
        long decoyId = insertCategory(userId, ledgerId, "餐饮", now);
        Ctx ctx = new Ctx(userId, ledgerId, travelId, decoyId);

        for (int i = 0; i < 7; i++) {                       // FIRST_RECORD + STREAK_7
            insertTransaction(ctx, "expense", "9.90", yesterday.minusDays(i).atTime(8, 0), decoyId);
        }
        for (int i = 0; i < 12; i++) {                      // RECORD_10 + TRAVEL_MASTER（门槛 10）
            insertTransaction(ctx, "expense", "1.10", yesterday.atTime(11, 0), travelId);
        }
        jdbcTemplate.update(                                // COLLAB_1
                "INSERT INTO ledger_members (ledger_id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
                ledgerId, SEQ.getAndIncrement(), LedgerMember.ROLE_EDITOR, now);
        jdbcTemplate.update(                                // INVITE_1
                "INSERT INTO invite_relations "
                        + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'REGISTERED', ?, ?)",
                userId, SEQ.getAndIncrement(), now, now, now);

        // 三个回看月各「收入 1000 / 支出 100」→ 结余 900 ≥ 储蓄门槛 200，三个储蓄月同时成立 → SAVING_MASTER。
        // 记账日仍取昨天（只改 occurred_at），故不新增记账日。
        for (String month : lookbackMonths(settleDate)) {
            LocalDate mid = YearMonth.parse(month).atDay(15);
            insertTransactionOn(ctx, "income", "1000.00", mid.atTime(10, 0), yesterday, decoyId);
            insertTransactionOn(ctx, "expense", "100.00", mid.atTime(11, 0), yesterday, decoyId);
        }
        // 前一个自然月的宽松总预算（该月支出 100 ≪ 100000）→ BUDGET_MET。
        String prevMonth = YearMonth.from(settleDate).minusMonths(1).toString();
        jdbcTemplate.update(
                "INSERT INTO budgets (user_id, ledger_id, budget_month, amount, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                userId, ledgerId, prevMonth, new BigDecimal("100000.00"), now, now);

        // 基线结算：新用户尚无档案行，本次不会被节流跳过。
        settlementService.settle(userId, TriggerSource.RECORD);
        return ctx;
    }

    /** 结算日所属月的前 3 / 2 / 1 个自然月，升序的 {@code YYYY-MM}（与需求 4.1 的回看窗口一致）。 */
    private static List<String> lookbackMonths(LocalDate settleDate) {
        YearMonth settleMonth = YearMonth.from(settleDate);
        List<String> months = new ArrayList<>(3);
        for (int back = 3; back >= 1; back--) {
            months.add(settleMonth.minusMonths(back).toString());
        }
        return months;
    }

    private long insertCategory(long userId, long ledgerId, String name, LocalDateTime now) {
        long id = SEQ.getAndIncrement();
        jdbcTemplate.update(
                "INSERT INTO categories (id, user_id, ledger_id, parent_id, kind, name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 'EXPENSE', ?, ?, ?)",
                id, userId, ledgerId, name, now, now);
        return id;
    }

    /** 一笔有效记账交易，记账日（{@code created_at}）与 {@code occurred_at} 同值。 */
    private void insertTransaction(Ctx ctx, String type, String amount,
                                  LocalDateTime occurredAt, long categoryId) {
        insertTransactionOn(ctx, type, amount, occurredAt, occurredAt.toLocalDate(), categoryId);
    }

    /** 一笔有效记账交易，{@code occurred_at} 与记账日（{@code created_at}）分别指定。 */
    private void insertTransactionOn(Ctx ctx, String type, String amount,
                                     LocalDateTime occurredAt, LocalDate recordDay, long categoryId) {
        Timestamp createdAt = Timestamp.valueOf(recordDay.atTime(12, 0));
        jdbcTemplate.update(INSERT_TX_SQL,
                ctx.userId(), ctx.ledgerId(), ctx.userId(), type, new BigDecimal(amount),
                ctx.ledgerId(), categoryId, Timestamp.valueOf(occurredAt), createdAt, createdAt);
    }

    // ---------------- 基础设施 ----------------

    /** {@code @Primary} 可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日与节流窗口可确定性驱动。 */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    /** 可推进、可归位的时钟（供每次迭代前 reset）。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        void reset(Instant to) {
            this.instant = to;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
