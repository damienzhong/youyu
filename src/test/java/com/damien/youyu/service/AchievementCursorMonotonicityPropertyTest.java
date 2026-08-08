package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import com.damien.youyu.error.ApiException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 7：播报游标单调不减且并发终态取最大值</b>的属性测试（任务 8.4）。
 *
 * <p><i>对任意</i> {@code lastEventId} 请求序列（长度 1–30，取值 ∈ {@code {null}}、{@code ""}、
 * {@code "abc"}、{@code "-1"}、{@code "0"}、当前游标、当前游标 ±1、最大 {@code BADGE} id、
 * 最大 id + 1、随机合法值）× 并发度 ∈ [1, 8] × 初始有/无游标行，断言四条不变式：</p>
 *
 * <ul>
 *   <li><b>单调不减</b>（需求 5.9）：每个批次之后的 {@code last_notified_event_id} 大于或等于该批次
 *       之前的取值；每个成功响应回传的取值也落在 {@code [批次前游标, 批次后游标]} 内。</li>
 *   <li><b>终态 == {@code max(合法取值 ∪ {初始值})}</b>（需求 5.7、5.8、5.10）：不只在序列末尾断言，
 *       而是<b>每个批次</b>都断言 {@code 批次后游标 == max(批次前游标, 该批次内合法取值的最大者)}
 *       ——逐批次的等式比只看终态强得多：终态相等可以被「中途冲高又被别的请求压回、最后恰好又冲到同一个
 *       最大值」蒙混过关，逐批次等式不能。</li>
 *   <li><b>行数终态恒为 1</b>（需求 5.10、5.11）：只要初始有行或序列里出现过<b>任何一个</b>合法取值。
 *       全部请求皆非法且初始无行时行数恒为 0——需求 5.12 明确要求此时「行数与全部列取值不变」，
 *       把这种情形也断言成 1 是错的，故本类按「有行的充要条件」断言，比无条件断言 1 更强。</li>
 *   <li><b>非法请求前后表快照逐列相等</b>（需求 5.12）：整张 {@code achievement_notices}（不止本用户
 *       那一行）四列逐列比对，且比对的是<b>库里读回</b>的行而不是服务返回的对象。</li>
 * </ul>
 *
 * <h2>成立方式：构造性（本测试只负责锁住它，防回归）</h2>
 *
 * <p>游标推进是<b>一条</b> {@code INSERT ... ON DUPLICATE KEY UPDATE ... GREATEST(旧值, 新值)}
 * 语句，单调性、重复确认幂等与并发终态取最大值三条压在同一条 SQL 里，没有「先读后写」的竞态窗口，
 * 也不需要行锁。因此本属性的价值在于回归锁：一旦有人把 {@code GREATEST} 换成 {@code = ?}、
 * 或改成「先 {@code findById} 比一比再 {@code save}」，逐批次等式与并发批次会立刻变红。</p>
 *
 * <h2>H2 上可以断言什么，什么只能进 MySQL 人工清单</h2>
 *
 * <p>本类跑在 H2（{@code MODE=MySQL}）上，与 {@code AchievementBroadcastIntegrationTest} 划的是<b>同
 * 一条线</b>，理由也相同：{@code updated_at} 的 {@code CASE} 必须写在
 * {@code last_notified_event_id = GREATEST(...)} <b>之前</b>，这条依赖来自 MySQL「ODKU 赋值列表按书写
 * 顺序从左到右求值、右侧读到左侧的新值」的语义，任务 1.5 已在 MySQL {@code 8.0.46} 上正反两面实测
 * （设计写法：{@code updated_at} 随推进而推进；两句调换的反例：{@code updated_at} 永久停在首次写入
 * 时刻）。H2 对该求值顺序不做承诺，所以：</p>
 *
 * <ul>
 *   <li><b>本类断言（与赋值求值顺序无关，H2 与 MySQL 上同真）</b>：
 *     <ul>
 *       <li>逐批次 {@code last_notified_event_id == max(批次前游标, 批次内合法取值最大者)}
 *           ——这是 {@code GREATEST} 的语义，与两句赋值的先后无关；</li>
 *       <li>批次内全部合法取值都 ≤ 批次前游标（纯重复确认）时<b>四列全部不变</b>，含
 *           {@code updated_at}：此时 {@code GREATEST(旧, v ≤ 旧) == 旧}，故
 *           {@code ? > last_notified_event_id} 无论读到旧值还是读到「新值」都恒为假，
 *           <b>两种求值顺序下结论相同</b>；</li>
 *       <li>首次建行时 {@code created_at == updated_at}：走的是纯 {@code INSERT} 分支
 *           （{@code VALUES (?, ?, ?, ?)} 两列取同一个 {@code now}），完全不经过 ODKU 赋值列表；</li>
 *       <li>真实推进时 {@code created_at} 不变：ODKU 的赋值列表<b>根本不提及</b>该列；</li>
 *       <li>非法请求前后整表四列逐列相等：该路径一条写语句都不发；</li>
 *       <li>该用户的行数：主键约束下恒 ≤1，且「有行」的充要条件如上。</li>
 *     </ul>
 *   </li>
 *   <li><b>本类不断言（只属于 MySQL 人工清单，见任务 1.5 与 design.md「5. 播报游标」的实测结论块）</b>：
 *     <ul>
 *       <li>「真实推进时 {@code updated_at} 同时被推进到本次请求时刻」——它是否成立<b>只</b>取决于 ODKU
 *           赋值列表的求值顺序。在 H2 上断言它，要么锁死一个 H2 特有的实现细节，要么以一个与生产语句
 *           无关的理由挂掉；两种结果都不是这条属性想要的；</li>
 *       <li>把两句顺序调换后 {@code updated_at} 不再推进的<b>反例</b>——同上，MySQL 上实测过，
 *           H2 上无从判定。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>「什么都没变」类断言的非空洞守卫</h2>
 *
 * <p>{@code ack} 的数据库异常会被 {@code cursorDegraded} 就地降级成「返回当前游标、不报错」
 * （需求 5.19），因此「表一字未变」在语句压根没执行成功时也会通过。三道守卫使它非空洞：
 * ① 逐批次等式要求真实推进的批次<b>确实</b>把 {@code last_notified_event_id} 改成了那个最大值；
 * ② 每次迭代前把时钟推进 1 秒（{@link #STEP}），于是「{@code updated_at} 未变」是在时钟已经走了的
 * 前提下成立的，而不是因为两次取的是同一个时刻；
 * ③ {@link #everyIllegalKindIsRejectedAndLeavesTableIdentical_thenLegalAckAdvances()} 在把 11 类
 * 非法取值逐个打完之后紧接着打一发合法取值，断言它真的推进了。</p>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 *
 * <p>{@code ack} 的写语句走 {@link JdbcTemplate} 自动提交，并发批次必须真实<b>提交</b>才能观察到终态，
 * 故本类<b>不用测试级事务包裹</b>（与同 spec 既有两个属性测试类同一取舍：{@code settle} 带
 * {@code REQUIRES_NEW}）；清理相应地不能靠回滚，由 {@link #resetState()} 每次迭代前显式清表，
 * 并用全局自增序号 {@link #SEQ} 保证 {@code userId} 全局唯一（双重隔离）。时钟用 {@code @Primary}
 * 的可推进 {@link MutableClock}，使 {@code created_at} / {@code updated_at} 的比对可确定性驱动。
 * jqwik 属性方法不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 里手工完成（上下文缓存复用，多次迭代只加载一次）。</p>
 *
 * <h2>并发批次怎么构造（需求 5.10 的「1000 毫秒内并发」）</h2>
 *
 * <p>序列按并发度切成若干批次，同一批次的请求用一个 {@link CountDownLatch} <b>同时释放</b>，
 * 因此它们进入 {@code ack} 的时刻落在同一毫秒量级的窗口内，远小于需求所说的 1000 毫秒。
 * 「当前游标 / ±1」这三类取值按<b>批次开始前</b>读到的游标解析——并发请求的入参本来就只能由客户端
 * 在发起前算出，这正是需求 5.10 所描述的情形。<b>刻意不断言「批次耗时 &lt; 1000ms」</b>：
 * 那是一条会随 CI 负载随机变红的时间断言，而需求 5.10 要保证的是终态取最大值，不是吞吐。</p>
 *
 * <p>另建一个<b>诱饵用户</b>的游标行，每个批次后断言它四列一字不变：跨用户串写（例如 ODKU 漏了
 * {@code user_id}、或降级分支读错了行）在单用户测试里完全观察不到。</p>
 *
 * <p>Feature: achievement-system, Property 7: 播报游标单调不减且并发终态取最大值</p>
 *
 * <p>Validates: Requirements 5.7, 5.8, 5.9, 5.10, 5.11, 5.12, 5.13</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-cursor-pt;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 同一批次最多 8 个 ack 各自占用一个连接，抬高池上限避免误报为「获取连接超时」。
        "spring.datasource.hikari.maximum-pool-size=32"
})
@Import(AchievementCursorMonotonicityPropertyTest.ClockConfig.class)
class AchievementCursorMonotonicityPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 每个批次前的时钟推进量：使「{@code updated_at} 未变」不是因为两次取到同一个时刻。 */
    private static final Duration STEP = Duration.ofSeconds(1);

    /** 全局自增序号：保证 {@code userId} 跨迭代全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(830_000_000L);

    /**
     * 播种的 {@code BADGE} 行所用编码（取自需求 1.1 表格，顺序即写入顺序）。
     *
     * <p>播种 5 行使 {@code maxBadgeEventId} 有一个真实上界，且「初始游标取中间值」有值可取
     * ——上界为 0 时「当前游标 −1」「maxId + 1」这些档位会退化成同一个非法取值。</p>
     */
    private static final List<String> SEED_CODES =
            List.of("FIRST_RECORD", "STREAK_7", "RECORD_10", "INVITE_1", "COLLAB_1");

    /** 播种行的时刻：远早于时钟基准，使「初始行的 {@code created_at} 未被改动」可判定。 */
    private static final LocalDateTime SEED_AT = LocalDateTime.of(2024, 1, 2, 3, 4, 5);

    /** 诱饵用户的初始游标取值（跨用户隔离哨兵）。 */
    private static final long DECOY_CURSOR = 7L;

    private static final String INSERT_EVENT_SQL =
            "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                    + "VALUES (?, ?, ?, ?, ?)";

    private static final String INSERT_NOTICE_SQL =
            "INSERT INTO achievement_notices "
                    + "(user_id, last_notified_event_id, created_at, updated_at) VALUES (?, ?, ?, ?)";

    /** 游标表的四列，逐列比对时的取列顺序。 */
    private static final String NOTICE_COLUMNS =
            "user_id, last_notified_event_id, created_at, updated_at";

    @Autowired
    private AchievementQueryService queryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(AchievementCursorMonotonicityPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // ack 的写语句自动提交，清理不能靠回滚：每次迭代前硬删游标表与事件表（两表间无外键）。
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM growth_events");
    }

    // ---------------- 生成器 ----------------

    /**
     * 一次请求的取值档位（需求 5.12 列举的四类非法 + 需求 5.6 的边界 + 随机合法值）。
     *
     * <p>{@link #CURRENT_MINUS_ONE} 在游标为 0 时解析成 {@code "-1"}，因此它同时覆盖合法与非法两侧
     * ——档位是「怎么算出这个入参」，合法性由 {@link #legalValueOf} 独立判定。</p>
     */
    enum ValueKind {
        /** 入参缺失（需求 5.12）。 */
        NULL,
        /** 空字符串（需求 5.12）。 */
        EMPTY,
        /** 无法解析为整数（需求 5.12）。 */
        NOT_A_NUMBER,
        /** 小于 0（需求 5.12）。 */
        NEGATIVE,
        /** 下界 0：无 {@code BADGE} 行时唯一的合法取值（需求 5.13）。 */
        ZERO,
        /** 恰好等于当前游标：重复确认（需求 5.8）。 */
        CURRENT,
        /** 当前游标 + 1：越上界时非法，否则真实推进。 */
        CURRENT_PLUS_ONE,
        /** 当前游标 − 1：游标为 0 时退化为 {@code "-1"}（非法）。 */
        CURRENT_MINUS_ONE,
        /** 恰好等于允许取值上界（需求 5.6）。 */
        MAX_ID,
        /** 越上界（需求 5.12）。 */
        MAX_ID_PLUS_ONE,
        /** {@code [0, maxId]} 内的随机合法值。 */
        RANDOM_LEGAL
    }

    /** 初始游标状态：无行，或有行且取值分别为下界 / 中间 / 上界。 */
    enum InitialCursor {
        /** 无游标行：游标按 0 计（需求 5.3），首次合法请求要在本次请求内建行（需求 5.11）。 */
        ABSENT,
        /** 有行且取值 0。 */
        ZERO,
        /** 有行且取值为第 3 枚 {@code BADGE} 事件的 id。 */
        MIDDLE,
        /** 有行且取值已是上界：此后任何合法请求都只是重复确认。 */
        MAX
    }

    /**
     * 一次请求：档位 + 一个用于 {@link ValueKind#RANDOM_LEGAL} 取值的百分位。
     *
     * @param percent {@code [0, 100]}，随机合法值取 {@code maxId * percent / 100}
     */
    record Request(ValueKind kind, int percent) {
    }

    /** 请求序列：长度 1–30，元素随机（含重复，正是幂等要考验的东西）。 */
    @Provide
    Arbitrary<List<Request>> requests() {
        Arbitrary<Request> one = Combinators.combine(
                Arbitraries.of(ValueKind.class),
                Arbitraries.integers().between(0, 100)
        ).as(Request::new);
        return one.list().ofMinSize(1).ofMaxSize(30);
    }

    // ---------------- Property 7 ----------------

    /**
     * Feature: achievement-system, Property 7: 播报游标单调不减且并发终态取最大值
     *
     * <p>把请求序列按并发度切成批次逐批执行，<b>每个批次</b>之后复核：单调不减、
     * {@code 游标 == max(批次前游标, 批次内合法取值最大者)}、行数、{@code created_at} 与
     * （纯重复确认批次的）四列不变、非法批次整表逐列相等、诱饵用户的行一字不变。
     * 序列结束后再复核终态 {@code == max(全部合法取值 ∪ {初始值})} 与行数。</p>
     *
     * <p>Validates: Requirements 5.7, 5.8, 5.9, 5.10, 5.11, 5.12, 5.13</p>
     */
    @Property(tries = 15)
    void property7_cursorIsMonotonicAndFinalStateIsMaxOfLegalValues(
            @ForAll("requests") List<Request> requests,
            @ForAll @IntRange(min = 1, max = 8) int concurrency,
            @ForAll InitialCursor initial) throws Exception {

        long userId = SEQ.getAndIncrement();
        long decoyUserId = SEQ.getAndIncrement();
        List<Long> badgeIds = seedBadges(userId);
        long maxId = badgeIds.get(badgeIds.size() - 1);

        // 跨用户隔离哨兵：另一个用户的游标行，全程一字不许变。
        insertNotice(decoyUserId, DECOY_CURSOR);
        Map<String, Object> decoyRow = noticeRow(decoyUserId);

        long initialValue = switch (initial) {
            case ABSENT, ZERO -> 0L;
            case MIDDLE -> badgeIds.get(2);
            case MAX -> maxId;
        };
        boolean rowPresent = initial != InitialCursor.ABSENT;
        if (rowPresent) {
            insertNotice(userId, initialValue);
        }

        long expectedFinal = initialValue;
        boolean anyLegalOverall = false;

        for (int from = 0; from < requests.size(); from += concurrency) {
            List<Request> batch = requests.subList(from, Math.min(from + concurrency, requests.size()));
            String stage = "第 " + (from / concurrency + 1) + " 个批次（并发度 " + batch.size() + "）";

            // 时钟往前走一步：使本批次写入的 updated_at 与上一批次必然不同（非空洞守卫 ②）。
            CLOCK.advance(STEP);

            long cursorBefore = cursorOf(userId);
            Map<String, Object> rowBefore = rowPresent ? noticeRow(userId) : null;
            List<Map<String, Object>> tableBefore = wholeTable();

            // 「当前游标 / ±1」按批次开始前读到的游标解析：并发请求的入参只能由客户端在发起前算出。
            List<String> raws = new ArrayList<>(batch.size());
            for (Request request : batch) {
                raws.add(rawOf(request, cursorBefore, maxId));
            }
            List<OptionalLong> legals = new ArrayList<>(raws.size());
            for (String raw : raws) {
                legals.add(legalValueOf(raw, maxId));
            }
            OptionalLong maxLegal = legals.stream()
                    .filter(OptionalLong::isPresent)
                    .mapToLong(OptionalLong::getAsLong)
                    .max();
            long expectedAfter = maxLegal.isPresent()
                    ? Math.max(cursorBefore, maxLegal.getAsLong())
                    : cursorBefore;

            List<Outcome> outcomes = fire(userId, raws);

            // ── 逐请求：非法必须被拒且带对的错误码与字段；合法必须回传落在 [批次前, 批次后] 的取值 ──
            for (int i = 0; i < raws.size(); i++) {
                Outcome outcome = outcomes.get(i);
                if (legals.get(i).isEmpty()) {
                    assertThat(outcome.rejection())
                            .as("%s 的非法取值 %s 必须被拒（需求 5.12）", stage, display(raws.get(i)))
                            .isNotNull();
                    assertThat(outcome.rejection().getCode())
                            .as("%s 的非法取值 %s 的错误码", stage, display(raws.get(i)))
                            .isEqualTo("ACHIEVEMENT_ACK_PARAM_INVALID");
                    assertThat(outcome.rejection().getField())
                            .as("%s 的非法取值 %s 的出错字段", stage, display(raws.get(i)))
                            .isEqualTo("lastEventId");
                } else {
                    assertThat(outcome.rejection())
                            .as("%s 的合法取值 %s 不该被拒（需求 5.7、5.8、5.13）", stage, display(raws.get(i)))
                            .isNull();
                    assertThat(outcome.returned())
                            .as("%s 的合法取值 %s 回传的游标必须落在 [批次前, 批次后] 内（需求 5.9）",
                                    stage, display(raws.get(i)))
                            .isBetween(cursorBefore, expectedAfter);
                }
            }

            // ── 批次后的库状态 ────────────────────────────────────────────────
            long cursorAfter = cursorOf(userId);
            assertThat(cursorAfter)
                    .as("%s 之后游标单调不减（需求 5.9）", stage)
                    .isGreaterThanOrEqualTo(cursorBefore);
            assertThat(cursorAfter)
                    .as("%s 之后游标 == max(批次前游标, 批次内合法取值最大者)（需求 5.7、5.8、5.10）", stage)
                    .isEqualTo(expectedAfter);

            if (maxLegal.isEmpty()) {
                // 需求 5.12：全部非法 → 整张表的行数与四列取值一字不变（含诱饵用户那一行）。
                assertThat(wholeTable())
                        .as("%s 全部请求非法，游标表前后逐列相等（需求 5.12）", stage)
                        .isEqualTo(tableBefore);
            } else {
                anyLegalOverall = true;
                expectedFinal = Math.max(expectedFinal, maxLegal.getAsLong());

                assertThat(noticeRowCount(userId))
                        .as("%s 之后该用户在游标表中恰好 1 行（需求 5.10）", stage)
                        .isEqualTo(1L);
                Map<String, Object> rowAfter = noticeRow(userId);
                if (!rowPresent) {
                    // 需求 5.11：本次请求内建行，created_at 与 updated_at 为同一服务端时刻。
                    // 走的是纯 INSERT 分支，不经过 ODKU 赋值列表，故在 H2 上也是合法断言。
                    assertThat(column(rowAfter, "created_at"))
                            .as("%s 首次建行时 created_at 与 updated_at 为同一时刻（需求 5.11）", stage)
                            .isEqualTo(column(rowAfter, "updated_at"));
                    assertThat(column(rowAfter, "created_at"))
                            .as("%s 首次建行的时刻取本次请求的服务端时刻", stage)
                            .isEqualTo(Timestamp.valueOf(LocalDateTime.now(CLOCK)));
                    rowPresent = true;
                } else {
                    // ODKU 的赋值列表根本不提及 created_at：它在任何求值顺序下都不该变。
                    assertThat(column(rowAfter, "created_at"))
                            .as("%s 推进游标不改 created_at", stage)
                            .isEqualTo(column(rowBefore, "created_at"));
                    if (expectedAfter == cursorBefore) {
                        // 纯重复确认（批次内全部合法取值 ≤ 批次前游标）：四列全部不变，含 updated_at
                        // ——GREATEST(旧, v ≤ 旧) == 旧 使 CASE 在两种求值顺序下同为假（见类级 Javadoc）。
                        assertThat(rowAfter)
                                .as("%s 是纯重复确认，四列全部不变（需求 5.8）", stage)
                                .isEqualTo(rowBefore);
                    }
                }
            }

            assertThat(noticeRow(decoyUserId))
                    .as("%s 之后其它用户的游标行一字不变", stage)
                    .isEqualTo(decoyRow);
        }

        // ── 终态 ──────────────────────────────────────────────────────────
        assertThat(cursorOf(userId))
                .as("终态 == max(全部合法取值 ∪ {初始值})（需求 5.7、5.8、5.10）")
                .isEqualTo(expectedFinal);
        assertThat(noticeRowCount(userId))
                .as("行数终态：初始有行或出现过任一合法取值时恰好 1 行，否则恒 0（需求 5.10、5.12）")
                .isEqualTo((initial != InitialCursor.ABSENT || anyLegalOverall) ? 1L : 0L);
    }

    /**
     * 11 类取值档位逐个打完之后紧接着打一发合法取值：非法的一律被拒且整表一字不变，
     * 合法的确实把游标推进了（「什么都没变」类断言的非空洞守卫 ③，见类级 Javadoc）。
     *
     * <p>这是属性方法覆盖不到的一个角：随机序列不保证把 11 个档位都取到，也不保证「非法请求紧跟一发
     * 合法请求」这个次序出现。它同时把需求 5.13 的「无 {@code BADGE} 行时以 0 请求应被接受」单列
     * 一段——那一段必须在一个<b>没有</b>播种任何 {@code BADGE} 行的用户上验。</p>
     *
     * <p>Validates: Requirements 5.12, 5.13</p>
     */
    @Example
    void everyIllegalKindIsRejectedAndLeavesTableIdentical_thenLegalAckAdvances() {
        long userId = SEQ.getAndIncrement();
        List<Long> badgeIds = seedBadges(userId);
        long maxId = badgeIds.get(badgeIds.size() - 1);
        long initialValue = badgeIds.get(2);
        insertNotice(userId, initialValue);

        List<String> illegal = new ArrayList<>();
        for (ValueKind kind : ValueKind.values()) {
            String raw = rawOf(new Request(kind, 50), initialValue, maxId);
            if (legalValueOf(raw, maxId).isEmpty()) {
                illegal.add(raw);
            }
        }
        assertThat(illegal)
                .as("档位里必须确实含非法取值，否则本用例沦为空洞")
                .isNotEmpty();

        for (String raw : illegal) {
            CLOCK.advance(STEP);
            List<Map<String, Object>> before = wholeTable();
            Outcome outcome = invoke(userId, raw);
            assertThat(outcome.rejection())
                    .as("非法取值 %s 必须被拒（需求 5.12）", display(raw))
                    .isNotNull();
            assertThat(outcome.rejection().getCode()).isEqualTo("ACHIEVEMENT_ACK_PARAM_INVALID");
            assertThat(outcome.rejection().getField()).isEqualTo("lastEventId");
            assertThat(wholeTable())
                    .as("非法取值 %s 被拒后游标表逐列相等（需求 5.12）", display(raw))
                    .isEqualTo(before);
        }

        // 非空洞守卫：紧接着一发合法取值确实推进了游标，证明上面的「一字未变」不是因为语句根本没生效。
        CLOCK.advance(STEP);
        assertThat(invoke(userId, String.valueOf(maxId)).returned())
                .as("合法取值必须真实推进游标（需求 5.7）")
                .isEqualTo(maxId);
        assertThat(cursorOf(userId)).isEqualTo(maxId);

        // 需求 5.13：没有任何 BADGE 行的用户以 0 请求推进，应被接受并返回 0。
        long emptyUserId = SEQ.getAndIncrement();
        CLOCK.advance(STEP);
        Outcome zero = invoke(emptyUserId, "0");
        assertThat(zero.rejection())
                .as("无 BADGE 行时以 0 请求推进应被接受（需求 5.13）")
                .isNull();
        assertThat(zero.returned()).isZero();
        assertThat(noticeRowCount(emptyUserId))
                .as("该次请求内创建游标行（需求 5.11）")
                .isEqualTo(1L);
        assertThat(invoke(emptyUserId, "1").rejection())
                .as("无 BADGE 行时上界按 0 计，取值 1 越界（需求 5.6、5.12）")
                .isNotNull();
    }

    // ---------------- 取值解析与参考合法性判定 ----------------

    /** 档位 → 请求入参原文（{@code null} 表示入参缺失）。 */
    private static String rawOf(Request request, long cursor, long maxId) {
        return switch (request.kind()) {
            case NULL -> null;
            case EMPTY -> "";
            case NOT_A_NUMBER -> "abc";
            case NEGATIVE -> "-1";
            case ZERO -> "0";
            case CURRENT -> String.valueOf(cursor);
            case CURRENT_PLUS_ONE -> String.valueOf(cursor + 1);
            case CURRENT_MINUS_ONE -> String.valueOf(cursor - 1);
            case MAX_ID -> String.valueOf(maxId);
            case MAX_ID_PLUS_ONE -> String.valueOf(maxId + 1);
            case RANDOM_LEGAL -> String.valueOf(maxId * request.percent() / 100);
        };
    }

    /**
     * 参考模型：入参原文的合法取值，非法时返回空（需求 5.6、5.12 的<b>独立副本</b>）。
     *
     * <p>刻意不复用 {@code AchievementQueryService} 的解析与校验代码：共用一份实现的话，
     * 「非法必须被拒」会随实现一起漂移而永远自我一致。四种非法情形逐条照需求 5.12 写：
     * 缺失 / 空白 / 不可解析为整数 / 小于 0，再加上需求 5.6 的上界。</p>
     */
    private static OptionalLong legalValueOf(String raw, long maxId) {
        if (raw == null || raw.isBlank()) {
            return OptionalLong.empty();
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        if (value < 0 || value > maxId) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(value);
    }

    private static String display(String raw) {
        return raw == null ? "null" : "\"" + raw + "\"";
    }

    // ---------------- 请求执行 ----------------

    /** 一次 {@code ack} 的结果：要么回传游标取值，要么被 {@link ApiException} 拒绝。 */
    private record Outcome(Long returned, ApiException rejection) {
    }

    /**
     * 同一批次的请求<b>同时</b>发起（需求 5.10）。批次只有一个请求时直接串行执行
     * ——并发度 1 的语义就是串行，套一层线程池只会让失败堆栈更难读。
     */
    private List<Outcome> fire(long userId, List<String> raws) throws Exception {
        if (raws.size() == 1) {
            return List.of(invoke(userId, raws.get(0)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(raws.size());
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>(raws.size());
            for (String raw : raws) {
                Callable<Outcome> task = () -> {
                    assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
                    return invoke(userId, raw);
                };
                futures.add(pool.submit(task));
            }
            gate.countDown();
            List<Outcome> outcomes = new ArrayList<>(raws.size());
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 调一次生产的 {@code ack}；入参非法的 {@link ApiException} 是正常答复，捕获下来供逐条断言。 */
    private Outcome invoke(long userId, String raw) {
        try {
            return new Outcome(queryService.ack(userId, raw).lastNotifiedEventId(), null);
        } catch (ApiException e) {
            return new Outcome(null, e);
        }
    }

    // ---------------- 播种与库读取 ----------------

    /**
     * 播种 {@link #SEED_CODES} 五条与结算写出的行<b>逐列同形</b>的 {@code BADGE} 行，返回其 id 升序列表。
     *
     * <p>{@code BADGE} 行的写入路径在生产代码里只有结算一处（仓储刻意不提供单行写入方法），
     * 因此播种任意初始状态只能走原生 SQL；形状与结算写出的完全一致
     * （{@code event_type = 'BADGE'}、{@code event_key = 'BADGE:<清单编码>'}、{@code exp_amount = 0}）。</p>
     */
    private List<Long> seedBadges(long userId) {
        for (String code : SEED_CODES) {
            jdbcTemplate.update(INSERT_EVENT_SQL, userId, GrowthEventType.BADGE,
                    GrowthBadgeCatalog.eventKeyOf(code), 0, Timestamp.valueOf(SEED_AT));
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM growth_events WHERE user_id = ? AND event_type = 'BADGE' ORDER BY id",
                Long.class, userId);
        assertThat(ids).as("播种的 BADGE 行数").hasSize(SEED_CODES.size());
        return ids;
    }

    /** 直插一行初始游标（{@code created_at} / {@code updated_at} 取 {@link #SEED_AT}）。 */
    private void insertNotice(long userId, long cursor) {
        Timestamp at = Timestamp.valueOf(SEED_AT);
        jdbcTemplate.update(INSERT_NOTICE_SQL, userId, cursor, at, at);
    }

    /** 当前游标取值，无行时按 0 计（需求 5.3）。 */
    private long cursorOf(long userId) {
        Long cursor = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(last_notified_event_id), 0) FROM achievement_notices WHERE user_id = ?",
                Long.class, userId);
        return cursor == null ? 0L : cursor;
    }

    private long noticeRowCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM achievement_notices WHERE user_id = ?", Long.class, userId);
        return count == null ? 0L : count;
    }

    /**
     * 按<b>列名大小写不敏感</b>地取一列，缺列时抛错。
     *
     * <p>H2 回传的列标签是<b>大写</b>的（{@code CREATED_AT}），MySQL 上是小写。直接
     * {@code row.get("created_at")} 会在 H2 上静默拿到 {@code null}，于是「两列相等」「该列未变」
     * 这类断言会因为两侧都是 {@code null} 而恒真——那是最难发现的一种假绿。缺列即抛错，
     * 使这种写错列名的情形立刻暴露。</p>
     */
    private static Object column(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        throw new IllegalStateException("游标行缺少列 " + column + "，实际列为 " + row.keySet());
    }

    /** 某用户的游标行四列，无行时返回空 map（供逐列比对）。 */
    private Map<String, Object> noticeRow(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + NOTICE_COLUMNS + " FROM achievement_notices WHERE user_id = ?", userId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** 整张游标表的四列快照，按 {@code user_id} 升序（非法请求前后逐列比对的依据，需求 5.12）。 */
    private List<Map<String, Object>> wholeTable() {
        return jdbcTemplate.queryForList(
                "SELECT " + NOTICE_COLUMNS + " FROM achievement_notices ORDER BY user_id");
    }

    // ---------------- 基础设施 ----------------

    /** {@code @Primary} 可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使两个时刻列可确定性驱动。 */
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
