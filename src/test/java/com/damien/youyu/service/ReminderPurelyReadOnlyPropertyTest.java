package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.CustomReminderRepository;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 7：纯增量只读</b>的属性测试（任务 10.3，关联需求 6.8、11.1、11.2）。
 *
 * <p><i>对任意</i>由自定义提醒的 CRUD（{@link ReminderService#create} / {@link ReminderService#update} /
 * {@link ReminderService#delete} / {@link ReminderService#grantQuota} / {@link ReminderService#list}）与
 * 发送编排（{@link ReminderDispatchService#dispatch}）交错组成的操作序列：执行前后，被自定义提醒声明为
 * <b>只读</b>的两张既有表逐行、逐列相等——</p>
 * <ul>
 *   <li>{@code user_growth} 的六项（需求 11.1）：{@code exp} / {@code level} / {@code total_record_days} /
 *       {@code current_streak_days} / {@code max_streak_days} / {@code last_record_date}；</li>
 *   <li>{@code users} 的各列（需求 11.2）：{@code email} / {@code nickname} / {@code invite_code} /
 *       {@code wx_openid} / {@code wx_unionid} / {@code plan} / {@code plan_started_at} /
 *       {@code plan_expires_at} / {@code role} / {@code created_at} / {@code updated_at}。</li>
 * </ul>
 * <p>自定义提醒只读这两张表、绝不写：发送编排只读 {@code user_growth.last_record_date}（判「今日已记账」）
 * 与 {@code users.wx_openid}（判收件地址），一切写入都落在本 spec 新增的 {@code custom_reminders} /
 * {@code reminder_quota} / {@code reminder_send_logs} 三张表——那正是「纯增量」，不在本属性的比对范围内。</p>
 *
 * <h2>如何锁死「只读」</h2>
 * <p>先播种若干用户（含 {@code user_growth} 档案、微信 openid、初始额度），<b>拍一次快照</b>作为基线，
 * 再跑随机操作序列（每步就地 try/catch，业务异常如重复/超限/非法输入不影响不变式），<b>再拍一次快照</b>，
 * 断言两次快照逐字段相等。发送编排里的微信调用由 {@link WeChatClient} / {@link WeChatAccessTokenProvider}
 * 的 Mockito 替身兜住（返回可配置 errcode，含 {@code 43101} 归零分支），不外呼真实微信、不消耗凭证额度。
 * 固定 {@link Clock} 保证判定日与触发时刻可复现。</p>
 *
 * <h2>反向断言（不可选，锁死本属性非空断言）</h2>
 * <p>{@link #reverseAssertion_writingUserGrowthBreaksTheProperty()}：在基线快照之后手工改一行
 * {@code user_growth}（{@code exp += 1}，模拟「将来有人在提醒链路里顺手写一行成长档案」的非增量回归），
 * 前后快照必然分叉、本属性必须失败——证明正向属性不是恒真的空断言。</p>
 *
 * <p>jqwik 属性方法不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 手工完成（上下文缓存复用）。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Feature: custom-reminder, Property 7: 纯增量只读</p>
 * <p>Validates: Requirements 6.8, 11.1, 11.2</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-reminder-readonly-pt;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(ReminderPurelyReadOnlyPropertyTest.FixtureConfig.class)
class ReminderPurelyReadOnlyPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定「今天」：dispatch 的判定日恒为它，播种的最近记账日一律落在它当日或更早。 */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2025, 6, 1);
    /** 固定时钟瞬时：{@link #FIXED_TODAY} 当日 21:00（东八区），CRUD 写入的时间戳可复现。 */
    private static final Instant FIXED_INSTANT = FIXED_TODAY.atTime(21, 0).atZone(ZONE).toInstant();

    /** 全局自增序号：跨迭代给用户 / openid 分配互不相同的取值，迭代间天然互不影响。 */
    private static final AtomicLong SEQ = new AtomicLong(1_710_000_000L);
    /** 微信发送替身返回的 errcode，由每个 DISPATCH 命令临时设定（0 成功 / 非零 / 43101 归零）。 */
    private static final AtomicInteger ERRCODE = new AtomicInteger(0);

    /** 频率原文候选：前三条合法，后两条非法（触发 FREQUENCY_INVALID，仍不得写既有表）。 */
    private static final String[] FREQ = {"DAILY", "WEEKDAY", "WEEKEND", "daily", ""};
    /** 授权次数原文候选：含合法 [1,5] 与非法（0/6/非数字/空），非法仍不得写既有表。 */
    private static final String[] GRANT = {"1", "5", "3", "0", "6", "abc", ""};

    @Autowired private ReminderService reminderService;
    @Autowired private ReminderDispatchService dispatchService;
    @Autowired private CustomReminderRepository customReminderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserGrowthRepository userGrowthRepository;
    @Autowired private WeChatClient weChatClient;
    @Autowired private WeChatAccessTokenProvider accessTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 本次迭代创建的提醒 id（供 UPDATE/DELETE/DISPATCH 命令引用），每 try 复位。 */
    private final List<Long> reminderIds = new ArrayList<>();

    @BeforeTry
    void prepare() throws Exception {
        new TestContextManager(ReminderPurelyReadOnlyPropertyTest.class).prepareTestInstance(this);
        reminderIds.clear();
        ERRCODE.set(0);
        reset(weChatClient, accessTokenProvider);
        when(accessTokenProvider.getToken()).thenReturn("tk-fixed");
        when(weChatClient.sendSubscribeMessage(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> ERRCODE.get());
    }

    @AfterTry
    void resetErrcode() {
        ERRCODE.set(0);
    }

    // ---------------- 生成器 ----------------

    /** 一个用户的播种参数：是否建成长档案、最近记账日偏移、是否绑定 openid、初始额度。 */
    record UserSeed(boolean hasGrowth, int lastRecordOffset, boolean hasOpenid, int initialQuota) {
    }

    /** 一条操作命令（全为整数，jqwik 友好），运行时按取模映射到具体用户 / 提醒 / 参数。 */
    record Command(int kind, int userSel, int freqSel, int hour, int minute, int misc, int extra) {
    }

    @Provide
    Arbitrary<UserSeed> userSeeds() {
        return Combinators.combine(
                Arbitraries.of(true, false),
                Arbitraries.integers().between(0, 6),
                Arbitraries.of(true, false),
                Arbitraries.integers().between(0, 6)
        ).as(UserSeed::new);
    }

    @Provide
    Arbitrary<List<UserSeed>> userSeedLists() {
        return userSeeds().list().ofMinSize(1).ofMaxSize(3);
    }

    @Provide
    Arbitrary<Command> commands() {
        return Combinators.combine(
                Arbitraries.integers().between(0, 5),    // kind: 0..5
                Arbitraries.integers().between(0, 9),    // userSel
                Arbitraries.integers().between(0, 4),    // freqSel
                Arbitraries.integers().between(0, 24),   // hour（含 24 → 非法时间）
                Arbitraries.integers().between(0, 60),   // minute（含 60 → 非法时间）
                Arbitraries.integers().between(0, 9),    // misc（enabled/reminderSel/grantSel 复用）
                Arbitraries.integers().between(0, 2)     // extra（errcode 分支）
        ).as(Command::new);
    }

    @Provide
    Arbitrary<List<Command>> commandLists() {
        return commands().list().ofMinSize(0).ofMaxSize(30);
    }

    // ---------------- Property 7（正向）----------------

    /**
     * Feature: custom-reminder, Property 7: 纯增量只读
     *
     * <p>任意 CRUD 与 dispatch 交错序列执行前后，{@code user_growth} 六项与 {@code users} 各列逐行相等。</p>
     *
     * <p>Validates: Requirements 6.8, 11.1, 11.2</p>
     */
    @Property(tries = 25)
    void crudAndDispatch_neverWriteUserGrowthOrUsers(
            @ForAll("userSeedLists") List<UserSeed> seeds,
            @ForAll("commandLists") List<Command> commandList) {

        List<Long> userIds = seedUsers(seeds);

        List<GrowthRow> growthBefore = snapshotGrowth(userIds);
        List<UserRow> usersBefore = snapshotUsers(userIds);

        runCommands(commandList, userIds);

        assertThat(snapshotGrowth(userIds))
                .as("CRUD/dispatch 前后 user_growth 六项逐行相等（需求 6.8、11.1）")
                .isEqualTo(growthBefore);
        assertThat(snapshotUsers(userIds))
                .as("CRUD/dispatch 前后 users 各列逐行相等（需求 11.2）")
                .isEqualTo(usersBefore);
    }

    // ---------------- Property 7（反向断言，不可选）----------------

    /**
     * 反向断言：若提醒链路顺手改一行 {@code user_growth}，正向属性必须失败——锁死「纯增量只读」。
     *
     * <p>在基线快照之后手工把某用户 {@code exp += 1}（模拟「非只读」回归），前后快照必然分叉。
     * 若这道断言没抛出，说明比对漏掉了 {@code user_growth} 的改动，那样正向属性就是恒真的空断言。</p>
     *
     * <p>Validates: Requirements 11.1</p>
     */
    @Example
    void reverseAssertion_writingUserGrowthBreaksTheProperty() {
        List<Long> userIds = seedUsers(List.of(new UserSeed(true, 0, true, 3)));
        List<GrowthRow> before = snapshotGrowth(userIds);

        // 模拟提醒链路「顺手写一行成长档案」的非增量回归。
        jdbcTemplate.update("UPDATE user_growth SET exp = exp + 1 WHERE user_id = ?", userIds.get(0));

        assertThat(snapshotGrowth(userIds))
                .as("既有表被写后，「纯增量只读」属性必须能观察到分叉")
                .isNotEqualTo(before);
    }

    // ---------------- 命令解释 ----------------

    private void runCommands(List<Command> commandList, List<Long> userIds) {
        for (Command c : commandList) {
            long userId = userIds.get(c.userSel() % userIds.size());
            try {
                switch (c.kind()) {
                    case 0 -> doCreate(userId, c);
                    case 1 -> doUpdate(userId, c);
                    case 2 -> doDelete(userId, c);
                    case 3 -> reminderService.grantQuota(userId, GRANT[c.misc() % GRANT.length]);
                    case 4 -> reminderService.list(userId);
                    case 5 -> doDispatch(c);
                    default -> { /* 不可达 */ }
                }
            } catch (RuntimeException ignored) {
                // 业务异常（重复/超限/非法输入/NOT_FOUND）或发送侧异常均属正常路径，
                // 不变式（既有表不被写）在异常路径下同样必须成立，故就地吞掉、继续下一条。
            }
        }
    }

    private void doCreate(long userId, Command c) {
        String freq = FREQ[c.freqSel() % FREQ.length];
        String time = String.format("%02d:%02d", c.hour(), c.minute());   // 24:xx / xx:60 触发 TIME_INVALID
        Boolean enabled = switch (c.misc() % 3) {
            case 0 -> Boolean.TRUE;
            case 1 -> Boolean.FALSE;
            default -> null;
        };
        ReminderItem item = reminderService.create(userId, freq, time, enabled);
        reminderIds.add(item.reminderId());
    }

    private void doUpdate(long userId, Command c) {
        if (reminderIds.isEmpty()) {
            return;
        }
        Long reminderId = reminderIds.get(c.misc() % reminderIds.size());
        String freq = (c.extra() == 0) ? null : FREQ[c.freqSel() % FREQ.length];
        String time = (c.extra() == 1) ? null : String.format("%02d:%02d", c.hour(), c.minute());
        Boolean enabled = (c.extra() == 2) ? Boolean.FALSE : null;
        reminderService.update(userId, reminderId, freq, time, enabled);
    }

    private void doDelete(long userId, Command c) {
        if (reminderIds.isEmpty()) {
            return;
        }
        int idx = c.misc() % reminderIds.size();
        Long reminderId = reminderIds.get(idx);
        reminderService.delete(userId, reminderId);
        reminderIds.remove(idx);   // 删成功才移除；未删成功（NOT_FOUND）会抛出、不到这行
    }

    private void doDispatch(Command c) {
        if (reminderIds.isEmpty()) {
            return;
        }
        Long reminderId = reminderIds.get(c.misc() % reminderIds.size());
        CustomReminder reminder = customReminderRepository.findById(reminderId).orElse(null);
        if (reminder == null) {
            return;
        }
        // errcode 分支：0 成功（SENT + 扣额度）/ 非零失败 / 43101 归零对齐——都只写新增表。
        ERRCODE.set(switch (c.extra()) {
            case 1 -> 40003;
            case 2 -> 43101;
            default -> 0;
        });
        // now 相对触发时刻：extra==0 落窗口内（到点发送），否则超窗口（SKIPPED_STALE），两条都不得写既有表。
        LocalTime now = (c.extra() == 0)
                ? reminder.getRemindTime()
                : reminder.getRemindTime().plusMinutes(15);
        dispatchService.dispatch(reminder, FIXED_TODAY, now);
    }

    // ---------------- 播种 ----------------

    /** 播种 seeds.size() 个用户（含可选成长档案、openid、额度），返回其自增 id 列表。 */
    private List<Long> seedUsers(List<UserSeed> seeds) {
        List<Long> ids = new ArrayList<>(seeds.size());
        for (UserSeed seed : seeds) {
            long tag = SEQ.getAndIncrement();
            User user = new User();
            user.setNickname("u" + tag);
            user.setWxOpenid(seed.hasOpenid() ? ("openid-" + tag) : null);
            user.setPlan(Plan.FREE);
            user.setRole(Role.USER);
            user.setPlanStartedAt(FIXED_TODAY.atStartOfDay());
            user.setPlanExpiresAt(FIXED_TODAY.plusYears(1).atStartOfDay());
            user.setCreatedAt(FIXED_TODAY.atStartOfDay());
            user.setUpdatedAt(FIXED_TODAY.atStartOfDay());
            long userId = userRepository.saveAndFlush(user).getId();
            ids.add(userId);

            if (seed.hasGrowth()) {
                seedGrowth(userId, seed);
            }
            if (seed.initialQuota() > 0) {
                // 经真实上限累加建额度行（合法上报次数 1..5，多次累加到目标值）。
                int remaining = seed.initialQuota();
                while (remaining > 0) {
                    int step = Math.min(5, remaining);
                    reminderService.grantQuota(userId, Integer.toString(step));
                    remaining -= step;
                }
            }
        }
        return ids;
    }

    private void seedGrowth(long userId, UserSeed seed) {
        LocalDate lastRecord = FIXED_TODAY.minusDays(seed.lastRecordOffset());
        UserGrowth growth = new UserGrowth();
        growth.setUserId(userId);
        growth.setExp(120);
        growth.setLevel(3);
        growth.setTotalRecordDays(15);
        growth.setCurrentStreakDays(4);
        growth.setMaxStreakDays(9);
        growth.setLastRecordDate(lastRecord);
        growth.setCreatedAt(FIXED_TODAY.atStartOfDay());
        growth.setUpdatedAt(FIXED_TODAY.atStartOfDay());
        userGrowthRepository.saveAndFlush(growth);
    }

    // ---------------- 可比快照 ----------------

    /** {@code user_growth} 的六项（需求 11.1），不含时间戳与 {@code user_id} 之外无关列。 */
    private record GrowthRow(long userId, long exp, int level, int totalRecordDays,
                             int currentStreakDays, int maxStreakDays, LocalDate lastRecordDate) {
    }

    /** {@code users} 的各列（需求 11.2）。 */
    private record UserRow(long id, String email, String nickname, String inviteCode, String wxOpenid,
                           String wxUnionid, String plan, LocalDateTime planStartedAt,
                           LocalDateTime planExpiresAt, String role, LocalDateTime createdAt,
                           LocalDateTime updatedAt) {
    }

    private List<GrowthRow> snapshotGrowth(List<Long> userIds) {
        List<GrowthRow> rows = new ArrayList<>();
        for (Long userId : userIds) {
            jdbcTemplate.query(
                    "SELECT user_id, exp, level, total_record_days, current_streak_days, "
                            + "max_streak_days, last_record_date FROM user_growth WHERE user_id = ?",
                    rs -> {
                        rows.add(new GrowthRow(
                                rs.getLong("user_id"), rs.getLong("exp"), rs.getInt("level"),
                                rs.getInt("total_record_days"), rs.getInt("current_streak_days"),
                                rs.getInt("max_streak_days"),
                                rs.getObject("last_record_date", LocalDate.class)));
                    }, userId);
        }
        return rows;
    }

    private List<UserRow> snapshotUsers(List<Long> userIds) {
        List<UserRow> rows = new ArrayList<>();
        for (Long userId : userIds) {
            jdbcTemplate.query(
                    "SELECT id, email, nickname, invite_code, wx_openid, wx_unionid, plan, "
                            + "plan_started_at, plan_expires_at, role, created_at, updated_at "
                            + "FROM users WHERE id = ?",
                    rs -> {
                        rows.add(new UserRow(
                                rs.getLong("id"), rs.getString("email"), rs.getString("nickname"),
                                rs.getString("invite_code"), rs.getString("wx_openid"),
                                rs.getString("wx_unionid"), rs.getString("plan"),
                                rs.getObject("plan_started_at", LocalDateTime.class),
                                rs.getObject("plan_expires_at", LocalDateTime.class),
                                rs.getString("role"),
                                rs.getObject("created_at", LocalDateTime.class),
                                rs.getObject("updated_at", LocalDateTime.class)));
                    }, userId);
        }
        return rows;
    }

    // ---------------- 测试基础设施 ----------------

    /** 固定 {@link Clock} + 微信侧 Mockito 替身（不外呼真实微信、不消耗凭证额度）。 */
    @TestConfiguration
    static class FixtureConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_INSTANT, ZONE);
        }

        @Bean
        @Primary
        WeChatClient mockWeChatClient() {
            return mock(WeChatClient.class);
        }

        @Bean
        @Primary
        WeChatAccessTokenProvider mockWeChatAccessTokenProvider() {
            return mock(WeChatAccessTokenProvider.class);
        }
    }
}
