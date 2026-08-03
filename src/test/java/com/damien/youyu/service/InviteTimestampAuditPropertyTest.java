package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.UserRepository;
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
 * 时刻一致与审计列（Property 8）：{@code register_time} 与被邀请人 {@code users.created_at}
 * 落库取值严格相等，建行时 {@code created_at == updated_at}，状态更新只动
 * {@code status} 与 {@code updated_at}，已存在行的其余列此后一律不再改变。
 *
 * <h2>测试层级选择</h2>
 * <p>用真实持久化（{@code @DataJpaTest} + H2，表由实体生成）而非替身：本属性断言的全部对象都是
 * <b>落库取值</b>——两列的相等性、审计列的写入与更新、以及「后续操作不改旧行」。把持久化换成 mock
 * 等于把被测机制删掉。</p>
 *
 * <p><b>比对一律读库取值，绝不比较内存中的 {@link LocalDateTime}。</b>内存里的时刻带纳秒，
 * Hibernate 写入后实体字段仍是带纳秒的原值而库中已按列精度截断：比较内存值会得到「内存相等而库中
 * 不等」（或反之）的假绿测试。因此 {@code register_time} 与 {@code users.created_at} 都经
 * {@link JdbcTemplate} 读回来比对，并额外在 SQL 侧以 {@code r.register_time = u.created_at}
 * 连接判定一次，排除 JDBC 类型转换把差异抹平的可能。</p>
 *
 * <p>{@link AuthService} 手工 new（依赖的验证码与微信客户端与本属性无关，用替身注入），
 * 并注入<b>可推进的固定 {@link MutableClock}</b>：每一环注册前推进一段随机时长，使 200 次迭代
 * 覆盖大量互不相同的时刻，而不依赖真实时间。事务边界由 {@link TransactionTemplate} 提供，
 * 满足 {@link InviteBindingService#bindOnRegister} 的 {@code MANDATORY} 传播要求。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，
 * 依赖注入改由 {@link TestContextManager} 在 {@link BeforeTry} 中手工完成。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li><b>链式邀请</b>：A→B、B→C…（长度 2–5），每个新用户以上一环的邀请码注册——使同一行先作为
 *       {@code invitee} 存在、随后其持有者又去邀请他人，正是需求 5.9「一级邀请」的形状。</li>
 *   <li><b>时刻推进序列</b>：亚秒级（1–999ms）与秒/时/天级混合，逐环推进固定时钟。</li>
 *   <li><b>账号形态</b>：邮箱验证码登录与微信一键登录混合（两条建号路径各自读一次时钟）。</li>
 *   <li><b>状态更新</b>：对链上任一被邀请人执行 0–2 次注销联动
 *       （{@link InviteRelationRepository#markInvalidByInviteeId}），每次之间推进时钟。</li>
 * </ul>
 *
 * <p>不在本属性范围内：唯一冲突经保存点消化（Property 6）、未绑定原因的优先级（Property 4）、
 * 注销后的完整保留不变式（Property 12）。</p>
 *
 * <p>Feature: invite-system, Property 8: 时刻一致与审计列</p>
 *
 * <p>Validates: Requirements 5.2, 5.8, 5.9, 9.15</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ InviteBindingService.class, InviteCodeGenerator.class })
class InviteTimestampAuditPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-07-01T02:00:00Z");

    /** 同一个 H2 库跨迭代复用：用序号保证 email / openid / 邀请码全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /** 邀请关系七列快照：除 {@code status}/{@code updated_at} 外一律不许变。 */
    private static final String SEVEN_COLUMNS =
            "SELECT invite_id, inviter_id, invitee_id, register_time, status, created_at, updated_at "
                    + "FROM invite_relations";

    /** 两条建号路径：各自只读一次时钟，createdAt 与 register_time 共用该取值。 */
    private enum AccountForm { EMAIL, WECHAT }

    @Autowired
    private InviteBindingService bindingService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InviteRelationRepository inviteRelationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private VerificationCodeService verificationCodeService;
    private WeChatClient weChatClient;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(InviteTimestampAuditPropertyTest.class).prepareTestInstance(this);
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

    /** 时刻推进步长：亚秒级与秒/时/天级混合，覆盖列精度边界附近的取值。 */
    @Provide
    Arbitrary<List<Long>> advanceMillis() {
        return Arbitraries.oneOf(
                        Arbitraries.longs().between(1L, 999L),
                        Arbitraries.longs().between(1_000L, 86_400_000L))
                .list().ofSize(8);
    }

    @Provide
    Arbitrary<List<AccountForm>> accountForms() {
        return Arbitraries.of(AccountForm.values()).list().ofSize(8);
    }

    // ---------------- Property 8 ----------------

    /**
     * Feature: invite-system, Property 8: 时刻一致与审计列
     *
     * <p>对任意链式注册序列（A→B、B→C…）与任意时刻推进序列、任意注销联动次数：</p>
     * <ul>
     *   <li><b>需求 5.8</b>：每条成功建立的关系，其 {@code register_time} 与被邀请人
     *       {@code users.created_at} 的<b>落库取值</b>严格相等（时间差 0 毫秒）——两值都从库里读回来
     *       比对，另在 SQL 侧以连接条件复核一次；</li>
     *   <li><b>需求 5.2</b>：建号与建关系同事务——在提交<b>之前</b>的同一物理事务内，
     *       {@code users} 新行与其邀请关系行都已可见；</li>
     *   <li><b>需求 9.15</b>：建行时 {@code created_at == updated_at}（读库比对）；状态更新后
     *       仅 {@code status} 与 {@code updated_at} 变化，{@code invite_id}/{@code inviter_id}/
     *       {@code invitee_id}/{@code register_time}/{@code created_at} 一列不动，
     *       且 {@code updated_at} 严格变大（多次更新下单调不减）；</li>
     *   <li><b>需求 5.9</b>：只记一级关系——被邀请人后续邀请他人、或任一行状态被更新，都不改变其它
     *       已存在行的任何一列（逐行七列快照相等）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 5.2, 5.8, 5.9, 9.15</p>
     */
    @Property(tries = 200)
    void property8_registerTimeEqualsCreatedAtAndAuditColumnsAreStable(
            @ForAll @IntRange(min = 2, max = 5) int chainLength,
            @ForAll("advanceMillis") List<Long> advances,
            @ForAll("accountForms") List<AccountForm> forms,
            @ForAll @IntRange(min = 0, max = 4) int deactivateSlot,
            @ForAll @IntRange(min = 0, max = 2) int deactivateRounds) {

        long seq = SEQ.incrementAndGet();
        MutableClock clock = new MutableClock(T0.plusSeconds(seq * 3600L), ZONE);
        AuthService authService = new AuthService(userRepository, clock, weChatClient,
                verificationCodeService, new InviteCodeGenerator(), bindingService);

        // 链首邀请人：本迭代内 id 最小者，用作快照过滤的下界（更早迭代的行 id 一定更小）。
        LocalDateTime rootNow = LocalDateTime.now(clock);
        Long rootId = tx.execute(s -> persistUser(seq, codeOf(900_000_000L + seq * 10), rootNow).getId());
        assertThat(rootId).isNotNull();
        long minId = rootId;

        List<Long> inviteeIds = new ArrayList<>();
        String inviterCode = inviteCodeOf(rootId);

        for (int i = 0; i < chainLength; i++) {
            clock.advance(Duration.ofMillis(advances.get(i % advances.size())));

            Map<Long, Map<String, Object>> before = snapshot(minId);
            String code = inviterCode;
            AccountForm form = forms.get(i % forms.size());
            String email = "p8-" + seq + "-" + i + "@example.com";
            String openid = "wx-p8-" + seq + "-" + i;

            LoginOutcome outcome = tx.execute(s -> {
                LoginOutcome o = switch (form) {
                    case EMAIL -> authService.emailLogin(email, "123456", code);
                    case WECHAT -> authService.wxLogin(openid, code);
                };
                // 需求 5.2：提交之前，两次写入已在同一物理事务内可见（同事务的必要条件）。
                assertThat(countUsers(o.user().getId())).isEqualTo(1L);
                assertThat(countRelationsOf(o.user().getId())).isEqualTo(1L);
                return o;
            });

            assertThat(outcome).isNotNull();
            assertThat(outcome.isNewUser()).isTrue();
            assertThat(outcome.inviteBind().bound())
                    .as("第 %d 环应绑定成功（未绑定原因 %s）", i, outcome.inviteBind().reason())
                    .isTrue();

            long inviteeId = outcome.user().getId();
            inviteeIds.add(inviteeId);

            // 需求 5.8：register_time 与 users.created_at 的落库取值严格相等（读库比对）。
            LocalDateTime registerTimeInDb = relationTime(inviteeId, "register_time");
            LocalDateTime userCreatedAtInDb = jdbcTemplate.queryForObject(
                    "SELECT created_at FROM users WHERE id = ?", LocalDateTime.class, inviteeId);
            assertThat(registerTimeInDb).isNotNull().isEqualTo(userCreatedAtInDb);
            // SQL 侧复核：连接条件直接判等，避免 JDBC 类型转换掩盖差异。
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM invite_relations r JOIN users u ON u.id = r.invitee_id "
                            + "WHERE r.invitee_id = ? AND r.register_time = u.created_at",
                    Long.class, inviteeId)).isEqualTo(1L);

            // 需求 9.15：建行时 created_at 与 updated_at 相等（同一服务端时刻）。
            assertThat(relationTime(inviteeId, "created_at"))
                    .isEqualTo(relationTime(inviteeId, "updated_at"));

            // 需求 5.9：本次新增不改动任何已存在行（含上一环里本人作为 invitee 的那一行）。
            assertRowsUnchanged(before, snapshot(minId));

            // 下一环由本次新用户去邀请：制造 A→B→C 的链式形状。
            inviterCode = inviteCodeOf(inviteeId);
        }

        // ---- 状态更新（注销联动）：只动 status 与 updated_at ----
        long target = inviteeIds.get(deactivateSlot % inviteeIds.size());
        for (int r = 0; r < deactivateRounds; r++) {
            // 步长 ≥1ms，保证 updated_at 在列精度下严格变大。
            clock.advance(Duration.ofMillis(1L + advances.get(r % advances.size())));
            LocalDateTime updateNow = LocalDateTime.now(clock);

            Map<Long, Map<String, Object>> before = snapshot(minId);
            Map<String, Object> targetBefore = rowOf(target);
            Long targetInviteId = ((Number) targetBefore.get("invite_id")).longValue();

            Integer affected = tx.execute(s ->
                    inviteRelationRepository.markInvalidByInviteeId(target, updateNow));
            assertThat(affected).isEqualTo(1);

            Map<String, Object> targetAfter = rowOf(target);
            // 需求 9.15：invite_id / inviter_id / invitee_id / register_time / created_at 一列不动。
            assertThat(targetAfter)
                    .containsEntry("invite_id", targetBefore.get("invite_id"))
                    .containsEntry("inviter_id", targetBefore.get("inviter_id"))
                    .containsEntry("invitee_id", targetBefore.get("invitee_id"))
                    .containsEntry("register_time", targetBefore.get("register_time"))
                    .containsEntry("created_at", targetBefore.get("created_at"));
            assertThat(targetAfter.get("status")).isEqualTo(InviteStatus.INVALID.name());
            // updated_at 更新为本次操作的服务端时刻：严格变大（多轮更新下单调不减）。
            assertThat(relationTime(target, "updated_at"))
                    .isAfter(toLocalDateTime(targetBefore.get("updated_at")));

            // register_time 与被邀请人 created_at 的相等关系不因状态更新而破坏（读库比对）。
            assertThat(relationTime(target, "register_time")).isEqualTo(
                    jdbcTemplate.queryForObject("SELECT created_at FROM users WHERE id = ?",
                            LocalDateTime.class, target));

            // 需求 5.9：其余行一列不动。
            before.remove(targetInviteId);
            Map<Long, Map<String, Object>> after = snapshot(minId);
            after.remove(targetInviteId);
            assertRowsUnchanged(before, after);
        }

        // 收尾：本迭代全部关系行的 register_time 与其被邀请人 created_at 在库内逐行相等。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations r JOIN users u ON u.id = r.invitee_id "
                        + "WHERE r.inviter_id >= ? AND r.register_time <> u.created_at",
                Long.class, minId)).isZero();
    }

    // ---------------- 测试基础设施 ----------------

    /** 本迭代涉及的关系行（更早迭代的 id 一定更小，故以 minId 为下界即可精确切出本迭代）。 */
    private Map<Long, Map<String, Object>> snapshot(long minId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SEVEN_COLUMNS + " WHERE inviter_id >= ? OR invitee_id >= ?", minId, minId);
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byId.put(((Number) row.get("invite_id")).longValue(), row);
        }
        return byId;
    }

    /** 快照逐行七列相等：{@code before} 中的每一行在 {@code after} 中必须一列不差。 */
    private static void assertRowsUnchanged(Map<Long, Map<String, Object>> before,
            Map<Long, Map<String, Object>> after) {
        for (Map.Entry<Long, Map<String, Object>> e : before.entrySet()) {
            assertThat(after)
                    .as("已存在的关系行 invite_id=%d 不得被后续操作改动", e.getKey())
                    .containsEntry(e.getKey(), e.getValue());
        }
    }

    private Map<String, Object> rowOf(long inviteeId) {
        return jdbcTemplate.queryForMap(SEVEN_COLUMNS + " WHERE invitee_id = ?", inviteeId);
    }

    private LocalDateTime relationTime(long inviteeId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM invite_relations WHERE invitee_id = ?",
                LocalDateTime.class, inviteeId);
    }

    private static LocalDateTime toLocalDateTime(Object dbValue) {
        return ((java.sql.Timestamp) dbValue).toLocalDateTime();
    }

    private String inviteCodeOf(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT invite_code FROM users WHERE id = ?", String.class, userId);
    }

    private long countUsers(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Long.class, userId);
        return count == null ? 0L : count;
    }

    private long countRelationsOf(long inviteeId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations WHERE invitee_id = ?", Long.class, inviteeId);
        return count == null ? 0L : count;
    }

    private User persistUser(long seq, String inviteCode, LocalDateTime now) {
        User u = new User();
        u.setEmail("p8root-" + seq + "@example.com");
        u.setNickname("p8root-" + seq);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    /**
     * 把 n 编码成 8 位邀请码：前两位固定为本类专属前缀 {@code T8}，后 6 位取字母表 32 进制，
     * 与其它属性测试的取值空间因此严格不重叠。
     *
     * <p>类专属前缀是<b>必需的</b>而非装饰：全部切片测试共用同一个内存 H2
     * （{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且 Spring 上下文缓存跨测试类复用，
     * {@code create-drop} 因此不会在类之间清库；各兄弟属性测试的序号又都从 0 开始，
     * 若共用同一段码空间，{@code uk_users_invite_code} 会随机爆掉。
     * 同理本类的 email / openid 一律带 {@code p8-} / {@code p8root-} 前缀。</p>
     */
    private static String codeOf(long n) {
        char[] out = new char[InviteCodeGenerator.LENGTH];
        out[0] = 'T';
        out[1] = '8';
        long v = n;
        for (int i = InviteCodeGenerator.LENGTH - 1; i >= 2; i--) {
            out[i] = InviteCodeGenerator.ALPHABET.charAt((int) (v % InviteCodeGenerator.ALPHABET.length()));
            v /= InviteCodeGenerator.ALPHABET.length();
        }
        return new String(out);
    }

    // ---------------- 可推进的固定时钟 ----------------

    private static final class MutableClock extends java.time.Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public java.time.Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
