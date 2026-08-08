package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 注销后的保留不变式（Property 12）。
 *
 * <p>对任意「注册序列（邀请码构成邀请图）× 注销顺序 × 注销前置校验结果」的交错序列，在<b>每一步
 * 之后</b>：注销从不删除任何 {@code invite_relations} 行；注销只把「该用户作为被邀请人」那一行的
 * {@code status} 置 {@code INVALID} 并刷新 {@code updated_at}，其余行（含该用户作为<b>邀请人</b>
 * 的全部行）逐列逐行一字不改；邀请码随 {@code users} 行释放且可被重新占用；统计恒等式
 * 「总条数 − 已邀请人数 == INVALID 行数」始终成立；归属判定认 {@code inviter_id} 而非邀请码取值。</p>
 *
 * <h2>测试层级选择：为什么必须是真实持久化（{@code @DataJpaTest} + H2）</h2>
 * <p>本属性断言的对象<b>就是数据库里的行</b>：「删除 {@code users} 行不会连带删掉
 * {@code invite_relations} 行」这一条完全取决于表上有没有指向 {@code users(id)} 的外键（需求 9.5、9.6
 * 刻意不建），而「只改两列」取决于 {@code markInvalidByInviteeId} 的 UPDATE 语句真的只写这两列。
 * 把仓储换成测试替身，这两条就都变成了对替身自身行为的自证——真正会咬人的回归（有人顺手补上外键、
 * 有人把 UPDATE 改成 {@code save(entity)} 从而回写全列、有人把「置 INVALID」挪到删 {@code users}
 * 行之后）一个都测不出来。因此这里走 {@code @DataJpaTest} + H2（表结构由实体生成，
 * {@link com.damien.youyu.domain.InviteRelation} 同样没有关联映射与外键，与生产 MySQL 一致，
 * 故结论可迁移），真实跑 {@link AuthService#emailLogin} / {@link AuthService#wxLogin} 的建号绑定路径、
 * {@link AccountDeletionService#requireDeletable} /
 * {@link AccountDeletionService#verifySecondFactor} /
 * {@link AccountDeletionService#deleteAccount} 的注销路径，以及
 * {@link InviteService#getInviteInfo} / {@link InviteService#listInvitees} /
 * {@link InviteService#findInviterNickname} 的读路径。</p>
 *
 * <p>只有三处与邀请数据无关的测试替身：{@link VerificationCodeService}（否则得先发一封邮件；
 * 其返回值同时用于制造「二次验证不通过」这一前置校验失败分支）、{@link WeChatClient}（否则得外呼微信）、
 * 以及 {@link InviteCodeGenerator} 的随机源（换成可编排的随机源，才能让新用户精确抽到某个已释放的
 * 邀请码，验证「码可被重新占用且历史行不串味」）。{@link Clock} 换成可推进的固定时钟，
 * {@code updated_at} 的刷新因此可以精确断言而不是「大概变了」。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文由 Spring 静态上下文缓存复用，
 * 200 次迭代只加载一次）。同理也没有测试事务回滚，各次迭代写入的行一直留在同一张表里——这正是本属性
 * 需要的：注销的「保留」语义只有在数据真实留存时才有意义。断言范围因此按本次迭代创建的用户 id 过滤，
 * 而不是整张表（同一个内存库里可能有别的测试类留下的行）；唯一的例外是「注销前后
 * {@code invite_relations} 全表行数不变」这一条——它刻意取全表，把「一行都不许少」表达到最强。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li><b>用户池 3–8 人</b>，每人经真实登录接口建号（邮箱 / 微信两种身份形态各占一半，
 *       身份形态决定二次验证走哪条分支）。</li>
 *   <li><b>邀请图</b>：每个新用户可携带池中任一既有成员的邀请码，或刻意链式携带上一个用户的码——
 *       后者稳定制造<b>双重身份</b>用户（既是若干行的 {@code inviter_id}，又是某一行的
 *       {@code invitee_id}），即需求 10.3 的核心场景。</li>
 *   <li><b>注销序列 1–5 次</b>，目标由选择子在存活用户中取模选出（顺序任意排列）。</li>
 *   <li><b>前置校验结果</b> ∈ {通过, {@code DELETE_BLOCKED_COLLAB}（协作账本仍有他人成员）,
 *       二次验证失败（邮箱验证码不通过 / 微信 openid 不匹配）}，按 5:1:1 加权——失败分支用于断言
 *       需求 10.6 的「零副作用」。</li>
 * </ul>
 *
 * <p>Feature: invite-system, Property 12: 注销后的保留不变式</p>
 *
 * <p>Validates: Requirements 9.5, 9.6, 10.1, 10.2, 10.3, 10.4, 10.6, 10.7, 10.9, 10.10</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuthService.class, AccountDeletionService.class, InviteBindingService.class,
        InviteService.class, InviteRateLimiter.class })
class InviteDeletionRetentionPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:00:00Z");

    /**
     * 同一个 H2 库跨迭代复用：用序号保证 email / openid 全局唯一。
     *
     * <p>序号只在类内唯一，因此本类造出的所有 email / openid 一律带 {@code p12-} 前缀：
     * 全部切片测试共用同一个内存 H2（{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且
     * Spring 上下文缓存跨测试类复用，{@code create-drop} 不会在类之间清库，
     * 兄弟属性测试的序号又都从 0 开始——不带类专属前缀就会在 {@code users} 的唯一约束上
     * 随机撞车。本类的邀请码全部由 {@link InviteCodeGenerator} 从
     * {@link PlannedRandom} 抽取（未编排时回落到伪随机，且生成器自带唯一性重试），
     * 因此不需要额外的码空间前缀；兄弟测试则各自占一段固定的两字符前缀码空间。</p>
     */
    private static final AtomicLong SEQ = new AtomicLong();

    /** 可推进的固定时钟：{@code updated_at} 的刷新因此可精确断言（需求 10.2）。 */
    private static final MutableClock CLOCK = new MutableClock(T0);

    /** 可编排的随机源：用于让新用户精确抽到某个已释放的邀请码（需求 10.4、10.10）。 */
    private static final PlannedRandom PLANNED = new PlannedRandom();

    /** 邮箱验证码校验结果开关：制造「二次验证不通过」的前置校验失败分支（需求 10.6）。 */
    private static final AtomicBoolean EMAIL_CODE_VALID = new AtomicBoolean(true);

    @Autowired
    private AuthService authService;
    @Autowired
    private InviteService inviteService;
    @Autowired
    private AccountDeletionService deletionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private LedgerMemberRepository memberRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(InviteDeletionRetentionPropertyTest.class).prepareTestInstance(this);
        PLANNED.reset();
        EMAIL_CODE_VALID.set(true);
    }

    @TestConfiguration
    static class Stubs {

        @Bean
        Clock clock() {
            return CLOCK;
        }

        @Bean
        InviteCodeGenerator inviteCodeGenerator() {
            return new InviteCodeGenerator(PLANNED);
        }

        @Bean
        VerificationCodeService verificationCodeService() {
            VerificationCodeService stub = mock(VerificationCodeService.class);
            when(stub.verifyConsume(anyString(), any(EmailCodePurpose.class), anyString()))
                    .thenAnswer(inv -> EMAIL_CODE_VALID.get());
            return stub;
        }

        @Bean
        WeChatClient weChatClient() {
            WeChatClient stub = mock(WeChatClient.class);
            // openid 直接取一次性 code：调用方因此能控制「本人授权」与「他人授权」两种二次验证结果。
            when(stub.jscode2session(anyString()))
                    .thenAnswer(inv -> new WxSession(inv.getArgument(0), null));
            return stub;
        }
    }

    // ---------------- 生成器 ----------------

    /** 一次建号：身份形态 + 是否携带邀请码 + 邀请人选择子（链式则取上一个用户，稳定制造双重身份）。 */
    record Reg(boolean wx, boolean withInviter, boolean chainToPrevious, int inviterSelector) {
    }

    /** 注销前置校验结果。 */
    enum Precheck {
        /** 前置校验通过，真正执行注销。 */
        PASS,
        /** 协作账本仍有他人成员 → {@code DELETE_BLOCKED_COLLAB}。 */
        BLOCKED_COLLAB,
        /** 二次验证不通过（邮箱验证码无效 / 微信 openid 不匹配）。 */
        SECOND_FACTOR_FAIL
    }

    /** 一次注销尝试：目标选择子（在存活用户中取模）+ 前置校验结果。 */
    record Del(int selector, Precheck precheck) {
    }

    @Provide
    Arbitrary<List<Reg>> registrationSequences() {
        Arbitrary<Reg> reg = Combinators.combine(
                        Arbitraries.of(true, false),
                        Arbitraries.frequency(Tuple.of(4, true), Tuple.of(1, false)),
                        Arbitraries.of(true, false),
                        Arbitraries.integers().between(0, 7))
                .as(Reg::new);
        return reg.list().ofMinSize(3).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<Del>> deletionSequences() {
        Arbitrary<Del> del = Combinators.combine(
                        Arbitraries.integers().between(0, 7),
                        Arbitraries.frequency(
                                Tuple.of(5, Precheck.PASS),
                                Tuple.of(1, Precheck.BLOCKED_COLLAB),
                                Tuple.of(1, Precheck.SECOND_FACTOR_FAIL)))
                .as(Del::new);
        return del.list().ofMinSize(1).ofMaxSize(5);
    }

    // ---------------- Property 12 ----------------

    /**
     * Feature: invite-system, Property 12: 注销后的保留不变式
     *
     * <p>对任意注册序列（邀请码构成含双重身份的邀请图）与任意注销序列（含前置校验失败），
     * 在每一步之后：</p>
     * <ul>
     *   <li>注销不删除任何 {@code invite_relations} 行：全表行数与本次迭代相关行的主键集合不变
     *       （需求 9.5、9.6、10.1、10.2）；</li>
     *   <li>以注销者 id 为 {@code inviter_id} 的行逐行七列快照不变，{@code status} 也不变
     *       （需求 10.1、10.3）；</li>
     *   <li>以注销者 id 为 {@code invitee_id} 的行仍存在，仅 {@code status} 变为 {@code INVALID}、
     *       {@code updated_at} 刷新为注销时刻，另外五列不变，且这样的行至多一行
     *       （需求 10.2、10.3）；</li>
     *   <li>与注销者无关的行一列不改（把「影响行数至多为 1」表达到全表范围）；</li>
     *   <li>注销者的邀请码被释放（{@code users} 中该码行数为 0），且可被后续新用户重新占用而不触发
     *       唯一冲突、不返回 {@code INVITE_CODE_GEN_FAILED}（需求 10.4）；</li>
     *   <li>前置校验未通过时（{@code DELETE_BLOCKED_COLLAB} / 二次验证失败）
     *       {@code invite_relations} 零副作用、该用户 {@code users.invite_code} 不变、用户行仍在
     *       （需求 10.6）；</li>
     *   <li>被邀请人注销后其邀请人的已邀请人数恰好减 1、总条数不变，列表中仍返回该行且
     *       {@code status} 为 {@code INVALID}、昵称为空值（需求 10.7）；</li>
     *   <li>统计恒等式「总条数 − 已邀请人数 == INVALID 行数」在每一步之后成立（需求 10.7）；</li>
     *   <li>以已释放的码查询邀请人展示信息得 {@code NOT_FOUND}，带该码登录得 {@code CODE_NOT_FOUND}
     *       且登录成功（需求 10.9）；</li>
     *   <li>该码被新用户重新占用后，历史行（{@code inviter_id} 仍是已注销者）不出现在新持有者的邀请
     *       信息与被邀请人列表中——归属以 {@code inviter_id} 判定，与邀请码取值无关（需求 10.10）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 9.5, 9.6, 10.1, 10.2, 10.3, 10.4, 10.6, 10.7, 10.9, 10.10</p>
     */
    @Property(tries = 25)
    void property12_retentionInvariantsAfterAccountDeletion(
            @ForAll("registrationSequences") List<Reg> registrations,
            @ForAll("deletionSequences") List<Del> deletions) {

        long iteration = SEQ.incrementAndGet();
        List<Long> created = new ArrayList<>();   // 本次迭代创建的全部用户 id（含已注销）
        List<Long> live = new ArrayList<>();      // 仍存活的用户 id

        // ---------- 建号阶段：邀请图（含双重身份用户） ----------
        for (Reg reg : registrations) {
            CLOCK.advanceSeconds(60);
            Long inviterId = chooseInviter(reg, live);
            String inviteCode = inviterId == null ? null : codeOf(inviterId);

            Map<Long, Row> before = snapshot(created);
            Long newId = registerUser(reg.wx(), inviteCode);
            created.add(newId);
            live.add(newId);

            // 建号步骤只允许新增行（至多 1 行），既有行一列不改。
            Map<Long, Row> after = snapshot(created);
            assertThat(after.keySet()).as("建号不得删除既有邀请关系行").containsAll(before.keySet());
            assertThat(after.size() - before.size()).isBetween(0, 1);
            before.forEach((id, row) -> assertThat(after.get(id))
                    .as("建号不得改动既有邀请关系行 inviteId=%d", id).isEqualTo(row));
            if (inviteCode != null) {
                assertThat(after.size() - before.size()).as("携带有效邀请码应恰好新增 1 行").isEqualTo(1);
            }
            assertStatsSelfConsistent(newId, "新用户建号后");
            if (inviterId != null) {
                assertStatsSelfConsistent(inviterId, "邀请人在其被邀请人建号后");
            }
        }

        // ---------- 注销阶段 ----------
        boolean releasedCodeProbed = false;
        for (Del del : deletions) {
            if (live.isEmpty()) {
                break;
            }
            CLOCK.advanceSeconds(60);
            Long target = live.get(Math.floorMod(del.selector(), live.size()));

            switch (del.precheck()) {
                case BLOCKED_COLLAB -> assertPrecheckFailureHasNoSideEffect(
                        created, target, () -> blockByCollaboration(target, live));
                case SECOND_FACTOR_FAIL -> assertPrecheckFailureHasNoSideEffect(
                        created, target, () -> failSecondFactor(target));
                case PASS -> {
                    boolean probeReleasedCode = !releasedCodeProbed
                            && countInviterRows(target) > 0;
                    releasedCodeProbed |= probeReleasedCode;
                    deleteAndAssertRetention(created, live, target, probeReleasedCode, iteration);
                }
            }
        }

        // ---------- 迭代收尾：全部存活用户的统计恒等式再扫一遍 ----------
        for (Long userId : live) {
            assertStatsSelfConsistent(userId, "迭代收尾");
        }
    }

    // ---------------- 注销与断言 ----------------

    /**
     * 前置校验通过 → 真正注销 → 逐条断言保留不变式（需求 9.5、9.6、10.1、10.2、10.3、10.4、10.7）。
     */
    private void deleteAndAssertRetention(List<Long> created, List<Long> live, Long target,
            boolean probeReleasedCode, long iteration) {

        String releasedCode = codeOf(target);
        Row inviteeRowBefore = findByInvitee(created, target);
        Long inviterOfTarget = inviteeRowBefore == null ? null : inviteeRowBefore.inviterId();
        long[] inviterStatsBefore = inviterOfTarget != null && live.contains(inviterOfTarget)
                ? serviceStats(inviterOfTarget)
                : null;

        Map<Long, Row> before = snapshot(created);
        long tableCountBefore = countAllRelations();
        LocalDateTime now = LocalDateTime.ofInstant(CLOCK.instant(), ZONE);

        deletionService.requireDeletable(target);
        passSecondFactor(target);
        deletionService.deleteAccount(target);
        live.remove(target);

        // 1) 一行都不许少（需求 9.5、9.6、10.1、10.2）：全表行数不变，相关行主键集合不变。
        assertThat(countAllRelations()).as("注销不得删除任何 invite_relations 行").isEqualTo(tableCountBefore);
        Map<Long, Row> after = snapshot(created);
        assertThat(after.keySet()).as("注销前后邀请关系行主键集合相同").isEqualTo(before.keySet());

        // 2) 逐行比对：只有「以注销者为 invitee」的那一行允许变化，且只允许变 status 与 updated_at。
        int changed = 0;
        for (Map.Entry<Long, Row> entry : before.entrySet()) {
            Row b = entry.getValue();
            Row a = after.get(entry.getKey());
            if (b.inviteeId().equals(target)) {
                changed++;
                // 需求 10.2、10.3：置 INVALID、刷新 updated_at，其余五列不变，且不删行。
                assertThat(a.status()).as("被邀请人注销后该行 status").isEqualTo(InviteStatus.INVALID.name());
                assertThat(a.updatedAt()).as("被邀请人注销后 updated_at 刷新为注销时刻").isEqualTo(now);
                assertThat(a.updatedAt()).as("updated_at 单调不减").isAfterOrEqualTo(b.updatedAt());
                assertThat(a.inviteId()).isEqualTo(b.inviteId());
                assertThat(a.inviterId()).isEqualTo(b.inviterId());
                assertThat(a.inviteeId()).isEqualTo(b.inviteeId());
                assertThat(a.registerTime()).isEqualTo(b.registerTime());
                assertThat(a.createdAt()).isEqualTo(b.createdAt());
            } else {
                // 需求 10.1、10.3：以注销者为 inviter_id 的行（以及一切无关行）七列一字不改。
                assertThat(a).as("注销不得改动 inviteId=%d（inviterId=%d）", b.inviteId(), b.inviterId())
                        .isEqualTo(b);
            }
        }
        // 需求 10.2：更新语句影响行数至多为 1（唯一索引 uk_invite_relations_invitee 保证）。
        assertThat(changed).as("以注销者为 invitee 的行至多一行").isLessThanOrEqualTo(1);
        assertThat(changed).isEqualTo(inviteeRowBefore == null ? 0 : 1);

        // 3) 需求 10.4：邀请码随 users 行释放。
        assertThat(userRepository.findById(target)).as("注销后 users 行已删除").isEmpty();
        if (releasedCode != null) {
            assertThat(countUsersWithCode(releasedCode))
                    .as("注销后 users 中该邀请码行数为 0").isZero();
        }

        // 4) 需求 10.7：被邀请人注销后邀请人的已邀请人数减 1、总条数不变，列表仍返回该行。
        if (inviterStatsBefore != null) {
            long[] afterStats = serviceStats(inviterOfTarget);
            assertThat(afterStats[0]).as("被邀请人注销后邀请关系总条数不变").isEqualTo(inviterStatsBefore[0]);
            boolean wasRegistered = InviteStatus.REGISTERED.name().equals(inviteeRowBefore.status());
            assertThat(afterStats[1]).as("被邀请人注销后已邀请人数变化")
                    .isEqualTo(inviterStatsBefore[1] - (wasRegistered ? 1 : 0));

            InviteeItemView item = findItem(inviterOfTarget, inviteeRowBefore.inviteId());
            assertThat(item).as("被邀请人注销后列表中仍应返回该行").isNotNull();
            assertThat(item.status()).isEqualTo(InviteStatus.INVALID.name());
            assertThat(item.nickname()).as("已注销被邀请人的昵称以空值返回").isNull();
            assertThat(item.registerTime()).isEqualTo(inviteeRowBefore.registerTime());
            assertStatsSelfConsistent(inviterOfTarget, "被邀请人注销后");
        }

        if (probeReleasedCode && releasedCode != null) {
            assertReleasedCodeSemantics(releasedCode, target, iteration);
        } else if (releasedCode != null) {
            // 需求 10.9：已释放的码查询邀请人展示信息一律 NOT_FOUND（每次注销都验，代价极低）。
            assertLookupNotFound(releasedCode, iteration);
        }
    }

    /**
     * 需求 10.9、10.10、10.4：已释放邀请码的完整语义——公开查询得 {@code NOT_FOUND}；带该码登录得
     * {@code CODE_NOT_FOUND} 且登录成功；被新用户重新占用时不触发唯一冲突，且已注销者名下的历史行
     * 不出现在新持有者的邀请信息与列表中（归属以 {@code inviter_id} 判定）。
     */
    private void assertReleasedCodeSemantics(String releasedCode, Long deletedId, long iteration) {
        long historyRows = countInviterRows(deletedId);
        assertThat(historyRows).as("探测前提：已注销者名下应有历史邀请行").isPositive();

        // 1) 公开查询：NOT_FOUND。
        assertLookupNotFound(releasedCode, iteration);

        // 2) 带该码登录：CODE_NOT_FOUND，且登录成功（新建用户）。
        CLOCK.advanceSeconds(60);
        long seq = SEQ.incrementAndGet();
        LoginOutcome carried = authService.emailLogin("p12-carry-" + seq + "@example.com", "000000",
                releasedCode);
        assertThat(carried.isNewUser()).isTrue();
        assertThat(carried.inviteBind().bound()).isFalse();
        assertThat(carried.inviteBind().reason()).isEqualTo(UnboundReason.CODE_NOT_FOUND);

        // 3) 重新占用：编排随机源让新用户恰好抽到该已释放的码。
        CLOCK.advanceSeconds(60);
        long seq2 = SEQ.incrementAndGet();
        PLANNED.plan(releasedCode);
        LoginOutcome reoccupied = authService.emailLogin("p12-reuse-" + seq2 + "@example.com",
                "000000", null);
        Long newHolderId = reoccupied.user().getId();
        assertThat(codeOf(newHolderId)).as("已释放的码可被重新占用，不触发唯一冲突").isEqualTo(releasedCode);

        // 4) 历史行不串味：新持有者的邀请信息与列表都为空（需求 10.10）。
        InviteInfoView info = inviteService.getInviteInfo(newHolderId);
        assertThat(info.inviteCode()).isEqualTo(releasedCode);
        assertThat(info.invitedCount()).as("码的新持有者不继承历史已邀请人数").isZero();
        InviteeListView list = inviteService.listInvitees(newHolderId, Integer.valueOf(0),
                Integer.valueOf(50));
        assertThat(list.total()).as("码的新持有者不继承历史邀请关系总条数").isZero();
        assertThat(list.items()).isEmpty();
        // 历史行本身仍在（只是归属于已注销的 inviter_id）。
        assertThat(countInviterRows(deletedId)).isEqualTo(historyRows);
    }

    /** 需求 10.9：以某个不存在于 {@code users.invite_code} 的码查询展示信息得 {@code NOT_FOUND}。 */
    private void assertLookupNotFound(String releasedCode, long iteration) {
        assertThatThrownBy(() -> inviteService.findInviterNickname(releasedCode, "p12-ip-" + iteration))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("NOT_FOUND"));
    }

    /**
     * 需求 10.6：前置校验未通过时对 {@code invite_relations} 零副作用，该用户 {@code users.invite_code}
     * 不变、用户行仍在。
     */
    private void assertPrecheckFailureHasNoSideEffect(List<Long> created, Long target,
            Runnable failingAttempt) {
        Map<Long, Row> before = snapshot(created);
        long tableCountBefore = countAllRelations();
        String codeBefore = codeOf(target);

        failingAttempt.run();

        assertThat(countAllRelations()).as("前置校验失败后全表行数不变").isEqualTo(tableCountBefore);
        assertThat(snapshot(created)).as("前置校验失败后邀请关系全部列取值不变").isEqualTo(before);
        assertThat(codeOf(target)).as("前置校验失败后 users.invite_code 不变").isEqualTo(codeBefore);
        assertThat(userRepository.findById(target)).as("前置校验失败后用户行仍在").isPresent();
        assertStatsSelfConsistent(target, "前置校验失败后");
    }

    /** 制造协作牵连：注销者拥有的协作账本里还有他人成员 → {@code DELETE_BLOCKED_COLLAB}。 */
    private void blockByCollaboration(Long target, List<Long> live) {
        LocalDateTime now = LocalDateTime.ofInstant(CLOCK.instant(), ZONE);
        Ledger ledger = new Ledger();
        ledger.setUserId(target);
        ledger.setName("p12-collab");
        ledger.setType(Ledger.TYPE_COLLABORATIVE);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        Long ledgerId = ledgerRepository.save(ledger).getId();

        Long foreign = live.stream().filter(id -> !id.equals(target)).findFirst().orElse(-1L);
        LedgerMember member = new LedgerMember();
        member.setLedgerId(ledgerId);
        member.setUserId(foreign);
        member.setRole(LedgerMember.ROLE_EDITOR);
        member.setCreatedAt(now);
        Long memberId = memberRepository.save(member).getId();

        assertThatThrownBy(() -> deletionService.requireDeletable(target))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("DELETE_BLOCKED_COLLAB"));

        // 还原：拆掉牵连，使后续注销尝试仍可命中其它分支。
        memberRepository.deleteById(memberId);
        ledgerRepository.deleteById(ledgerId);
    }

    /** 二次验证不通过：邮箱身份用户验证码无效；纯微信用户 openid 不匹配。 */
    private void failSecondFactor(Long target) {
        User user = userRepository.findById(target).orElseThrow();
        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();
        if (hasEmail) {
            EMAIL_CODE_VALID.set(false);
            try {
                assertThatThrownBy(() -> deletionService.verifySecondFactor(target, "000000", null))
                        .isInstanceOf(ApiException.class)
                        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CODE_INVALID"));
            } finally {
                EMAIL_CODE_VALID.set(true);
            }
            return;
        }
        String wrongWxCode = "wrong-" + user.getWxOpenid();
        assertThatThrownBy(() -> deletionService.verifySecondFactor(target, null, wrongWxCode))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("WX_LOGIN_FAILED"));
    }

    /** 二次验证通过：邮箱身份用户走验证码，纯微信用户以本人 openid 重新授权。 */
    private void passSecondFactor(Long target) {
        User user = userRepository.findById(target).orElseThrow();
        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();
        if (hasEmail) {
            EMAIL_CODE_VALID.set(true);
            deletionService.verifySecondFactor(target, "000000", null);
        } else {
            deletionService.verifySecondFactor(target, null, user.getWxOpenid());
        }
    }

    /**
     * 统计恒等式（需求 10.7）：读接口返回的「总条数 − 已邀请人数」恒等于该邀请人名下 {@code INVALID}
     * 行数；总条数含 {@code INVALID} 行；邀请信息与列表两个接口的已邀请人数一致。
     */
    private void assertStatsSelfConsistent(Long userId, String label) {
        long registered = countInviterRowsWithStatus(userId, InviteStatus.REGISTERED);
        long invalid = countInviterRowsWithStatus(userId, InviteStatus.INVALID);
        long[] stats = serviceStats(userId);
        assertThat(stats[0]).as(label + "：总条数含 INVALID 行").isEqualTo(registered + invalid);
        assertThat(stats[1]).as(label + "：已邀请人数只数 REGISTERED").isEqualTo(registered);
        assertThat(stats[0] - stats[1]).as(label + "：总条数 − 已邀请人数 == INVALID 行数")
                .isEqualTo(invalid);
        assertThat(inviteService.getInviteInfo(userId).invitedCount())
                .as(label + "：邀请信息与列表的已邀请人数一致").isEqualTo(stats[1]);
    }

    /** 经真实读接口取 {@code [total, invitedCount]}。 */
    private long[] serviceStats(Long userId) {
        InviteeListView list = inviteService.listInvitees(userId, Integer.valueOf(0),
                Integer.valueOf(50));
        return new long[] { list.total(), list.invitedCount() };
    }

    private InviteeItemView findItem(Long inviterId, Long inviteId) {
        return inviteService.listInvitees(inviterId, Integer.valueOf(0), Integer.valueOf(50))
                .items().stream()
                .filter(i -> i.inviteId().equals(inviteId))
                .findFirst().orElse(null);
    }

    // ---------------- 建号 ----------------

    /** 链式优先：取上一个建号的用户作为邀请人，稳定制造双重身份用户（需求 10.3）。 */
    private static Long chooseInviter(Reg reg, List<Long> live) {
        if (!reg.withInviter() || live.isEmpty()) {
            return null;
        }
        return reg.chainToPrevious()
                ? live.get(live.size() - 1)
                : live.get(Math.floorMod(reg.inviterSelector(), live.size()));
    }

    private Long registerUser(boolean wx, String inviteCode) {
        long seq = SEQ.incrementAndGet();
        LoginOutcome outcome = wx
                ? authService.wxLogin("p12-wx-" + seq, inviteCode)
                : authService.emailLogin("p12-" + seq + "@example.com", "000000", inviteCode);
        assertThat(outcome.isNewUser()).as("建号路径应新建用户").isTrue();
        if (inviteCode != null) {
            assertThat(outcome.inviteBind().bound()).as("携带有效邀请码应绑定成功").isTrue();
        }
        return outcome.user().getId();
    }

    // ---------------- 读库 ----------------

    /** 邀请关系行的七列快照。 */
    record Row(Long inviteId, Long inviterId, Long inviteeId, LocalDateTime registerTime,
            String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 本次迭代相关（{@code inviter_id} 或 {@code invitee_id} 落在给定 id 集合内）的全部行快照。 */
    private Map<Long, Row> snapshot(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String inClause = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Row> rows = jdbcTemplate.query(
                "SELECT invite_id, inviter_id, invitee_id, register_time, status, created_at, updated_at"
                        + " FROM invite_relations WHERE inviter_id IN (" + inClause + ")"
                        + " OR invitee_id IN (" + inClause + ") ORDER BY invite_id",
                (rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getTimestamp(4).toLocalDateTime(), rs.getString(5),
                        rs.getTimestamp(6).toLocalDateTime(), rs.getTimestamp(7).toLocalDateTime()));
        Map<Long, Row> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.inviteId(), row));
        return byId;
    }

    private Row findByInvitee(Collection<Long> ids, Long inviteeId) {
        return snapshot(ids).values().stream()
                .filter(r -> r.inviteeId().equals(inviteeId))
                .findFirst().orElse(null);
    }

    private long countAllRelations() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invite_relations", Long.class);
        return count == null ? 0L : count;
    }

    private long countInviterRows(Long inviterId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations WHERE inviter_id = ?", Long.class, inviterId);
        return count == null ? 0L : count;
    }

    private long countInviterRowsWithStatus(Long inviterId, InviteStatus status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations WHERE inviter_id = ? AND status = ?",
                Long.class, inviterId, status.name());
        return count == null ? 0L : count;
    }

    private long countUsersWithCode(String code) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE invite_code = ?", Long.class, code);
        return count == null ? 0L : count;
    }

    /** 直接读库取该用户的 {@code invite_code}（不经服务层与内存实体）。 */
    private String codeOf(Long userId) {
        List<String> codes = jdbcTemplate.query("SELECT invite_code FROM users WHERE id = ?",
                (rs, i) -> rs.getString(1), userId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    // ---------------- 测试基础设施 ----------------

    /** 可推进的固定时钟：让 {@code created_at} / {@code register_time} / {@code updated_at} 可精确断言。 */
    private static final class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * 可编排的随机源：把 {@link InviteCodeGenerator} 逐字符抽取的下标序列改写成事先安排好的候选码，
     * 用于让新用户精确抽到某个已释放的邀请码（需求 10.4）。编排耗尽后回落到固定种子的伪随机。
     *
     * <p>{@link InviteCodeGenerator} 每生成一个候选码调用 {@code nextInt(32)} 恰好 8 次，因此以
     * 「每 8 次调用」为一个候选码的边界。</p>
     */
    private static final class PlannedRandom extends Random {

        private static final long serialVersionUID = 1L;

        private final Deque<String> planned = new ArrayDeque<>();
        private final Random fallback = new Random(20250612L);
        private String current;
        private int position;

        void reset() {
            planned.clear();
            current = null;
            position = 0;
        }

        void plan(String code) {
            planned.addLast(code);
        }

        @Override
        public int nextInt(int bound) {
            if (bound != InviteCodeGenerator.ALPHABET.length()) {
                return fallback.nextInt(bound);
            }
            if (position == 0) {
                current = planned.pollFirst();
            }
            char c = current != null
                    ? current.charAt(position)
                    : InviteCodeGenerator.ALPHABET.charAt(fallback.nextInt(bound));
            position = (position + 1) % InviteCodeGenerator.LENGTH;
            return InviteCodeGenerator.ALPHABET.indexOf(c);
        }
    }
}
