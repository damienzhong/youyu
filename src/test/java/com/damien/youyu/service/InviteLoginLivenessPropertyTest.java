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
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.JwtService;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 登录/注册的活性（Property 3）：邀请码不管多离谱，登录主路径一律走通。
 *
 * <h2>测试层级选择</h2>
 * <p>用真实持久化（{@code @DataJpaTest} + H2，{@code MODE=MySQL}，表由实体生成）而非测试替身：
 * 本属性断言的是「事务提交后新建的 {@code users} 行存在且 {@code invite_code} 非空」，
 * 而绑定路径里那条 JDBC 插入与 {@code uk_invite_relations_invitee} 唯一索引都是载荷本身——
 * 把 {@link InviteBindingService} 换成 mock 就等于把被测机制删掉，属性会永真而毫无价值。</p>
 *
 * <p>{@link AuthService} 刻意<b>手工 new</b> 而不进 Spring 容器：它依赖
 * {@link VerificationCodeService}（要邮件与验证码表）与 {@link WeChatClient}（要外呼微信），
 * 这两者与本属性无关，用 Mockito 替身注入。事务边界由 {@link TransactionTemplate} 提供，
 * 与生产中 {@code @Transactional} 的物理事务等价，也满足
 * {@link InviteBindingService#bindOnRegister} 的 {@code MANDATORY} 传播要求
 * （该服务是真实 Spring bean，代理照常生效）。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，
 * 依赖注入改由 {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文由 Spring 的
 * 静态上下文缓存复用，200 次迭代只加载一次）。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li><b>邀请码输入空间</b>：缺失（null）／纯空白／合法码的大小写与带空白变形／合法但库中不存在／
 *       指向已删除用户（需求 9.19 的应用层存在性校验）／自邀（以受控随机源锁定新用户将拿到的码）／
 *       任意 Unicode 串（0–200 字符，含中文、控制字符、emoji、字母表外的 {@code I}/{@code O}/{@code 0}/{@code 1}）／
 *       原始长度 &gt;64。</li>
 *   <li><b>账号形态</b>：新邮箱、新 openid、已存在邮箱、已存在 openid。</li>
 * </ul>
 *
 * <p>不在本属性范围内：唯一冲突经保存点消化（Property 6）、未绑定原因的完整优先级判定
 * （Property 4）、「唯一冲突以外的数据库故障回滚整个事务」（需求 5.7，反向属性）。</p>
 *
 * <p>Feature: invite-system, Property 3: 登录/注册的活性（邀请码问题不阻断主路径）</p>
 *
 * <p>Validates: Requirements 5.3, 5.5, 5.6, 6.2, 9.19</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ InviteBindingService.class, InviteCodeGenerator.class, JwtService.class })
class InviteLoginLivenessPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");

    /** 同一个 H2 库跨迭代复用，用序号保证 email / openid / 邀请码全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /** 邀请码输入的九类取值，覆盖 Property 3 声明的整个输入空间。 */
    private enum CodeScenario {
        /** 字段缺失 / NULL。 */
        NULL,
        /** 空串与纯空白（空格、制表、换行）。 */
        BLANK,
        /** 库中存在的合法码，附带大小写与首尾空白变形。 */
        EXISTING,
        /** 格式合法但 {@code users.invite_code} 中不存在。 */
        NONEXISTENT,
        /** 持有者已被删除：码在库中查不到（需求 9.19 的应用层存在性校验）。 */
        DELETED_HOLDER,
        /** 自邀：以受控随机源把新用户的码锁定为该取值。 */
        SELF,
        /** 任意 Unicode 串（含中文、控制字符、emoji、字母表外字符）。 */
        JUNK,
        /** 原始取值长度 &gt;64（规整后仍是合法码的刻意用例）。 */
        OVER_64_PADDED,
        /** 原始取值长度 &gt;64 且内容为垃圾。 */
        OVER_64_JUNK
    }

    private enum AccountForm { NEW_EMAIL, NEW_OPENID, EXISTING_EMAIL, EXISTING_OPENID }

    @Autowired
    private InviteBindingService bindingService;
    @Autowired
    private InviteCodeGenerator inviteCodeGenerator;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private VerificationCodeService verificationCodeService;
    private WeChatClient weChatClient;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(InviteLoginLivenessPropertyTest.class).prepareTestInstance(this);
        // 默认传播行为 REQUIRED：为 bindOnRegister 的 MANDATORY 提供外层物理事务。
        tx = new TransactionTemplate(transactionManager);

        // 验证码校验恒真：本属性不关心验证码，只关心邀请码不阻断主路径。
        verificationCodeService = mock(VerificationCodeService.class);
        when(verificationCodeService.verifyConsume(anyString(), any(EmailCodePurpose.class), anyString()))
                .thenReturn(true);
        // 把一次性 code 直接当作 openid 返回，由测试控制新建/已存在的微信身份。
        weChatClient = mock(WeChatClient.class);
        when(weChatClient.jscode2session(anyString()))
                .thenAnswer(inv -> new WxSession(inv.getArgument(0), null));
    }

    // ---------------- 生成器 ----------------

    @Provide
    Arbitrary<CodeScenario> codeScenarios() {
        return Arbitraries.of(CodeScenario.values());
    }

    @Provide
    Arbitrary<AccountForm> accountForms() {
        return Arbitraries.of(AccountForm.values());
    }

    /** 任意 Unicode 串：含中文、控制字符、emoji 与字母表外的易混字符。 */
    @Provide
    Arbitrary<String> junkStrings() {
        return Arbitraries.oneOf(
                Arbitraries.strings().all().ofMinLength(0).ofMaxLength(200),
                Arbitraries.strings().withChars("IO01").ofMinLength(1).ofMaxLength(12),
                Arbitraries.strings().withChars("邀请码测试用例").ofMinLength(1).ofMaxLength(10),
                Arbitraries.strings().withChars("😀🎉🙈").ofMinLength(1).ofMaxLength(8),
                Arbitraries.strings().withCharRange('\u0000', '\u001f').ofMinLength(1).ofMaxLength(8));
    }

    // ---------------- Property 3 ----------------

    /**
     * Feature: invite-system, Property 3: 登录/注册的活性（邀请码问题不阻断主路径）
     *
     * <p>对任意邀请码输入取值（缺失、null、纯空白、长度 ≠8、含字母表外字符、原始长度 &gt;64、
     * 合法但库中不存在、自邀、指向已删除用户）与任意账号形态（新邮箱 / 新 openid / 已存在邮箱 /
     * 已存在 openid）：</p>
     * <ul>
     *   <li>登录/注册不抛异常、事务照常提交，且可签发出非空且可解析回本人 id 的令牌；</li>
     *   <li>本次新建的 {@code users} 行在提交后存在，其 {@code invite_code} 非空、长度 8、字符全部取自字母表；</li>
     *   <li>未绑定时 {@code invite_relations} 行数不变（需求 5.5、5.6、6.2），已绑定时恰好 +1；</li>
     *   <li>码在 {@code users.invite_code} 中查不到（含持有者已被删除）时原因为
     *       {@code CODE_NOT_FOUND}，登录仍成功——这就是替代外键的应用层 {@code inviter_id}
     *       存在性校验（需求 9.19）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 5.3, 5.5, 5.6, 6.2, 9.19</p>
     */
    @Property(tries = 25)
    void property3_inviteCodeNeverBlocksLogin(
            @ForAll("codeScenarios") CodeScenario scenario,
            @ForAll("accountForms") AccountForm form,
            @ForAll("junkStrings") String junk,
            @ForAll @IntRange(min = 0, max = 4) int mangle,
            @ForAll @IntRange(min = 0, max = 3) int blankKind) {

        long seq = SEQ.incrementAndGet();
        Clock clock = Clock.fixed(T0.plusSeconds(seq), ZONE);
        LocalDateTime now = LocalDateTime.ofInstant(T0.plusSeconds(seq), ZONE);

        String existingInviterCode = codeOf(seq * 100);
        String nonexistentCode = codeOf(seq * 100 + 1);
        String deletedHolderCode = codeOf(seq * 100 + 2);
        String selfCode = codeOf(seq * 100 + 3);

        // 预置：一个正常邀请人；一个「持有者随即被删除」的码（需求 9.19 的悬空 id 场景）。
        tx.execute(s -> persistUser(seq, "inviter", existingInviterCode, now).getId());
        Long deletedHolderId = tx.execute(s -> persistUser(seq, "ghost", deletedHolderCode, now).getId());
        tx.execute(s -> {
            userRepository.deleteById(deletedHolderId);
            return null;
        });
        assertThat(userRepository.findById(deletedHolderId)).isEmpty();

        // 已存在账号形态的预置身份。
        String email = "p3-login-" + seq + "@example.com";
        String openid = "wx-p3-login-" + seq;
        boolean expectNewUser = form == AccountForm.NEW_EMAIL || form == AccountForm.NEW_OPENID;
        if (!expectNewUser) {
            tx.execute(s -> {
                User u = persistUser(seq, "existing", codeOf(seq * 100 + 4), now);
                if (form == AccountForm.EXISTING_EMAIL) {
                    u.setEmail(email);
                } else {
                    u.setWxOpenid(openid);
                }
                return userRepository.save(u).getId();
            });
        }

        String raw = rawInput(scenario, existingInviterCode, nonexistentCode, deletedHolderCode,
                selfCode, junk, mangle, blankKind);

        // 自邀场景以受控随机源把新用户将拿到的码锁定为 selfCode；其余场景走真实随机源。
        AuthService authService = new AuthService(userRepository, clock, weChatClient,
                verificationCodeService,
                scenario == CodeScenario.SELF
                        ? new InviteCodeGenerator(fixedCodeRandom(selfCode))
                        : new InviteCodeGenerator(),
                bindingService);

        long relationsBefore = countRelations();
        // 期望原因中「码是否查得到」一项以调用前的库状态判定：随机串也可能凑巧命中某个已存在的码。
        boolean holderExistedBefore = codeHeldByExistingUser(inviteCodeGenerator.normalize(raw));

        LoginOutcome outcome = tx.execute(s -> switch (form) {
            case NEW_EMAIL, EXISTING_EMAIL -> authService.emailLogin(email, "123456", raw);
            case NEW_OPENID, EXISTING_OPENID -> authService.wxLogin(openid, raw);
        });

        // 1) 活性：事务提交（tx.execute 正常返回即已提交），拿到用户，可签发非空令牌。
        assertThat(outcome).isNotNull();
        assertThat(outcome.isNewUser()).isEqualTo(expectNewUser);
        User user = outcome.user();
        assertThat(user.getId()).isNotNull();
        String token = jwtService.generateToken(user);
        assertThat(token).isNotBlank();
        assertThat(jwtService.extractUserId(token)).isEqualTo(user.getId());

        // 2) 提交后 users 行存在，且 invite_code 满足格式不变式（需求 1.1/1.2 的必要前提）。
        String storedCode = jdbcTemplate.queryForObject(
                "SELECT invite_code FROM users WHERE id = ?", String.class, user.getId());
        assertThat(storedCode).isNotNull().hasSize(InviteCodeGenerator.LENGTH);
        assertThat(inviteCodeGenerator.isWellFormed(storedCode)).isTrue();

        // 3) 绑定结果：恰好一个标识与至多一个原因；原因取自五取值集合。
        InviteBindResult bind = outcome.inviteBind();
        assertThat(bind.bound() ^ (bind.reason() != null)).isTrue();

        UnboundReason expectedReason = expectedReason(scenario, raw, expectNewUser, holderExistedBefore);
        assertThat(bind.reason())
                .as("场景 %s / 账号形态 %s 的未绑定原因", scenario, form)
                .isEqualTo(expectedReason);

        // 4) 未绑定不写 invite_relations（需求 5.5、5.6、6.2）；绑定成功恰好 +1。
        assertThat(countRelations() - relationsBefore).isEqualTo(expectedReason == null ? 1L : 0L);

        // 5) 需求 9.19：码查不到（含持有者已删除）→ CODE_NOT_FOUND，且登录照常完成。
        if (expectNewUser && !holderExistedBefore
                && (scenario == CodeScenario.DELETED_HOLDER || scenario == CodeScenario.NONEXISTENT)) {
            assertThat(bind.reason()).isEqualTo(UnboundReason.CODE_NOT_FOUND);
        }
        // 需求 6.2：自邀保留新建的 users 行，且不写关系行（已由第 2、4 条断言覆盖）。
        if (expectNewUser && scenario == CodeScenario.SELF) {
            assertThat(bind.reason()).isEqualTo(UnboundReason.SELF_INVITE);
            assertThat(storedCode).isEqualTo(selfCode);
        }
    }

    // ---------------- 期望值推演 ----------------

    /**
     * 按判定链的固定优先级推演本次应得的未绑定原因（{@code null} 表示应当绑定成功）。
     * 与被测实现同一套规则，但刻意由测试独立表达，而不是调用生产代码来算期望值。
     */
    private UnboundReason expectedReason(CodeScenario scenario, String raw,
            boolean expectNewUser, boolean holderExistedBefore) {
        String normalized = inviteCodeGenerator.normalize(raw);
        if (normalized.isEmpty()) {
            return UnboundReason.NO_CODE;
        }
        if (!expectNewUser) {
            return UnboundReason.NOT_NEW_USER;
        }
        if (raw.length() > InviteBindingService.MAX_RAW_CODE_LENGTH
                || !inviteCodeGenerator.isWellFormed(normalized)) {
            return UnboundReason.CODE_NOT_FOUND;
        }
        if (scenario == CodeScenario.SELF) {
            return UnboundReason.SELF_INVITE;
        }
        return holderExistedBefore ? null : UnboundReason.CODE_NOT_FOUND;
    }

    // ---------------- 测试基础设施 ----------------

    /** 组装本次请求携带的邀请码原始取值。 */
    private static String rawInput(CodeScenario scenario, String existingCode, String nonexistentCode,
            String deletedHolderCode, String selfCode, String junk, int mangle, int blankKind) {
        return switch (scenario) {
            case NULL -> null;
            case BLANK -> switch (blankKind) {
                case 1 -> " ";
                case 2 -> "   \t  ";
                case 3 -> "\n\t \r";
                default -> "";
            };
            case EXISTING -> mangleCode(existingCode, mangle);
            case NONEXISTENT -> mangleCode(nonexistentCode, mangle);
            case DELETED_HOLDER -> mangleCode(deletedHolderCode, mangle);
            case SELF -> mangleCode(selfCode, mangle);
            case JUNK -> junk;
            // 规整后仍是合法码，但原始长度 >64：必须按 CODE_NOT_FOUND 处理（需求 5.6）。
            case OVER_64_PADDED -> " ".repeat(60) + existingCode + " ".repeat(60);
            case OVER_64_JUNK -> repeatUntilLongerThan64(junk);
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
     * 把 n 编码成 8 位邀请码：前两位固定为本类专属前缀 {@code L3}，后 6 位取字母表 32 进制。
     *
     * <p>类专属前缀是<b>必需的</b>而非装饰：全部切片测试共用同一个内存 H2
     * （{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且 Spring 上下文缓存跨测试类复用，
     * {@code create-drop} 因此不会在类之间清库；各兄弟属性测试的序号又都从 0 开始，
     * 若共用同一段码空间，{@code uk_users_invite_code} 会随机爆掉。
     * 同理本类的 email / openid 一律带 {@code p3-} 前缀。</p>
     */
    private static String codeOf(long n) {
        char[] out = new char[InviteCodeGenerator.LENGTH];
        out[0] = 'L';
        out[1] = '3';
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

    private User persistUser(long seq, String tag, String inviteCode, LocalDateTime now) {
        User u = new User();
        // p3- 前缀：库跨测试类共用，邮箱唯一约束同样需要类专属命名空间（见 codeOf 的说明）。
        u.setEmail("p3-" + tag + "-" + seq + "@example.com");
        u.setNickname("p3-" + tag + "-" + seq);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    /** 调用前的库状态：该规整取值是否为某个现存用户持有。 */
    private boolean codeHeldByExistingUser(String normalized) {
        if (!inviteCodeGenerator.isWellFormed(normalized)) {
            return false;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE invite_code = ?", Long.class, normalized);
        return count != null && count > 0;
    }

    private long countRelations() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invite_relations", Long.class);
        return count == null ? 0L : count;
    }
}
