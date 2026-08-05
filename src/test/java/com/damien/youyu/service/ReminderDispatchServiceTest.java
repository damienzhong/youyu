package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.ReminderFrequency;
import com.damien.youyu.domain.ReminderSendLog;
import com.damien.youyu.domain.ReminderSendResult;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.ReminderQuotaRepository;
import com.damien.youyu.repository.ReminderSendLogRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;

/**
 * {@link ReminderDispatchService#dispatch} 的单条发送编排单元测试（任务 6.2，关联需求 3.4、4.5、
 * 5.5、5.6、6.1、6.2、6.3、6.4、6.6）。
 *
 * <p>走 {@code @DataJpaTest} + H2（{@code MODE=MySQL}）连真实的四个仓储（发送记录、额度、成长档案投影、
 * 用户 openid 投影），仅把<b>微信侧</b>（{@link WeChatClient} 与 {@link WeChatAccessTokenProvider}）
 * 换成 Mockito 替身，从而在不外呼真实微信、不消耗凭证额度的前提下，用真实持久化断言幂等、额度扣减、
 * 各 {@code SendResult} 分支与文案变体落库。发送记录唯一键、{@code decrementFloorZero} 的条件更新、
 * {@code zero} 的归零全在真实 H2 连接上生效——用测试替身会把被测机制删掉。</p>
 *
 * <p>无法用真实单线程仓储触达的两条故障分支（读 {@code user_growth} 抛异常兜底 NOT_YET、发送记录写入
 * 撞唯一键静默放弃）另置于纯 Mockito 的 {@link ReminderDispatchServiceFaultTest}——它们要求仓储
 * 「查得到却写冲突」或「读即抛错」，只能用替身注入。</p>
 *
 * <ul>
 *   <li>幂等（需求 6.5、6.6）：同 {@code (reminderId, today)} 重复 dispatch，微信只被调 1 次、发送记录只 1 条。</li>
 *   <li>{@code SKIPPED_NO_QUOTA}（需求 6.2）：额度为 0 → 不发、额度不动。</li>
 *   <li>{@code SKIPPED_NO_QUOTA}（需求 6.3）：{@code wx_openid} 为空 → 不发、额度不动、不报错。</li>
 *   <li>{@code SKIPPED_STALE}（需求 3.4）：{@code remindTime} 超追补窗口 → 不发、额度不动。</li>
 *   <li>{@code SENT}（需求 6.1、5.5）：{@code errcode=0} → 记 SENT、额度 -1。</li>
 *   <li>{@code FAILED}（需求 6.4）：非零 errcode → 记 FAILED（存 errcode）、额度不动。</li>
 *   <li>{@code 43101}（需求 5.6）：用户拒收/无额度 → 记 FAILED、额度归零。</li>
 *   <li>文案变体（需求 4.2、4.3）：今日已记账→DONE，否则→NOT_YET。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReminderDispatchService.class)
class ReminderDispatchServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2025, 6, 1, 21, 0);

    /** 触发日与触发时刻由 dispatch 参数显式传入（不取时钟），此处固定复现。 */
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 1);
    private static final LocalTime NOW = LocalTime.of(21, 0);
    /** 窗口内触发时刻（now-5min，未超 10 分钟追补窗口）。 */
    private static final LocalTime IN_WINDOW = LocalTime.of(20, 55);
    /** 超窗口触发时刻（now-11min，早于 now-10min 下界）。 */
    private static final LocalTime STALE = LocalTime.of(20, 49);

    private static final String OPENID = "o-user-openid";
    private static final String TOKEN = "tk-123";

    @Autowired private ReminderDispatchService service;
    @Autowired private ReminderSendLogRepository sendLogRepository;
    @Autowired private ReminderQuotaRepository quotaRepository;
    @Autowired private WeChatClient weChatClient;
    @Autowired private WeChatAccessTokenProvider accessTokenProvider;
    @Autowired private TestEntityManager em;

    /**
     * 微信侧替身为 Spring 单例 bean，跨测试方法复用，Mockito 的交互历史会累积。每个测试前重置，
     * 使 {@code verify(times(1))} / {@code verifyNoInteractions} 只针对本方法的调用计数。
     */
    @BeforeEach
    void resetMocks() {
        reset(weChatClient, accessTokenProvider);
    }

    @TestConfiguration
    static class MockWeChatConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_NOW.atZone(ZONE).toInstant(), ZONE);
        }

        @Bean
        WeChatClient weChatClient() {
            return mock(WeChatClient.class);
        }

        @Bean
        WeChatAccessTokenProvider weChatAccessTokenProvider() {
            return mock(WeChatAccessTokenProvider.class);
        }
    }

    // ============================================================ 幂等（需求 6.5、6.6）

    /** 同 (reminderId, today) 重复 dispatch：微信只发一次、发送记录只一条、额度只扣一次。 */
    @Test
    void dispatch_isIdempotent_sendsOnceOnRepeat() {
        long userId = newUser(OPENID);
        CustomReminder reminder = newReminder(userId, IN_WINDOW);
        quotaRepository.addCapped(userId, 3, FIXED_NOW);
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(anyString(), eq(OPENID), anyString())).thenReturn(0);
        clearContext();

        service.dispatch(reminder, TODAY, NOW);
        service.dispatch(reminder, TODAY, NOW);   // 第二次应被幂等预检短路

        verify(weChatClient, times(1)).sendSubscribeMessage(anyString(), eq(OPENID), anyString());
        List<ReminderSendLog> logs = sendLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getResult()).isEqualTo(ReminderSendResult.SENT);
        assertThat(remaining(userId)).isEqualTo(2);   // 只扣一次
    }

    // ============================================================ SKIPPED_NO_QUOTA（需求 6.2、6.3）

    /** 额度为 0：不调微信、记 SKIPPED_NO_QUOTA、额度保持 0。 */
    @Test
    void dispatch_skipsNoQuota_whenRemainingZero() {
        long userId = newUser(OPENID);
        CustomReminder reminder = newReminder(userId, IN_WINDOW);
        quotaRepository.addCapped(userId, 0, FIXED_NOW);   // 建行但剩余 0
        clearContext();

        service.dispatch(reminder, TODAY, NOW);

        verifyNoInteractions(weChatClient);
        assertSingleLog(ReminderSendResult.SKIPPED_NO_QUOTA, null);
        assertThat(remaining(userId)).isZero();
    }

    /** wx_openid 为空：不调微信、记 SKIPPED_NO_QUOTA、额度不动、不报错。 */
    @Test
    void dispatch_skipsNoQuota_whenOpenidMissing() {
        long userId = newUser(null);                       // 纯邮箱用户，无 openid
        CustomReminder reminder = newReminder(userId, IN_WINDOW);
        quotaRepository.addCapped(userId, 5, FIXED_NOW);
        clearContext();

        service.dispatch(reminder, TODAY, NOW);

        verifyNoInteractions(weChatClient);
        assertSingleLog(ReminderSendResult.SKIPPED_NO_QUOTA, null);
        assertThat(remaining(userId)).isEqualTo(5);        // 额度未动
    }

    // ============================================================ SKIPPED_STALE（需求 3.4）

    /** 触发时刻超追补窗口：不调微信、记 SKIPPED_STALE、额度不动。 */
    @Test
    void dispatch_skipsStale_whenBeyondCatchUpWindow() {
        long userId = newUser(OPENID);
        CustomReminder reminder = newReminder(userId, STALE);   // 20:49 < now-10min(20:50)
        quotaRepository.addCapped(userId, 5, FIXED_NOW);
        clearContext();

        service.dispatch(reminder, TODAY, NOW);

        verifyNoInteractions(weChatClient);
        assertSingleLog(ReminderSendResult.SKIPPED_STALE, null);
        assertThat(remaining(userId)).isEqualTo(5);
    }

    // ============================================================ SENT + 扣减（需求 6.1、5.5）

    /** errcode=0：记 SENT（errcode 0）、额度 -1。 */
    @Test
    void dispatch_sendsAndDecrements_whenErrcodeZero() {
        long userId = newUser(OPENID);
        CustomReminder reminder = newReminder(userId, IN_WINDOW);
        quotaRepository.addCapped(userId, 3, FIXED_NOW);
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(eq(TOKEN), eq(OPENID), anyString())).thenReturn(0);
        clearContext();

        service.dispatch(reminder, TODAY, NOW);

        verify(weChatClient, times(1)).sendSubscribeMessage(eq(TOKEN), eq(OPENID), anyString());
        assertSingleLog(ReminderSendResult.SENT, 0);
        assertThat(remaining(userId)).isEqualTo(2);
    }

    // ============================================================ FAILED 不扣（需求 6.4）

    /** 非零 errcode（非 43101）：记 FAILED 并存 errcode、额度不动。 */
    @Test
    void dispatch_failsWithoutDecrement_whenNonZeroErrcode() {
        long userId = newUser(OPENID);
        CustomReminder reminder = newReminder(userId, IN_WINDOW);
        quotaRepository.addCapped(userId, 3, FIXED_NOW);
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(eq(TOKEN), eq(OPENID), anyString())).thenReturn(40003);
        clearContext();

        service.dispatch(reminder, TODAY, NOW);

        assertSingleLog(ReminderSendResult.FAILED, 40003);
        assertThat(remaining(userId)).isEqualTo(3);        // 失败不扣
    }

    // ============================================================ 43101 归零（需求 5.6）

    /** errcode=43101（用户拒收/无额度）：记 FAILED 并存 43101、额度归零对齐。 */
    @Test
    void dispatch_zerosQuota_whenErrcode43101() {
        long userId = newUser(OPENID);
        CustomReminder reminder = newReminder(userId, IN_WINDOW);
        quotaRepository.addCapped(userId, 5, FIXED_NOW);
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(eq(TOKEN), eq(OPENID), anyString())).thenReturn(43101);
        clearContext();

        service.dispatch(reminder, TODAY, NOW);

        assertSingleLog(ReminderSendResult.FAILED, 43101);
        assertThat(remaining(userId)).isZero();            // 归零对齐
    }

    // ============================================================ 文案变体（需求 4.2、4.3）

    /** 今日已记账（last_record_date == today）：选 DONE 文案，变体落库 DONE。 */
    @Test
    void dispatch_picksDoneVariant_whenRecordedToday() {
        long userId = newUser(OPENID);
        CustomReminder reminder = newReminder(userId, IN_WINDOW);
        quotaRepository.addCapped(userId, 3, FIXED_NOW);
        seedGrowth(userId, TODAY);                          // 今日已记账
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(eq(TOKEN), eq(OPENID), eq(ReminderMessageResolver.MSG_DONE)))
                .thenReturn(0);
        clearContext();

        service.dispatch(reminder, TODAY, NOW);

        verify(weChatClient).sendSubscribeMessage(TOKEN, OPENID, ReminderMessageResolver.MSG_DONE);
        assertSingleLog(ReminderSendResult.SENT, 0);
        assertThat(sendLogRepository.findAll().get(0).getMessageVariant())
                .isEqualTo(ReminderDispatchService.VARIANT_DONE);
    }

    /** 无成长档案（今日未记账）：选 NOT_YET 文案，变体落库 NOT_YET。 */
    @Test
    void dispatch_picksNotYetVariant_whenNotRecordedToday() {
        long userId = newUser(OPENID);
        CustomReminder reminder = newReminder(userId, IN_WINDOW);
        quotaRepository.addCapped(userId, 3, FIXED_NOW);   // 不建 user_growth 行
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(eq(TOKEN), eq(OPENID), eq(ReminderMessageResolver.MSG_NOT_YET)))
                .thenReturn(0);
        clearContext();

        service.dispatch(reminder, TODAY, NOW);

        verify(weChatClient).sendSubscribeMessage(TOKEN, OPENID, ReminderMessageResolver.MSG_NOT_YET);
        assertThat(sendLogRepository.findAll().get(0).getMessageVariant())
                .isEqualTo(ReminderDispatchService.VARIANT_NOT_YET);
    }

    // ---------------------------------------------------------------- 辅助

    /** 断言恰有一条发送记录且结果/errcode 符合预期。 */
    private void assertSingleLog(ReminderSendResult expectedResult, Integer expectedErrcode) {
        List<ReminderSendLog> logs = sendLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getResult()).isEqualTo(expectedResult);
        assertThat(logs.get(0).getWxErrcode()).isEqualTo(expectedErrcode);
    }

    private int remaining(long userId) {
        em.clear();
        return quotaRepository.findRemaining(userId).orElse(-1);
    }

    /** 建一个用户并返回其自增 id；openid 为 null 时不绑定微信（纯邮箱用户）。 */
    private long newUser(String openid) {
        User user = new User();
        user.setWxOpenid(openid);
        user.setPlan(Plan.FREE);
        user.setRole(Role.USER);
        user.setPlanStartedAt(FIXED_NOW);
        user.setPlanExpiresAt(FIXED_NOW.plusYears(1));
        user.setCreatedAt(FIXED_NOW);
        user.setUpdatedAt(FIXED_NOW);
        return em.persistAndFlush(user).getId();
    }

    private CustomReminder newReminder(long userId, LocalTime remindTime) {
        CustomReminder reminder = new CustomReminder();
        reminder.setUserId(userId);
        reminder.setFrequency(ReminderFrequency.DAILY);
        reminder.setRemindTime(remindTime);
        reminder.setEnabled(true);
        reminder.setCreatedAt(FIXED_NOW);
        reminder.setUpdatedAt(FIXED_NOW);
        return em.persistAndFlush(reminder);
    }

    private void seedGrowth(long userId, LocalDate lastRecordDate) {
        UserGrowth growth = new UserGrowth();
        growth.setUserId(userId);
        growth.setExp(0);
        growth.setLevel(1);
        growth.setTotalRecordDays(1);
        growth.setCurrentStreakDays(1);
        growth.setMaxStreakDays(1);
        growth.setLastRecordDate(lastRecordDate);
        growth.setCreatedAt(FIXED_NOW);
        growth.setUpdatedAt(FIXED_NOW);
        em.persistAndFlush(growth);
    }

    /** 清空持久化上下文，确保后续查询读到落库事实而非一级缓存。 */
    private void clearContext() {
        em.flush();
        em.clear();
    }
}
