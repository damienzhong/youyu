package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.ReminderFrequency;
import com.damien.youyu.domain.ReminderSendLog;
import com.damien.youyu.repository.ReminderQuotaRepository;
import com.damien.youyu.repository.ReminderSendLogRepository;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;

/**
 * {@link ReminderDispatchService#dispatch} 的两条<b>故障注入</b>分支单元测试（任务 6.2，关联需求 4.8、6.6）——
 * 纯 Mockito，全部依赖为替身。
 *
 * <p>这两条分支无法用 {@link ReminderDispatchServiceTest} 的真实单线程 H2 仓储触达，只能靠替身注入：</p>
 * <ul>
 *   <li><b>读 {@code user_growth} 失败兜底 NOT_YET（需求 4.8）</b>：{@code findLastRecordDate} 抛异常时，
 *       {@code done=false}、选「今天还没记账哦~」，仍正常发送、不外抛、不写 {@code user_growth}。真实仓储
 *       对缺失用户只会返回空 {@link Optional}（那是需求 4.6 的「无记录」路径），无法制造「读即抛错」。</li>
 *   <li><b>发送记录唯一键冲突静默放弃（需求 6.6）</b>：幂等预检通过（{@code existsBy=false}）但写入时撞唯一键
 *       （并发触发，另一线程已写）→ 捕 {@link DataIntegrityViolationException} 后放弃本次、不重复报错、
 *       <b>不扣额度</b>。单线程真实仓储里「查得到却写冲突」的竞态无法复现，故用替身让
 *       {@code saveAndFlush} 定向抛冲突。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReminderDispatchServiceFaultTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2025, 6, 1, 21, 0);
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 1);
    private static final LocalTime NOW = LocalTime.of(21, 0);
    private static final LocalTime IN_WINDOW = LocalTime.of(20, 55);

    private static final long USER_ID = 42L;
    private static final long REMINDER_ID = 7L;
    private static final String OPENID = "o-user-openid";
    private static final String TOKEN = "tk-123";

    @Mock private ReminderSendLogRepository sendLogRepository;
    @Mock private ReminderQuotaRepository quotaRepository;
    @Mock private UserGrowthRepository userGrowthRepository;
    @Mock private UserRepository userRepository;
    @Mock private WeChatAccessTokenProvider accessTokenProvider;
    @Mock private WeChatClient weChatClient;

    private ReminderDispatchService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW.atZone(ZONE).toInstant(), ZONE);
        service = new ReminderDispatchService(sendLogRepository, quotaRepository, userGrowthRepository,
                userRepository, accessTokenProvider, weChatClient, clock);
    }

    private CustomReminder reminder() {
        CustomReminder reminder = new CustomReminder();
        reminder.setId(REMINDER_ID);
        reminder.setUserId(USER_ID);
        reminder.setFrequency(ReminderFrequency.DAILY);
        reminder.setRemindTime(IN_WINDOW);
        reminder.setEnabled(true);
        reminder.setCreatedAt(FIXED_NOW);
        reminder.setUpdatedAt(FIXED_NOW);
        return reminder;
    }

    /**
     * 读 {@code user_growth.last_record_date} 抛异常 → 兜底今日未记账、选 NOT_YET 文案，仍正常发送，
     * 不外抛、不写 {@code user_growth}（需求 4.8）。
     */
    @Test
    void dispatch_fallsBackToNotYet_whenGrowthReadThrows() {
        when(sendLogRepository.existsByReminderIdAndTriggerDate(REMINDER_ID, TODAY)).thenReturn(false);
        when(userGrowthRepository.findLastRecordDate(USER_ID))
                .thenThrow(new RuntimeException("db read failed"));
        when(quotaRepository.findRemaining(USER_ID)).thenReturn(Optional.of(3));
        when(userRepository.findWxOpenid(USER_ID)).thenReturn(Optional.of(OPENID));
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(eq(TOKEN), eq(OPENID), eq(ReminderMessageResolver.MSG_NOT_YET)))
                .thenReturn(0);

        assertThatCode(() -> service.dispatch(reminder(), TODAY, NOW)).doesNotThrowAnyException();

        // 兜底选了 NOT_YET 文案且发送成功，记录写 NOT_YET 变体、扣一次额度。
        verify(weChatClient).sendSubscribeMessage(TOKEN, OPENID, ReminderMessageResolver.MSG_NOT_YET);
        verify(sendLogRepository).saveAndFlush(argThatVariant(ReminderDispatchService.VARIANT_NOT_YET));
        verify(quotaRepository).decrementFloorZero(eq(USER_ID), any(LocalDateTime.class));
        // 绝不写 user_growth。
        verify(userGrowthRepository, never()).save(any());
    }

    /**
     * 发送记录写入撞唯一键（并发触发）→ 捕 {@link DataIntegrityViolationException} 后静默放弃：
     * 不外抛、不再次调微信、<b>不扣额度</b>（需求 6.6）。
     */
    @Test
    void dispatch_silentlyAbandons_whenSendLogUniqueKeyConflicts() {
        when(sendLogRepository.existsByReminderIdAndTriggerDate(REMINDER_ID, TODAY)).thenReturn(false);
        when(userGrowthRepository.findLastRecordDate(USER_ID)).thenReturn(Optional.empty());
        when(quotaRepository.findRemaining(USER_ID)).thenReturn(Optional.of(3));
        when(userRepository.findWxOpenid(USER_ID)).thenReturn(Optional.of(OPENID));
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(eq(TOKEN), eq(OPENID), anyString())).thenReturn(0);
        // 并发：另一线程已为同 (reminderId, today) 写入 → 本次写入撞唯一键。
        when(sendLogRepository.saveAndFlush(any(ReminderSendLog.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatCode(() -> service.dispatch(reminder(), TODAY, NOW)).doesNotThrowAnyException();

        // 微信只被调一次；唯一键冲突后不扣额度（写入未成功）。
        verify(weChatClient, times(1)).sendSubscribeMessage(eq(TOKEN), eq(OPENID), anyString());
        verify(quotaRepository, never()).decrementFloorZero(any(), any());
        verify(quotaRepository, never()).zero(any(), any());
    }

    /** Mockito 参数匹配器：断言发送记录的文案变体等于期望值。 */
    private static ReminderSendLog argThatVariant(String expectedVariant) {
        return org.mockito.ArgumentMatchers.argThat(
                log -> log != null && expectedVariant.equals(log.getMessageVariant()));
    }
}
