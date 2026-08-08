package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.config.TimeConfig;
import com.damien.youyu.domain.InviteStatus;
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
 * 一个 invitee 至多一条邀请关系（Property 5）：{@code invite_relations} 中以任一 {@code invitee_id}
 * 为键的行数恒 ≤1，重复登录终态恒为 1 行，直接重复插入被数据库以唯一约束违例拒绝且不产生部分写入，
 * 并发以同一 {@code invitee_id} 插入后终态恰好 1 行、落败方以 {@code ALREADY_BOUND} 完成登录。
 *
 * <h2>测试层级选择</h2>
 * <p>被断言的主体是 {@code uk_invite_relations_invitee} 这条<b>数据库唯一索引</b>本身
 * （需求 6.1 明确要求唯一性不得由应用层的「先查询后写入」保证），因此必须是真实数据库上的真实事务：</p>
 * <ul>
 *   <li>「直接重复插入被拒且无部分写入」（需求 6.4）只能由真实索引给出，任何测试替身都是自证。</li>
 *   <li>「并发落败方以 {@code ALREADY_BOUND} 完成登录、不返回 5xx」（需求 6.9）依赖真实的行锁争用 →
 *       {@link DuplicateKeyException} → 保存点回滚这条完整链路。</li>
 *   <li>「重复登录终态 1 行」（需求 6.6）与「行写定后不再改动」（需求 6.9）判定标准都是<b>读库快照</b>。</li>
 * </ul>
 * <p>故走 {@code @DataJpaTest} + H2（{@code MODE=MySQL}，表由实体生成，唯一约束由
 * {@link com.damien.youyu.domain.InviteRelation} 的 {@code @Table} 声明）。{@link AuthService}
 * 由测试手工构造：验证码校验与微信换取 openid 是无关的外部依赖，用替身消掉；邀请码生成器与
 * {@link InviteBindingService} 用的是容器 Bean，绑定链路照真实路径走。</p>
 *
 * <p><b>为什么这里没有测试事务</b>：jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension}
 * 与 {@code TransactionalTestExecutionListener} 都不生效，依赖注入改由 {@link TestContextManager}
 * 在 {@link BeforeTry} 中手工完成（上下文由 Spring 静态缓存复用，100 次迭代只加载一次）。于是
 * {@link TransactionTemplate} 走的是真实的「开启—提交」——这正是本属性需要的：并发阶段的各个请求
 * 必须落在各自独立、各自真会提交的物理事务上，否则唯一索引根本不会被争用。</p>
 *
 * <h2>并发阶段确实被真实执行</h2>
 * <p>{@code concurrency} 个线程各自从连接池取独立连接、各自开启事务，由 {@link CountDownLatch}
 * 对齐到同一时刻后同时以<b>同一个</b> {@code invitee_id} 进入 {@code bindOnRegister}。断言
 * 「恰好一个线程 bound ∧ 其余全为 {@code ALREADY_BOUND} ∧ 无任何线程抛出 ∧ 终态 1 行」对
 * 「去掉唯一索引」这一改动是敏感的：没有 {@code uk_invite_relations_invitee}，多个线程会各自插入成功，
 * bound 的线程数大于一、终态行数大于 1，本属性随即失败。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li>请求序列长度 1–30，元素取自 {@link ReqKind}（邮箱注册、微信注册、同一被邀请人重复登录 2–10 次）；
 *       邀请人池 2–4 人（小值域，多个被邀请人共享同一 {@code inviter_id}）。</li>
 *   <li>重复登录携带的邀请码变体：同一个码 / 另一名邀请人的码 / 畸形码 / 被邀请人自己的码
 *       ——四者都应得到 {@code NOT_NEW_USER}（判定链优先级，需求 6.6）。</li>
 *   <li>直接重复插入的目标从本次已建立的关系行中随机选取，写入与原行不同的
 *       {@code inviter_id}/{@code register_time}/{@code status}，以便「无部分写入」是可观测的。</li>
 *   <li>并发度 2–8。</li>
 * </ul>
 *
 * <p>Feature: invite-system, Property 5: 一个 invitee 至多一条邀请关系</p>
 *
 * <p>Validates: Requirements 6.1, 6.3, 6.4, 6.6, 6.9, 9.3</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ InviteBindingService.class, InviteCodeGenerator.class, TimeConfig.class })
class InviteUniqueRelationPropertyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    /** 同一个 H2 库跨迭代复用：迭代序号保证 email / openid 全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * 全局用户序号：邀请码由它派生，跨迭代绝不重号（同一个库里 invite_code 是唯一列）。
     * 只在类内唯一，跨类的不相交由 {@link #codeOf} 的 {@code U5} 前缀与 {@code u5-} 邮箱前缀负责。
     */
    private static final AtomicLong USER_TAG = new AtomicLong();

    private static final String INSERT_RELATION_SQL =
            "INSERT INTO invite_relations "
                    + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SELECT_RELATION_SQL =
            "SELECT invite_id, inviter_id, invitee_id, register_time, status, created_at, updated_at "
                    + "FROM invite_relations WHERE invitee_id = ?";
    /** 全表体检：存在任一 {@code invitee_id} 出现两次以上即为反例。 */
    private static final String DUPLICATE_GROUPS_SQL =
            "SELECT COUNT(*) FROM (SELECT invitee_id FROM invite_relations "
                    + "GROUP BY invitee_id HAVING COUNT(*) > 1) AS dup";

    @Autowired
    private InviteBindingService bindingService;
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
        new TestContextManager(InviteUniqueRelationPropertyTest.class).prepareTestInstance(this);
        // 默认传播行为 REQUIRED：为 bindOnRegister 的 MANDATORY 提供外层物理事务。
        tx = new TransactionTemplate(transactionManager);

        VerificationCodeService codes = mock(VerificationCodeService.class);
        when(codes.verifyConsume(anyString(), any(), anyString())).thenReturn(true);
        WeChatClient wechat = mock(WeChatClient.class);
        // openid 直接取 code 原文：于是「用某个 openid 登录」可复现地命中同一个账号。
        when(wechat.jscode2session(anyString()))
                .thenAnswer(inv -> new WxSession(inv.getArgument(0), null));
        authService = new AuthService(userRepository, clock, wechat, codes,
                inviteCodeGenerator, bindingService);
    }

    // ---------------- 生成器 ----------------

    /** 请求序列：每个元素 = 请求类型 + 邀请人槽位 + 目标被邀请人序号 + 重复次数 + 邀请码变体。 */
    @Provide
    Arbitrary<List<Req>> requestSequences() {
        Arbitrary<Req> one = Combinators.combine(
                        Arbitraries.of(ReqKind.values()),
                        Arbitraries.integers().between(0, 3),
                        Arbitraries.integers().between(0, 29),
                        Arbitraries.integers().between(2, 10),
                        Arbitraries.integers().between(0, 3))
                .as(Req::new);
        return one.list().ofMinSize(1).ofMaxSize(30);
    }

    // ---------------- Property 5 ----------------

    /**
     * Feature: invite-system, Property 5: 一个 invitee 至多一条邀请关系
     *
     * <p>对任意登录/注册请求序列（含同一被邀请人的重复登录 2–10 次、含多个请求并发以同一
     * {@code invitee_id} 插入）：</p>
     * <ul>
     *   <li>每一步之后，全表中任一 {@code invitee_id} 的行数 ≤1——唯一性由数据库索引
     *       {@code uk_invite_relations_invitee} 保证，而非应用层先查后写（需求 6.1、9.3）；</li>
     *   <li>同一被邀请人连续重复登录 2–10 次后，以其为 {@code invitee_id} 的行数恒为 1，
     *       第 2 次起未绑定原因为 {@code NOT_NEW_USER}，且该行逐列快照全程不变（需求 6.6、6.9）；</li>
     *   <li>直接以已存在的 {@code invitee_id} 插入被数据库以唯一约束违例拒绝，表行数与该行全部列取值
     *       保持语句执行前的状态，不产生部分写入（需求 6.4）；</li>
     *   <li>并发以同一 {@code invitee_id} 插入：终态行数为 1，恰好一个请求 bound，其余全部以
     *       {@code ALREADY_BOUND} 正常返回（无异常穿出 ⇒ 登录照常提交、响应状态码 200 &lt; 500），
     *       且获胜行的 {@code inviter_id}/{@code register_time}/{@code status} 此后不再改变
     *       （需求 6.3、6.9）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 6.1, 6.3, 6.4, 6.6, 6.9, 9.3</p>
     */
    @Property(tries = 25)
    void property5_atMostOneRelationPerInvitee(
            @ForAll("requestSequences") List<Req> requests,
            @ForAll @IntRange(min = 2, max = 4) int inviterPoolSize,
            @ForAll @IntRange(min = 0, max = 29) int dupInsertTarget,
            @ForAll @IntRange(min = 2, max = 8) int concurrency) {

        long seq = SEQ.incrementAndGet();
        LocalDateTime now = BASE.plusSeconds(seq);
        List<Inviter> inviters = createInviters(inviterPoolSize, now);
        List<Invitee> invitees = new ArrayList<>();

        // ---------- 阶段一：请求序列（需求 6.1、6.6、6.9、9.3） ----------
        int idx = 0;
        for (Req req : requests) {
            Inviter inviter = inviters.get(req.inviterSlot() % inviterPoolSize);
            switch (req.kind()) {
                case REGISTER_EMAIL -> invitees.add(registerByEmail(seq, idx++, inviter));
                case REGISTER_WECHAT -> invitees.add(registerByWechat(seq, idx++, inviter));
                case REPEAT_LOGIN -> {
                    if (!invitees.isEmpty()) {
                        Invitee target = invitees.get(req.target() % invitees.size());
                        repeatLogin(target, inviters, req);
                    }
                }
            }
            // 每一步之后全表体检：任何 invitee_id 都不得出现两次以上。
            assertNoDuplicateInvitee();
        }

        // 序列可能一条关系也没建立（例如全是空转的 REPEAT_LOGIN）：补一个被邀请人，
        // 让后两个阶段的断言无条件生效。
        if (invitees.isEmpty()) {
            invitees.add(registerByEmail(seq, idx, inviters.get(0)));
            assertNoDuplicateInvitee();
        }

        // ---------- 阶段二：直接重复插入被唯一约束拒绝，且无部分写入（需求 6.4） ----------
        Invitee dupTarget = invitees.get(dupInsertTarget % invitees.size());
        long countBefore = countRelations();
        Map<String, Object> rowBefore = relationOf(dupTarget.userId());
        Long otherInviterId = inviters.get(inviterPoolSize - 1).userId();

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_RELATION_SQL,
                otherInviterId, dupTarget.userId(), now.plusMinutes(7),
                InviteStatus.INVALID.name(), now.plusMinutes(7), now.plusMinutes(7)))
                .as("以已存在的 invitee_id 插入必须被唯一约束违例拒绝（需求 6.4）")
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(countRelations()).as("被拒的插入不得改变表行数").isEqualTo(countBefore);
        assertThat(relationOf(dupTarget.userId()))
                .as("被拒的插入不得产生部分写入：该行逐列取值不变").isEqualTo(rowBefore);
        assertNoDuplicateInvitee();

        // ---------- 阶段三：并发以同一 invitee_id 插入（需求 6.3、6.9） ----------
        long concurrentTag = USER_TAG.incrementAndGet();
        Long concurrentInviteeId = tx.execute(s ->
                persistUser(concurrentTag, "concurrent", codeOf(concurrentTag), now).getId());
        List<Inviter> racers = createInviters(concurrency, now);
        List<Object> outcomes = raceOnSameInvitee(concurrentInviteeId, racers, now, concurrency);

        List<Object> thrown = outcomes.stream().filter(o -> o instanceof Throwable).toList();
        assertThat(thrown)
                .as("异常穿出 bindOnRegister 会让登录事务回滚并返回 5xx；落败方必须无异常（需求 6.9）")
                .isEmpty();
        List<InviteBindResult> results = outcomes.stream()
                .map(InviteBindResult.class::cast).toList();
        assertThat(results).hasSize(concurrency);
        assertThat(results.stream().filter(InviteBindResult::bound).count())
                .as("并发插入同一 invitee_id：恰好一个请求建立关系").isEqualTo(1L);
        for (InviteBindResult result : results) {
            if (!result.bound()) {
                assertThat(result.reason())
                        .as("落败方一律以 ALREADY_BOUND 完成登录（需求 6.9）")
                        .isEqualTo(UnboundReason.ALREADY_BOUND);
            }
        }
        assertThat(statusCodesOf(outcomes))
                .as("全部并发请求的响应状态码均 < 500")
                .allSatisfy(status -> assertThat(status).isLessThan(500));

        assertThat(countRelationsOf(concurrentInviteeId))
                .as("并发插入后终态行数为 1（需求 6.9）").isEqualTo(1L);
        Map<String, Object> winnerRow = relationOf(concurrentInviteeId);
        List<Long> racerIds = racers.stream().map(Inviter::userId).toList();
        assertThat((Long) winnerRow.get("INVITER_ID"))
                .as("获胜行的 inviter_id 来自参与竞争的邀请人之一").isIn(racerIds);
        assertNoDuplicateInvitee();

        // 关系一次写定：再来一次绑定尝试仍得 ALREADY_BOUND，且该行逐列不变（需求 6.3、6.9）。
        User concurrentInvitee = userRepository.findById(concurrentInviteeId).orElseThrow();
        InviteBindResult again = tx.execute(s -> bindingService.bindOnRegister(
                concurrentInvitee, true, racers.get(0).inviteCode(), now.plusMinutes(3)));
        assertThat(again).isNotNull();
        assertThat(again.reason()).isEqualTo(UnboundReason.ALREADY_BOUND);
        assertThat(relationOf(concurrentInviteeId))
                .as("已存在的关系行不可改绑（需求 6.3）").isEqualTo(winnerRow);
        assertThat(countRelationsOf(concurrentInviteeId)).isEqualTo(1L);
        assertNoDuplicateInvitee();
    }

    // ---------------- 请求执行 ----------------

    /** 邮箱注册：建号即绑定，该被邀请人应恰好得到一行关系。 */
    private Invitee registerByEmail(long seq, int idx, Inviter inviter) {
        String email = "u5-invitee-" + seq + "-" + idx + "@example.com";
        LoginOutcome outcome = tx.execute(s ->
                authService.emailLogin(email, "000000", inviter.inviteCode()));
        return assertRegistered(outcome, inviter, email, false);
    }

    /** 微信注册：openid 取 code 原文（见 {@link #injectSpringBeans}）。 */
    private Invitee registerByWechat(long seq, int idx, Inviter inviter) {
        String openid = "u5-openid-invitee-" + seq + "-" + idx;
        LoginOutcome outcome = tx.execute(s ->
                authService.wxLogin(openid, inviter.inviteCode()));
        return assertRegistered(outcome, inviter, openid, true);
    }

    private Invitee assertRegistered(LoginOutcome outcome, Inviter inviter, String loginKey,
            boolean wechat) {
        assertThat(outcome).isNotNull();
        assertThat(outcome.isNewUser()).as("首次登录即建号").isTrue();
        assertThat(outcome.inviteBind().bound())
                .as("合法且非自邀的邀请码在建号时应绑定成功").isTrue();

        Long inviteeId = outcome.user().getId();
        assertThat(countRelationsOf(inviteeId)).as("新被邀请人恰好一行关系").isEqualTo(1L);
        Map<String, Object> row = relationOf(inviteeId);
        assertThat((Long) row.get("INVITER_ID")).isEqualTo(inviter.userId());
        return new Invitee(inviteeId, loginKey, wechat, readInviteCode(inviteeId), row);
    }

    /**
     * 同一被邀请人以同一身份重复登录 2–10 次（需求 6.6）：每次都应得到 {@code NOT_NEW_USER}
     * （判定链中 {@code NOT_NEW_USER} 优先于格式校验，故畸形码变体同样如此），行数恒为 1，
     * 且该行逐列快照全程与建立时相同（一次写定，需求 6.9）。
     */
    private void repeatLogin(Invitee target, List<Inviter> inviters, Req req) {
        for (int i = 0; i < req.repeats(); i++) {
            String code = repeatLoginCode(target, inviters, req, i);
            LoginOutcome outcome = target.wechat()
                    ? tx.execute(s -> authService.wxLogin(target.loginKey(), code))
                    : tx.execute(s -> authService.emailLogin(target.loginKey(), "000000", code));

            assertThat(outcome).isNotNull();
            assertThat(outcome.isNewUser()).as("重复登录不再建号").isFalse();
            assertThat(outcome.user().getId()).isEqualTo(target.userId());
            assertThat(outcome.inviteBind().bound()).isFalse();
            assertThat(outcome.inviteBind().reason())
                    .as("第 2 次及其后各次的未绑定原因为 NOT_NEW_USER（需求 6.6）")
                    .isEqualTo(UnboundReason.NOT_NEW_USER);
            assertThat(countRelationsOf(target.userId()))
                    .as("重复登录幂等：行数恒为 1（需求 6.6）").isEqualTo(1L);
            assertThat(relationOf(target.userId()))
                    .as("邀请关系一次写定，重复登录不得改动任何列（需求 6.9）")
                    .isEqualTo(target.rowAtCreation());
        }
    }

    /** 重复登录携带的邀请码：同一个码 / 另一名邀请人的码 / 畸形码 / 被邀请人自己的码。 */
    private String repeatLoginCode(Invitee target, List<Inviter> inviters, Req req, int round) {
        return switch (req.variant()) {
            case 1 -> inviters.get((req.inviterSlot() + 1 + round) % inviters.size()).inviteCode();
            case 2 -> "!!bad-code-" + round;
            case 3 -> target.ownCode();
            default -> inviters.get(req.inviterSlot() % inviters.size()).inviteCode();
        };
    }

    // ---------------- 并发阶段 ----------------

    /**
     * {@code concurrency} 个线程对齐到同一时刻后，同时以<b>同一个</b> {@code invitee_id} 调用
     * {@code bindOnRegister}（各自携带不同邀请人的邀请码，便于识别获胜者）。
     *
     * <p>每个线程各自 {@link TransactionTemplate#execute} 开启独立物理事务、从连接池取独立连接，
     * 唯一索引上的争用是真实发生的。返回每个线程的 {@link InviteBindResult} 或抛出的
     * {@link Throwable}（后者即「返回了 5xx」的等价物）。</p>
     */
    private List<Object> raceOnSameInvitee(Long inviteeId, List<Inviter> racers,
            LocalDateTime now, int concurrency) {
        User invitee = userRepository.findById(inviteeId).orElseThrow();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Object> outcomes = new ArrayList<>(concurrency);
        try {
            List<Future<InviteBindResult>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                String code = racers.get(i).inviteCode();
                Callable<InviteBindResult> task = () -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发起跑信号超时");
                    }
                    return tx.execute(s -> bindingService.bindOnRegister(invitee, true, code, now));
                };
                futures.add(executor.submit(task));
            }
            if (!await(ready)) {
                throw new IllegalStateException("并发线程未能在 10 秒内就绪");
            }
            go.countDown();

            for (Future<InviteBindResult> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause() == null ? e : e.getCause());
                } catch (TimeoutException e) {
                    outcomes.add(new IllegalStateException("请求在 30 秒内未返回（疑似死锁）", e));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return outcomes;
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把每个并发请求的结局折算成 HTTP 状态码：正常返回的绑定结果对应登录成功的 200；
     * {@link ApiException} 取其自带状态；其余异常穿出 {@code @Transactional} 会让登录事务回滚，
     * 由全局异常处理器映射为 500。
     */
    private List<Integer> statusCodesOf(List<Object> outcomes) {
        List<Integer> statuses = new ArrayList<>(outcomes.size());
        for (Object outcome : outcomes) {
            if (outcome instanceof InviteBindResult) {
                statuses.add(200);
            } else if (outcome instanceof ApiException api) {
                statuses.add(api.getStatus().value());
            } else {
                statuses.add(500);
            }
        }
        return statuses;
    }

    // ---------------- 断言 ----------------

    /** 全表体检：不存在任何出现两次以上的 {@code invitee_id}（需求 6.1、9.3）。 */
    private void assertNoDuplicateInvitee() {
        assertThat(jdbcTemplate.queryForObject(DUPLICATE_GROUPS_SQL, Long.class))
                .as("invite_relations 中不得存在任何重复的 invitee_id")
                .isZero();
    }

    // ---------------- 测试基础设施 ----------------

    /** 一个登录/注册请求。 */
    record Req(ReqKind kind, int inviterSlot, int target, int repeats, int variant) {
    }

    /** 请求类型：两种建号路径 + 同一被邀请人的重复登录。 */
    enum ReqKind {
        REGISTER_EMAIL, REGISTER_WECHAT, REPEAT_LOGIN
    }

    private record Inviter(Long userId, String inviteCode) {
    }

    private record Invitee(Long userId, String loginKey, boolean wechat, String ownCode,
                           Map<String, Object> rowAtCreation) {
    }

    private List<Inviter> createInviters(int size, LocalDateTime now) {
        List<Inviter> inviters = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long tag = USER_TAG.incrementAndGet();
            String code = codeOf(tag);
            Long id = tx.execute(s -> persistUser(tag, "inviter", code, now).getId());
            inviters.add(new Inviter(id, code));
        }
        return inviters;
    }

    /**
     * 把 n 编码成 8 位邀请码：前两位固定为本类专属前缀 {@code U5}，后 6 位取字母表 32 进制。
     *
     * <p>类专属前缀是<b>必需的</b>而非装饰：全部切片测试共用同一个内存 H2
     * （{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且 Spring 上下文缓存跨测试类复用，
     * {@code create-drop} 因此不会在类之间清库；各兄弟属性测试的序号又都从 0 开始，
     * 若共用同一段码空间，{@code uk_users_invite_code} 会随机爆掉。
     * 同理本类的 email / openid 一律带 {@code u5-} 前缀。</p>
     */
    private static String codeOf(long n) {
        char[] out = new char[InviteCodeGenerator.LENGTH];
        out[0] = 'U';
        out[1] = '5';
        long v = n;
        for (int i = InviteCodeGenerator.LENGTH - 1; i >= 2; i--) {
            out[i] = InviteCodeGenerator.ALPHABET.charAt(
                    (int) (v % InviteCodeGenerator.ALPHABET.length()));
            v /= InviteCodeGenerator.ALPHABET.length();
        }
        return new String(out);
    }

    private User persistUser(long tag, String label, String inviteCode, LocalDateTime now) {
        User u = new User();
        // u5- 前缀：库跨测试类共用，邮箱唯一约束同样需要类专属命名空间（见 codeOf 的说明）。
        u.setEmail("u5-" + label + "-" + tag + "@example.com");
        u.setNickname("u5-" + label + "-" + tag);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    private long countRelations() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations", Long.class);
        return count == null ? 0L : count;
    }

    private long countRelationsOf(Long inviteeId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations WHERE invitee_id = ?", Long.class, inviteeId);
        return count == null ? 0L : count;
    }

    /** 该被邀请人的关系行逐列快照（含审计列），用于「一次写定」与「无部分写入」的比对。 */
    private Map<String, Object> relationOf(Long inviteeId) {
        return jdbcTemplate.queryForMap(SELECT_RELATION_SQL, inviteeId);
    }

    private String readInviteCode(Long userId) {
        String code = jdbcTemplate.queryForObject(
                "SELECT invite_code FROM users WHERE id = ?", String.class, userId);
        return code == null ? "" : code.toUpperCase(Locale.ROOT);
    }
}
