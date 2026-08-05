package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.LedgerMember;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 1：成就解锁幂等（任意操作序列后每枚成就至多一行）</b>的属性测试（任务 8.1）。
 *
 * <p><i>对任意</i>由记账、旅行记账、软删除、恢复、改预算、邀请、加成员、改分类名、直接结算、
 * 请求成就清单组成的操作序列（长度 1–40，用户池 2–5，其中含 2–8 个结算<b>同时</b>发起的并发爆发）：</p>
 *
 * <ul>
 *   <li>{@code growth_events} 中以任一 {@code (user_id, 'BADGE:<编码>')} 为键的<b>行数恒 ∈ {0, 1}</b>
 *       （需求 2.1、2.7、2.9）；</li>
 *   <li>已存在那一行的 {@code id} / {@code event_type} / {@code exp_amount} / {@code created_at}
 *       在后续任意操作之后<b>逐列不变</b>（需求 2.5、2.8）——首次写入的行既不被覆写也不被换新。</li>
 * </ul>
 *
 * <h2>「读库比对，不是内存值」是这条属性的关键</h2>
 *
 * <p>全部快照一律经 {@link JdbcTemplate} 从 {@code growth_events} <b>重新读回</b>再逐列比对
 * （见 {@link #badgeSnapshot(long)}），不缓存任何服务层返回的对象、也不比对
 * {@code AchievementListResponse} 里的 {@code eventId} / {@code unlockedAt}。理由：接口投影出来的
 * 「解锁时刻」如果被实现改成了「当前时刻」或「本次结算时刻」，内存比对照样能自我一致地相等，
 * 唯有把库里的四列原值读出来比才能发现行被覆写。同理「行数 ≤1」用
 * {@code GROUP BY event_key HAVING COUNT(*) > 1} 在库里判定，而不是数内存集合的元素个数。</p>
 *
 * <h2>成立方式：构造性（本测试只负责锁住它，防回归）</h2>
 *
 * <p>幂等由 {@code uk_growth_events_user_key} 唯一索引 + 批量插入语句的
 * {@code ON DUPLICATE KEY UPDATE id = id} 在<b>数据库层</b>承担，应用层不做「先查再写」
 * ——{@code GrowthSettlementService.add(...)} 里那道 {@code existingKeys} 过滤只是减少无效写入的优化，
 * 不承担唯一性（需求 2.7）。因此并发结算的终态与串行相同，个体结算的成败无关紧要。</p>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 *
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有真实<b>提交</b>才能在库里观察到终态，
 * 故本类<b>不用测试级事务包裹</b>；清理相应地不能靠回滚，由 {@link #resetState()} 每次迭代前显式清表，
 * 并用全局自增序号 {@link #SEQ} 保证 {@code userId} / {@code ledgerId} 全局唯一（双重隔离）。
 * 时钟用 {@code @Primary} 的可推进 {@link MutableClock}（固定 {@code Asia/Shanghai} 的
 * {@code 2025-06-15 08:00}），每次结算前推进 61 秒即可越过记账侧 60 秒节流窗口与概览侧 10 秒窗口，
 * 而不跨自然日——结算日恒为 {@code 2025-06-15}。jqwik 属性方法不经 {@code SpringExtension}，
 * 依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成（上下文缓存复用）。</p>
 *
 * <h2>并发爆发怎么构造（需求 2.9 的「1000 毫秒内并发」）</h2>
 *
 * <p>{@link #runConcurrentSettlements(long, int)} 用一个 {@link CountDownLatch} 把 2–8 个线程<b>同时
 * 释放</b>，因此它们进入 {@code settle} 的时刻落在同一毫秒量级的窗口内，远小于需求所说的 1000 毫秒；
 * 个体异常（含 H2 上的争锁失败、{@code GrowthLockAbandonedException}）按生产语义就地吞掉
 * ——生产里这层吞异常由 {@code GrowthSettlementTrigger.settleQuietly} 负责，本属性只锁终态。
 * <b>刻意不断言「爆发耗时 &lt; 1000ms」</b>：那是一条会随 CI 负载随机变红的时间断言，
 * 而需求 2.9 要保证的是终态每键至多一行，不是吞吐。</p>
 *
 * <h2>反向断言：把批量插入改成 {@code INSERT IGNORE} 时必须变红</h2>
 *
 * <p>见 {@link #reverseAssertion_batchInsertOnlyIgnoresDuplicateKeys()}。它<b>用反射读取生产语句本身</b>
 * （{@code GrowthSettlementService.INSERT_EVENT_SQL}）去写非法数据，而不是在测试里另抄一份 SQL——
 * 抄一份的话「改了生产就变红」只是一句注释里的承诺，反射读则是机械保证。</p>
 *
 * <p>Feature: achievement-system, Property 1: 成就解锁幂等（任意操作序列后每枚成就至多一行）</p>
 *
 * <p>Validates: Requirements 2.1, 2.5, 2.7, 2.8, 2.9</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-idem-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 并发结算（每个各自 REQUIRES_NEW）在争锁窗口内会短暂占用多个连接，抬高池上限避免误报为「获取连接超时」。
        "spring.datasource.hikari.maximum-pool-size=32"
})
@Import(AchievementIdempotencyPropertyTest.ClockConfig.class)
class AchievementIdempotencyPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）：结算日恒为 2025-06-15，全程不越自然日。 */
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 越过记账侧 60 秒节流窗口（也顺带越过概览侧 10 秒窗口）的推进量。 */
    private static final Duration BEYOND_THROTTLE = Duration.ofSeconds(61);

    /** 结算日往前第 1 个自然月：用于 {@code CHANGE_BUDGET} 造预算达成 / 不达成的翻转。 */
    private static final String PREV_MONTH = "2025-05";
    private static final LocalDate PREV_MONTH_DAY = LocalDate.of(2025, 5, 10);

    /** 全局自增序号：保证跨迭代 userId / ledgerId / 成员 id / 被邀请人 id 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(710_000_000L);

    /**
     * 16 枚成就的编码（需求 1.1 表格的独立副本）：终态逐编码断言「行数 ∈ {0, 1}」时的枚举依据，
     * 刻意不从 {@code GrowthBadgeCatalog} 取，避免测试与被测共用同一处清单。
     */
    private static final List<String> EXPECTED_CODES = List.of(
            "FIRST_RECORD",
            "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365",
            "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000", "DAYS_100",
            "INVITE_1", "COLLAB_1",
            "BUDGET_MET", "BUDGET_MASTER", "SAVING_MASTER", "TRAVEL_MASTER");

    /** 交易直插语句：列顺序与 {@link #insertTransaction} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** H2 上补建的两个 CHECK 约束是否已就绪（进程内只需补建一次，见 {@link #ensureCheckConstraints()}）。 */
    private static volatile boolean checksReady = false;

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private AchievementQueryService achievementQueryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(AchievementIdempotencyPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        ensureCheckConstraints();
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

    /**
     * 在 H2 上补建迁移脚本 {@code V33__achievement.sql} / {@code V32__user_growth.sql} 的两个 CHECK
     * 约束的等价物，使反向断言非空洞。
     *
     * <p>测试库表结构由 Hibernate 依实体 {@code ddl-auto=create-drop} 生成，而 {@code GrowthEvent}
     * 实体<b>不声明</b>这两个 CHECK（{@code ck_growth_events_type} 的七元取值集合 /
     * {@code ck_growth_events_exp} 的 {@code exp_amount >= 0}），因此裸 H2 表不会自动带上它们——
     * 若不补建，「非法写入必须抛错」会因为「非法插入竟然成功」而沦为假绿。</p>
     *
     * <p>取值集合与迁移脚本逐项一致地扩到<b>七项</b>（本 spec 新增 {@code SAVING_MONTH}）。H2 不支持
     * {@code COLLATE utf8mb4_bin}，故这里省去该子句：「区分大小写」那一维度
     * （{@code 'saving_month'} / {@code 'Badge'} / {@code 'BADGE '} 被 {@code ERROR 3819} 拒）
     * 的<b>最终确认属于真实 MySQL 的人工清单</b>（任务 1.4 已完成并回写 design.md），本处不冒充。</p>
     */
    private void ensureCheckConstraints() {
        if (checksReady) {
            return;
        }
        synchronized (AchievementIdempotencyPropertyTest.class) {
            if (checksReady) {
                return;
            }
            jdbcTemplate.execute(
                    "ALTER TABLE growth_events ADD CONSTRAINT IF NOT EXISTS ck_growth_events_type "
                            + "CHECK (event_type IN ('FIRST_RECORD','DAILY_RECORD','STREAK','BUDGET_MET',"
                            + "'FIRST_INVITE','SAVING_MONTH','BADGE'))");
            jdbcTemplate.execute(
                    "ALTER TABLE growth_events ADD CONSTRAINT IF NOT EXISTS ck_growth_events_exp "
                            + "CHECK (exp_amount >= 0)");
            checksReady = true;
        }
    }

    // ---------------- 生成器 ----------------

    /**
     * 一次操作。取值覆盖需求 2.1 / 2.5 所述的全部事实源变更方向：新增（{@link #RECORD}、
     * {@link #RECORD_TRAVEL}、{@link #INVITE}、{@link #ADD_MEMBER}）、回撤（{@link #SOFT_DELETE_ONE}）、
     * 复原（{@link #RESTORE_ONE}）、改写（{@link #CHANGE_BUDGET}、{@link #RENAME_TRAVEL_CATEGORY}），
     * 以及两条会真正写 {@code growth_events} 的路径（{@link #SETTLE}、{@link #SETTLE_CONCURRENT}、
     * {@link #LIST}）。
     */
    enum Op {
        /** 一笔有效记账（记账日随操作序号在结算日往前 0–39 天间移动，顺带推动连续 / 累计口径）。 */
        RECORD,
        /** 一笔落在「旅行」分类下的有效支出（推动 {@code TRAVEL_RECORD_COUNT}）。 */
        RECORD_TRAVEL,
        /** 软删该用户当前最早一笔未删交易（移入回收站）。 */
        SOFT_DELETE_ONE,
        /** 恢复该用户当前最早一笔已软删交易（从回收站取回）。 */
        RESTORE_ONE,
        /** 重设前一个自然月的总预算，金额在「宽松（达成）」与「0.01（不达成）」之间翻转。 */
        CHANGE_BUDGET,
        /** 新增一条 {@code REGISTERED} 邀请关系（推动 {@code FIRST_INVITE_EVENT}）。 */
        INVITE,
        /** 给自有账本新增一个他人的 {@code EDITOR} 成员行（推动 {@code COLLAB_MEMBER_COUNT}）。 */
        ADD_MEMBER,
        /** 把「旅行」分类改名 / 改回（使 {@code TRAVEL_RECORD_COUNT} 在 0 与 N 之间来回）。 */
        RENAME_TRAVEL_CATEGORY,
        /** 直接串行结算一次。 */
        SETTLE,
        /** 2–8 个结算同时发起的并发爆发（需求 2.9）。 */
        SETTLE_CONCURRENT,
        /** 请求成就清单（写入型 GET，内含一次 {@code OVERVIEW} 结算）。 */
        LIST
    }

    /** 操作序列：长度 1–40，元素随机（含重复，正是幂等要考验的东西）。 */
    @Provide
    Arbitrary<List<Op>> operations() {
        return Arbitraries.of(Op.class).list().ofMinSize(1).ofMaxSize(40);
    }

    // ---------------- Property 1 ----------------

    /**
     * Feature: achievement-system, Property 1: 成就解锁幂等（任意操作序列后每枚成就至多一行）
     *
     * <p>对 2–5 个用户轮流施加 1–40 个操作，<b>每个操作之后</b>对<b>全部</b>用户复核两条不变式：
     * ① 任一 {@code (user_id, 'BADGE:<编码>')} 行数 ∈ {0, 1}；② 此前观察到的每一行的
     * {@code id} / {@code event_type} / {@code exp_amount} / {@code created_at} 逐列不变（读库比对）。
     * 序列结束后再串行结算一次并逐编码复核，确保「最后一次结算」也不会把已有行换新。</p>
     *
     * <p>用户池 ≥2 让「跨用户不串行为」一并被覆盖：某个用户的操作绝不能改动另一个用户的 {@code BADGE} 行
     * ——那种缺陷（例如漏了 {@code user_id} 过滤）在单用户测试里完全观察不到。</p>
     *
     * <p>Validates: Requirements 2.1, 2.5, 2.7, 2.8, 2.9</p>
     */
    @Property(tries = 20)
    void property1_atMostOneRowPerBadgeAndFirstRowIsImmutable(
            @ForAll("operations") List<Op> ops,
            @ForAll @IntRange(min = 2, max = 5) int userCount,
            @ForAll @IntRange(min = 2, max = 8) int concurrency) throws Exception {

        List<Ctx> users = new ArrayList<>(userCount);
        for (int i = 0; i < userCount; i++) {
            users.add(newUser());
        }

        // userId -> 编码 -> 首次观察到的四列快照。只增不改：一旦某编码进了这张表，它的四列就必须永久不变。
        Map<Long, Map<String, List<Object>>> observed = new LinkedHashMap<>();

        for (int i = 0; i < ops.size(); i++) {
            Ctx ctx = users.get(i % users.size());
            applyOp(ops.get(i), i, ctx, concurrency);
            for (Ctx each : users) {
                assertBadgeInvariants(each, observed, "第 " + (i + 1) + " 个操作 " + ops.get(i));
            }
        }

        // 序列末尾再串行结算一次：这一次也不许把任何既有 BADGE 行换新（需求 2.5）。
        for (Ctx ctx : users) {
            CLOCK.advance(BEYOND_THROTTLE);
            settlementService.settle(ctx.userId(), TriggerSource.RECORD);
        }
        for (Ctx ctx : users) {
            assertBadgeInvariants(ctx, observed, "序列末尾的收尾结算");
            // 逐编码断言「行数 ∈ {0, 1}」：GROUP BY 只能看见已存在的键，这一圈把 16 个编码全枚举一遍。
            for (String code : EXPECTED_CODES) {
                assertThat(countByKey(ctx.userId(), GrowthBadgeCatalog.BADGE_KEY_PREFIX + code))
                        .as("用户 %s 的成就 %s 行数必须 ∈ {0, 1}（需求 2.1、2.7）", ctx.userId(), code)
                        .isBetween(0L, 1L);
            }
            // 非空洞守卫：基线事实源使这六枚必然已解锁，上面的「逐列不变」因而确实在考验既有行。
            assertThat(badgeSnapshot(ctx.userId()).keySet())
                    .as("基线事实源应使用户 %s 至少解锁六枚成就，否则本属性沦为空洞", ctx.userId())
                    .contains("FIRST_RECORD", "STREAK_7", "RECORD_10", "INVITE_1", "COLLAB_1",
                            "TRAVEL_MASTER");
        }
    }

    /**
     * 反向断言（{@code ON DUPLICATE KEY UPDATE} 的回归锁，<b>不标可选</b>）：生产的批量插入语句
     * <b>只忽略重复键</b>，CHECK 违例必须照样抛错，且被拒后表行数不变（需求 2.8 的边界）。
     *
     * <h2>为什么用反射读生产语句</h2>
     *
     * <p>本方法用反射取出 {@code GrowthSettlementService.INSERT_EVENT_SQL} 的<b>当前取值</b>去执行非法写入
     * （见 {@link #productionBatchInsertSql()}），因此「改成 {@code INSERT IGNORE} 就变红」是机械保证而不是
     * 注释里的承诺：一旦有人改了那个常量，本方法执行的就是改后的语句。测试里另抄一份 SQL 做不到这一点。</p>
     *
     * <p>两层断言各管一段，缺一不可：</p>
     * <ol>
     *   <li><b>语句形状</b>：生产语句必须含 {@code ON DUPLICATE KEY UPDATE} 且<b>不含</b>
     *       {@code IGNORE}。这一条与数据库无关，在任何库上都会因为改动而立刻变红。</li>
     *   <li><b>行为</b>：用该语句插入非法 {@code event_type}（{@code 'FOO'}，落在七元合法集合之外）与负
     *       {@code exp_amount} 时必须抛 {@link DataIntegrityViolationException}，且插入合法的
     *       {@code 'SAVING_MONTH'}（本 spec 把取值集合从 6 扩到 7 项）必须成功——后者保证前者不是因为
     *       「CHECK 把什么都拒了」而假绿。</li>
     * </ol>
     *
     * <h2>已实测：把生产语句改成 {@code INSERT IGNORE} 时本方法确实变红</h2>
     *
     * <p>把 {@code GrowthSettlementService.INSERT_EVENT_SQL} 改成
     * {@code "INSERT IGNORE INTO growth_events (...) VALUES (?, ?, ?, ?, ?)"}（去掉 ODKU 尾句，其余一字不改）
     * 后单独重跑本方法：第 ① 层即刻变红，先炸的是「必须含 {@code ON DUPLICATE KEY UPDATE}」那一条；
     * 改回后恢复绿。随后把第 ① 层临时停用、只留第 ② 层再跑一遍（仍是 {@code INSERT IGNORE} 版本），
     * 结果是<b>绿的</b>——即在 H2 2.3.232 上 {@code INSERT IGNORE} 语法可用、合法的
     * {@code 'SAVING_MONTH'} 照样写入成功，而两条非法写入仍抛
     * {@link DataIntegrityViolationException}：<b>H2 的 {@code IGNORE} 只吞重复键，不吞 CHECK 违例</b>。</p>
     *
     * <p>这与 MySQL 的语义<b>不同</b>：MySQL 的 {@code INSERT IGNORE} 会把 CHECK / 非空 / 超长一并静默降级
     * 为警告、让脏数据落库，那正是这条禁令存在的原因。所以第 ① 层不是「顺手多写一条」，而是在 H2 上替
     * MySQL 的那半条语义站岗——本 spec 没有 Testcontainers，MySQL 专属行为一律走人工清单（任务 1.4，
     * 已完成并回写 design.md）。两层合起来的效果是：<b>无论在哪个库上，把批量插入改成
     * {@code INSERT IGNORE} 都会让本方法变红。</b></p>
     *
     * <p>Validates: Requirements 2.8</p>
     */
    @Example
    void reverseAssertion_batchInsertOnlyIgnoresDuplicateKeys() throws Exception {
        long userId = SEQ.getAndIncrement();
        String sql = productionBatchInsertSql();
        LocalDateTime now = LocalDateTime.now(CLOCK);

        // ① 语句形状：只忽略重复键，绝不 INSERT IGNORE（见方法 Javadoc「已实测」一段）。
        assertThat(sql)
                .as("结算的批量插入必须走 ON DUPLICATE KEY UPDATE（只忽略重复键）")
                .contains("ON DUPLICATE KEY UPDATE");
        assertThat(sql)
                .as("结算的批量插入绝不能改成 INSERT IGNORE：它会把 CHECK 违例静默降级为警告（需求 2.8）")
                .doesNotContainIgnoringCase("IGNORE INTO")
                .doesNotContainIgnoringCase("INSERT IGNORE");

        long rowsBefore = totalEventRows();

        // ② 合法取值必须能写进去：本 spec 把 ck_growth_events_type 从 6 个取值扩到 7 个。
        jdbcTemplate.update(sql, userId, GrowthEventType.SAVING_MONTH, "SAVING_MONTH:2025-05", 0, now);
        assertThat(countByKey(userId, "SAVING_MONTH:2025-05"))
                .as("SAVING_MONTH 是合法取值，必须能写入（否则下面的「非法必须被拒」是假绿）")
                .isEqualTo(1L);

        // ② 非法 event_type：CHECK ck_growth_events_type 拒绝。
        assertThatThrownBy(() -> jdbcTemplate.update(sql,
                userId, "FOO", "FOO:illegal", 0, now))
                .as("非法 event_type 必须被 CHECK 违例拒绝（MySQL 上改成 INSERT IGNORE 会让这条变红）")
                .isInstanceOf(DataIntegrityViolationException.class);

        // ② 负 exp_amount：CHECK ck_growth_events_exp 拒绝。
        assertThatThrownBy(() -> jdbcTemplate.update(sql,
                userId, GrowthEventType.BADGE, GrowthBadgeCatalog.eventKeyOf("FIRST_RECORD"), -1, now))
                .as("负 exp_amount 必须被 CHECK 违例拒绝（MySQL 上改成 INSERT IGNORE 会让这条变红）")
                .isInstanceOf(DataIntegrityViolationException.class);

        // 被拒后无部分写入：表行数只多了上面那条合法的 SAVING_MONTH。
        assertThat(totalEventRows())
                .as("CHECK 违例被拒后不产生任何新行")
                .isEqualTo(rowsBefore + 1);
    }

    /**
     * 用反射取回生产的事件批量插入语句（{@code GrowthSettlementService.INSERT_EVENT_SQL}）。
     *
     * <p>该常量刻意是 {@code private}——它只该被结算路径使用，不该为了测试而放宽可见性。反射在这里是
     * 有意的耦合：反向断言执行的必须是<b>生产当前那一句</b>，否则回归锁锁不住任何东西。</p>
     */
    private static String productionBatchInsertSql() throws Exception {
        Field field = GrowthSettlementService.class.getDeclaredField("INSERT_EVENT_SQL");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    // ---------------- 不变式断言 ----------------

    /**
     * 复核某用户的两条不变式，并把本次观察到的行并入 {@code observed}（只增不改）。
     *
     * @param observed userId -&gt; 编码 -&gt; 首次观察到的四列快照
     * @param stage    出错时用于定位是哪一步操作之后破的
     */
    private void assertBadgeInvariants(Ctx ctx, Map<Long, Map<String, List<Object>>> observed, String stage) {
        long userId = ctx.userId();

        // ① 行数 ∈ {0, 1}：在库里用 GROUP BY ... HAVING 判定，不数内存集合。
        List<String> duplicated = jdbcTemplate.queryForList(
                "SELECT event_key FROM growth_events "
                        + "WHERE user_id = ? AND event_type = 'BADGE' "
                        + "GROUP BY event_key HAVING COUNT(*) > 1",
                String.class, userId);
        assertThat(duplicated)
                .as("%s 之后用户 %s 出现重复的 BADGE 行（需求 2.1、2.7、2.9）", stage, userId)
                .isEmpty();

        // ② 已观察到的行逐列不变（读库比对，非内存值）。
        Map<String, List<Object>> current = badgeSnapshot(userId);
        Map<String, List<Object>> before = observed.computeIfAbsent(userId, key -> new LinkedHashMap<>());
        assertThat(current)
                .as("%s 之后用户 %s 的既有 BADGE 行必须逐列不变（需求 2.5、2.8）", stage, userId)
                .containsAllEntriesOf(before);

        // 列级硬约束：BADGE 行的 event_type 恒为 BADGE、exp_amount 恒为 0（需求 2.1）。
        current.forEach((code, row) -> {
            assertThat(row.get(1)).as("成就 %s 的 event_type", code).isEqualTo(GrowthEventType.BADGE);
            assertThat(row.get(2)).as("成就 %s 的 exp_amount 恒为 0", code).isEqualTo(0);
        });

        before.putAll(current);
    }

    /**
     * 该用户全部 {@code BADGE} 行的四列快照：编码 -&gt; {@code [id, event_type, exp_amount, created_at]}。
     *
     * <p>每行用 {@code List<Object>} 而不是 {@code Object[]} 承载：{@code List.equals} 逐元素比较，
     * 而 {@code Object[].equals} 是引用相等，会让「逐列不变」的断言恒真（两个内容相同的数组不相等）。</p>
     */
    private Map<String, List<Object>> badgeSnapshot(long userId) {
        Map<String, List<Object>> snapshot = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT event_key, id, event_type, exp_amount, created_at FROM growth_events "
                        + "WHERE user_id = ? AND event_type = 'BADGE' ORDER BY id",
                userId);
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("event_key");
            snapshot.put(key.substring(GrowthBadgeCatalog.BADGE_KEY_PREFIX.length()),
                    List.of(((Number) row.get("id")).longValue(),
                            row.get("event_type"),
                            ((Number) row.get("exp_amount")).intValue(),
                            row.get("created_at")));
        }
        return snapshot;
    }

    // ---------------- 操作执行 ----------------

    /** 施加一个操作。写事实源的操作一律走 {@link JdbcTemplate}，使「本次是第几次结算」完全确定。 */
    private void applyOp(Op op, int index, Ctx ctx, int concurrency) throws Exception {
        long userId = ctx.userId();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        LocalDate settleDate = LocalDate.now(CLOCK);
        switch (op) {
            case RECORD -> insertTransaction(ctx, "expense", "12.34",
                    settleDate.minusDays(index % 40).atTime(9, 0), ctx.decoyCategoryId());
            case RECORD_TRAVEL -> insertTransaction(ctx, "expense", "56.78",
                    settleDate.minusDays(index % 40).atTime(10, 0), ctx.travelCategoryId());
            case SOFT_DELETE_ONE -> {
                Long id = firstTransactionId(userId, true);
                if (id != null) {
                    jdbcTemplate.update("UPDATE transactions SET deleted_at = ? WHERE id = ?", now, id);
                }
            }
            case RESTORE_ONE -> {
                Long id = firstTransactionId(userId, false);
                if (id != null) {
                    jdbcTemplate.update("UPDATE transactions SET deleted_at = NULL WHERE id = ?", id);
                }
            }
            case CHANGE_BUDGET -> {
                // 金额在「宽松（该月必达成）」与 0.01（必不达成）之间翻转，使 BUDGET_MET_COUNT 反复成立。
                String amount = (index % 2 == 0) ? "100000.00" : "0.01";
                jdbcTemplate.update("DELETE FROM budgets WHERE user_id = ?", userId);
                jdbcTemplate.update(
                        "INSERT INTO budgets (user_id, ledger_id, budget_month, amount, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        userId, ctx.ledgerId(), PREV_MONTH, new BigDecimal(amount), now, now);
                // 该月一笔支出（记账日仍取结算日往前若干天，不引入新的月份归属歧义）。
                insertTransaction(ctx, "expense", "80.00", PREV_MONTH_DAY.atTime(9, 0),
                        ctx.decoyCategoryId());
            }
            case INVITE -> jdbcTemplate.update(
                    "INSERT INTO invite_relations "
                            + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'REGISTERED', ?, ?)",
                    userId, SEQ.getAndIncrement(), now, now, now);
            case ADD_MEMBER -> jdbcTemplate.update(
                    "INSERT INTO ledger_members (ledger_id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
                    ctx.ledgerId(), SEQ.getAndIncrement(), LedgerMember.ROLE_EDITOR, now);
            case RENAME_TRAVEL_CATEGORY -> jdbcTemplate.update(
                    "UPDATE categories SET name = ?, updated_at = ? WHERE id = ?",
                    (index % 2 == 0) ? "旅行保险" : "旅行", now, ctx.travelCategoryId());
            case SETTLE -> {
                CLOCK.advance(BEYOND_THROTTLE);
                settlementService.settle(userId, TriggerSource.RECORD);
            }
            case SETTLE_CONCURRENT -> {
                CLOCK.advance(BEYOND_THROTTLE);
                runConcurrentSettlements(userId, concurrency);
            }
            case LIST -> {
                CLOCK.advance(BEYOND_THROTTLE);
                // 写入型 GET：内含一次 OVERVIEW 结算，因此也是 BADGE 行的写入路径之一。
                assertThat(achievementQueryService.getAchievements(userId).achievements())
                        .as("成就清单恒 16 项").hasSize(EXPECTED_CODES.size());
            }
            default -> throw new IllegalStateException("未覆盖的操作: " + op);
        }
    }

    /**
     * 同一用户的 {@code concurrency} 个结算<b>同时</b>发起（需求 2.9）。
     *
     * <p>用一个 {@link CountDownLatch} 齐发，个体异常按生产语义就地吞掉——生产里这层由
     * {@code GrowthSettlementTrigger.settleQuietly} 承担；本属性只锁终态，与个体成败无关。</p>
     */
    private void runConcurrentSettlements(long userId, int concurrency) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                Callable<Void> task = () -> {
                    start.await();
                    try {
                        settlementService.settle(userId, TriggerSource.RECORD);
                    } catch (Exception ignored) {
                        // 争锁放弃 / 任何结算故障在生产中都在事务边界外被吞掉，不向主路径传播。
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ---------------- 事实源播种 ----------------

    /** 一个用户的固定上下文：自有账本、「旅行」父分类、一个用于「改分类」的诱饵分类。 */
    private record Ctx(long userId, long ledgerId, long travelCategoryId, long decoyCategoryId) {
    }

    /**
     * 建一个用户：自有账本 + 「旅行」父分类 + 诱饵分类，并播种一份<b>基线事实源</b>。
     *
     * <p>基线（昨天往前 7 个连续记账日各一笔 + 10 笔旅行支出 + 1 个 {@code EDITOR} 成员 + 1 条
     * {@code REGISTERED} 邀请）的作用是让属性<b>非空洞</b>：只要序列里发生过任意一次结算，
     * 就必然已经解锁 {@code FIRST_RECORD} / {@code STREAK_7} / {@code RECORD_10} / {@code INVITE_1} /
     * {@code COLLAB_1} / {@code TRAVEL_MASTER} 六枚，于是后续每个操作都在真的考验「既有行逐列不变」；
     * 若不播种基线，随机序列里完全可能一枚成就都没解锁，「至多一行」在 0 上恒真、测不到任何东西。</p>
     *
     * <p>基线的记账日一律 ≤ 昨天，故 {@code last_record_date != 结算日}，记账侧 60 秒节流的两个条件
     * 不会同时成立——但每次结算前仍会推进 61 秒，双保险。</p>
     */
    private Ctx newUser() {
        long userId = SEQ.getAndIncrement();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        LocalDate yesterday = LocalDate.now(CLOCK).minusDays(1);
        long ledgerId = SEQ.getAndIncrement();
        jdbcTemplate.update(
                "INSERT INTO ledgers (id, user_id, name, type, sort_order, is_default, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PERSONAL', 0, FALSE, ?, ?)",
                ledgerId, userId, "idem-" + userId, now, now);
        long travelId = insertCategory(userId, ledgerId, "旅行", now);
        long decoyId = insertCategory(userId, ledgerId, "餐饮", now);
        Ctx ctx = new Ctx(userId, ledgerId, travelId, decoyId);

        for (int i = 0; i < 7; i++) {                                  // FIRST_RECORD + STREAK_7
            insertTransaction(ctx, "expense", "9.90", yesterday.minusDays(i).atTime(8, 0), decoyId);
        }
        for (int i = 0; i < 10; i++) {                                 // RECORD_10 + TRAVEL_MASTER
            insertTransaction(ctx, "expense", "1.10", yesterday.atTime(11, 0), travelId);
        }
        jdbcTemplate.update(                                           // COLLAB_1
                "INSERT INTO ledger_members (ledger_id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
                ledgerId, SEQ.getAndIncrement(), LedgerMember.ROLE_EDITOR, now);
        jdbcTemplate.update(                                           // INVITE_1
                "INSERT INTO invite_relations "
                        + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'REGISTERED', ?, ?)",
                userId, SEQ.getAndIncrement(), now, now, now);

        // 基线结算：把六枚成就落库，使随后的每一个操作都在考验「既有行逐列不变」而不是在空表上恒真。
        // 新用户尚无档案行，本次结算不会被记账侧节流跳过。
        settlementService.settle(userId, TriggerSource.RECORD);
        return ctx;
    }

    private long insertCategory(long userId, long ledgerId, String name, LocalDateTime now) {
        long id = SEQ.getAndIncrement();
        jdbcTemplate.update(
                "INSERT INTO categories (id, user_id, ledger_id, parent_id, kind, name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 'EXPENSE', ?, ?, ?)",
                id, userId, ledgerId, name, now, now);
        return id;
    }

    /** 一笔「有效记账交易」：{@code created_by} = 用户、{@code deleted_at} 为 NULL、{@code ledger_id} 非空。 */
    private void insertTransaction(Ctx ctx, String type, String amount,
                                  LocalDateTime occurredAt, long categoryId) {
        Timestamp createdAt = Timestamp.valueOf(occurredAt);
        jdbcTemplate.update(INSERT_TX_SQL,
                ctx.userId(), ctx.ledgerId(), ctx.userId(), type, new BigDecimal(amount),
                ctx.ledgerId(), categoryId, Timestamp.valueOf(occurredAt), createdAt, createdAt);
    }

    // ---------------- 库读取工具 ----------------

    /** 该用户最早一笔「未删 / 已软删」交易的 id，没有则返回 {@code null}。 */
    private Long firstTransactionId(long userId, boolean notDeleted) {
        String predicate = notDeleted ? "deleted_at IS NULL" : "deleted_at IS NOT NULL";
        return jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM transactions WHERE created_by = ? AND " + predicate,
                Long.class, userId);
    }

    private long countByKey(long userId, String eventKey) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_key = ?",
                Long.class, userId, eventKey);
        return count == null ? 0L : count;
    }

    private long totalEventRows() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM growth_events", Long.class);
        return count == null ? 0L : count;
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
