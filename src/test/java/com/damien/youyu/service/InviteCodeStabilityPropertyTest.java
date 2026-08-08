package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.config.TimeConfig;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 邀请码的稳定性与请求幂等（Property 2）：不含注销的任意操作序列下，同一用户的
 * {@code users.invite_code} 一经由空变为非空便不再改变，重复请求返回同一取值且不产生任何写入；
 * 并发触发惰性补齐时终态恰好一个非空取值，全部成功响应返回同一个码。
 *
 * <h2>测试层级选择</h2>
 * <p>必须是真实数据库上的真实事务。本属性的三条断言各自依赖被测链路的不同真实部分：</p>
 * <ul>
 *   <li><b>稳定性</b>（需求 1.13）依赖真实的登录 / 改昵称 / 绑定与解绑身份路径是否碰
 *       {@code invite_code} 这一列——判定标准是「读库取值」，任何替身都会把这个事实抹掉。</li>
 *   <li><b>幂等</b>（需求 1.4）断言的是「已非空时一行都不写」，连 {@code updated_at} 都不动，
 *       只能靠逐列比对真实行快照。</li>
 *   <li><b>并发终态唯一</b>（需求 1.12）由 {@code findForUpdateById} 的
 *       {@code PESSIMISTIC_WRITE} 行级写锁保证，而行锁只在真实 JDBC 连接 + 真实事务上存在。</li>
 * </ul>
 * <p>故走 {@code @DataJpaTest} + H2（{@code MODE=MySQL}，表由实体生成），只额外导入被测服务与
 * 它依赖的组件；{@link AuthService} 由测试手工构造（见下）。</p>
 *
 * <p><b>为什么这里没有测试事务</b>：jqwik 的属性方法不经 JUnit Jupiter 引擎，
 * {@code SpringExtension} 与 {@code TransactionalTestExecutionListener} 都不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文由 Spring 静态缓存复用，
 * 100 次迭代只加载一次）。于是被测方法上的 {@code @Transactional} 走的是真实的「开启—提交」，
 * 这正是本属性需要的：并发阶段的两个请求必须落在两个真正独立、真正会提交的物理事务上。
 * 每次迭代用<b>全新的用户</b>隔离数据，跨迭代残留不影响任何断言。</p>
 *
 * <h2>并发阶段确实被真实执行</h2>
 * <p>并发阶段不是形式上的摆设：{@code concurrency} 个线程各自从连接池取独立连接、各自开启事务，
 * 由 {@link CountDownLatch} 对齐到同一时刻后同时进入 {@code requireInviteCode}。断言
 * 「全部线程都成功 ∧ 返回值去重后恰好一个 ∧ 等于库中终态」对<b>去掉行锁</b>这一改动是敏感的：
 * 把 {@code findForUpdateById} 换成 {@code findById}，各线程会各自抽出一个不同的候选码并先后
 * {@code UPDATE} 同一行，于是返回值去重后大于一个、且先到者的返回值与库中终态不再相等——
 * 本属性随即失败。该反向验证已在编写时实测确认（见任务 9.2 的执行记录）。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li>操作序列长度 1–30，元素取自 {@link OpKind} 的 9 种操作（重复邮箱登录、重复微信登录、
 *       改昵称、绑定 / 解绑邮箱、绑定 / 解绑微信、请求邀请信息、直接请求邀请码），
 *       每个元素带一个用户槽位与一个变体（登录是否携带邀请码等）。</li>
 *   <li>用户池 3–5 人，形态刻意混合：<b>存量用户</b>（{@code invite_code} 为 NULL，用于触达
 *       惰性补齐）、经邮箱注册建号的用户、经微信注册建号的用户（后两者建号即带非空邀请码）。</li>
 *   <li>请求邀请信息的重复次数 1–5。</li>
 *   <li>并发度 2–8。</li>
 * </ul>
 *
 * <p><b>刻意不含注销</b>：注销随 {@code users} 行删除释放邀请码，属于 Property 12 的范围；
 * 本属性的前提正是「不含注销」（需求 1.13）。</p>
 *
 * <p>Feature: invite-system, Property 2: 邀请码的稳定性与请求幂等</p>
 *
 * <p>Validates: Requirements 1.3, 1.4, 1.12, 1.13</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ InviteService.class, InviteBindingService.class, InviteCodeGenerator.class,
        InviteRateLimiter.class, TimeConfig.class })
class InviteCodeStabilityPropertyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    /**
     * 同一个 H2 库跨迭代复用：用序号保证 email / openid 全局唯一。
     *
     * <p>序号只在类内唯一，因此本类造出的所有 email / openid 一律带 {@code p2-} 前缀：
     * 全部切片测试共用同一个内存 H2（{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且
     * Spring 上下文缓存跨测试类复用，{@code create-drop} 不会在类之间清库，
     * 兄弟属性测试的序号又都从 0 开始——不带类专属前缀就会在 {@code users} 的
     * 唯一约束上随机撞车。本类的邀请码全部由真实 {@link InviteCodeGenerator} 抽取
     * （自带重试避让），故不需要额外的码空间前缀。</p>
     */
    private static final AtomicLong SEQ = new AtomicLong();

    private static final String SELECT_USER_ROW_SQL =
            "SELECT id, email, nickname, wx_openid, wx_unionid, invite_code, plan, role, "
                    + "plan_started_at, plan_expires_at, created_at, updated_at "
                    + "FROM users WHERE id = ?";

    @Autowired
    private InviteService inviteService;
    @Autowired
    private InviteBindingService inviteBindingService;
    @Autowired
    private InviteCodeGenerator inviteCodeGenerator;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private Clock clock;

    private TransactionTemplate tx;
    private AuthService authService;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(InviteCodeStabilityPropertyTest.class).prepareTestInstance(this);
        tx = new TransactionTemplate(transactionManager);

        // AuthService 手工构造：本属性只关心它是否碰 invite_code，验证码校验与微信换取是无关的
        // 外部依赖，用替身消掉。openid 直接取 code 原文，于是「用某个 openid 登录」可复现地命中
        // 同一个账号。真实实例是必须的部分——邀请码生成器与绑定服务——用的都是容器里的 Bean，
        // 因此 bindOnRegister 的 MANDATORY 传播、保存点插入都照真实路径走（外层事务由 tx 提供）。
        VerificationCodeService codes = mock(VerificationCodeService.class);
        when(codes.verifyConsume(anyString(), any(), anyString())).thenReturn(true);
        WeChatClient wechat = mock(WeChatClient.class);
        when(wechat.jscode2session(anyString()))
                .thenAnswer(inv -> new WxSession(inv.getArgument(0), null));
        authService = new AuthService(userRepository, clock, wechat, codes,
                inviteCodeGenerator, inviteBindingService);
    }

    // ---------------- 生成器 ----------------

    /** 不含注销的操作：每个元素 = 操作类型 + 用户槽位 + 变体（登录是否携带邀请码等）。 */
    @Provide
    Arbitrary<List<Op>> operationSequences() {
        Arbitrary<Op> one = Combinators.combine(
                        Arbitraries.of(OpKind.values()),
                        Arbitraries.integers().between(0, 4),
                        Arbitraries.integers().between(0, 3))
                .as(Op::new);
        return one.list().ofMinSize(1).ofMaxSize(30);
    }

    // ---------------- Property 2 ----------------

    /**
     * Feature: invite-system, Property 2: 邀请码的稳定性与请求幂等
     *
     * <p>对任意不含注销的操作序列（重复登录、改昵称、绑定 / 解绑邮箱或微信、任意次数请求邀请信息）：</p>
     * <ul>
     *   <li>每个用户的 {@code invite_code} 读库取值序列形如 {@code null*} 后接同一非空取值的重复
     *       ——单调、一次性、终态唯一，任何操作都不得使其改变或回到空（需求 1.3、1.13）；</li>
     *   <li>同一用户任意多次请求邀请信息返回的邀请码去重后至多一个，且等于库中终态取值（需求 1.4）；</li>
     *   <li>邀请码已非空时再次请求<b>不产生任何写入</b>：{@code users} 行逐列快照（含
     *       {@code updated_at}）与请求前完全相同（需求 1.4）；</li>
     *   <li>并发触发惰性补齐：全部线程都成功返回，返回值去重后恰好一个，等于库中终态的非空取值，
     *       且该取值在 {@code users} 表中恰好被一行持有（需求 1.12）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 1.3, 1.4, 1.12, 1.13</p>
     */
    @Property(tries = 25)
    void property2_inviteCodeStabilityAndRequestIdempotence(
            @ForAll("operationSequences") List<Op> ops,
            @ForAll @IntRange(min = 3, max = 5) int poolSize,
            @ForAll @IntRange(min = 1, max = 5) int infoRepeats,
            @ForAll @IntRange(min = 2, max = 8) int concurrency) {

        long seq = SEQ.incrementAndGet();
        List<Long> pool = createUserPool(seq, poolSize);

        // 每个用户的读库取值序列与「请求邀请信息返回的取值」集合。
        List<List<String>> observed = new ArrayList<>();
        List<Set<String>> returned = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            observed.add(new ArrayList<>());
            returned.add(new LinkedHashSet<>());
        }
        snapshotAll(pool, observed);

        // ---------- 阶段一：不含注销的操作序列（需求 1.3、1.4、1.13） ----------
        int opIndex = 0;
        for (Op op : ops) {
            int slot = op.userSlot() % poolSize;
            Long userId = pool.get(slot);
            applyOperation(op, userId, pool, slot, seq, opIndex++, infoRepeats, returned.get(slot));
            // 每一步之后重读全部用户：某个用户的操作不得改动任何人的邀请码。
            snapshotAll(pool, observed);
        }

        // ---------- 阶段一断言 ----------
        Set<String> allCodes = new HashSet<>();
        for (int slot = 0; slot < poolSize; slot++) {
            String label = "用户槽位 " + slot;
            String settled = assertStableSequence(observed.get(slot), label);

            // 返回值与库中终态一致，且去重后至多一个（需求 1.4）。
            Set<String> codes = returned.get(slot);
            assertThat(codes).as(label + " 各次请求返回的邀请码去重后至多一个").hasSizeLessThanOrEqualTo(1);
            if (!codes.isEmpty()) {
                assertThat(codes.iterator().next())
                        .as(label + " 返回的邀请码等于库中取值").isEqualTo(settled);
            }
            if (settled != null) {
                assertThat(allCodes.add(settled)).as(label + " 邀请码不得与他人相同").isTrue();
            }
        }

        // ---------- 阶段二：并发惰性补齐（需求 1.12） ----------
        Long concurrentUserId = tx.execute(s ->
                persistUser(seq, "concurrent", null, null, BASE.plusSeconds(seq)).getId());
        ConcurrentOutcome outcome = concurrentlyRequireInviteCode(concurrentUserId, concurrency);

        assertThat(outcome.failures())
                .as("并发补齐被行级写锁串行化，全部请求都应成功；实际失败：%s", outcome.failures())
                .isEmpty();
        assertThat(outcome.successes()).as("并发请求的返回值").hasSize(concurrency);
        Set<String> distinct = new LinkedHashSet<>(outcome.successes());
        assertThat(distinct).as("全部成功响应中的邀请码取值相同（需求 1.12）").hasSize(1);

        String finalCode = readInviteCode(concurrentUserId);
        String won = distinct.iterator().next();
        assertThat(finalCode).as("终态恰好一个非空取值，且等于全部响应中的取值").isEqualTo(won);
        assertWellFormed(finalCode, "并发补齐的邀请码");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE invite_code = ?", Long.class, finalCode))
                .as("该邀请码在 users 表中恰好被一行持有").isEqualTo(1L);

        // 并发补齐之后再串行请求一次：仍返回同一个码，且不产生任何写入（需求 1.4）。
        Map<String, Object> before = userRow(concurrentUserId);
        assertThat(inviteService.requireInviteCode(concurrentUserId)).isEqualTo(won);
        assertThat(userRow(concurrentUserId)).as("已非空时再次请求不写库").isEqualTo(before);
    }

    // ---------------- 操作执行 ----------------

    /**
     * 执行一个操作。真实业务路径上合法的失败（本账号已绑该身份、解绑最后一种登录方式、
     * 昵称非法等）一律吞掉：本属性关心的是这些操作<b>是否碰 invite_code</b>，
     * 而失败的操作同样不该碰它——所以失败分支也是有效输入，不是需要规避的噪声。
     */
    private void applyOperation(Op op, Long userId, List<Long> pool, int slot, long seq,
            int opIndex, int infoRepeats, Set<String> returnedCodes) {
        switch (op.kind()) {
            case EMAIL_LOGIN -> {
                String email = readColumn(userId, "email");
                if (email != null) {
                    // 老用户重复登录：携带或不携带邀请码都不得改动 invite_code（需求 1.13、5.11）。
                    runInTransaction(() -> authService.emailLogin(
                            email, "000000", inviteCodeInput(op, pool, slot)));
                }
            }
            case WX_LOGIN -> {
                String openid = readColumn(userId, "wx_openid");
                if (openid != null) {
                    runInTransaction(() -> authService.wxLogin(
                            openid, inviteCodeInput(op, pool, slot)));
                }
            }
            case UPDATE_NICKNAME -> runInTransaction(() -> authService.updateNickname(
                    userId, "nick-" + seq + "-" + opIndex));
            case BIND_EMAIL -> runInTransaction(() -> authService.bindEmail(
                    userId, "p2-bind-" + seq + "-" + opIndex + "@example.com", "000000"));
            case BIND_WECHAT -> runInTransaction(() -> authService.bindWechat(
                    userId, "p2-openid-bind-" + seq + "-" + opIndex));
            case UNBIND_EMAIL -> runInTransaction(() -> authService.unbind(userId, "email"));
            case UNBIND_WECHAT -> runInTransaction(() -> authService.unbind(userId, "wechat"));
            case REQUEST_INFO -> {
                for (int i = 0; i < infoRepeats; i++) {
                    // 已非空时不得有任何写入：逐列比对整行快照（需求 1.4）。
                    Map<String, Object> before = userRow(userId);
                    boolean alreadySet = before.get("INVITE_CODE") != null;
                    InviteInfoView view = inviteService.getInviteInfo(userId);
                    returnedCodes.add(view.inviteCode());
                    assertThat(view.inviteLink())
                            .as("邀请链接由邀请码派生").endsWith("?code=" + view.inviteCode());
                    if (alreadySet) {
                        assertThat(userRow(userId))
                                .as("邀请码已非空时请求邀请信息不得写库").isEqualTo(before);
                    }
                }
            }
            case REQUIRE_CODE -> {
                for (int i = 0; i < infoRepeats; i++) {
                    Map<String, Object> before = userRow(userId);
                    boolean alreadySet = before.get("INVITE_CODE") != null;
                    returnedCodes.add(inviteService.requireInviteCode(userId));
                    if (alreadySet) {
                        assertThat(userRow(userId))
                                .as("邀请码已非空时惰性补齐不得写库").isEqualTo(before);
                    }
                }
            }
        }
    }

    /** 登录携带的邀请码输入：不携带 / 自己的码 / 另一名池内用户的码 / 一个不存在的合法码。 */
    private String inviteCodeInput(Op op, List<Long> pool, int slot) {
        return switch (op.variant()) {
            case 1 -> readInviteCode(pool.get(slot));
            case 2 -> readInviteCode(pool.get((slot + 1) % pool.size()));
            case 3 -> "ZZZZZZZZ";
            default -> null;
        };
    }

    /** 在真实事务内执行；业务上合法的失败吞掉（见 {@link #applyOperation} 注释）。 */
    private void runInTransaction(Runnable action) {
        try {
            tx.executeWithoutResult(status -> action.run());
        } catch (ApiException ignored) {
            // 已绑 / 最后一种登录方式 / 昵称非法等：对 invite_code 同样必须零副作用。
        }
    }

    // ---------------- 并发阶段 ----------------

    /**
     * {@code concurrency} 个线程对齐到同一时刻后同时调用 {@code requireInviteCode}。
     *
     * <p>线程池里跑的是容器 Bean 上的 {@code @Transactional} 方法，因此每个线程各自从连接池取
     * 独立连接、各自开启物理事务——行级写锁的争用是真实发生的。等待时间给足（就绪 10 秒、
     * 取结果 30 秒）：超时会让断言以「失败」而不是「卡死」暴露出来。</p>
     */
    private ConcurrentOutcome concurrentlyRequireInviteCode(Long userId, int concurrency) {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<String> successes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            List<Future<String>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                Callable<String> task = () -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发起跑信号超时");
                    }
                    return inviteService.requireInviteCode(userId);
                };
                futures.add(executor.submit(task));
            }
            if (!await(ready, 10)) {
                throw new IllegalStateException("并发线程未能在 10 秒内就绪");
            }
            go.countDown();

            for (Future<String> future : futures) {
                try {
                    successes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    failures.add(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (java.util.concurrent.TimeoutException e) {
                    failures.add("TimeoutException: 请求在 30 秒内未返回（疑似死锁）");
                }
            }
        } finally {
            executor.shutdownNow();
        }
        // 锁争用导致的失败也是失败：并发补齐的语义是「串行化后都成功」，不是「一个赢其余报错」。
        // 这里不对 ConcurrencyFailureException 做特殊放行，只把类型信息带进失败消息里。
        return new ConcurrentOutcome(successes, failures);
    }

    private static boolean await(CountDownLatch latch, long seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---------------- 断言 ----------------

    /**
     * 取值序列必须形如 {@code null*} 后接同一非空取值的重复：单调（不得回到空）、一次性
     * （非空后不得改变）。返回终态取值（全程为空时返回 {@code null}）。
     */
    private String assertStableSequence(List<String> values, String label) {
        String settled = null;
        for (String value : values) {
            if (value == null) {
                assertThat(settled).as(label + " 邀请码不得由非空回到空").isNull();
            } else if (settled == null) {
                assertWellFormed(value, label);
                settled = value;
            } else {
                assertThat(value).as(label + " 邀请码一经生成便不得改变").isEqualTo(settled);
            }
        }
        return settled;
    }

    private void assertWellFormed(String code, String label) {
        assertThat(code).as(label + " 长度恰为 8").hasSize(InviteCodeGenerator.LENGTH);
        assertThat(inviteCodeGenerator.isWellFormed(code)).as(label + " 全部字符取自字母表").isTrue();
    }

    // ---------------- 测试基础设施 ----------------

    /** 一个不含注销的操作。 */
    record Op(OpKind kind, int userSlot, int variant) {
    }

    /** 不含注销的操作类型（需求 1.13 点名的三类变更 + 重复登录 + 任意次数请求邀请信息）。 */
    enum OpKind {
        EMAIL_LOGIN, WX_LOGIN, UPDATE_NICKNAME, BIND_EMAIL, BIND_WECHAT,
        UNBIND_EMAIL, UNBIND_WECHAT, REQUEST_INFO, REQUIRE_CODE
    }

    private record ConcurrentOutcome(List<String> successes, List<String> failures) {
    }

    /**
     * 用户池：形态刻意混合。槽位 0、3 为<b>存量用户</b>（{@code invite_code} 为 NULL，同时持有
     * 邮箱与微信身份，用于触达惰性补齐与解绑两种路径）；槽位 1、4 经邮箱注册建号；
     * 槽位 2 经微信注册建号（后两者建号即带非空邀请码，用于验证「一经生成不再改变」）。
     */
    private List<Long> createUserPool(long seq, int poolSize) {
        List<Long> pool = new ArrayList<>(poolSize);
        LocalDateTime now = BASE.plusSeconds(seq);
        for (int i = 0; i < poolSize; i++) {
            int kind = i % 3;
            long tag = seq * 100 + i;
            Long id = switch (kind) {
                case 0 -> tx.execute(s -> persistUser(tag, "legacy",
                        "p2-legacy-" + tag + "@example.com", "p2-openid-legacy-" + tag, now).getId());
                case 1 -> tx.execute(s -> authService.emailLogin(
                        "p2-reg-" + tag + "@example.com", "000000", null).user().getId());
                default -> tx.execute(s -> authService.wxLogin("p2-openid-reg-" + tag, null)
                        .user().getId());
            };
            pool.add(id);
        }
        return pool;
    }

    /** 存量用户：{@code invite_code} 留空（迁移脚本不回填，见需求 9.1），供惰性补齐触达。 */
    private User persistUser(long tag, String label, String email, String openid,
            LocalDateTime now) {
        User u = new User();
        u.setEmail(email);
        u.setWxOpenid(openid);
        u.setNickname(label + "-" + tag);
        u.setInviteCode(null);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    /** 每一步之后重读全部用户的邀请码（读库取值，不看内存中的实体）。 */
    private void snapshotAll(List<Long> pool, List<List<String>> observed) {
        for (int i = 0; i < pool.size(); i++) {
            observed.get(i).add(readInviteCode(pool.get(i)));
        }
    }

    /** 读库取邀请码；NULL 与去空白为空一律折叠为 {@code null}（需求 1.3 的判定口径）。 */
    private String readInviteCode(Long userId) {
        String code = readColumn(userId, "invite_code");
        return (code == null || code.isBlank()) ? null : code;
    }

    private String readColumn(Long userId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM users WHERE id = ?", String.class, userId);
    }

    /** 整行快照：用于断言「已非空时一列都不写」，含 {@code updated_at}。 */
    private Map<String, Object> userRow(Long userId) {
        return jdbcTemplate.queryForMap(SELECT_USER_ROW_SQL, userId);
    }
}
