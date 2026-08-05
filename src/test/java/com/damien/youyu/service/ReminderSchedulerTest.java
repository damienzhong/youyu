package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.ReminderFrequency;
import com.damien.youyu.repository.CustomReminderRepository;

/**
 * {@link ReminderScheduler#scan()} 的单元测试（任务 7.2，关联需求 3.3、3.4、3.7、3.8、6.7）。
 *
 * <p>调度器是纯编排逻辑：由注入的 {@link Clock}（{@code TimeConfig} 固定 {@code Asia/Shanghai}）取
 * 触发时刻，按当日 {@code DayOfWeek} 算频率集合，调 {@link CustomReminderRepository#findDue} 取到点提醒，
 * 逐条交给 {@link ReminderDispatchService#dispatch}。因此本测试<b>不起 Spring 上下文</b>，直接以 Mockito
 * 替身注入两个依赖 + 固定时钟，聚焦调度器自身三件事：</p>
 *
 * <ul>
 *   <li><b>故障隔离（需求 3.8、6.7）</b>：{@code findDue} 返回多条时，其中一条 {@code dispatch} 抛异常
 *       不得中断整轮——其余每条仍各被 {@code dispatch} 一次，且 {@code scan()} 本身不向外抛。</li>
 *   <li><b>追补窗口边界（需求 3.3、3.7）</b>：{@code findDue} 的下界恒为 {@code max(now-10min, 00:00)}、
 *       上界恒为 {@code now}（截到分钟）。「超窗」由下界夹住、「未来不处理」由上界 {@code now} 夹住——
 *       实际行过滤在 {@code findDue} 的 SQL {@code between} 内，此处断言调度器传入的边界正确。</li>
 *   <li><b>跨自然日不追补（需求 3.3 已知取舍）</b>：{@code now < 00:10} 时下界夹到 {@code 00:00}
 *       （{@code LocalTime.MIN}），不回卷到前一日 23:5x。</li>
 * </ul>
 *
 * <p>「停用不入选」是 {@code findDue} 的 JPQL（{@code enabled = true}）承担、由仓库测试
 * {@code ReminderRepositoryTest} 在真实 H2 上覆盖；调度器只透传 {@code findDue} 的结果、不做二次过滤，
 * 本测试以「返回什么就派发什么、findDue 决定入选集」的契约间接锁定：调度器绝不自行放行停用提醒。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReminderSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock private CustomReminderRepository reminderRepository;
    @Mock private ReminderDispatchService dispatchService;

    @Captor private ArgumentCaptor<CustomReminder> dispatchCaptor;
    @Captor private ArgumentCaptor<LocalDate> todayCaptor;
    @Captor private ArgumentCaptor<LocalTime> nowCaptor;

    /** 以某 {@code Asia/Shanghai} 本地时刻构造固定时钟，复现调度器在该分钟触发。 */
    private ReminderScheduler schedulerAt(LocalDateTime localNow) {
        Clock clock = Clock.fixed(localNow.atZone(ZONE).toInstant(), ZONE);
        return new ReminderScheduler(reminderRepository, dispatchService, clock);
    }

    // ============================================================ 故障隔离（需求 3.8、6.7）

    /** 中间一条 dispatch 抛异常：整轮不中断，其余每条各被派发一次，scan() 本身不外抛。 */
    @Test
    void scan_isolatesFailure_continuesRemainingWhenOneDispatchThrows() {
        LocalDateTime localNow = LocalDateTime.of(2025, 6, 2, 21, 0);   // 周一 21:00
        ReminderScheduler scheduler = schedulerAt(localNow);

        CustomReminder r1 = reminder(1L, 10L, LocalTime.of(21, 0));
        CustomReminder r2 = reminder(2L, 20L, LocalTime.of(20, 58));
        CustomReminder r3 = reminder(3L, 30L, LocalTime.of(20, 55));
        when(reminderRepository.findDue(any(), any(), any())).thenReturn(List.of(r1, r2, r3));
        // 中间一条抛异常（模拟单条派发故障）。
        doThrow(new RuntimeException("boom")).when(dispatchService)
                .dispatch(eq(r2), any(LocalDate.class), any(LocalTime.class));

        assertThatCode(scheduler::scan).doesNotThrowAnyException();

        // 三条都被尝试派发，坏的那条没拖垮前后两条。
        verify(dispatchService, times(1)).dispatch(eq(r1), any(LocalDate.class), any(LocalTime.class));
        verify(dispatchService, times(1)).dispatch(eq(r2), any(LocalDate.class), any(LocalTime.class));
        verify(dispatchService, times(1)).dispatch(eq(r3), any(LocalDate.class), any(LocalTime.class));
    }

    /** 首条即抛异常：后续条目仍照常派发（异常不冒泡截断循环）。 */
    @Test
    void scan_continuesAfterFirstDispatchThrows() {
        LocalDateTime localNow = LocalDateTime.of(2025, 6, 2, 9, 30);
        ReminderScheduler scheduler = schedulerAt(localNow);

        CustomReminder first = reminder(1L, 10L, LocalTime.of(9, 30));
        CustomReminder second = reminder(2L, 20L, LocalTime.of(9, 25));
        when(reminderRepository.findDue(any(), any(), any())).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("first fails")).when(dispatchService)
                .dispatch(eq(first), any(LocalDate.class), any(LocalTime.class));

        assertThatCode(scheduler::scan).doesNotThrowAnyException();

        verify(dispatchService, times(1)).dispatch(eq(second), any(LocalDate.class), any(LocalTime.class));
    }

    // ============================================================ 追补窗口边界（需求 3.3、3.7）

    /** 常规时刻：findDue 下界 = now-10min、上界 = now（截到分钟）；今日与 now 正确透传给 dispatch。 */
    @Test
    void scan_passesWindowStartNowMinus10AndEndNow() {
        LocalDateTime localNow = LocalDateTime.of(2025, 6, 2, 21, 0, 37);   // 含秒，应被截到分钟
        ReminderScheduler scheduler = schedulerAt(localNow);

        CustomReminder due = reminder(1L, 10L, LocalTime.of(20, 55));
        when(reminderRepository.findDue(any(), eq(LocalTime.of(20, 50)), eq(LocalTime.of(21, 0))))
                .thenReturn(List.of(due));

        scheduler.scan();

        // 边界精确：windowStart = 20:50（now-10），end = 21:00（now，秒被截掉）。
        verify(reminderRepository).findDue(any(), eq(LocalTime.of(20, 50)), eq(LocalTime.of(21, 0)));
        // 命中的那条按今日 + now 派发（未来提醒由 end=now 在 findDue 内排除，此处不出现）。
        verify(dispatchService).dispatch(dispatchCaptor.capture(), todayCaptor.capture(), nowCaptor.capture());
        assertThat(dispatchCaptor.getValue()).isSameAs(due);
        assertThat(todayCaptor.getValue()).isEqualTo(LocalDate.of(2025, 6, 2));
        assertThat(nowCaptor.getValue()).isEqualTo(LocalTime.of(21, 0));   // 已截到分钟
    }

    /** now-10 恰为窗口下界：10:00 → 下界 09:50、上界 10:00。 */
    @Test
    void scan_windowStartIsExactlyNowMinusTenMinutes() {
        ReminderScheduler scheduler = schedulerAt(LocalDateTime.of(2025, 6, 2, 10, 0));
        when(reminderRepository.findDue(any(), any(), any())).thenReturn(List.of());

        scheduler.scan();

        verify(reminderRepository).findDue(any(), eq(LocalTime.of(9, 50)), eq(LocalTime.of(10, 0)));
    }

    // ============================================================ 跨自然日不追补（需求 3.3 已知取舍）

    /** now < 00:10：下界夹到 00:00（LocalTime.MIN），不回卷追补前一日 23:5x。 */
    @Test
    void scan_clampsWindowStartToMidnight_whenNowBeforeMinutesTen() {
        ReminderScheduler scheduler = schedulerAt(LocalDateTime.of(2025, 6, 2, 0, 3));
        when(reminderRepository.findDue(any(), any(), any())).thenReturn(List.of());

        scheduler.scan();

        // 00:03 - 10min 会回卷到昨天 23:53，被夹到当日 00:00；上界为 00:03。
        verify(reminderRepository).findDue(any(), eq(LocalTime.MIN), eq(LocalTime.of(0, 3)));
    }

    /** now = 00:00：下界与上界均为 00:00（下界不为负）。 */
    @Test
    void scan_clampsWindowStartToMidnight_atMidnight() {
        ReminderScheduler scheduler = schedulerAt(LocalDateTime.of(2025, 6, 2, 0, 0));
        when(reminderRepository.findDue(any(), any(), any())).thenReturn(List.of());

        scheduler.scan();

        verify(reminderRepository).findDue(any(), eq(LocalTime.MIN), eq(LocalTime.MIN));
    }

    // ============================================================ 频率↔星期几（需求 3.2）

    /** 工作日（周一）：传给 findDue 的频率集合为 {DAILY, WEEKDAY}，不含 WEEKEND。 */
    @Test
    void scan_passesWeekdayFrequencies_onMonday() {
        ReminderScheduler scheduler = schedulerAt(LocalDateTime.of(2025, 6, 2, 8, 0));   // 周一
        when(reminderRepository.findDue(any(), any(), any())).thenReturn(List.of());

        scheduler.scan();

        ArgumentCaptor<Set<ReminderFrequency>> freqs = freqCaptor();
        verify(reminderRepository).findDue(freqs.capture(), any(), any());
        assertThat(freqs.getValue())
                .containsExactlyInAnyOrder(ReminderFrequency.DAILY, ReminderFrequency.WEEKDAY);
    }

    /** 周末（周六）：传给 findDue 的频率集合为 {DAILY, WEEKEND}，不含 WEEKDAY。 */
    @Test
    void scan_passesWeekendFrequencies_onSaturday() {
        ReminderScheduler scheduler = schedulerAt(LocalDateTime.of(2025, 6, 7, 8, 0));   // 周六
        when(reminderRepository.findDue(any(), any(), any())).thenReturn(List.of());

        scheduler.scan();

        ArgumentCaptor<Set<ReminderFrequency>> freqs = freqCaptor();
        verify(reminderRepository).findDue(freqs.capture(), any(), any());
        assertThat(freqs.getValue())
                .containsExactlyInAnyOrder(ReminderFrequency.DAILY, ReminderFrequency.WEEKEND);
    }

    // ============================================================ 空结果不派发

    /** findDue 无到点提醒：不调用任何 dispatch，scan() 静默返回。 */
    @Test
    void scan_dispatchesNothing_whenNoDueReminders() {
        ReminderScheduler scheduler = schedulerAt(LocalDateTime.of(2025, 6, 2, 21, 0));
        when(reminderRepository.findDue(any(), any(), any())).thenReturn(List.of());

        scheduler.scan();

        verify(dispatchService, never()).dispatch(any(), any(), any());
        verifyNoInteractions(dispatchService);
    }

    // ---------------------------------------------------------------- 辅助

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Set<ReminderFrequency>> freqCaptor() {
        return ArgumentCaptor.forClass(Set.class);
    }

    private static CustomReminder reminder(long id, long userId, LocalTime remindTime) {
        CustomReminder reminder = new CustomReminder();
        reminder.setId(id);
        reminder.setUserId(userId);
        reminder.setFrequency(ReminderFrequency.DAILY);
        reminder.setRemindTime(remindTime);
        reminder.setEnabled(true);
        reminder.setCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        reminder.setUpdatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        return reminder;
    }
}
