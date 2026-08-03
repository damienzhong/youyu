package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.mockito.Mockito;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.support.InMemoryUserRepository;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 身份模型的属性测试（jqwik），覆盖设计文档 Correctness Properties 中的 Property 4、Property 5。
 * 每个属性运行 200 次随机迭代（与仓库既有 {@code *PropertyTest} 约定一致）。
 *
 * <p>使用真实的 {@link InMemoryUserRepository} 存储实现与真实的 {@link AuthService} 绑定/解绑
 * 业务逻辑；验证码校验以测试替身恒真（{@code verifyConsume → true}）隔离，微信 {@code jscode2session}
 * 以测试替身把传入的 code 直接当作 openid 返回，从而由生成器控制被绑定的 openid 值。核心的身份唯一性
 * 与冲突检查全部真实执行，不预置任何"制造通过"的桩。</p>
 *
 * <p>对一小组账号施加随机的「绑定邮箱 / 绑定微信 / 解绑邮箱 / 解绑微信」操作序列，在<strong>每一步操作
 * 之后</strong>断言不变式成立。</p>
 *
 * <ul>
 *   <li>Property 4（身份唯一性）：Validates Requirements 4.1, 5.2, 6.2</li>
 *   <li>Property 5（至少一种登录方式）：Validates Requirements 4.2, 7.1, 7.2</li>
 * </ul>
 */
class IdentityPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZONE);

    private static final int ACCOUNTS = 3;
    // 待绑定身份取自小值域池，以刻意制造跨账号冲突。
    private static final List<String> EMAIL_POOL =
            List.of("pool0@example.com", "pool1@example.com", "pool2@example.com");
    private static final List<String> OPENID_POOL = List.of("wx-a", "wx-b");

    private enum OpType { BIND_EMAIL, BIND_WECHAT, UNBIND_EMAIL, UNBIND_WECHAT }

    /** 一次操作：作用于哪个账号、什么操作、绑定用的值池下标。 */
    private record Op(int accountIndex, OpType type, int valueIndex) {
    }

    // ---------------- 生成器 ----------------

    @Provide
    Arbitrary<List<Op>> opSequences() {
        Arbitrary<Op> op = Combinators.combine(
                Arbitraries.integers().between(0, ACCOUNTS - 1),
                Arbitraries.of(OpType.values()),
                Arbitraries.integers().between(0, 2)
        ).as(Op::new);
        return op.list().ofMinSize(1).ofMaxSize(30);
    }

    // ---------------- Property 4: 身份唯一性 ----------------

    /**
     * Property 4（身份唯一性）：在任意「绑定/解绑」操作序列的每一步之后，{@code email} 与 {@code wx_openid}
     * 都各自在所有账号中全局唯一；且任何试图绑定「已被其它账号占用」的身份的操作，一律被
     * {@code IDENTITY_TAKEN} 拒绝（且不修改任何账号）。
     *
     * <p>Validates: Requirements 4.1, 5.2, 6.2</p>
     */
    @Property(tries = 200)
    void property4_identitiesGloballyUnique(@ForAll("opSequences") List<Op> ops) {
        Fixture f = newFixture();

        for (Op op : ops) {
            User account = f.accounts.get(op.accountIndex());
            Long id = account.getId();
            // 记录操作前的全局状态与目标身份的持有者，用于事后判定"占用即拒绝"。
            boolean isBind = op.type() == OpType.BIND_EMAIL || op.type() == OpType.BIND_WECHAT;
            String targetValue = null;
            Long holderIdBefore = null;
            boolean currentTypeAlreadySet = false;
            if (op.type() == OpType.BIND_EMAIL) {
                targetValue = EMAIL_POOL.get(op.valueIndex());
                holderIdBefore = holderOfEmail(f, targetValue);
                currentTypeAlreadySet = notBlank(account.getEmail());
            } else if (op.type() == OpType.BIND_WECHAT) {
                targetValue = OPENID_POOL.get(Math.min(op.valueIndex(), OPENID_POOL.size() - 1));
                holderIdBefore = holderOfOpenid(f, targetValue);
                currentTypeAlreadySet = notBlank(account.getWxOpenid());
            }

            ApiException thrown = applyCatching(f, op);

            // 不变式：任一步之后，email 与 openid 各自全局唯一。
            assertUnique(f, "email");
            assertUnique(f, "wx_openid");

            // 占用即拒绝：绑定目标被"其它账号"持有、且当前账号尚未绑定该类身份时，必以 IDENTITY_TAKEN 拒绝。
            if (isBind && holderIdBefore != null && !holderIdBefore.equals(id) && !currentTypeAlreadySet) {
                assertThat(thrown).isNotNull();
                assertThat(thrown.getCode()).isEqualTo("IDENTITY_TAKEN");
            }
        }
    }

    // ---------------- Property 5: 至少一种登录方式 ----------------

    /**
     * Property 5（至少一种登录方式）：在任意操作序列的每一步之后，每个账号都至少保留一种登录身份
     * （email 或 wx_openid 非空）；任何会使账号失去全部登录身份的解绑操作，一律被 {@code LAST_LOGIN_METHOD}
     * 拒绝，且该账号的身份保持不变。
     *
     * <p>Validates: Requirements 4.2, 7.1, 7.2</p>
     */
    @Property(tries = 200)
    void property5_atLeastOneLoginMethod(@ForAll("opSequences") List<Op> ops) {
        Fixture f = newFixture();

        for (Op op : ops) {
            User account = f.accounts.get(op.accountIndex());
            boolean isUnbind = op.type() == OpType.UNBIND_EMAIL || op.type() == OpType.UNBIND_WECHAT;
            // 操作前的身份快照。
            String emailBefore = account.getEmail();
            String openidBefore = account.getWxOpenid();
            boolean hadEmail = notBlank(emailBefore);
            boolean hadWechat = notBlank(openidBefore);
            boolean wouldLoseLast = (op.type() == OpType.UNBIND_EMAIL && hadEmail && !hadWechat)
                    || (op.type() == OpType.UNBIND_WECHAT && hadWechat && !hadEmail);

            ApiException thrown = applyCatching(f, op);

            // 不变式：任一步之后，每个账号都至少保留一种登录身份。
            for (User u : f.repository.findAll()) {
                assertThat(notBlank(u.getEmail()) || notBlank(u.getWxOpenid()))
                        .as("账号 %d 至少保留一种登录身份", u.getId())
                        .isTrue();
            }

            // 解绑唯一身份必被拒，且账号身份保持不变（零副作用）。
            if (isUnbind && wouldLoseLast) {
                assertThat(thrown).isNotNull();
                assertThat(thrown.getCode()).isEqualTo("LAST_LOGIN_METHOD");
                User reloaded = f.repository.findById(account.getId()).orElseThrow();
                assertThat(reloaded.getEmail()).isEqualTo(emailBefore);
                assertThat(reloaded.getWxOpenid()).isEqualTo(openidBefore);
            }
        }
    }

    // ---------------- 测试基础设施 ----------------

    private static final class Fixture {
        final InMemoryUserRepository repository;
        final AuthService service;
        final List<User> accounts;

        Fixture(InMemoryUserRepository repository, AuthService service, List<User> accounts) {
            this.repository = repository;
            this.service = service;
            this.accounts = accounts;
        }
    }

    /**
     * 构造一套全新的存储与服务，并预置 {@link #ACCOUNTS} 个账号，各自绑定一个唯一的种子邮箱
     * （{@code seedN@example.com}）作为初始登录身份，使起始状态即满足两条不变式。
     */
    private Fixture newFixture() {
        InMemoryUserRepository repository = new InMemoryUserRepository();

        VerificationCodeService verificationCodeService = Mockito.mock(VerificationCodeService.class);
        when(verificationCodeService.verifyConsume(anyString(), any(EmailCodePurpose.class), anyString()))
                .thenReturn(true);

        WeChatClient weChatClient = Mockito.mock(WeChatClient.class);
        // 把传入的一次性 code 直接当作 openid 返回，由生成器控制被绑定的 openid 值。
        when(weChatClient.jscode2session(anyString()))
                .thenAnswer(inv -> new WxSession(inv.getArgument(0), null));

        InviteBindingService inviteBindingService = Mockito.mock(InviteBindingService.class);
        when(inviteBindingService.bindOnRegister(any(), Mockito.anyBoolean(), any(), any()))
                .thenReturn(InviteBindResult.ofUnbound(UnboundReason.NO_CODE));
        AuthService service = new AuthService(
                repository, Clock.fixed(T0, ZONE), weChatClient, verificationCodeService,
                new InviteCodeGenerator(), inviteBindingService);

        List<User> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            User u = new User();
            u.setEmail("seed" + i + "@example.com");
            u.setNickname("seed" + i);
            u.setPlan(Plan.FREE);
            u.setRole(Role.USER);
            u.setPlanStartedAt(NOW);
            u.setPlanExpiresAt(NOW.plusDays(365));
            u.setCreatedAt(NOW);
            u.setUpdatedAt(NOW);
            accounts.add(repository.save(u));
        }
        return new Fixture(repository, service, accounts);
    }

    /** 施加一次操作；捕获并返回业务异常（无异常则返回 {@code null}）。 */
    private ApiException applyCatching(Fixture f, Op op) {
        Long id = f.accounts.get(op.accountIndex()).getId();
        try {
            switch (op.type()) {
                case BIND_EMAIL -> f.service.bindEmail(id, EMAIL_POOL.get(op.valueIndex()), "123456");
                case BIND_WECHAT -> f.service.bindWechat(id,
                        OPENID_POOL.get(Math.min(op.valueIndex(), OPENID_POOL.size() - 1)));
                case UNBIND_EMAIL -> f.service.unbind(id, "email");
                case UNBIND_WECHAT -> f.service.unbind(id, "wechat");
            }
            return null;
        } catch (ApiException e) {
            return e;
        }
    }

    private Long holderOfEmail(Fixture f, String email) {
        return f.repository.findByEmail(email).map(User::getId).orElse(null);
    }

    private Long holderOfOpenid(Fixture f, String openid) {
        return f.repository.findByWxOpenid(openid).map(User::getId).orElse(null);
    }

    /** 断言给定身份字段在所有账号中不重复（忽略空值）。 */
    private void assertUnique(Fixture f, String field) {
        Set<String> seen = new HashSet<>();
        for (User u : f.repository.findAll()) {
            String value = "email".equals(field) ? u.getEmail() : u.getWxOpenid();
            if (notBlank(value)) {
                assertThat(seen.add(value))
                        .as("身份 %s=%s 在多个账号间重复", field, value)
                        .isTrue();
            }
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
