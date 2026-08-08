package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.statistics.Statistics;

/**
 * 绑定结果与未绑定原因的确定性（Property 4）：结果标识恰好 1 个、原因至多 1 个，
 * 且原因恒等于固定优先级链上<b>首个成立</b>的情形；单次请求对 {@code invite_relations}
 * 的插入尝试次数 ≤1（需求 5.12，失败不重试）。
 *
 * <h2>测试层级选择</h2>
 * <p>真实持久化（{@code @DataJpaTest} + H2，{@code MODE=MySQL}，表由实体生成）：优先级链的后两环
 * （{@code SELF_INVITE} 要真实的「码的持有者就是本人」、{@code ALREADY_BOUND} 要真实的
 * {@code uk_invite_relations_invitee} 唯一索引冲突）都是数据库事实，用测试替身会把被测机制删掉。
 * 与 Property 3 / 6 的测试同一套 wiring：{@link AuthService} 手工 new（验证码与微信客户端用
 * Mockito 替身），事务边界由 {@link TransactionTemplate} 提供，
 * {@link InviteBindingService} 是真实 Spring bean（{@code MANDATORY} 传播照常生效）。
 * jqwik 属性方法不经 JUnit Jupiter 引擎，注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 中手工完成（Spring 静态上下文缓存复用，200 次迭代只加载一次）。</p>
 *
 * <h2>插入尝试次数怎么数（需求 5.12）</h2>
 * <p>数的是<b>语句尝试次数</b>而不是落库行数：只看行数无法区分「插了 1 次成功」与「插了 3 次、
 * 前 2 次冲突后重试成功」。做法是用 {@link CountingJdbcTemplate}（{@link JdbcTemplate} 子类）
 * 顶替容器里的 {@code jdbcTemplate} bean，在 {@code update(String, Object...)} 里对
 * {@code INSERT INTO invite_relations ...} 计数，且<b>在委托 super 之前</b>自增——
 * 失败的尝试同样计入，任何重试都会让计数变成 2。计数器在被测调用前一刻归零，
 * 测试自身预置数据的插入因此不混入。</p>
 * <p>这个计数点是完备的：{@link InviteBindingService} 通往 {@code invite_relations} 的唯一写路径
 * 就是这条 {@link JdbcTemplate} 的 INSERT，而「插入不得经过 Hibernate」由
 * {@link InviteSavepointPropertyTest}（Property 6）锁死——两者合起来保证没有绕过计数器的插入。</p>
 *
 * <h2>生成维度（刻意构造同时命中多个情形的组合）</h2>
 * <p>三维笛卡儿积 {@code 入口 × 邀请码输入 × 库状态} 全部生成，重点在多重命中而非逐分支：</p>
 * <ul>
 *   <li><b>入口</b>：{@code EMAIL_LOGIN} / {@code WX_LOGIN}（端到端）与 {@code DIRECT_BIND}
 *       （直接调 {@link InviteBindingService#bindOnRegister}）。后者是唯一能构造
 *       「本次新建用户的 id 上已有邀请关系」的入口——登录路径下新用户 id 由 IDENTITY 现场分配，
 *       调用前无从预置冲突行，故 {@code ALREADY_BOUND} 一维只在 {@code DIRECT_BIND} 下取真。</li>
 *   <li><b>邀请码输入</b>：缺失 / 纯空白 / 任意 Unicode 垃圾串 / 原始长度 &gt;64（含「补空白后仍规整为
 *       合法且存在的码」这个刻意用例）/ 合法但不存在 / 合法且属他人 / 合法且属本人（自邀，
 *       以受控随机源把新用户的码锁定为该取值）。</li>
 *   <li><b>库状态</b>：是否建号（{@code isNewUser}）× 该 invitee 是否已有邀请关系。</li>
 * </ul>
 * <p>典型多重命中：「老用户 + 畸形码」→ {@code NOT_NEW_USER}；「老用户 + 自邀 + 已有关系」→
 * {@code NOT_NEW_USER}；「新用户 + 自邀 + 已有关系」→ {@code SELF_INVITE}；
 * 「新用户 + 畸形码 + 已有关系」→ {@code CODE_NOT_FOUND}。期望值不是照抄判定链的 if-else，
 * 而是先由测试独立算出<b>全部成立的情形集合</b>，再按 {@link UnboundReason} 的声明顺序取首个——
 * 优先级本身因此成为被断言对象。</p>
 *
 * <p>不在本属性范围内：登录活性与令牌签发（Property 3）、保存点范围与冲突行快照（Property 6）、
 * {@code register_time} 与 {@code created_at} 的时刻一致（Property 8）。</p>
 *
 * <p>Feature: invite-system, Property 4: 绑定结果与未绑定原因的确定性</p>
 *
 * <p>Validates: Requirements 5.1, 5.4, 5.11, 5.12, 6.10</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ InviteBindingService.class, InviteCodeGenerator.class })
class InviteBindReasonPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");

    /** 需求 5.11 / 6.10 规定的固定优先顺序；同时锁死枚举声明顺序不被后人调换。 */
    private static final List<UnboundReason> PRIORITY_CHAIN = List.of(
            UnboundReason.NO_CODE,
            UnboundReason.NOT_NEW_USER,
            UnboundReason.CODE_NOT_FOUND,
            UnboundReason.SELF_INVITE,
            UnboundReason.ALREADY_BOUND);

    /** 同一个 H2 库跨迭代复用，用序号保证 email / openid / 邀请码全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private static final String INSERT_RELATION_SQL =
            "INSERT INTO invite_relations "
                    + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    /** 被测入口。 */
    private enum Entry { EMAIL_LOGIN, WX_LOGIN, DIRECT_BIND }

    /** 邀请码输入的七类取值。 */
    private enum CodeKind {
        /** 字段缺失 / NULL（需求 5.1）。 */
        MISSING,
        /** 空串与纯空白（需求 5.1）。 */
        BLANK,
        /** 任意 Unicode 垃圾串：长度 ≠8 或含字母表外字符。 */
        MALFORMED,
        /** 原始长度 &gt;64，但规整后是库中存在的合法码（需求 5.6 的刻意用例）。 */
        OVER_64_VALID,
        /** 原始长度 &gt;64 且内容为垃圾。 */
        OVER_64_JUNK,
        /** 格式合法但 {@code users.invite_code} 中不存在。 */
        VALID_UNKNOWN,
        /** 格式合法且属于另一个用户。 */
        VALID_OTHER,
        /** 格式合法且属于本次的 invitee 本人（自邀）。 */
        VALID_SELF
    }

    @TestConfiguration
    static class CountingJdbcTemplateConfig {
        /**
         * 顶替 {@code JdbcTemplateAutoConfiguration} 的 {@code jdbcTemplate} bean
         * （它是 {@code @ConditionalOnMissingBean(JdbcOperations.class)}），
         * 让 {@link InviteBindingService} 注入到会计数的那一个。
         */
        @Bean
        CountingJdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new CountingJdbcTemplate(dataSource);
        }
    }

    /** 只做一件事：数「打向 {@code invite_relations} 的 INSERT 语句」的尝试次数（含失败的尝试）。 */
    static class CountingJdbcTemplate extends JdbcTemplate {

        private final AtomicInteger relationInsertAttempts = new AtomicInteger();

        CountingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) throws DataAccessException {
            if (isRelationInsert(sql)) {
                // 刻意在委托之前自增：唯一冲突等失败的尝试同样计入，重试会让计数变成 2。
                relationInsertAttempts.incrementAndGet();
            }
            return super.update(sql, args);
        }

        private static boolean isRelationInsert(String sql) {
            return sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim()
                    .startsWith("INSERT INTO INVITE_RELATIONS");
        }

        void resetAttempts() {
            relationInsertAttempts.set(0);
        }

        int attempts() {
            return relationInsertAttempts.get();
        }
    }

    @Autowired
    private InviteBindingService bindingService;
    @Autowired
    private InviteCodeGenerator inviteCodeGenerator;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CountingJdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private VerificationCodeService verificationCodeService;
    private WeChatClient weChatClient;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(InviteBindReasonPropertyTest.class).prepareTestInstance(this);
        // 默认传播行为 REQUIRED：为 bindOnRegister 的 MANDATORY 提供外层物理事务。
        tx = new TransactionTemplate(transactionManager);

        verificationCodeService = mock(VerificationCodeService.class);
        when(verificationCodeService.verifyConsume(anyString(), any(EmailCodePurpose.class), anyString()))
                .thenReturn(true);
        weChatClient = mock(WeChatClient.class);
        when(weChatClient.jscode2session(anyString()))
                .thenAnswer(inv -> new WxSession(inv.getArgument(0), null));
    }

    // ---------------- 生成器 ----------------

    /**
     * 入口刻意向 {@code DIRECT_BIND} 偏斜：优先级链最深的两环（{@code SELF_INVITE} 压住
     * {@code ALREADY_BOUND}）只在该入口可达，均匀抽样会让这条最贵的组合稀少到近乎不覆盖。
     */
    @Provide
    Arbitrary<Entry> entries() {
        return Arbitraries.frequency(
                Tuple.of(1, Entry.EMAIL_LOGIN),
                Tuple.of(1, Entry.WX_LOGIN),
                Tuple.of(2, Entry.DIRECT_BIND));
    }

    /**
     * 邀请码输入向「能走到判定链末端」的取值偏斜（{@code VALID_OTHER} / {@code VALID_SELF}）：
     * 前几环一旦成立就早返回，若不偏斜则后两环几乎抽不到。
     */
    @Provide
    Arbitrary<CodeKind> codeKinds() {
        return Arbitraries.frequency(
                Tuple.of(1, CodeKind.MISSING),
                Tuple.of(1, CodeKind.BLANK),
                Tuple.of(2, CodeKind.MALFORMED),
                Tuple.of(1, CodeKind.OVER_64_VALID),
                Tuple.of(1, CodeKind.OVER_64_JUNK),
                Tuple.of(2, CodeKind.VALID_UNKNOWN),
                Tuple.of(4, CodeKind.VALID_OTHER),
                Tuple.of(3, CodeKind.VALID_SELF));
    }

    /** 「该 invitee 已有邀请关系」向真偏斜：为假的情形已被两条登录入口大量覆盖。 */
    @Provide
    Arbitrary<Boolean> alreadyBoundFlags() {
        return Arbitraries.frequency(
                Tuple.of(3, Boolean.TRUE),
                Tuple.of(1, Boolean.FALSE));
    }

    /** 任意 Unicode 串：含中文、控制字符、emoji 与字母表外的易混字符。 */
    @Provide
    Arbitrary<String> junkStrings() {
        return Arbitraries.oneOf(
                Arbitraries.strings().all().ofMinLength(0).ofMaxLength(120),
                Arbitraries.strings().withChars("IO01").ofMinLength(1).ofMaxLength(12),
                Arbitraries.strings().withChars("邀请码").ofMinLength(1).ofMaxLength(10),
                Arbitraries.strings().withChars("😀🎉").ofMinLength(1).ofMaxLength(8),
                Arbitraries.strings().withCharRange('\u0000', '\u001f').ofMinLength(1).ofMaxLength(8));
    }

    // ---------------- Property 4 ----------------

    /**
     * Feature: invite-system, Property 4: 绑定结果与未绑定原因的确定性
     *
     * <p>对任意三元组（是否建号 × 邀请码输入 × 库中已有数据状态，含同时命中多个情形的组合）：</p>
     * <ul>
     *   <li>结果恰好含 1 个绑定标识与至多 1 个原因：{@code bound XOR (reason != null)}（需求 5.4）；</li>
     *   <li>未绑定原因取自 {@code NO_CODE} / {@code NOT_NEW_USER} / {@code CODE_NOT_FOUND} /
     *       {@code SELF_INVITE} / {@code ALREADY_BOUND} 五个取值之一（需求 5.4）；</li>
     *   <li>原因等于固定优先级链上首个成立的情形——期望值由测试独立算出的「全部成立情形集合」
     *       取首个得到（需求 5.11、6.10）；</li>
     *   <li>同一输入类型在两套彼此独立的等价夹具上得到同一原因（确定性，与 id / 执行序无关）；</li>
     *   <li>对 {@code invite_relations} 的插入尝试次数 ≤1，且失败不重试：只有「应当绑定」与
     *       「唯一冲突（{@code ALREADY_BOUND}）」两种情形各尝试 1 次，其余情形一次不试（需求 5.12）；</li>
     *   <li>落库行数变化与结果一致：绑定 +1，未绑定 0（需求 5.1 规定的「未携带邀请码」等情形零副作用）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 5.1, 5.4, 5.11, 5.12, 6.10</p>
     */
    @Property(tries = 25)
    void property4_bindResultAndUnboundReasonAreDeterministic(
            @ForAll("entries") Entry entry,
            @ForAll("codeKinds") CodeKind codeKind,
            @ForAll boolean isNewUser,
            @ForAll("alreadyBoundFlags") boolean inviteeAlreadyBound,
            @ForAll("junkStrings") String junk,
            @ForAll @IntRange(min = 0, max = 4) int mangle,
            @ForAll @IntRange(min = 0, max = 3) int blankKind) {

        // 优先级链就是枚举声明顺序：先把这条实现约定钉住，后面的期望值推演才有意义。
        assertThat(List.of(UnboundReason.values())).isEqualTo(PRIORITY_CHAIN);

        // 登录路径下新用户 id 由 IDENTITY 现场分配，调用前无从预置冲突行，
        // 故「invitee 已有关系」这一维只在 DIRECT_BIND 入口取真。
        boolean preexistingRelation = inviteeAlreadyBound && entry == Entry.DIRECT_BIND;

        Observed first = runOnce(entry, codeKind, isNewUser, preexistingRelation, junk, mangle, blankKind);
        Observed second = runOnce(entry, codeKind, isNewUser, preexistingRelation, junk, mangle, blankKind);

        for (Observed observed : List.of(first, second)) {
            InviteBindResult result = observed.result();
            String where = "入口 %s / 邀请码 %s / 建号 %s / 已有关系 %s".formatted(
                    entry, codeKind, isNewUser, preexistingRelation);

            // 1) 恰好 1 个绑定标识与至多 1 个原因（需求 5.4）。
            assertThat(result.bound() ^ (result.reason() != null)).as(where).isTrue();
            if (result.reason() != null) {
                assertThat(PRIORITY_CHAIN).as(where).contains(result.reason());
            }

            // 2) 原因 == 全部成立情形中优先级最高者（需求 5.11、6.10）。
            assertThat(result.reason())
                    .as("%s：成立情形 %s", where, observed.satisfied())
                    .isEqualTo(observed.expected());

            // 3) 插入尝试次数 ≤1，失败不重试（需求 5.12）。
            int expectedAttempts =
                    observed.expected() == null || observed.expected() == UnboundReason.ALREADY_BOUND ? 1 : 0;
            assertThat(observed.insertAttempts())
                    .as("%s：invite_relations 插入尝试次数", where)
                    .isLessThanOrEqualTo(1)
                    .isEqualTo(expectedAttempts);

            // 4) 落库行数变化与结果一致（未绑定一律零副作用）。
            assertThat(observed.relationDelta())
                    .as("%s：invite_relations 行数变化", where)
                    .isEqualTo(observed.expected() == null ? 1L : 0L);
        }

        // 5) 确定性：两套彼此独立的等价夹具得到同一原因。
        assertThat(second.result()).isEqualTo(first.result());
        // 覆盖可见性：报告各原因与「同时命中几个情形」的分布，防止属性在窄输入上空转。
        Statistics.label("同时命中情形数 / 最终原因")
                .collect(first.satisfied().size(), first.expected());
    }

    // ---------------- 单次执行 ----------------

    private record Observed(InviteBindResult result, UnboundReason expected,
                            Set<UnboundReason> satisfied, int insertAttempts, long relationDelta) {
    }

    /**
     * 在一套全新夹具上执行一次绑定，并独立推演本次应得的原因。
     *
     * <p>每次调用都用新的序号造用户与邀请码，因此同一输入类型可以被执行两次而互不干扰——
     * 这正是「确定性」断言需要的等价夹具。</p>
     */
    private Observed runOnce(Entry entry, CodeKind codeKind, boolean isNewUser,
            boolean preexistingRelation, String junk, int mangle, int blankKind) {

        long seq = SEQ.incrementAndGet();
        Clock clock = Clock.fixed(T0.plusSeconds(seq), ZONE);
        LocalDateTime now = LocalDateTime.ofInstant(T0.plusSeconds(seq), ZONE);

        String otherCode = codeOf(seq * 100);
        String unknownCode = codeOf(seq * 100 + 1);
        String selfCode = codeOf(seq * 100 + 2);
        String ghostCode = codeOf(seq * 100 + 3);
        String email = "p4-login-" + seq + "@example.com";
        String openid = "wx-p4-" + seq;

        // 「码属他人」的持有者：更早的已提交事务。
        tx.execute(s -> persistUser(seq, "other", otherCode, now, null, null).getId());

        // 登录路径下的「老用户」夹具：其自身邀请码即 selfCode，使「老用户 + 自邀」成为多重命中用例。
        if (entry != Entry.DIRECT_BIND && !isNewUser) {
            tx.execute(s -> persistUser(seq, "existing", selfCode, now,
                    entry == Entry.EMAIL_LOGIN ? email : null,
                    entry == Entry.WX_LOGIN ? openid : null).getId());
        }

        String raw = rawInput(codeKind, otherCode, unknownCode, selfCode, junk, mangle, blankKind);
        String normalized = inviteCodeGenerator.normalize(raw);
        // 「码是否查得到」以调用前的库状态判定：垃圾串也可能凑巧命中某个已存在的码。
        Long holderIdBefore = holderIdOf(normalized);

        Result run = entry == Entry.DIRECT_BIND
                ? runDirect(seq, now, selfCode, ghostCode, isNewUser, preexistingRelation, raw)
                : runLogin(entry, clock, email, openid, selfCode, codeKind, isNewUser, raw);

        // 自邀且本次建号时，码的持有者是调用中才出现的新用户本人。
        Long holderId = holderIdBefore != null || !normalized.equals(selfCode)
                ? holderIdBefore
                : run.inviteeId();

        Set<UnboundReason> satisfied = satisfiedReasons(raw, normalized, isNewUser,
                holderId, run.inviteeId(), preexistingRelation);
        UnboundReason expected = PRIORITY_CHAIN.stream().filter(satisfied::contains).findFirst().orElse(null);

        return new Observed(run.result(), expected, satisfied, run.insertAttempts(),
                countRelations() - run.relationCountBefore());
    }

    private record Result(InviteBindResult result, Long inviteeId, int insertAttempts,
                          long relationCountBefore) {
    }

    /** 端到端入口：{@link AuthService#emailLogin} / {@link AuthService#wxLogin}。 */
    private Result runLogin(Entry entry, Clock clock, String email, String openid,
            String selfCode, CodeKind codeKind, boolean isNewUser, String raw) {

        // 自邀且本次建号：以受控随机源把新用户将拿到的码锁定为 selfCode。
        InviteCodeGenerator generator = codeKind == CodeKind.VALID_SELF && isNewUser
                ? new InviteCodeGenerator(fixedCodeRandom(selfCode))
                : new InviteCodeGenerator();
        AuthService authService = new AuthService(userRepository, clock, weChatClient,
                verificationCodeService, generator, bindingService);

        long countBefore = countRelations();
        jdbcTemplate.resetAttempts();
        LoginOutcome outcome = tx.execute(s -> entry == Entry.EMAIL_LOGIN
                ? authService.emailLogin(email, "123456", raw)
                : authService.wxLogin(openid, raw));
        int attempts = jdbcTemplate.attempts();

        assertThat(outcome).isNotNull();
        assertThat(outcome.isNewUser()).as("本次是否建号应与夹具一致").isEqualTo(isNewUser);
        return new Result(outcome.inviteBind(), outcome.user().getId(), attempts, countBefore);
    }

    /**
     * 直接入口：在同一物理事务内造出「本次新建用户」并按需预置冲突行，再调
     * {@link InviteBindingService#bindOnRegister}。这是唯一能触达 {@code ALREADY_BOUND} 的入口。
     */
    private Result runDirect(long seq, LocalDateTime now, String selfCode, String ghostCode,
            boolean isNewUser, boolean preexistingRelation, String raw) {

        return tx.execute(s -> {
            // 本次「新建」的用户：IDENTITY 主键，save 即刻发出 INSERT，id 可用；其自身码即 selfCode。
            User invitee = persistUser(seq, "invitee", selfCode, now, null, null);
            if (preexistingRelation) {
                Long ghostInviterId = persistUser(seq, "ghost", ghostCode, now, null, null).getId();
                jdbcTemplate.update(INSERT_RELATION_SQL, ghostInviterId, invitee.getId(), now,
                        InviteStatus.REGISTERED.name(), now, now);
            }
            long countBefore = countRelations();
            jdbcTemplate.resetAttempts();
            InviteBindResult result = bindingService.bindOnRegister(invitee, isNewUser, raw, now);
            return new Result(result, invitee.getId(), jdbcTemplate.attempts(), countBefore);
        });
    }

    // ---------------- 期望值推演 ----------------

    /**
     * 独立算出本次<b>全部成立</b>的未绑定情形（刻意不复用判定链的早返回结构）：
     * 每一条都按需求原文单独判定，优先级由调用方在 {@link #PRIORITY_CHAIN} 上取首个得到。
     */
    private Set<UnboundReason> satisfiedReasons(String raw, String normalized, boolean isNewUser,
            Long codeHolderId, Long inviteeId, boolean preexistingRelation) {

        Set<UnboundReason> satisfied = EnumSet.noneOf(UnboundReason.class);
        // 需求 5.1：字段缺失 / NULL / 去空白后长度为 0。
        if (normalized.isEmpty()) {
            satisfied.add(UnboundReason.NO_CODE);
        }
        // 需求 5.3：本次未在 users 表新插入行。
        if (!isNewUser) {
            satisfied.add(UnboundReason.NOT_NEW_USER);
        }
        // 需求 5.5、5.6、9.19：原始长度 >64 / 格式非法 / 该码无人持有。
        boolean overLong = raw != null && raw.length() > InviteBindingService.MAX_RAW_CODE_LENGTH;
        if (overLong || !inviteCodeGenerator.isWellFormed(normalized) || codeHolderId == null) {
            satisfied.add(UnboundReason.CODE_NOT_FOUND);
        }
        // 需求 6.2：持有者就是本次的 invitee 本人。
        if (codeHolderId != null && codeHolderId.equals(inviteeId)) {
            satisfied.add(UnboundReason.SELF_INVITE);
        }
        // 需求 6.3：该 invitee 已有邀请关系（插入必然撞唯一约束）。
        if (preexistingRelation) {
            satisfied.add(UnboundReason.ALREADY_BOUND);
        }
        return satisfied;
    }

    // ---------------- 测试基础设施 ----------------

    /** 组装本次请求携带的邀请码原始取值。 */
    private static String rawInput(CodeKind kind, String otherCode, String unknownCode,
            String selfCode, String junk, int mangle, int blankKind) {
        return switch (kind) {
            case MISSING -> null;
            case BLANK -> switch (blankKind) {
                case 1 -> " ";
                case 2 -> "   \t  ";
                case 3 -> "\n\t \r";
                default -> "";
            };
            case MALFORMED -> junk;
            // 规整后仍是库中存在的合法码，但原始长度 >64：必须按 CODE_NOT_FOUND 处理（需求 5.6）。
            case OVER_64_VALID -> " ".repeat(60) + otherCode + " ".repeat(60);
            case OVER_64_JUNK -> repeatUntilLongerThan64(junk);
            case VALID_UNKNOWN -> mangleCode(unknownCode, mangle);
            case VALID_OTHER -> mangleCode(otherCode, mangle);
            case VALID_SELF -> mangleCode(selfCode, mangle);
        };
    }

    /** 把任意串堆到 65 个字符以上，用于覆盖「原始取值长度 &gt;64」这条判定（需求 5.6）。 */
    private static String repeatUntilLongerThan64(String junk) {
        String base = junk.isBlank() ? "X7" : junk;
        StringBuilder sb = new StringBuilder();
        while (sb.length() <= InviteBindingService.MAX_RAW_CODE_LENGTH) {
            sb.append(base);
        }
        return sb.toString();
    }

    /**
     * 把 n 编码成 8 位邀请码（后 6 位取字母表 32 进制），保证跨迭代不重复。
     *
     * <p>前两位固定为 {@code TZ} 是<b>必需的</b>而非装饰：全部切片测试共用同一个内存 H2
     * （{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且上下文缓存跨测试类复用，
     * 兄弟属性测试各自从 0 开始编号，若同用 {@code AA......} 这段码空间，
     * {@code users.invite_code} 的唯一约束会在跨类复用同一上下文时随机爆掉。
     * 同理 {@link #persistUser} 的邮箱一律带 {@code p4-} 前缀。</p>
     */
    private static String codeOf(long n) {
        char[] out = new char[InviteCodeGenerator.LENGTH];
        out[0] = 'T';
        out[1] = 'Z';
        long v = n;
        for (int i = InviteCodeGenerator.LENGTH - 1; i >= 2; i--) {
            out[i] = InviteCodeGenerator.ALPHABET.charAt((int) (v % InviteCodeGenerator.ALPHABET.length()));
            v /= InviteCodeGenerator.ALPHABET.length();
        }
        return new String(out);
    }

    /** 请求携带的原始取值变形：规整（trim + 大写）后应命中同一个码。 */
    private static String mangleCode(String code, int mangle) {
        return switch (mangle) {
            case 1 -> code.toLowerCase(Locale.ROOT);
            case 2 -> code.substring(0, 4).toLowerCase(Locale.ROOT) + code.substring(4);
            case 3 -> "  " + code + " ";
            case 4 -> "\t" + code + " \n";
            default -> code;
        };
    }

    /** 受控随机源：逐字符吐出目标码的字母表下标，使新用户必然拿到该码（自邀场景）。 */
    private static Random fixedCodeRandom(String targetCode) {
        return new Random() {
            private int i = 0;

            @Override
            public int nextInt(int bound) {
                char c = targetCode.charAt(i % InviteCodeGenerator.LENGTH);
                i++;
                return InviteCodeGenerator.ALPHABET.indexOf(c);
            }
        };
    }

    private User persistUser(long seq, String tag, String inviteCode, LocalDateTime now,
            String email, String openid) {
        User u = new User();
        u.setEmail(email != null ? email : "p4-" + tag + "-" + seq + "@example.com");
        u.setWxOpenid(openid);
        u.setNickname("p4-" + tag + "-" + seq);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    /** 调用前的库状态：该规整取值由哪个现存用户持有（无人持有返回 {@code null}）。 */
    private Long holderIdOf(String normalized) {
        if (!inviteCodeGenerator.isWellFormed(normalized)) {
            return null;
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE invite_code = ?", Long.class, normalized);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long countRelations() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invite_relations", Long.class);
        return count == null ? 0L : count;
    }
}
