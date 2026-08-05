package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.repository.UserRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * {@code GrowthSettlementTrigger} 的回归锁（<b>Property 14：结算的故障隔离、节流与并发终态</b>）。
 *
 * <p>本测试把 {@code GrowthSettlementTrigger} 那四条「长得不像约束」的禁令（异常不得穿出
 * {@code afterCommit}、同一事务只注册一次回调、回调内只携带不可变 {@code Long}、异常在
 * {@code settle} 事务边界<b>之外</b>吞掉）连同 {@code GrowthSettlementService} 的
 * {@code REQUIRES_NEW} 事务边界一起锁住。这些性质在正常路径上「看起来都对」，只有在被具体重构
 * 破坏时才会以运行期故障暴露，故必须由一个跑在真实事务管理器上的测试守住。</p>
 *
 * <h2>驱动方式：为什么不用 {@code @Transactional}，而用 {@link TransactionTemplate} 显式包裹</h2>
 * <p><b>本测试方法刻意不加 {@code @Transactional}。</b>Spring Test 的
 * {@code TransactionalTestExecutionListener} 会在测试方法结束时把事务<b>回滚</b>，于是被测事务从不
 * 真正提交，{@code TransactionSynchronization.afterCommit} 回调<b>永不触发</b>——而结算恰恰挂在
 * {@code afterCommit} 上。若那样写，「结算未发生」这类断言会因为回调根本没跑而<b>假绿通过</b>，
 * 测试彻底失去意义。因此本测试用 {@link TransactionTemplate} 显式开启并<b>真实提交</b>被测事务，
 * 让 {@code afterCommit} 真的触发；由此带来的代价是数据会真的落库，故清理不能依赖事务回滚
 * （见下「测试数据清理」）。</p>
 *
 * <h2>两处必然失败的破坏性改动（回归锁的核心）</h2>
 * <ul>
 *   <li><b>把 {@code catch} 挪进 {@code GrowthSettlementService.settle} 内部</b>：
 *       {@link #property14_requiresNewIsolatesSettlementRollback()} 会失败。settle 在 {@code REQUIRES_NEW}
 *       事务内遇到畸形 {@code DAILY_RECORD} 键抛 {@link DateTimeParseException}；本测试断言该异常
 *       <b>穿出</b> settle。一旦 settle 自己 catch 掉并正常返回，{@code assertThatThrownBy(...)} 立即失败
 *       （且 Spring 会提交一个已被标记 rollback-only 的事务，破坏需求 9.7 的「无部分写入」）。</li>
 *   <li><b>把 {@code REQUIRES_NEW} 改成 {@code REQUIRED}</b>：同一测试会失败。届时 settle 并入外层
 *       （业务）事务，settle 抛异常会把外层事务标记为 rollback-only，外层 {@code TransactionTemplate}
 *       提交时抛 {@code UnexpectedRollbackException}，于是「外层已提交的业务行仍存在」这条断言无从满足
 *       （需求 9.3：结算回滚绝不连坐已提交的记账与余额）。</li>
 * </ul>
 *
 * <h2>测试层级：必须是 {@code @SpringBootTest}</h2>
 * <p>{@code REQUIRES_NEW} 需要一个<b>真实的事务管理器</b>来挂起外层事务、开启独立事务并独立提交/回滚，
 * {@code afterCommit} 同步回调也只在真实事务提交时触发；这些都无法用纯 Mockito 单测替代。故走全栈
 * {@code @SpringBootTest} + H2（{@code MODE=MySQL}，表由实体经 {@code ddl-auto=create-drop} 生成），
 * 独立命名内存库避免污染其它共享库的切片测试。</p>
 *
 * <h2>如何在真实结算里植入故障与观测点</h2>
 * <p>{@link RecordingSettlementService} 是一个 {@code @Primary} 的
 * {@link GrowthSettlementService} 子类，被 {@code GrowthSettlementTrigger} 注入。它默认<b>委托</b>给真实
 * （被 Spring 事务代理包裹的）{@code GrowthSettlementService} bean（{@code REQUIRES_NEW} 因而照常生效），
 * 只在委托前后记录：每次 {@code settle} 的 {@code userId}（用于「去重合并」与「顺序稳定」断言）、
 * 执行线程（用于「结算发生在测试线程」的断言——比断言「不存在线程池 Bean」更直接），并可按需在委托前
 * 抛出注入的异常（用于「故障隔离」断言）。真实结算与真实事务全程未被替换。</p>
 *
 * <h2>结算发生在测试线程</h2>
 * <p>需求 9.9 禁止用 {@code @Async} / 定时任务 / 线程池 / 执行器驱动结算。与其去断言「上下文里不存在
 * 线程池 Bean」（易漏、易被绕过），本测试直接在结算内部记录 {@code Thread.currentThread()} 并与测试线程
 * 比对：只要结算被挪到任何别的线程，比对立即失败。</p>
 *
 * <h2>测试数据清理（不能依赖事务回滚）</h2>
 * <p>因为要真实提交，{@link #resetState()} 在每次迭代前<b>显式清库</b>（{@code growth_events} /
 * {@code user_growth} / {@code users}）并清空 {@link RecordingSettlementService} 的记录；同时用全局自增
 * 序号 {@link #SEQ} 保证每次迭代的 {@code userId} / 邮箱 / 邀请码全局唯一，双重隔离。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 静态上下文缓存复用，多次迭代
 * 只加载一次上下文）。</p>
 *
 * <p>Feature: growth-level-system, Property 14: 结算的故障隔离、节流与并发终态</p>
 *
 * <p>Validates: Requirements 3.11, 6.6, 6.7, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10, 9.11, 9.15, 9.16, 10.14</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-growthtrigger-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 并发终态用例会让「同一记账请求」在结算窗口内同时占用 2 个连接，抬高池上限避免误报为「获取连接超时」。
        "spring.datasource.hikari.maximum-pool-size=32"
})
@Import(GrowthSettlementTriggerPropertyTest.RecordingConfig.class)
class GrowthSettlementTriggerPropertyTest {

    /** 同一个 H2 库跨迭代复用，用序号保证 userId / email / 邀请码全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(1_000_000L);

    @Autowired
    private GrowthSettlementTrigger trigger;
    @Autowired
    private RecordingSettlementService recording;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private GrowthEventRepository growthEventRepository;

    private TransactionTemplate tx;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthSettlementTriggerPropertyTest.class).prepareTestInstance(this);
        tx = new TransactionTemplate(transactionManager);
        // 显式清库：真实提交后不能靠回滚清理（见类级 Javadoc）。
        jdbcTemplate.execute("DELETE FROM growth_events");
        jdbcTemplate.execute("DELETE FROM user_growth");
        jdbcTemplate.execute("DELETE FROM users");
        recording.reset();
    }

    // ---------------- 生成器 ----------------

    /** 调用序列：每个元素是「用户下标」（0–4），列表长度 1–15，含大量重复以考验去重与顺序稳定。 */
    @Provide
    Arbitrary<List<Integer>> callSequences() {
        return Arbitraries.integers().between(0, 4).list().ofMinSize(1).ofMaxSize(15);
    }

    /** 故障注入选择子：映射到不同类型的运行期异常（含受检异常包装、行锁/连接类异常）。 */
    @Provide
    Arbitrary<Integer> faultSelectors() {
        return Arbitraries.integers().between(0, 5);
    }

    private static RuntimeException faultOf(int selector, long userId) {
        return switch (selector) {
            case 1 -> new IllegalStateException("注入：非法状态");
            case 2 -> new DataAccessResourceFailureException("注入：连接获取失败");
            case 3 -> new PessimisticLockingFailureException("注入：行锁等待超时");
            case 4 -> new GrowthLockAbandonedException(userId, new PessimisticLockingFailureException("注入：放弃锁"));
            case 5 -> new RuntimeException("注入：受检异常包装", new java.io.IOException("底层 IO"));
            default -> new RuntimeException("注入：运行期异常");
        };
    }

    // ---------------- Property 14 ----------------

    /**
     * 同一事务内多次 {@code requestSettlement} 只合并为<b>一轮</b>结算，多 {@code userId} 结算顺序稳定，
     * 且结算发生在<b>测试线程</b>（需求 9.3 的「只注册一次回调」、9.4 的「合并为一次结算」、9.9 的
     * 「不引入任何执行器/线程池」）。
     *
     * <p>把生成的调用序列（含重复）在<b>同一个真实提交的事务</b>内逐个投给
     * {@code requestSettlement}；提交后断言：结算的 {@code userId} 序列恰好等于调用序列中
     * <b>首次出现顺序</b>去重后的列表（每个用户结算一次、顺序由 {@code LinkedHashSet} 稳定保证），
     * 且每一次结算都跑在测试线程上。</p>
     *
     * <p>Validates: Requirements 9.1, 9.3, 9.4, 9.9</p>
     */
    @Property(tries = 40)
    void property14_sameTxDedupesAndMergesInStableOrderOnTestThread(
            @ForAll("callSequences") List<Integer> callIndices) {

        long base = SEQ.getAndAdd(100);
        // 首次出现顺序去重（期望的结算顺序）。
        LinkedHashSet<Long> expectedOrder = new LinkedHashSet<>();
        for (int idx : callIndices) {
            expectedOrder.add(base + idx);
        }

        Thread testThread = Thread.currentThread();

        tx.executeWithoutResult(status -> {
            for (int idx : callIndices) {
                trigger.requestSettlement(base + idx);
            }
            // 事务提交前结算尚未发生（afterCommit 还没跑）：此刻记录必为空。
            assertThat(recording.settledUserIds()).isEmpty();
        });

        // 提交后：结算按首次出现顺序、每个用户恰好一次。
        assertThat(recording.settledUserIds()).containsExactlyElementsOf(new ArrayList<>(expectedOrder));
        // 结算全部发生在测试线程（无 @Async / 线程池 / 执行器）。
        assertThat(recording.threads()).containsExactly(testThread);
    }

    /**
     * {@code settle} 抛出<b>任意</b>异常时，调用方（业务事务）完全感知不到：外层事务照常提交、其已写入的
     * 业务行不受影响，成长两表对该用户零变更（需求 9.5、9.6、9.7）。
     *
     * <p>在一个真实提交的事务内先写入一行业务数据（{@code users}，代表「已提交的记账与余额」），再
     * {@code requestSettlement}；令 {@code settle} 在 {@code afterCommit} 阶段抛出注入的异常。断言：
     * {@code tx.execute} <b>正常返回不抛出</b>（异常没穿出 {@code afterCommit}）、业务行已提交、
     * {@code growth_events} 与 {@code user_growth} 中该用户<b>零行</b>（结算未产生任何写入）。</p>
     *
     * <p>Validates: Requirements 9.5, 9.6, 9.7</p>
     */
    @Property(tries = 40)
    void property14_settlementFailureIsInvisibleToBusinessTransaction(
            @ForAll("faultSelectors") int faultSelector) {

        long userId = SEQ.getAndIncrement();
        recording.throwOnSettle(faultOf(faultSelector, userId));

        long[] businessUserId = new long[1];
        // afterCommit 里 settle 会抛注入异常；trigger 必须吞掉，tx.execute 不得因此抛出。
        assertThatCode(() -> tx.executeWithoutResult(status -> {
            businessUserId[0] = persistBusinessUser(userId);   // 「已提交的记账与余额」的替身：一行业务数据
            trigger.requestSettlement(userId);
        })).doesNotThrowAnyException();

        // 业务事务已提交：业务行在（结算失败对它不可见）。
        assertThat(userRepository.findById(businessUserId[0])).isPresent();
        // 成长两表对该用户零变更（结算失败 → 无部分写入）。
        assertThat(growthEventRepository.countByUserId(userId)).isZero();
        assertThat(userGrowthRepository.findById(userId)).isEmpty();
    }

    /**
     * 同一线程上，一个事务 {@code afterCompletion} 之后绑定的资源必须已解绑：<b>下一个事务不被污染</b>
     * （需求 9.3、9.4 —— {@code bindResource} 的资源要在 {@code afterCompletion} 里自己解绑，否则线程池
     * 复用线程时会把上一个事务的待结算集合带进下一个事务，导致上一个用户被重复结算）。
     *
     * <p>先在事务 A 内为用户 A 请求多次结算并提交；再在<b>同一线程</b>的事务 B 内为用户 B 请求一次结算并
     * 提交。断言事务 B 只结算了用户 B——若 A 的资源泄漏进 B，B 的这一轮会把 A 再结算一次。</p>
     *
     * <p>Validates: Requirements 9.3, 9.4</p>
     */
    @Property(tries = 30)
    void property14_resourceUnboundAfterCompletionSoNextTxNotPolluted(
            @ForAll @IntRange(min = 1, max = 5) int repeatInTxA) {

        long userA = SEQ.getAndIncrement();
        long userB = SEQ.getAndIncrement();

        tx.executeWithoutResult(status -> {
            for (int i = 0; i < repeatInTxA; i++) {
                trigger.requestSettlement(userA);
            }
        });
        assertThat(recording.settledUserIds()).containsExactly(userA);

        recording.reset();

        tx.executeWithoutResult(status -> trigger.requestSettlement(userB));

        // 事务 B 只结算 B：A 的待结算集合没有泄漏进 B（资源已在 A 的 afterCompletion 里解绑）。
        assertThat(recording.settledUserIds()).containsExactly(userB);
    }

    /**
     * 无事务上下文时走<b>兜底路径</b>就地结算：不注册回调（无可提交的事务），由 {@code settle} 自己的
     * {@code REQUIRES_NEW} 开事务完成，且发生在测试线程（需求 9.1、9.9）。
     *
     * <p>直接在任何事务之外调用 {@code requestSettlement}；断言该用户被结算了恰好一次、落在测试线程，
     * 且真实写入了一行成长档案（结算确实发生，而非被静默跳过）。</p>
     *
     * <p>Validates: Requirements 9.1, 9.9</p>
     */
    @Property(tries = 20)
    void property14_noTransactionContextFallsBackToInlineSettlement(
            @ForAll @IntRange(min = 1, max = 3) int ignored) {

        long userId = SEQ.getAndIncrement();
        Thread testThread = Thread.currentThread();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        trigger.requestSettlement(userId);

        assertThat(recording.settledUserIds()).containsExactly(userId);
        assertThat(recording.threads()).containsExactly(testThread);
        // 兜底路径确实结算：真实写入了一行成长档案（level 初始为 1）。
        assertThat(userGrowthRepository.findById(userId))
                .hasValueSatisfying(g -> assertThat(g.getLevel()).isEqualTo(1));
    }

    /**
     * {@code REQUIRES_NEW} 的隔离性回归锁：{@code settle} 在其独立事务内抛异常时，其写入回滚，而外层
     * （业务）事务<b>照常提交</b>、已写入的业务行存活（需求 9.3、9.7）。
     *
     * <p><b>这是「{@code REQUIRES_NEW}」与「settle 不自己 catch 异常」两条约束的直接锁。</b>做法：预置一行
     * <b>畸形</b>的 {@code DAILY_RECORD} 事件（{@code event_key = 'DAILY_RECORD:BADDATE'}），使真实
     * {@code settle} 在读事实源阶段 {@code LocalDate.parse} 失败抛 {@link DateTimeParseException}。在一个真实
     * 提交的外层事务内先写业务行、再<b>直接调用</b>真实 {@code settle}（经 Spring 事务代理，
     * {@code REQUIRES_NEW} 生效），断言：settle 把异常<b>穿出</b>（若被挪进 settle 内 catch，则此断言失败）；
     * 吞掉该异常后外层事务提交成功、业务行存活（若把 {@code REQUIRES_NEW} 改成 {@code REQUIRED}，settle 的
     * 回滚会连坐外层事务，提交抛 {@code UnexpectedRollbackException}，业务行断言失败）。</p>
     *
     * <p>Validates: Requirements 9.3, 9.7, 9.16</p>
     */
    @Example
    void property14_requiresNewIsolatesSettlementRollback() {
        long userId = SEQ.getAndIncrement();
        // 预置畸形 DAILY_RECORD 键（先提交，settle 读到后 parse 失败）。
        jdbcTemplate.update(
                "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                        + "VALUES (?, 'DAILY_RECORD', 'DAILY_RECORD:BADDATE', 5, ?)",
                userId, LocalDateTime.now());

        long[] businessUserId = new long[1];
        assertThatCode(() -> tx.executeWithoutResult(status -> {
            businessUserId[0] = persistBusinessUser(userId);
            // 直接调用真实 settle（recording 委托给被事务代理包裹的真实 bean）：REQUIRES_NEW 生效。
            // 畸形键使 settle 抛 DateTimeParseException；断言它「穿出」settle（settle 内不自吞异常）。
            assertThatThrownBy(() -> recording.settle(userId, TriggerSource.RECORD))
                    .isInstanceOf(DateTimeParseException.class);
            // 在事务边界之外吞掉（正如 trigger.settleQuietly / GrowthQueryService 所为）。
        })).doesNotThrowAnyException();

        // 外层业务事务不被 settle 的回滚连坐：业务行存活（REQUIRES_NEW 的隔离性）。
        assertThat(userRepository.findById(businessUserId[0])).isPresent();
        // settle 自己的独立事务已回滚：畸形键之外没有新增成长档案（无部分写入）。
        assertThat(userGrowthRepository.findById(userId)).isEmpty();
    }

    /**
     * 并发终态：2–8 个并发结算（每个各自 {@code REQUIRES_NEW}）在同一用户上收敛到唯一档案行，且没有任何
     * 异常穿出触发路径（需求 1.8、9.5、9.7）。
     *
     * <p>多线程各自开一个真实提交的事务、对同一 {@code userId} {@code requestSettlement}。争抢
     * {@code user_growth} 行写锁的败者会以 {@link GrowthLockAbandonedException} 放弃（被 trigger 吞掉，
     * 下次自愈）。断言终态：{@code user_growth} 对该用户恰好一行（ODKU 建档幂等），触发路径未抛出任何异常。</p>
     *
     * <p>Validates: Requirements 1.8, 9.5, 9.7</p>
     */
    @Property(tries = 8)
    void property14_concurrentSettlementsConvergeToSingleProfileRow(
            @ForAll @IntRange(min = 2, max = 8) int concurrency) throws InterruptedException {

        long userId = SEQ.getAndIncrement();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        List<Throwable> escaped = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    tx.executeWithoutResult(status -> trigger.requestSettlement(userId));
                } catch (Throwable ex) {
                    escaped.add(ex);
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        // 触发路径吞掉一切结算故障：不应有任何异常穿出到业务侧。
        assertThat(escaped).isEmpty();
        // 终态唯一：ODKU 建档使该用户恰好一行成长档案。
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_growth WHERE user_id = ?", Long.class, userId);
        assertThat(rows).isEqualTo(1L);
    }

    // ---------------- 测试基础设施 ----------------

    /** 写入一行业务数据，代表「已提交的记账与余额」——结算失败/回滚绝不能连坐它。 */
    private long persistBusinessUser(long seq) {
        LocalDateTime now = LocalDateTime.now();
        User u = new User();
        u.setEmail("p14-" + seq + "@example.com");
        u.setNickname("p14-" + seq);
        u.setInviteCode(inviteCodeOf(seq));
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u).getId();
    }

    /** 8 位邀请码，带本类专属前缀 {@code P4}，避免与兄弟测试共用同一内存库时撞唯一约束。 */
    private static String inviteCodeOf(long seq) {
        String suffix = Long.toString(seq, 36).toUpperCase(java.util.Locale.ROOT);
        String base = "P4" + suffix;
        if (base.length() > 8) {
            return base.substring(base.length() - 8);
        }
        return base + "0".repeat(8 - base.length());
    }

    /**
     * 记录并可注入故障的 {@link GrowthSettlementService}：默认委托给真实（被事务代理包裹的）bean，
     * {@code REQUIRES_NEW} 因而照常生效。它<b>不是</b> Mockito 替身，也不替换真实结算——只在委托前后
     * 记录 {@code userId}/线程，并可在委托前抛出注入异常。构造时给父类传 {@code null}：本类覆盖了
     * {@code settle} 并只委托给 {@code delegate}，父类字段永不被触及。
     */
    static class RecordingSettlementService extends GrowthSettlementService {

        private final GrowthSettlementService delegate;
        private final List<Long> settledUserIds = new CopyOnWriteArrayList<>();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private volatile RuntimeException toThrow;

        RecordingSettlementService(GrowthSettlementService delegate) {
            // 13 个 null：构造参数在 achievement-system 任务 4.1 从 11 个扩到 13 个
            // （新增 LedgerMemberRepository 与 GrowthSavingMonthEvaluator）。本桩全部方法都转发给
            // delegate，父类字段一个都不用，因此逐个传 null。
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            this.delegate = delegate;
        }

        @Override
        public SettleOutcome settle(Long userId, TriggerSource source) {
            settledUserIds.add(userId);
            threads.add(Thread.currentThread());
            RuntimeException injected = this.toThrow;
            if (injected != null) {
                throw injected;
            }
            return delegate.settle(userId, source);   // 经事务代理 → REQUIRES_NEW 生效
        }

        void reset() {
            settledUserIds.clear();
            threads.clear();
            toThrow = null;
        }

        void throwOnSettle(RuntimeException e) {
            this.toThrow = e;
        }

        List<Long> settledUserIds() {
            return List.copyOf(settledUserIds);
        }

        List<Thread> threads() {
            // 去重：同一线程可能结算多个用户，断言「都在测试线程」只需看去重后的集合。
            return threads.stream().distinct().toList();
        }
    }

    @TestConfiguration
    static class RecordingConfig {
        @Bean
        @Primary
        RecordingSettlementService recordingSettlementService(
                @Qualifier("growthSettlementService") GrowthSettlementService real) {
            return new RecordingSettlementService(real);
        }
    }
}
