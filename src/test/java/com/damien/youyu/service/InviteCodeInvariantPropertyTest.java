package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;

import com.damien.youyu.config.TimeConfig;
import com.damien.youyu.domain.EmailCodePurpose;
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
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 邀请码的格式不变式与全局唯一性（Property 1）。
 *
 * <p>对任意由「注册新邮箱用户 / 注册新微信用户 / 存量用户请求邀请信息（惰性补齐）/ 注销用户」
 * 组成的操作序列，在<b>每一步之后</b>，{@code users} 表中每一行的 {@code invite_code} 要么为
 * NULL，要么长度恰为 8 且每个字符取自字母表
 * {@code ABCDEFGHJKLMNPQRSTUVWXYZ23456789}；且全部非空取值两两不相同。</p>
 *
 * <h2>测试层级选择：为什么必须是真实持久化（{@code @DataJpaTest} + H2）</h2>
 * <p>本属性有两个半独立的部分，测试层级由「哪一部分是真正的被测对象」决定：</p>
 * <ul>
 *   <li><b>格式不变式</b>本身是纯逻辑，{@link InviteCodeGenerator} 的单元测试
 *       （{@code InviteCodeGeneratorTest}）已覆盖单次抽取的字母表与长度。</li>
 *   <li><b>全局唯一性</b>不是纯逻辑：它是「生成器的占用判定谓词
 *       （{@code existsByInviteCode}）」+「写入路径」+「{@code uk_users_invite_code} 唯一约束」
 *       三者合起来的性质，而且只有在<b>多次真实注册累积到同一张表</b>上才有意义。用测试替身顶掉
 *       仓储就等于把 {@code occupied} 谓词换成测试自己写的 {@code Set::contains}——断言随即变成
 *       自证，而「建号路径漏掉唯一性判定」「惰性补齐写入时绕过占用判定」「注销释放的码被重新占用后
 *       与历史行串味」这类真实缺陷全都测不出来。</li>
 * </ul>
 * <p>因此这里走 {@code @DataJpaTest} + H2（{@code MODE=MySQL}，表结构由实体生成，
 * {@code users.invite_code} 上的唯一索引来自 {@link User} 的 {@code @Column(unique = true)}），
 * 真实跑 {@link AuthService#emailLogin}/{@link AuthService#wxLogin} 的建号路径、
 * {@link InviteService#requireInviteCode} 的惰性补齐路径与
 * {@link AccountDeletionService#deleteAccount} 的注销路径。只有两处不可避免的测试替身：
 * {@link VerificationCodeService}（否则得先发一封邮件）与 {@link WeChatClient}
 * （否则得外呼微信），二者都与邀请码无关。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文由 Spring 静态上下文缓存复用，
 * 200 次迭代只加载一次）。同理也没有测试事务回滚：<b>这正是本属性需要的</b>——各次迭代写入的用户行
 * 一直留在同一张表里，于是「全局唯一」的断言范围随迭代不断变大（累计数百个非空邀请码），而不是每次
 * 只看孤立的几行。</p>
 *
 * <h2>大小写与 MySQL 排序规则（需求 9.1）</h2>
 * <p>生产库 {@code users.invite_code} 用 {@code utf8mb4_unicode_ci}，仅大小写不同的两个码会被唯一
 * 约束判定为重复；H2 的唯一索引则区分大小写，无法在此复现该行为（真实 MySQL 上的元数据与行为由任务
 * 1.4/1.5 的实测清单覆盖）。本测试改从应用侧把这条差异消灭掉：断言每个落库取值都<b>等于自身的规整
 * 形态</b>（{@code trim + 大写}，见 {@link InviteCodeGenerator#normalize}），并额外断言全部非空取值
 * 在<b>忽略大小写</b>后仍两两不同。既然应用只会写入全大写且无空白的取值，CI 排序规则就不可能引入
 * 任何额外冲突——不管底层排序规则是 CI 还是 CS，唯一性结论都成立。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li>操作序列长度 1–40，元素取自四种操作；用户池上限 3–8 人（达上限后的注册操作跳过，
 *       让序列继续在既有池上做补齐/注销）。</li>
 *   <li>0–3 名<b>存量用户</b>（{@code invite_code} 为 NULL，模拟迁移后的历史行），
 *       是惰性补齐路径唯一的真实入口。</li>
 *   <li><b>受控随机源压缩码空间</b>：每一步可预先「安排」0/1/3/10 个候选码为库中已被占用的取值
 *       （{@link ScriptedRandom}），从而稳定制造碰撞——0 覆盖首发命中，1/3 覆盖重抽后成功，
 *       10 覆盖「10 次全被占用」的失败分支（此时建号事务回滚、惰性补齐保持 NULL，不变式仍须成立）。</li>
 * </ul>
 *
 * <p>Feature: invite-system, Property 1: 邀请码的格式不变式与全局唯一性</p>
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.5, 1.6, 9.1</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuthService.class, AccountDeletionService.class, InviteBindingService.class,
        InviteService.class, InviteRateLimiter.class, TimeConfig.class })
class InviteCodeInvariantPropertyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    /** 同一个 H2 库跨迭代复用：用序号保证 email / openid 全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * 本测试类创建的全部用户 id（跨迭代累积）。全局唯一性的断言范围取这个集合，而不是整张
     * {@code users} 表：同一个内存库里可能有别的测试类留下的行（它们的取值不受本属性约束）。
     * 数据库层面的唯一性仍是真实的——唯一索引作用于整张表，任何冲突都会在写入时立刻抛异常。
     */
    private static final Set<Long> OWNED_IDS = ConcurrentHashMap.newKeySet();

    /** 受控随机源：由 {@link Stubs} 注入被测的 {@link InviteCodeGenerator} 单例。 */
    private static final ScriptedRandom SCRIPTED = new ScriptedRandom();

    @Autowired
    private AuthService authService;
    @Autowired
    private InviteService inviteService;
    @Autowired
    private AccountDeletionService deletionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(InviteCodeInvariantPropertyTest.class).prepareTestInstance(this);
    }

    /**
     * 两处与邀请码无关的外部依赖用测试替身；邀请码生成器则换成注入受控随机源的实例，
     * 以便按需压缩码空间制造碰撞（生产构造器用 {@code SecureRandom}，无法安排碰撞）。
     */
    @TestConfiguration
    static class Stubs {

        @Bean
        InviteCodeGenerator inviteCodeGenerator() {
            return new InviteCodeGenerator(SCRIPTED);
        }

        @Bean
        VerificationCodeService verificationCodeService() {
            VerificationCodeService stub = mock(VerificationCodeService.class);
            when(stub.verifyConsume(anyString(), any(EmailCodePurpose.class), anyString()))
                    .thenReturn(true);
            return stub;
        }

        @Bean
        WeChatClient weChatClient() {
            WeChatClient stub = mock(WeChatClient.class);
            // openid 直接取一次性 code，调用方因此能控制「同一 openid 复登 / 新 openid 建号」。
            when(stub.jscode2session(anyString()))
                    .thenAnswer(inv -> new WxSession(inv.getArgument(0), null));
            return stub;
        }
    }

    // ---------------- 生成器 ----------------

    /** 一步操作：种类 + 目标用户选择子 + 预先安排的碰撞次数。 */
    enum Kind {
        /** 邮箱验证码登录/注册合一的建号路径。 */
        REGISTER_EMAIL,
        /** 微信一键登录的建号路径。 */
        REGISTER_WX,
        /** 已认证用户请求邀请信息：{@code invite_code} 为 NULL 的存量用户在此惰性补齐。 */
        INVITE_INFO,
        /** 注销：删除 {@code users} 行，随行释放该邀请码。 */
        DELETE
    }

    record Step(Kind kind, int selector, int plannedCollisions) {
    }

    /**
     * 操作序列：长度 1–40。碰撞次数按 6:3:2:1 加权——多数步骤首发命中（真实分布），
     * 少数步骤重抽 1/3 次，极少数步骤 10 次全被占用（失败分支）。
     */
    @Provide
    Arbitrary<List<Step>> operationSequences() {
        Arbitrary<Step> step = Combinators.combine(
                        Arbitraries.of(Kind.values()),
                        Arbitraries.integers().between(0, 7),
                        Arbitraries.frequency(
                                Tuple.of(6, 0),
                                Tuple.of(3, 1),
                                Tuple.of(2, 3),
                                Tuple.of(1, InviteCodeGenerator.MAX_ATTEMPTS)))
                .as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(40);
    }

    // ---------------- Property 1 ----------------

    /**
     * Feature: invite-system, Property 1: 邀请码的格式不变式与全局唯一性
     *
     * <p>对任意由注册、惰性补齐、注销组成的操作序列，在每一步之后：</p>
     * <ul>
     *   <li>每一行的 {@code invite_code} 为 NULL，或长度恰为 8 且字符全部取自字母表、全大写、
     *       无首尾空白（需求 1.1）；</li>
     *   <li>建号事务提交后新用户行的 {@code invite_code} 必为长度 8 的非空取值（需求 1.2）；</li>
     *   <li>全部非空取值两两不相同，忽略大小写后依然两两不相同（需求 1.5、9.1）；</li>
     *   <li>{@code uk_users_invite_code} 真实拒绝重复取值：直接插一行同码必失败且不落行（需求 1.5、9.1）；</li>
     *   <li>候选码被占用时重新抽取、最多 10 次、采用首个未被占用的候选；10 次全被占用时建号事务
     *       回滚（不留 {@code invite_code} 为空的新行）、惰性补齐保持 NULL（需求 1.6）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 1.1, 1.2, 1.5, 1.6, 9.1</p>
     */
    @Property(tries = 25)
    void property1_inviteCodeFormatAndGlobalUniqueness(
            @ForAll("operationSequences") List<Step> steps,
            @ForAll @IntRange(min = 3, max = 8) int poolCap,
            @ForAll @IntRange(min = 0, max = 3) int legacyUsers) {

        List<Long> pool = new ArrayList<>();

        // 存量用户：invite_code 为 NULL 的历史行，惰性补齐路径唯一的真实入口（需求 1.3 的前提）。
        for (int i = 0; i < legacyUsers; i++) {
            pool.add(persistLegacyUser());
        }
        assertInvariant(pool, "存量用户播种后");

        for (Step step : steps) {
            switch (step.kind()) {
                case REGISTER_EMAIL -> {
                    if (pool.size() < poolCap) {
                        registerEmailUser(pool, step.plannedCollisions());
                    }
                }
                case REGISTER_WX -> {
                    if (pool.size() < poolCap) {
                        registerWxUser(pool, step.plannedCollisions());
                    }
                }
                case INVITE_INFO -> requestInviteInfo(pool, step);
                case DELETE -> deleteUser(pool, step);
            }
            // 不变式必须在「每一步之后」成立，含失败分支之后。
            assertInvariant(pool, "操作 " + step.kind() + " 之后");
        }

        // 每次迭代都把断言范围放大到本测试类累计创建过的全部用户行：跨数百次注册的全局唯一性。
        assertInvariant(OWNED_IDS, "本次迭代结束（累计范围）");

        // 唯一性不是只靠生成器的占用判定：数据库层面同样必须拒绝重复取值（需求 1.5、9.1）。
        assertDatabaseRejectsDuplicateCode(pool);

        // 额外断言（受控 occupied 谓词，纯逻辑）：返回首个未占用候选，且抽取次数 ≤ 10（需求 1.6）。
        assertGenerateUniqueContract(0);
        assertGenerateUniqueContract(1);
        assertGenerateUniqueContract(InviteCodeGenerator.MAX_ATTEMPTS - 1);
        assertGenerateUniqueContract(InviteCodeGenerator.MAX_ATTEMPTS);
    }

    // ---------------- 操作 ----------------

    /** 邮箱登录/注册合一的建号路径（需求 1.2）。 */
    private void registerEmailUser(List<Long> pool, int plannedCollisions) {
        long seq = SEQ.incrementAndGet();
        String email = "p1-mail-" + seq + "@example.com";
        boolean expectFailure = planCollisions(pool, plannedCollisions);

        try {
            LoginOutcome outcome = authService.emailLogin(email, "000000", null);
            assertThat(expectFailure).as("安排了 10 次碰撞却仍建号成功").isFalse();
            assertThat(outcome.isNewUser()).isTrue();
            assertNewlyCreated(pool, outcome.user().getId());
        } catch (ApiException e) {
            assertGenerationExhausted(e, expectFailure);
            // 需求 1.7：事务回滚，库里不留 invite_code 为空的新用户行。
            assertThat(userRepository.findByEmail(email)).isEmpty();
        }
        assertDrawsWithinLimit();
    }

    /** 微信一键登录的建号路径（需求 1.2）。 */
    private void registerWxUser(List<Long> pool, int plannedCollisions) {
        long seq = SEQ.incrementAndGet();
        String openid = "p1-wx-" + seq;
        boolean expectFailure = planCollisions(pool, plannedCollisions);

        try {
            LoginOutcome outcome = authService.wxLogin(openid, null);
            assertThat(expectFailure).as("安排了 10 次碰撞却仍建号成功").isFalse();
            assertThat(outcome.isNewUser()).isTrue();
            assertNewlyCreated(pool, outcome.user().getId());
        } catch (ApiException e) {
            assertGenerationExhausted(e, expectFailure);
            assertThat(userRepository.findByWxOpenid(openid)).isEmpty();
        }
        assertDrawsWithinLimit();
    }

    /** 请求邀请信息：{@code invite_code} 为空的存量用户在此惰性补齐（需求 1.3、1.8）。 */
    private void requestInviteInfo(List<Long> pool, Step step) {
        Long userId = pick(pool, step.selector());
        if (userId == null) {
            return;
        }
        String before = codeOf(userId);
        // 先无条件安排（含 reset：清掉上一步的编排与抽取计数），再判定本次是否应当失败——
        // 已有邀请码的用户不会走生成路径，因此不可能因编排的碰撞而失败。
        boolean collisionsPlanned = planCollisions(pool, step.plannedCollisions());
        boolean expectFailure = before == null && collisionsPlanned;

        try {
            String code = inviteService.requireInviteCode(userId);
            assertThat(expectFailure).as("安排了 10 次碰撞却仍补齐成功").isFalse();
            assertThat(code).isNotBlank();
            assertWellFormed(code, "惰性补齐返回值");
            // 幂等：已有取值不得被改写（需求 1.4、1.13 的落库侧）。
            if (before != null) {
                assertThat(code).isEqualTo(before);
            }
            assertThat(codeOf(userId)).as("返回值与落库取值一致").isEqualTo(code);
        } catch (ApiException e) {
            assertGenerationExhausted(e, expectFailure);
            // 需求 1.8：补齐失败时 invite_code 保持原有取值（此处为 NULL）。
            assertThat(codeOf(userId)).isNull();
        }
        assertDrawsWithinLimit();
    }

    /** 注销：删除 {@code users} 行，该邀请码随行释放，后续候选码可再次抽到该取值。 */
    private void deleteUser(List<Long> pool, Step step) {
        Long userId = pick(pool, step.selector());
        if (userId == null) {
            return;
        }
        deletionService.deleteAccount(userId);
        pool.remove(userId);
        assertThat(userRepository.findById(userId)).as("注销后用户行应已删除").isEmpty();
    }

    // ---------------- 断言 ----------------

    /**
     * 不变式：给定 id 集合对应的每一行，{@code invite_code} 为 NULL 或格式合法；
     * 全部非空取值两两不相同（忽略大小写后依然两两不相同）。
     */
    private void assertInvariant(Collection<Long> ids, String stepLabel) {
        if (ids.isEmpty()) {
            return;
        }
        String inClause = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, invite_code FROM users WHERE id IN (" + inClause + ")");

        List<String> codes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String code = (String) row.get("INVITE_CODE");
            if (code == null) {
                continue;
            }
            assertWellFormed(code, stepLabel + "：userId=" + row.get("ID"));
            codes.add(code);
        }

        Set<String> distinct = new HashSet<>(codes);
        assertThat(distinct).as(stepLabel + "：非空邀请码两两不相同").hasSize(codes.size());
        // 需求 9.1：生产库排序规则大小写不敏感，仅大小写不同即算重复。落库取值全大写，故忽略大小写后
        // 的取值集合大小必须与原集合相同——CI 排序规则不会引入任何额外冲突。
        Set<String> caseInsensitive = codes.stream()
                .map(c -> c.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        assertThat(caseInsensitive).as(stepLabel + "：忽略大小写后仍两两不相同")
                .hasSize(codes.size());
    }

    /** 格式不变式（需求 1.1）：长度恰为 8、字符全部取自字母表、全大写且无首尾空白。 */
    private static void assertWellFormed(String code, String label) {
        assertThat(code).as(label + "：长度恰为 8").hasSize(InviteCodeGenerator.LENGTH);
        for (int i = 0; i < code.length(); i++) {
            assertThat(InviteCodeGenerator.ALPHABET.indexOf(code.charAt(i)))
                    .as(label + "：第 %d 个字符 '%s' 必须取自字母表", i, code.charAt(i))
                    .isGreaterThanOrEqualTo(0);
        }
        assertThat(code).as(label + "：全大写且无首尾空白")
                .isEqualTo(code.trim().toUpperCase(Locale.ROOT));
    }

    /** 建号事务提交后：新行存在，且 {@code invite_code} 是长度 8 的非空取值（需求 1.2）。 */
    private void assertNewlyCreated(List<Long> pool, Long userId) {
        pool.add(userId);
        OWNED_IDS.add(userId);
        String persisted = codeOf(userId);
        assertThat(persisted).as("建号提交后 invite_code 非空").isNotNull();
        assertWellFormed(persisted, "建号 userId=" + userId);
    }

    /** 生成失败只允许是 {@code INVITE_CODE_GEN_FAILED}，且只允许发生在安排了 10 次碰撞时。 */
    private static void assertGenerationExhausted(ApiException e, boolean expectFailure) {
        assertThat(e.getCode()).isEqualTo("INVITE_CODE_GEN_FAILED");
        assertThat(expectFailure).as("未安排 10 次碰撞却抛 INVITE_CODE_GEN_FAILED").isTrue();
    }

    /** 需求 1.6：一次生成最多抽取 10 个候选码。 */
    private static void assertDrawsWithinLimit() {
        assertThat(SCRIPTED.draws()).as("候选码抽取次数上限")
                .isLessThanOrEqualTo(InviteCodeGenerator.MAX_ATTEMPTS);
    }

    /**
     * 唯一性由数据库兜底（需求 1.5、9.1）：直接插入一行与在用取值相同的 {@code invite_code}
     * 必须被 {@code uk_users_invite_code} 拒绝，且不落任何行。
     */
    private void assertDatabaseRejectsDuplicateCode(List<Long> pool) {
        String occupied = anyOccupiedCode(pool);
        if (occupied == null) {
            return;
        }
        long seq = SEQ.incrementAndGet();
        String email = "p1-dup-" + seq + "@example.com";
        User duplicate = newUser("p1-dup-" + seq, email, occupied);

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .as("重复的 invite_code 必须被唯一约束拒绝")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(userRepository.findByEmail(email)).as("被拒绝的插入不得落行").isEmpty();
    }

    /**
     * 额外断言（需求 1.6，纯逻辑不碰库）：在受控 {@code occupied} 谓词下，
     * {@code generateUnique} 返回首个未被占用的候选码，抽取次数恰为「被占用的前缀长度 + 1」；
     * 10 个候选全被占用时抛 {@code INVITE_CODE_GEN_FAILED}，且抽取次数恰为 10。
     *
     * @param occupiedPrefix 预先安排为「已被占用」的候选码个数
     */
    private static void assertGenerateUniqueContract(int occupiedPrefix) {
        ScriptedRandom random = new ScriptedRandom();
        Set<String> occupied = new LinkedHashSet<>();
        for (int i = 0; i < occupiedPrefix; i++) {
            String taken = encode(900_000_000L + i);
            occupied.add(taken);
            random.plan(taken);
        }
        String free = encode(999_999_999L);
        random.plan(free);

        InviteCodeGenerator generator = new InviteCodeGenerator(random);
        AtomicInteger probes = new AtomicInteger();
        Predicate<String> occupiedPredicate = candidate -> {
            probes.incrementAndGet();
            return occupied.contains(candidate);
        };

        if (occupiedPrefix >= InviteCodeGenerator.MAX_ATTEMPTS) {
            assertThatThrownBy(() -> generator.generateUnique(occupiedPredicate))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode())
                            .isEqualTo("INVITE_CODE_GEN_FAILED"));
            assertThat(probes.get()).as("失败前恰好尝试 10 次")
                    .isEqualTo(InviteCodeGenerator.MAX_ATTEMPTS);
            assertThat(random.draws()).isEqualTo(InviteCodeGenerator.MAX_ATTEMPTS);
            return;
        }

        assertThat(generator.generateUnique(occupiedPredicate))
                .as("返回首个未被占用的候选码").isEqualTo(free);
        assertThat(probes.get()).as("抽取次数 == 被占用前缀长度 + 1")
                .isEqualTo(occupiedPrefix + 1);
        assertThat(random.draws()).isLessThanOrEqualTo(InviteCodeGenerator.MAX_ATTEMPTS);
    }

    // ---------------- 测试基础设施 ----------------

    /**
     * 为下一次生成安排 {@code count} 个「已被占用」的候选码（取自库中在用取值），返回本次是否
     * 应当以 {@code INVITE_CODE_GEN_FAILED} 失败。库中还没有任何在用取值时无从安排碰撞。
     */
    private boolean planCollisions(List<Long> pool, int count) {
        SCRIPTED.reset();
        if (count <= 0) {
            return false;
        }
        String occupied = anyOccupiedCode(pool);
        if (occupied == null) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            SCRIPTED.plan(occupied);
        }
        return count >= InviteCodeGenerator.MAX_ATTEMPTS;
    }

    /** 池中任一在用（非空）邀请码；没有则返回 {@code null}。 */
    private String anyOccupiedCode(List<Long> pool) {
        for (Long id : pool) {
            String code = codeOf(id);
            if (code != null) {
                return code;
            }
        }
        return null;
    }

    private Long pick(List<Long> pool, int selector) {
        return pool.isEmpty() ? null : pool.get(Math.floorMod(selector, pool.size()));
    }

    /** 直接读库取该用户的 {@code invite_code}（不经服务层缓存或内存实体）。 */
    private String codeOf(Long userId) {
        List<String> codes = jdbcTemplate.query(
                "SELECT invite_code FROM users WHERE id = ?",
                (rs, i) -> rs.getString(1), userId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    /** 存量用户：{@code invite_code} 为 NULL 的历史行（迁移脚本不回填，见需求 9.1）。 */
    private Long persistLegacyUser() {
        long seq = SEQ.incrementAndGet();
        User legacy = newUser("p1-legacy-" + seq, "p1-legacy-" + seq + "@example.com", null);
        Long id = userRepository.save(legacy).getId();
        OWNED_IDS.add(id);
        return id;
    }

    private static User newUser(String nickname, String email, String inviteCode) {
        User u = new User();
        u.setEmail(email);
        u.setNickname(nickname);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(BASE);
        u.setPlanExpiresAt(BASE.plusDays(365));
        u.setCreatedAt(BASE);
        u.setUpdatedAt(BASE);
        return u;
    }

    /**
     * 把 n 编码成 8 位邀请码，用于构造确定性的「已被占用」候选：
     * 前两位固定为本类专属前缀 {@code C3}，后 6 位取字母表 32 进制。
     *
     * <p>类专属前缀是<b>必需的</b>而非装饰：全部切片测试共用同一个内存 H2
     * （{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且 Spring 上下文缓存跨测试类复用，
     * {@code create-drop} 因此不会在类之间清库；各兄弟属性测试的序号又都从 0 开始，
     * 若共用同一段码空间，{@code uk_users_invite_code} 会随机爆掉。
     * 同理本类的 email / openid 一律带 {@code p1-} 前缀。</p>
     */
    private static String encode(long n) {
        char[] out = new char[InviteCodeGenerator.LENGTH];
        out[0] = 'C';
        out[1] = '3';
        long v = n;
        for (int i = InviteCodeGenerator.LENGTH - 1; i >= 2; i--) {
            out[i] = InviteCodeGenerator.ALPHABET
                    .charAt((int) (v % InviteCodeGenerator.ALPHABET.length()));
            v /= InviteCodeGenerator.ALPHABET.length();
        }
        return new String(out);
    }

    /**
     * 可编排的随机源：把 {@link InviteCodeGenerator} 逐字符抽取的下标序列改写成事先安排好的候选码，
     * 从而在不改动被测代码的前提下压缩码空间、稳定制造碰撞。安排耗尽后回落到普通伪随机
     * （固定种子，便于复现）。
     *
     * <p>{@link InviteCodeGenerator} 每生成一个候选码调用 {@code nextInt(32)} 恰好 8 次，
     * 因此这里以「每 8 次调用」为一个候选码的边界，同时把候选码个数记为 {@link #draws()}。</p>
     */
    private static final class ScriptedRandom extends Random {

        private static final long serialVersionUID = 1L;

        private final Deque<String> planned = new ArrayDeque<>();
        private final Random fallback = new Random(20250601L);
        private String current;
        private int position;
        private int draws;

        void reset() {
            planned.clear();
            current = null;
            position = 0;
            draws = 0;
        }

        void plan(String code) {
            planned.addLast(code);
        }

        int draws() {
            return draws;
        }

        @Override
        public int nextInt(int bound) {
            if (bound != InviteCodeGenerator.ALPHABET.length()) {
                return fallback.nextInt(bound);
            }
            if (position == 0) {
                current = planned.pollFirst();
                draws++;
            }
            char c = current != null
                    ? current.charAt(position)
                    : InviteCodeGenerator.ALPHABET.charAt(fallback.nextInt(bound));
            position = (position + 1) % InviteCodeGenerator.LENGTH;
            return InviteCodeGenerator.ALPHABET.indexOf(c);
        }
    }
}
