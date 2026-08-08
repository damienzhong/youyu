package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.ReminderFrequency;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CustomReminderRepository;
import com.damien.youyu.repository.ReminderQuotaRepository;
import com.damien.youyu.repository.ReminderSendLogRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 自定义提醒后端属性测试（任务 10.1）——{@code jqwik}，落在真实 {@code ReminderService} /
 * {@code ReminderDispatchService} 与三张新表（H2 {@code MODE=MySQL}），只把微信侧
 * （{@link WeChatClient} / {@link WeChatAccessTokenProvider}）替身化以计数与控制返回码。
 *
 * <p>覆盖 design.md「Correctness Properties」四条：</p>
 * <ul>
 *   <li><b>Property 1 发送幂等</b>（需求 3.5、6.5、6.6）：对同一 {@code (reminder_id, trigger_date)}
 *       任意次数、任意交错的 {@code dispatch}，{@code SENT} 记录至多 1 条、发送记录总数恰 1 条、
 *       额度至多扣减 1 次；顺序重复触发（进程重启/调度重叠再扫）下微信 {@code subscribeMessage.send}
 *       恰好被调用 1 次（幂等预检短路，需求 6.6 的「不再调用」）。</li>
 *   <li><b>Property 2 额度守恒且有界</b>（需求 5.3、5.5、5.8）：任意「授权上报 / 成功发送扣减」序列后，
 *       {@code remaining} 恒 ∈ {@code [0,50]}，且逐步等于参照模型（授权 {@code min(r+delta,50)}、
 *       扣减 {@code max(r-1,0)}）；并发上报或并发扣减不丢更新。</li>
 *   <li><b>Property 5 追补窗口单调</b>（需求 3.3、3.4、3.7）：{@code remindTime} 落在
 *       {@code [now-10min, now]} 且当日无记录 → 被 {@code findDue} 选中并补发一次；早于 {@code now-10min}
 *       → 不入选、{@code dispatch} 写 {@code SKIPPED_STALE} 不发；晚于 {@code now} → 不入选（本轮不处理）。</li>
 *   <li><b>Property 6 校验优先级确定</b>（需求 1.9）：对同时命中多条拒绝条件的创建输入，返回码恒为
 *       {@code FREQUENCY > TIME > DUPLICATE > LIMIT} 中优先级最高者，且 {@code custom_reminders} 表数据不变。</li>
 * </ul>
 *
 * <h2>驱动与清理</h2>
 * <p>{@code dispatch} / {@code grantQuota} / {@code decrementFloorZero} 均在各自事务内真实提交，观察终态不能靠
 * 测试级事务回滚，故每次迭代前显式清相关表，并用全局自增 {@link #SEQ} 保证 openid 全局唯一。jqwik 属性方法不经
 * {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 手工完成（上下文缓存复用）。
 * 直接调用 {@code @Modifying} 的额度扣减需活动事务，统一走 {@link #txTemplate}。使用独立命名的内存库。</p>
 *
 * <p>Feature: custom-reminder, Properties 1/2/5/6</p>
 * <p>Validates: Requirements 1.9, 3.3, 3.4, 3.5, 3.7, 5.3, 5.5, 5.8, 6.5, 6.6</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-reminder-pt;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 让 sendSubscribeMessage 走「真实发送」分支而非模板未配置的安全降级（本类已把 WeChatClient 替身化，
        // 该配置只是为 dispatch 的发送路径提供一个非空模板 id 的语义完备上下文）。
        "app.wechat.subscribe.reminder-template-id=tmpl-test"
})
class ReminderCorrectnessPropertyTest {

    /** 全局自增序号：保证跨迭代的 openid 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(3_500_000_000L);

    /** 微信 {@code subscribeMessage.send} 调用计数（每次迭代前归零）。 */
    private static final AtomicInteger WECHAT_CALLS = new AtomicInteger();

    /** 微信替身返回的 errcode（默认 0=成功）。 */
    private static volatile int wechatErrcode = 0;

    /** 额度累积上限（需求 5.3）。 */
    private static final int QUOTA_CAP = 50;

    @Autowired
    private ReminderService reminderService;
    @Autowired
    private ReminderDispatchService dispatchService;
    @Autowired
    private CustomReminderRepository reminderRepository;
    @Autowired
    private ReminderQuotaRepository quotaRepository;
    @Autowired
    private ReminderSendLogRepository sendLogRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    /** 微信订阅消息发送替身：只计数并回 {@link #wechatErrcode}，绝不外呼真实微信。 */
    @MockitoBean
    private WeChatClient weChatClient;
    /** 凭证网关替身：dispatch 发送分支取 token 用。 */
    @MockitoBean
    private WeChatAccessTokenProvider accessTokenProvider;

    @BeforeTry
    void prepare() throws Exception {
        new TestContextManager(ReminderCorrectnessPropertyTest.class).prepareTestInstance(this);
        txTemplate = new TransactionTemplate(transactionManager);

        WECHAT_CALLS.set(0);
        wechatErrcode = 0;
        reset(weChatClient, accessTokenProvider);
        when(accessTokenProvider.getToken()).thenReturn("token");
        when(weChatClient.sendSubscribeMessage(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    WECHAT_CALLS.incrementAndGet();
                    return wechatErrcode;
                });

        // 真实提交，清理不靠回滚：每次迭代前硬删本类涉及的全部表（三张新表 + users，均无外键）。
        jdbcTemplate.update("DELETE FROM reminder_send_logs");
        jdbcTemplate.update("DELETE FROM custom_reminders");
        jdbcTemplate.update("DELETE FROM reminder_quota");
        jdbcTemplate.update("DELETE FROM users");
    }

    // ============================ Property 1：发送幂等 ============================

    /**
     * Feature: custom-reminder, Property 1: 发送幂等
     *
     * <p>对同一 {@code (reminder_id, trigger_date)} 派发 2～6 次（顺序或并发）：{@code SENT} 记录至多 1 条、
     * 发送记录总数恰 1 条、额度恰扣减 1 次；顺序重复派发时微信发送恰被调用 1 次（幂等预检短路，需求 6.6）。</p>
     *
     * <p>Validates: Requirements 3.5, 6.5, 6.6</p>
     */
    @Property(tries = 20)
    void property1_dispatchIsIdempotentPerReminderAndTriggerDate(
            @ForAll @IntRange(min = 2, max = 6) int dispatchCount,
            @ForAll boolean concurrent) throws Exception {

        long userId = seedUser("openid-" + SEQ.getAndIncrement());
        seedQuota(userId, 5);
        LocalDate today = LocalDate.of(2025, 6, 16);      // 周一
        LocalTime now = LocalTime.of(12, 0);
        long reminderId = seedReminder(userId, ReminderFrequency.DAILY, now, true);

        Runnable oneDispatch = () -> {
            try {
                CustomReminder r = reminderRepository.findById(reminderId).orElseThrow();
                dispatchService.dispatch(r, today, now);
            } catch (RuntimeException ignored) {
                // 并发下唯一键冲突会使本次事务回滚（UnexpectedRollbackException 等），与调度器 scan 的
                // try/catch 隔离一致：吞掉不影响终态，终态由唯一约束构造性收敛。
            }
        };

        if (concurrent) {
            runConcurrently(dispatchCount, oneDispatch);
        } else {
            for (int i = 0; i < dispatchCount; i++) {
                oneDispatch.run();
            }
        }

        String because = String.format("派发 %d 次 / %s", dispatchCount, concurrent ? "并发" : "顺序");

        assertThat(sentCount(reminderId, today))
                .as("%s：SENT 记录至多 1 条（需求 3.5、6.5，唯一键构造性保证）", because)
                .isEqualTo(1L);
        assertThat(logCount(reminderId, today))
                .as("%s：发送记录总数恰 1 条（重复派发不产生第二条）", because)
                .isEqualTo(1L);
        assertThat(remaining(userId))
                .as("%s：额度恰扣减 1 次（成功发送仅一次，需求 5.5）", because)
                .isEqualTo(4);

        if (!concurrent) {
            // 顺序重复派发（等价进程重启/调度重叠再扫同一触发时刻）：首次发送后幂等预检短路，微信不再被调用（需求 6.6）。
            assertThat(WECHAT_CALLS.get())
                    .as("%s：顺序重复派发下微信 subscribeMessage.send 恰被调用 1 次（需求 6.6）", because)
                    .isEqualTo(1);
        }
    }

    // ======================= Property 2：额度守恒且有界（顺序） =======================

    /**
     * Feature: custom-reminder, Property 2: 额度守恒且有界
     *
     * <p>任意「授权上报（{@code grantedCount}∈[1,5]）/ 成功发送扣减」序列，逐步执行并与参照模型比对：
     * 授权为 {@code min(r+delta,50)}、扣减为 {@code max(r-1,0)}。每步后 DB {@code remaining} 与模型逐一相等，
     * 且恒 ∈ {@code [0,50]}。</p>
     *
     * <p>Validates: Requirements 5.3, 5.5, 5.8</p>
     */
    @Property(tries = 20)
    void property2_quotaStaysBoundedAndMatchesReferenceModel(
            @ForAll("quotaOps") List<Integer> ops) {

        long userId = seedUser("openid-" + SEQ.getAndIncrement());
        LocalDateTime opTime = LocalDateTime.of(2025, 6, 16, 12, 0);

        int model = 0;
        int step = 0;
        for (int op : ops) {
            step++;
            if (op == 0) {
                // 成功发送扣减（需求 5.5）：remaining 减 1 且不小于 0。
                txTemplate.executeWithoutResult(s -> quotaRepository.decrementFloorZero(userId, opTime));
                model = Math.max(0, model - 1);
            } else {
                // 上报授权（需求 5.2、5.3）：min(remaining + delta, 50)。
                reminderService.grantQuota(userId, String.valueOf(op));
                model = Math.min(QUOTA_CAP, model + op);
            }
            int actual = remaining(userId);
            assertThat(actual)
                    .as("第 %d 步（op=%d）后 remaining 恒 ∈ [0,50]（需求 5.3、5.5）", step, op)
                    .isBetween(0, QUOTA_CAP);
            assertThat(actual)
                    .as("第 %d 步（op=%d）后 remaining 与参照模型一致（需求 5.3、5.5、5.8）", step, op)
                    .isEqualTo(model);
        }
    }

    /** 额度操作序列：{@code 0}=成功发送扣减，{@code 1..5}=对应次数的授权上报。长度 0～30。 */
    @Provide
    Arbitrary<List<Integer>> quotaOps() {
        return Arbitraries.integers().between(0, 5).list().ofMinSize(0).ofMaxSize(30);
    }

    // ======================= Property 2：额度并发不丢更新 =======================

    /**
     * Feature: custom-reminder, Property 2: 额度守恒且有界（并发不丢更新）
     *
     * <p>{@code concurrency}∈[2,8] 个线程同时对同一用户操作，取「既不触顶也不触底」的规模，使净和与执行顺序无关：
     * 仅并发授权（各 +1，总和 ≤ 50）→ 终值 = 并发数；仅并发扣减（自 50 起，扣减数 ≤ 50）→ 终值 = 50 − 并发数。
     * 任一都要求原子增减不丢更新（需求 5.8）。</p>
     *
     * <p>Validates: Requirements 5.8</p>
     */
    @Property(tries = 15)
    void property2_concurrentGrantsAndDecrementsLoseNoUpdates(
            @ForAll @IntRange(min = 2, max = 8) int concurrency,
            @ForAll boolean sendsOnly) throws Exception {

        long userId = seedUser("openid-" + SEQ.getAndIncrement());
        LocalDateTime opTime = LocalDateTime.of(2025, 6, 16, 12, 0);

        if (sendsOnly) {
            seedQuota(userId, QUOTA_CAP);       // 自 50 起，扣减不触底
            runConcurrently(concurrency,
                    () -> txTemplate.executeWithoutResult(s -> quotaRepository.decrementFloorZero(userId, opTime)));
            assertThat(remaining(userId))
                    .as("并发 %d 次扣减自 50 起无丢更新，终值 = 50 − %d（需求 5.8）", concurrency, concurrency)
                    .isEqualTo(QUOTA_CAP - concurrency);
        } else {
            // 各 +1，总和 = concurrency ≤ 8 ≤ 50，不触顶。
            runConcurrently(concurrency, () -> reminderService.grantQuota(userId, "1"));
            assertThat(remaining(userId))
                    .as("并发 %d 次授权（各 +1）无丢更新，终值 = %d（需求 5.8）", concurrency, concurrency)
                    .isEqualTo(concurrency);
        }
    }

    // ============================ Property 5：追补窗口单调 ============================

    /**
     * Feature: custom-reminder, Property 5: 追补窗口单调
     *
     * <p>{@code remindTime = now + offset}（offset∈[-30,30] 分钟）。{@code findDue} 当且仅当 offset∈[-10,0] 选中
     * （需求 3.3 下界、3.4 排除陈旧、3.7 排除未来且不预发）。落在窗口内 → {@code dispatch} 补发一次
     * （{@code SENT}）；早于 {@code now-10min} → {@code dispatch} 写 {@code SKIPPED_STALE} 且不发不扣额度。</p>
     *
     * <p>Validates: Requirements 3.3, 3.4, 3.7</p>
     */
    @Property(tries = 25)
    void property5_catchUpWindowIsMonotonic(
            @ForAll @IntRange(min = -30, max = 30) int offsetMinutes) {

        long userId = seedUser("openid-" + SEQ.getAndIncrement());
        seedQuota(userId, 5);
        LocalDate today = LocalDate.of(2025, 6, 16);      // 周一：DAILY 命中
        LocalTime now = LocalTime.of(12, 0);
        LocalTime windowStart = now.minusMinutes(10);     // 11:50，不跨自然日
        LocalTime remindTime = now.plusMinutes(offsetMinutes);
        long reminderId = seedReminder(userId, ReminderFrequency.DAILY, remindTime, true);

        // findDue 选中 ⟺ offset ∈ [-10, 0]（需求 3.3、3.4、3.7）。
        Set<ReminderFrequency> freqs = ReminderFrequencies.matching(today.getDayOfWeek());
        boolean selected = reminderRepository.findDue(freqs, windowStart, now).stream()
                .anyMatch(r -> r.getId().equals(reminderId));
        boolean expectSelected = offsetMinutes >= -10 && offsetMinutes <= 0;
        assertThat(selected)
                .as("offset=%d 分钟：findDue 选中 ⟺ remindTime ∈ [now-10, now]（需求 3.3、3.4、3.7）", offsetMinutes)
                .isEqualTo(expectSelected);

        if (offsetMinutes > 0) {
            // 未来触发时刻：不入选、本轮不处理（需求 3.7 不预发）；调度器不会派发它，故不 dispatch。
            return;
        }

        CustomReminder r = reminderRepository.findById(reminderId).orElseThrow();
        dispatchService.dispatch(r, today, now);

        if (offsetMinutes < -10) {
            // 早于追补窗口下界 → SKIPPED_STALE，不发、不扣额度（需求 3.4）。
            assertThat(logResult(reminderId, today))
                    .as("offset=%d：超窗口写 SKIPPED_STALE（需求 3.4）", offsetMinutes)
                    .isEqualTo("SKIPPED_STALE");
            assertThat(WECHAT_CALLS.get())
                    .as("offset=%d：超窗口不调用微信（需求 3.4）", offsetMinutes).isZero();
            assertThat(remaining(userId))
                    .as("offset=%d：超窗口不扣额度（需求 3.4）", offsetMinutes).isEqualTo(5);
        } else {
            // 落在追补窗口 [now-10, now] 内 → 补发一次（需求 3.3）。
            assertThat(logResult(reminderId, today))
                    .as("offset=%d：窗口内补发 SENT（需求 3.3）", offsetMinutes)
                    .isEqualTo("SENT");
            assertThat(WECHAT_CALLS.get())
                    .as("offset=%d：窗口内恰调用微信 1 次（需求 3.3）", offsetMinutes).isEqualTo(1);
            assertThat(remaining(userId))
                    .as("offset=%d：成功发送扣额度 1（需求 3.3、5.5）", offsetMinutes).isEqualTo(4);
        }
    }

    // ============================ Property 6：校验优先级确定 ============================

    /**
     * Feature: custom-reminder, Property 6: 校验优先级确定
     *
     * <p>构造同时命中多条拒绝条件的创建输入（频率非法 / 时间非法 / 撞重复 / 达上限的任意组合），断言返回码恒为
     * {@code FREQUENCY > TIME > DUPLICATE > LIMIT} 中最高者，且拒绝时 {@code custom_reminders} 行数不变。</p>
     *
     * <p>Validates: Requirements 1.9</p>
     */
    @Property(tries = 16)
    void property6_creationValidationPriorityIsDeterministic(
            @ForAll boolean freqValid,
            @ForAll boolean timeValid,
            @ForAll boolean makeDuplicate,
            @ForAll boolean makeLimit) {

        long userId = seedUser("openid-" + SEQ.getAndIncrement());
        LocalTime dupTime = LocalTime.of(9, 0);           // 达上限时的 10 条含此时刻，用于撞重复

        // 预置存量：达上限时插 10 条（时刻 00:00..09:00）；仅撞重复时插 1 条（09:00）。
        if (makeLimit) {
            for (int h = 0; h < 10; h++) {
                seedReminder(userId, ReminderFrequency.DAILY, LocalTime.of(h, 0), true);
            }
        } else if (makeDuplicate) {
            seedReminder(userId, ReminderFrequency.DAILY, dupTime, true);
        }

        // 目标输入：撞重复时时刻取 09:00（与存量相同），否则取 10:00（不与任何存量相同）。
        String frequency = freqValid ? "DAILY" : "NOPE";
        String remindTime = timeValid ? (makeDuplicate ? "09:00" : "10:00") : "99:99";

        String expectedCode;
        if (!freqValid) {
            expectedCode = "REMINDER_FREQUENCY_INVALID";
        } else if (!timeValid) {
            expectedCode = "REMINDER_TIME_INVALID";
        } else if (makeDuplicate) {
            expectedCode = "REMINDER_DUPLICATE";
        } else if (makeLimit) {
            expectedCode = "REMINDER_LIMIT_EXCEEDED";
        } else {
            expectedCode = null;      // 全部合法且不撞重复不达上限 → 创建成功
        }

        long before = reminderRepository.countByUserId(userId);
        String because = String.format("freqValid=%s,timeValid=%s,dup=%s,limit=%s",
                freqValid, timeValid, makeDuplicate, makeLimit);

        if (expectedCode == null) {
            reminderService.create(userId, frequency, remindTime, true);
            assertThat(reminderRepository.countByUserId(userId))
                    .as("%s：全部合法应创建成功、行数 +1", because).isEqualTo(before + 1);
        } else {
            ApiException ex = catchThrowableOfType(
                    () -> reminderService.create(userId, frequency, remindTime, true), ApiException.class);
            assertThat(ex).as("%s：应被拒绝并抛 ApiException", because).isNotNull();
            assertThat(ex.getCode())
                    .as("%s：返回码恒为 FREQUENCY>TIME>DUPLICATE>LIMIT 中最高者（需求 1.9）", because)
                    .isEqualTo(expectedCode);
            assertThat(reminderRepository.countByUserId(userId))
                    .as("%s：拒绝创建后 custom_reminders 行数不变（需求 1.9）", because).isEqualTo(before);
        }
    }

    // ============================ 播种 / 读取 / 并发辅助 ============================

    /** 建一个仅含 {@code wx_openid} 的最小 users 行，返回其自增 id（dispatch 只读 wx_openid）。 */
    private long seedUser(String openid) {
        User u = new User();
        u.setWxOpenid(openid);
        LocalDateTime now = LocalDateTime.now();
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.saveAndFlush(u).getId();
    }

    /** 直插一行额度（无 CHECK 约束的 H2 生成表，remaining 直接给定）。 */
    private void seedQuota(long userId, int remaining) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update(
                "INSERT INTO reminder_quota(user_id, remaining, created_at, updated_at) VALUES(?,?,?,?)",
                userId, remaining, now, now);
    }

    /** 建一条提醒，返回其自增 id。 */
    private long seedReminder(long userId, ReminderFrequency freq, LocalTime time, boolean enabled) {
        CustomReminder r = new CustomReminder();
        r.setUserId(userId);
        r.setFrequency(freq);
        r.setRemindTime(time);
        r.setEnabled(enabled);
        LocalDateTime now = LocalDateTime.now();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return reminderRepository.saveAndFlush(r).getId();
    }

    private long sentCount(long reminderId, LocalDate day) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reminder_send_logs WHERE reminder_id = ? AND trigger_date = ? "
                        + "AND result = 'SENT'",
                Long.class, reminderId, Date.valueOf(day));
        return n == null ? 0L : n;
    }

    private long logCount(long reminderId, LocalDate day) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reminder_send_logs WHERE reminder_id = ? AND trigger_date = ?",
                Long.class, reminderId, Date.valueOf(day));
        return n == null ? 0L : n;
    }

    private String logResult(long reminderId, LocalDate day) {
        List<String> results = jdbcTemplate.queryForList(
                "SELECT result FROM reminder_send_logs WHERE reminder_id = ? AND trigger_date = ?",
                String.class, reminderId, Date.valueOf(day));
        assertThat(results).as("同一 (reminder_id, trigger_date) 至多一条发送记录").hasSize(1);
        return results.get(0);
    }

    private int remaining(long userId) {
        return quotaRepository.findRemaining(userId).orElse(0);
    }

    /** 一道倒计时门让 {@code concurrency} 个线程尽量同时起跑，并在 2000ms 内全部落定。 */
    private void runConcurrently(int concurrency, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        try {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(done.await(2000, TimeUnit.MILLISECONDS))
                    .as("%d 个并发操作应在 2000ms 内全部落定", concurrency).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
