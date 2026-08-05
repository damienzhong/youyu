package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.UserGrowthRepository;
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
 * 注销后清零且重新注册从 Lv1 的属性测试（<b>Property 15：注销后清零且重新注册从 Lv1</b>）。
 *
 * <p>对<i>任意</i>成长数据规模、注销前置状态、删除失败注入点、邀请关系与重新注册身份，本测试锁住需求 12
 * 与需求 11.21 交汇处的一组构造性不变式：</p>
 * <ul>
 *   <li><b>注销后两表清零、无悬空 user_id</b>（需求 12.1、12.2、11.21）：注销事务提交后，
 *       {@code growth_events} 与 {@code user_growth} 中该用户的行数均为 0；且两表按 {@code user_id}
 *       反查 {@code users.id} 不存在的行数（对账口径）为 0。</li>
 *   <li><b>成长删除失败整事务回滚</b>（需求 12.4）：让 {@code growth_events} 或 {@code user_growth} 的
 *       硬删抛错，注销失败后该用户 {@code users} 行的 {@code id}/{@code email}/{@code wx_openid}/
 *       {@code nickname}/{@code invite_code} 五列与两表成长数据全列快照与注销前逐行相等，且该用户仍可
 *       成功请求成长概览（等价于「注销前令牌仍可用」）。</li>
 *   <li><b>前置校验失败零副作用</b>（需求 12.5）：{@code DELETE_BLOCKED_COLLAB}（协作账本仍有他人成员）
 *       与二次验证失败两条路径下，两表全部行的列取值保持请求前状态，用户行仍在。</li>
 *   <li><b>不触及他人成长与 invite_relations</b>（需求 12.6、12.7）：注销一名<b>作为邀请人</b>的用户，
 *       其被邀请人的成长数据快照不变、{@code invite_relations} 全表快照一行不改（该表联动完全由既有
 *       invite 逻辑负责，成长删除一行都不碰；被注销者本身不是任何人的被邀请人，故无 INVALID 变更）。</li>
 *   <li><b>删除行数有界、无存在性预查询</b>（需求 12.9、12.11）：待删 {@code growth_events} 行数不超过
 *       「DAILY_RECORD 条数 + BUDGET_MET 条数 + 13」、{@code user_growth} 不超过 1 行；注销路径对两个
 *       成长仓储只发生「一次硬删」（用 spy 断言无其它调用，即删除前不查存在性）；三个规模参数全为 0 时
 *       两表本就无行，删除同样成功。</li>
 *   <li><b>同身份重新注册从 Lv1</b>（需求 12.3、11.21）：注销后以同一邮箱 / 同一 openid 重新注册（新
 *       {@code users.id}），{@code GET /api/growth} 等价的 {@link GrowthQueryService#getOverview} 返回
 *       等级 1、未满级、16 枚徽章均未点亮；两表悬空 id 对账数仍为 0。</li>
 * </ul>
 *
 * <h2>删除步骤位置（需求 12.8）</h2>
 * <p>成长删除固定位于既有第 12 步（{@code invite_relations} 置 INVALID）之后、第 13 步（删 {@code users}
 * 行）之前，这一位置由 {@link AccountDeletionService#deleteAccount} 的实现保证；本属性通过「注销成功后
 * {@code invite_relations} 该用户作为邀请人的行不变、users 行已删、两表已清零」间接锁住其相对顺序与影响
 * 行数不随成长删除改变。</p>
 *
 * <h2>测试层级与清理</h2>
 * <p>注销、注册与概览都要求真实事务管理器（{@code deleteAccount}/{@code emailLogin} 均 {@code @Transactional}、
 * {@code getOverview} 内的结算带 {@code REQUIRES_NEW}），故走全栈 {@code @SpringBootTest} + H2（独立命名内存库）。
 * 清理<b>不能靠事务回滚</b>：{@link #resetState()} 在每次迭代前显式清相关表、归位时钟并重配替身，用全局自增
 * 序号 {@link #SEQ} 保证每次迭代的 email/openid 全局唯一。jqwik 属性方法不经 {@code SpringExtension}，
 * 依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文缓存复用，多次迭代只加载一次）。</p>
 *
 * <p>三处与成长语义无关的替身：{@link VerificationCodeService}（邮箱验证码校验，同时用于制造「二次验证
 * 未通过」分支）与 {@link WeChatClient}（微信换 openid，openid 直接取一次性 code）用 {@link MockitoBean}；
 * 两个成长仓储用 {@link MockitoSpyBean}，未打桩时委托真实实现，仅删除失败注入分支对 {@code deleteByUserId}
 * 打桩抛错。</p>
 *
 * <p>Feature: growth-level-system, Property 15: 注销后清零且重新注册从 Lv1</p>
 *
 * <p>Validates: Requirements 11.21, 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.8, 12.9, 12.11</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-dereg-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class GrowthDeregistrationPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant BASE = Instant.parse("2025-06-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    /** 跨迭代复用同一内存库，用序号保证 email/openid 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(70_000_000L);

    /** 邮箱验证码校验结果开关：制造「二次验证未通过」的前置校验失败分支（需求 12.5）。 */
    private static final AtomicBoolean CODE_VALID = new AtomicBoolean(true);

    /** growth_events 六列快照（全部列，用于回滚 / 零副作用逐行比对）。 */
    private static final String EVENT_COLUMNS =
            "SELECT id, user_id, event_type, event_key, exp_amount, created_at FROM growth_events";

    /** user_growth 十列快照（全部列）。 */
    private static final String PROFILE_COLUMNS =
            "SELECT user_id, exp, level, total_record_days, current_streak_days, max_streak_days, "
                    + "last_record_date, last_settled_at, created_at, updated_at FROM user_growth";

    /** invite_relations 七列全表快照（含 updated_at）：需求 12.7 要求成长删除不碰该表任何行。 */
    private static final String INVITE_SEVEN_COLUMNS =
            "SELECT invite_id, inviter_id, invitee_id, register_time, status, created_at, updated_at "
                    + "FROM invite_relations ORDER BY invite_id";

    @Autowired
    private AuthService authService;
    @Autowired
    private AccountDeletionService deletionService;
    @Autowired
    private GrowthQueryService queryService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private LedgerMemberRepository ledgerMemberRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private VerificationCodeService verificationCodeService;
    @MockitoBean
    private WeChatClient weChatClient;

    /** 未打桩时委托真实仓储；仅删除失败注入分支对 {@code deleteByUserId} 打桩抛错。 */
    @MockitoSpyBean
    private GrowthEventRepository growthEventRepository;
    @MockitoSpyBean
    private UserGrowthRepository userGrowthRepository;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthDeregistrationPropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        CODE_VALID.set(true);

        // 替身每次迭代重配：spy 复位后委托真实实现；mock 复位后返回默认值，故重新打桩。
        reset(growthEventRepository, userGrowthRepository, verificationCodeService, weChatClient);
        when(verificationCodeService.verifyConsume(anyString(), any(EmailCodePurpose.class), anyString()))
                .thenAnswer(inv -> CODE_VALID.get());
        // openid 直接取一次性 code：注册与二次验证都能凭 code 精确控制身份是否匹配。
        when(weChatClient.jscode2session(anyString()))
                .thenAnswer(inv -> new WxSession(inv.getArgument(0), null));

        // 结算真实提交，清理不能靠回滚：每次迭代前硬删本测试涉及的全部表。均无外键 / 顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM invite_relations");
        jdbcTemplate.update("DELETE FROM ledger_members");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM users");
    }

    // ---------------- 生成器 ----------------

    /** 注销前置校验结果。 */
    enum Precheck { PASS, BLOCKED_COLLAB, SECOND_FACTOR_FAIL }

    /** 删除失败注入点（仅 PASS 分支生效）。 */
    enum Injection { NONE, EVENTS_THROW, PROFILE_THROW }

    /**
     * 一次注销场景：成长数据规模（DAILY_RECORD 条数 / BUDGET_MET 条数 / 徽章子集位图）×
     * 前置校验结果 × 删除失败注入 × 被注销者身份（邮箱 / 微信）× 被注销者是否曾邀请他人。
     */
    record Scenario(int dailyRecordCount, int budgetMetCount, int badgeBits,
                    Precheck precheck, Injection injection,
                    boolean subjectIsWx, boolean subjectInvitedBystander) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(
                Arbitraries.integers().between(0, 40),
                Arbitraries.integers().between(0, 8),
                Arbitraries.integers().between(0, 0b1_1111_1111),
                Arbitraries.frequency(
                        Tuple.of(4, Precheck.PASS),
                        Tuple.of(1, Precheck.BLOCKED_COLLAB),
                        Tuple.of(1, Precheck.SECOND_FACTOR_FAIL)),
                Arbitraries.of(Injection.NONE, Injection.EVENTS_THROW, Injection.PROFILE_THROW),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false)
        ).as(Scenario::new);
    }

    // ---------------- Property 15 ----------------

    /**
     * Feature: growth-level-system, Property 15: 注销后清零且重新注册从 Lv1
     *
     * <p>注册被注销者与旁观者（并按场景建立「被注销者邀请旁观者」的邀请关系），给两人预置成长数据，
     * 随后按前置校验结果分派：通过则（可能注入删除失败后）真正注销并断言两表清零 / 无悬空 id / 旁观者
     * 与 invite_relations 零变更 / 同身份重新注册回到 Lv1；失败注入则断言整事务回滚、五列与成长快照
     * 还原、概览仍可读；前置校验未通过则断言两表零副作用。</p>
     *
     * <p>Validates: Requirements 11.21, 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.8, 12.9, 12.11</p>
     */
    @Property(tries = 30)
    void property15_growthClearedOnDeletionAndReRegistrationStartsAtLevelOne(
            @ForAll("scenarios") Scenario scenario) {

        long seq = SEQ.getAndIncrement();
        String subjectEmail = "p15-" + seq + "@example.com";
        String subjectWx = "p15wx-" + seq;
        String bystanderEmail = "p15-by-" + seq + "@example.com";

        // ---------- 注册：被注销者（邮箱 / 微信）与旁观者 ----------
        long subjectId = registerSubject(scenario.subjectIsWx(), subjectEmail, subjectWx);
        String subjectInviteCode = userRepository.findById(subjectId).orElseThrow().getInviteCode();

        // 旁观者：按场景携带被注销者的邀请码（使被注销者成为「邀请人」），否则独立注册。
        long bystanderId = registerEmailUser(bystanderEmail,
                scenario.subjectInvitedBystander() ? subjectInviteCode : null);

        // ---------- 预置成长数据 ----------
        seedGrowth(subjectId, scenario.dailyRecordCount(), scenario.budgetMetCount(), scenario.badgeBits());
        seedGrowth(bystanderId, 3, 1, 0b0000_0011);

        // 旁观者成长快照 + invite_relations 全表快照（用于「不触及」断言）。
        List<Map<String, Object>> bystanderEventsBefore = eventsSnapshot(bystanderId);
        List<Map<String, Object>> bystanderProfileBefore = profileSnapshot(bystanderId);
        List<Map<String, Object>> inviteBefore = jdbcTemplate.queryForList(INVITE_SEVEN_COLUMNS);

        switch (scenario.precheck()) {
            case BLOCKED_COLLAB -> assertBlockedCollabHasNoSideEffect(
                    subjectId, bystanderId, bystanderEventsBefore, bystanderProfileBefore, inviteBefore);
            case SECOND_FACTOR_FAIL -> assertSecondFactorFailHasNoSideEffect(
                    scenario, subjectId, subjectWx, bystanderId,
                    bystanderEventsBefore, bystanderProfileBefore, inviteBefore);
            case PASS -> {
                deletionService.requireDeletable(subjectId);
                passSecondFactor(scenario.subjectIsWx(), subjectId, subjectWx);
                if (scenario.injection() == Injection.NONE) {
                    assertSuccessfulDeletionAndReRegistration(
                            scenario, subjectId, subjectEmail, subjectWx, bystanderId,
                            bystanderEventsBefore, bystanderProfileBefore, inviteBefore);
                } else {
                    assertDeletionFailureRollsBack(
                            scenario, subjectId, bystanderId,
                            bystanderEventsBefore, bystanderProfileBefore, inviteBefore);
                }
            }
        }
    }

    // ---------------- 分支断言 ----------------

    /**
     * 注销成功：两表清零、无悬空 id、旁观者与 invite_relations 零变更、同身份重新注册回到 Lv1；
     * 并断言删除行数落在需求 12.9 的上界内、两条硬删之外对两个成长仓储不再有任何调用（需求 12.11：
     * 不做存在性预查询），两表本就无行时删除同样成功（{@code dailyRecordCount/budgetMetCount/badgeBits}
     * 全为 0 的场景下 {@link #seedGrowth} 刻意不建档）。
     */
    private void assertSuccessfulDeletionAndReRegistration(
            Scenario scenario, long subjectId, String subjectEmail, String subjectWx, long bystanderId,
            List<Map<String, Object>> bystanderEventsBefore,
            List<Map<String, Object>> bystanderProfileBefore,
            List<Map<String, Object>> inviteBefore) {

        // 需求 12.9：待删 growth_events 行数 ≤ 累计记账天数 + BUDGET_MET 条数 + 13，user_growth ≤ 1。
        long eventsToDelete = growthEventCount(subjectId);
        assertThat(eventsToDelete).as("待删成长事件行数落在需求 12.9 的上界内")
                .isLessThanOrEqualTo(scenario.dailyRecordCount() + scenario.budgetMetCount() + 13L);
        assertThat(userGrowthCount(subjectId)).as("待删成长档案行数 ≤ 1").isLessThanOrEqualTo(1L);

        // 需求 12.11：注销路径对两个成长仓储只应发生「一次硬删」，删除前不做任何存在性预查询。
        Mockito.clearInvocations(growthEventRepository, userGrowthRepository);

        deletionService.deleteAccount(subjectId);

        Mockito.verify(growthEventRepository).deleteByUserId(subjectId);
        Mockito.verify(userGrowthRepository).deleteByUserId(subjectId);
        Mockito.verifyNoMoreInteractions(growthEventRepository, userGrowthRepository);

        // 需求 12.1、12.2：该用户在两表的行数均为 0，users 行已删。
        assertThat(growthEventCount(subjectId)).as("注销后 growth_events 该用户行数为 0").isZero();
        assertThat(userGrowthCount(subjectId)).as("注销后 user_growth 该用户行数为 0").isZero();
        assertThat(userRepository.findById(subjectId)).as("注销后 users 行已删").isEmpty();

        // 需求 11.21：两表反查 users.id 不存在的行数为 0。
        assertThat(orphanGrowthEvents()).as("growth_events 无悬空 user_id").isZero();
        assertThat(orphanUserGrowth()).as("user_growth 无悬空 user_id").isZero();

        // 需求 12.6：旁观者成长数据一列未动。
        assertThat(eventsSnapshot(bystanderId)).as("旁观者 growth_events 不变").isEqualTo(bystanderEventsBefore);
        assertThat(profileSnapshot(bystanderId)).as("旁观者 user_growth 不变").isEqualTo(bystanderProfileBefore);

        // 需求 12.7：invite_relations 全表快照一行不改（被注销者是邀请人，非任何人的被邀请人）。
        assertThat(jdbcTemplate.queryForList(INVITE_SEVEN_COLUMNS))
                .as("注销不修改 invite_relations 任何行").isEqualTo(inviteBefore);

        // 需求 12.3、11.21：同身份重新注册后从 Lv1、16 枚未点亮。
        long newId = scenario.subjectIsWx()
                ? registerSubject(true, subjectEmail, subjectWx)
                : registerSubject(false, subjectEmail, subjectWx);
        assertThat(newId).as("重新注册应得到新的 users.id").isNotEqualTo(subjectId);
        assertFreshGrowthProfile(newId);

        // 重新注册后旧数据仍清零、无悬空 id。
        assertThat(growthEventCount(subjectId)).isZero();
        assertThat(userGrowthCount(subjectId)).isZero();
        assertThat(orphanGrowthEvents()).isZero();
        assertThat(orphanUserGrowth()).isZero();
    }

    /** 删除失败注入：整个注销事务回滚，五列与成长快照还原，概览仍可读（等价于原令牌仍可用，需求 12.4）。 */
    private void assertDeletionFailureRollsBack(
            Scenario scenario, long subjectId, long bystanderId,
            List<Map<String, Object>> bystanderEventsBefore,
            List<Map<String, Object>> bystanderProfileBefore,
            List<Map<String, Object>> inviteBefore) {

        User before = userRepository.findById(subjectId).orElseThrow();
        Long idBefore = before.getId();
        String emailBefore = before.getEmail();
        String openidBefore = before.getWxOpenid();
        String nicknameBefore = before.getNickname();
        String inviteCodeBefore = before.getInviteCode();
        List<Map<String, Object>> eventsBefore = eventsSnapshot(subjectId);
        List<Map<String, Object>> profileBefore = profileSnapshot(subjectId);

        // 让「先 growth_events」或「后 user_growth」这一步硬删抛错（真实路径不会失败，靠替身制造）。
        DataIntegrityViolationException boom = new DataIntegrityViolationException("模拟成长数据删除失败");
        if (scenario.injection() == Injection.EVENTS_THROW) {
            doThrow(boom).when(growthEventRepository).deleteByUserId(eq(subjectId));
        } else {
            doThrow(boom).when(userGrowthRepository).deleteByUserId(eq(subjectId));
        }

        assertThatThrownBy(() -> deletionService.deleteAccount(subjectId))
                .as("成长数据删除失败应使注销失败").isInstanceOf(DataIntegrityViolationException.class);

        // 需求 12.4：users 行整体回滚，五列与注销前相同。
        User after = userRepository.findById(subjectId).orElseThrow(
                () -> new AssertionError("注销事务应整体回滚，users 行不应被删除"));
        assertThat(after.getId()).isEqualTo(idBefore);
        assertThat(after.getEmail()).isEqualTo(emailBefore);
        assertThat(after.getWxOpenid()).isEqualTo(openidBefore);
        assertThat(after.getNickname()).isEqualTo(nicknameBefore);
        assertThat(after.getInviteCode()).isEqualTo(inviteCodeBefore);

        // 需求 12.4：两表成长数据逐行快照与注销前相同。
        assertThat(eventsSnapshot(subjectId)).as("回滚后 growth_events 快照不变").isEqualTo(eventsBefore);
        assertThat(profileSnapshot(subjectId)).as("回滚后 user_growth 快照不变").isEqualTo(profileBefore);

        // 需求 12.6、12.7：旁观者与 invite_relations 同样不变。
        assertThat(eventsSnapshot(bystanderId)).isEqualTo(bystanderEventsBefore);
        assertThat(profileSnapshot(bystanderId)).isEqualTo(bystanderProfileBefore);
        assertThat(jdbcTemplate.queryForList(INVITE_SEVEN_COLUMNS)).isEqualTo(inviteBefore);

        // 需求 12.4：注销前持有的令牌仍可成功请求成长概览（服务层等价：概览可正常返回）。
        // 清除注入桩，让概览内部结算走真实仓储。
        reset(growthEventRepository, userGrowthRepository);
        assertThat(queryService.getOverview(subjectId)).as("回滚后成长概览仍可读").isNotNull();
    }

    /** {@code DELETE_BLOCKED_COLLAB}：两表零副作用、用户仍在（需求 12.5）。 */
    private void assertBlockedCollabHasNoSideEffect(
            long subjectId, long bystanderId,
            List<Map<String, Object>> bystanderEventsBefore,
            List<Map<String, Object>> bystanderProfileBefore,
            List<Map<String, Object>> inviteBefore) {

        List<Map<String, Object>> subjectEventsBefore = eventsSnapshot(subjectId);
        List<Map<String, Object>> subjectProfileBefore = profileSnapshot(subjectId);
        seedCollaborativeLedgerWithOtherMember(subjectId);

        assertThatThrownBy(() -> deletionService.requireDeletable(subjectId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("DELETE_BLOCKED_COLLAB"));

        assertNoGrowthSideEffect(subjectId, bystanderId, subjectEventsBefore, subjectProfileBefore,
                bystanderEventsBefore, bystanderProfileBefore, inviteBefore);
    }

    /** 二次验证未通过：两表零副作用、用户仍在（需求 12.5）。 */
    private void assertSecondFactorFailHasNoSideEffect(
            Scenario scenario, long subjectId, String subjectWx, long bystanderId,
            List<Map<String, Object>> bystanderEventsBefore,
            List<Map<String, Object>> bystanderProfileBefore,
            List<Map<String, Object>> inviteBefore) {

        List<Map<String, Object>> subjectEventsBefore = eventsSnapshot(subjectId);
        List<Map<String, Object>> subjectProfileBefore = profileSnapshot(subjectId);

        deletionService.requireDeletable(subjectId); // 协作牵连不成立，前置只读校验通过
        if (scenario.subjectIsWx()) {
            // 纯微信用户：提交与账号 openid 不匹配的一次性 code → WX_LOGIN_FAILED。
            assertThatThrownBy(() -> deletionService.verifySecondFactor(subjectId, null, "wrong-" + subjectWx))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("WX_LOGIN_FAILED"));
        } else {
            // 邮箱用户：验证码校验不通过 → CODE_INVALID。
            CODE_VALID.set(false);
            try {
                assertThatThrownBy(() -> deletionService.verifySecondFactor(subjectId, "000000", null))
                        .isInstanceOf(ApiException.class)
                        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CODE_INVALID"));
            } finally {
                CODE_VALID.set(true);
            }
        }

        assertNoGrowthSideEffect(subjectId, bystanderId, subjectEventsBefore, subjectProfileBefore,
                bystanderEventsBefore, bystanderProfileBefore, inviteBefore);
    }

    /** 前置校验失败两条路径共用的「零副作用」断言。 */
    private void assertNoGrowthSideEffect(
            long subjectId, long bystanderId,
            List<Map<String, Object>> subjectEventsBefore, List<Map<String, Object>> subjectProfileBefore,
            List<Map<String, Object>> bystanderEventsBefore, List<Map<String, Object>> bystanderProfileBefore,
            List<Map<String, Object>> inviteBefore) {

        assertThat(eventsSnapshot(subjectId)).as("前置校验失败后被注销者 growth_events 不变")
                .isEqualTo(subjectEventsBefore);
        assertThat(profileSnapshot(subjectId)).as("前置校验失败后被注销者 user_growth 不变")
                .isEqualTo(subjectProfileBefore);
        assertThat(eventsSnapshot(bystanderId)).isEqualTo(bystanderEventsBefore);
        assertThat(profileSnapshot(bystanderId)).isEqualTo(bystanderProfileBefore);
        assertThat(jdbcTemplate.queryForList(INVITE_SEVEN_COLUMNS)).isEqualTo(inviteBefore);
        assertThat(userRepository.findById(subjectId)).as("前置校验失败后用户行仍在").isPresent();
    }

    /**
     * 断言一名新用户的成长概览为「全新档案」：Lv1、未满级、16 枚徽章均未点亮（需求 12.3、11.21；
     * achievement-system 需求 12.2 把徽章清单从 9 枚扩到 16 枚）。
     */
    private void assertFreshGrowthProfile(long userId) {
        GrowthOverviewResponse overview = queryService.getOverview(userId);
        assertThat(overview.level()).as("重新注册后等级为 1").isEqualTo(1);
        assertThat(overview.exp()).as("重新注册后经验为 0").isZero();
        assertThat(overview.maxLevelReached()).as("重新注册后未满级").isFalse();
        assertThat(overview.totalRecordDays()).as("重新注册后累计记账天数为 0").isZero();
        assertThat(overview.currentStreakDays()).as("重新注册后当前连续天数为 0").isZero();
        assertThat(overview.maxStreakDays()).as("重新注册后最长连续天数为 0").isZero();
        assertThat(overview.badges())
                .as("恒为 16 枚徽章（achievement-system 需求 12.2）").hasSize(16);
        assertThat(overview.badges()).allSatisfy(badge -> {
            assertThat(badge.unlocked()).as("重新注册后徽章 %s 未点亮", badge.code()).isFalse();
            assertThat(badge.unlockedAt()).as("未点亮徽章无解锁时刻").isNull();
        });
    }

    // ---------------- 注册 / 二次验证 ----------------

    private long registerSubject(boolean wx, String email, String wxCode) {
        return wx ? registerWxUser(wxCode) : registerEmailUser(email, null);
    }

    private long registerEmailUser(String email, String inviteCode) {
        LoginOutcome outcome = authService.emailLogin(email, "000000", inviteCode);
        assertThat(outcome.isNewUser()).as("建号路径应新建用户: " + email).isTrue();
        if (inviteCode != null) {
            assertThat(outcome.inviteBind().bound()).as("携带有效邀请码应绑定成功").isTrue();
        }
        return outcome.user().getId();
    }

    private long registerWxUser(String wxCode) {
        LoginOutcome outcome = authService.wxLogin(wxCode, null);
        assertThat(outcome.isNewUser()).as("微信建号路径应新建用户").isTrue();
        return outcome.user().getId();
    }

    /** 二次验证通过：邮箱用户走验证码（CODE_VALID=true），纯微信用户以本人 openid 重新授权。 */
    private void passSecondFactor(boolean wx, long userId, String wxCode) {
        if (wx) {
            deletionService.verifySecondFactor(userId, null, wxCode);
        } else {
            CODE_VALID.set(true);
            deletionService.verifySecondFactor(userId, "000000", null);
        }
    }

    // ---------------- 成长数据播种与快照 ----------------

    /**
     * 直接以 {@link JdbcTemplate} 预置一名用户的成长数据：{@code dailyRecordCount} 条 DAILY_RECORD、
     * {@code budgetMetCount} 条 BUDGET_MET、{@code badgeBits} 位图选中的若干 BADGE 行，外加（有记账时）
     * 一条 FIRST_RECORD 与一条 user_growth 档案行。取值与真实结算产物同构。
     *
     * <p>三个规模参数全为 0 时<b>刻意一行都不写</b>（连档案行也不建），使需求 12.11 的「两表均无行时删除
     * 语句安全执行、影响行数 0 即成功」成为生成空间内的一个真实分支，而不是永远被档案行遮住。</p>
     */
    private void seedGrowth(long userId, int dailyRecordCount, int budgetMetCount, int badgeBits) {
        if (dailyRecordCount == 0 && budgetMetCount == 0 && badgeBits == 0) {
            return;   // 需求 12.11 的空数据分支：两表均无该用户的行
        }
        LocalDateTime ts = LocalDateTime.of(2025, 6, 1, 10, 0);
        long exp = 0;
        LocalDate lastDay = null;

        if (dailyRecordCount > 0) {
            insertEvent(userId, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", 10, ts);
            exp += 10;
        }
        LocalDate day0 = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < dailyRecordCount; i++) {
            LocalDate day = day0.plusDays(i);
            insertEvent(userId, GrowthEventType.DAILY_RECORD, "DAILY_RECORD:" + day, 5, ts);
            exp += 5;
            lastDay = day;
        }
        YearMonth m0 = YearMonth.of(2024, 1);
        for (int j = 0; j < budgetMetCount; j++) {
            YearMonth month = m0.plusMonths(j);
            insertEvent(userId, GrowthEventType.BUDGET_MET, "BUDGET_MET:" + month, 10, ts);
            exp += 10;
        }
        for (String code : BADGE_CODES) {
            int bit = BADGE_INDEX.get(code);
            if ((badgeBits & (1 << bit)) != 0) {
                insertEvent(userId, GrowthEventType.BADGE,
                        GrowthBadgeCatalog.eventKeyOf(code), 0, ts);
            }
        }

        int days = dailyRecordCount;
        jdbcTemplate.update(
                "INSERT INTO user_growth (user_id, exp, level, total_record_days, current_streak_days, "
                        + "max_streak_days, last_record_date, last_settled_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, exp, 1 + Math.min(days, 5), days, Math.min(days, 3), Math.min(days, 3),
                lastDay == null ? null : Date.valueOf(lastDay),
                Timestamp.valueOf(ts), Timestamp.valueOf(ts), Timestamp.valueOf(ts));
    }

    private void insertEvent(long userId, String type, String key, int exp, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, type, key, exp, Timestamp.valueOf(createdAt));
    }

    private long growthEventCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private long userGrowthCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_growth WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private long orphanGrowthEvents() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events ge "
                        + "WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = ge.user_id)", Long.class);
        return n == null ? 0L : n;
    }

    private long orphanUserGrowth() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_growth ug "
                        + "WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = ug.user_id)", Long.class);
        return n == null ? 0L : n;
    }

    private List<Map<String, Object>> eventsSnapshot(long userId) {
        return jdbcTemplate.queryForList(EVENT_COLUMNS + " WHERE user_id = ? ORDER BY id", userId);
    }

    private List<Map<String, Object>> profileSnapshot(long userId) {
        return jdbcTemplate.queryForList(PROFILE_COLUMNS + " WHERE user_id = ?", userId);
    }

    /** 给指定用户造一个「仍有他人成员」的协作账本，触发 {@code DELETE_BLOCKED_COLLAB}。 */
    private void seedCollaborativeLedgerWithOtherMember(long ownerId) {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        Ledger ledger = new Ledger();
        ledger.setUserId(ownerId);
        ledger.setName("协作账本");
        ledger.setType(Ledger.TYPE_COLLABORATIVE);
        ledger.setDefault(false);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        ledger = ledgerRepository.saveAndFlush(ledger);

        LedgerMember other = new LedgerMember();
        other.setLedgerId(ledger.getId());
        other.setUserId(ownerId + 100_000L);   // 任意「他人」id：requireDeletable 只看 user_id != 本人
        other.setRole(LedgerMember.ROLE_EDITOR);
        other.setCreatedAt(now);
        ledgerMemberRepository.saveAndFlush(other);
    }

    // ---------------- 徽章编码位图 ----------------

    private static final List<String> BADGE_CODES = List.of(
            "FIRST_RECORD", "RECORD_10", "RECORD_100", "RECORD_1000",
            "STREAK_7", "STREAK_30", "DAYS_100", "BUDGET_MET", "INVITE_1");
    private static final Map<String, Integer> BADGE_INDEX = Map.ofEntries(
            Map.entry("FIRST_RECORD", 0), Map.entry("RECORD_10", 1), Map.entry("RECORD_100", 2),
            Map.entry("RECORD_1000", 3), Map.entry("STREAK_7", 4), Map.entry("STREAK_30", 5),
            Map.entry("DAYS_100", 6), Map.entry("BUDGET_MET", 7), Map.entry("INVITE_1", 8));

    // ---------------- 测试基础设施 ----------------

    /** 提供一个 {@code @Primary} 的可推进时钟，覆盖 {@code TimeConfig} 的系统时钟，使结算日确定性。 */
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

        void reset(Instant to) {
            this.instant = to;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
