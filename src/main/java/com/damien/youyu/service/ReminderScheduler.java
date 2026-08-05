package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.ReminderFrequency;
import com.damien.youyu.repository.CustomReminderRepository;

/**
 * 提醒调度器（链路 B 的入口，本项目<strong>首个定时任务</strong>，高风险）：每分钟扫描一次「本分钟到点
 * 且落在追补窗口内」的启用提醒，逐条交给 {@link ReminderDispatchService#dispatch} 发送。
 *
 * <h2>触发与时区（需求 3.1、3.2、3.7）</h2>
 * <p>{@code @Scheduled(cron = "5 * * * * *", zone = "Asia/Shanghai")}：每分钟第 5 秒触发，错开整分峰值；
 * 相邻两次启动间隔恒为 60 秒（满足分钟粒度，提醒时间本就是分钟粒度）。当前触发时刻用注入的
 * {@link Clock}（{@code TimeConfig} 固定 {@code Asia/Shanghai}）取，<b>不依赖 JVM 默认时区</b>：
 * {@code today = LocalDate.now(clock)}、{@code now = LocalTime.now(clock).truncatedTo(MINUTES)}。</p>
 *
 * <h2>追补窗口（需求 3.3、3.7）</h2>
 * <p>单轮只处理触发时刻落在 {@code [now-10min, now]} 闭区间的提醒（{@code findDue} 的 {@code between}）。
 * {@code windowStart = max(now-10min, 00:00)}：若 {@code now.minusMinutes(10)} 回卷到前一自然日，
 * 夹到当日 {@code 00:00}——<b>刻意不追补跨自然日</b>（design.md「已知取舍」1），避免每次重启回灌昨日漏发
 * 与「昨天是否为触发日 / 昨天已记账状态」的二义。超窗口的过期提醒由 {@code dispatch} 视作
 * {@code SKIPPED_STALE}（需求 3.4），未来触发时刻的提醒不入选（需求 3.7，不预发）。</p>
 *
 * <h2>频率↔星期几（需求 2、3.2）</h2>
 * <p>由当日 {@code DayOfWeek} 经 {@link ReminderFrequencies#matching} 得命中频率集合，作为
 * {@code findDue} 的 {@code :freqs} 参数在 SQL 侧过滤（走 {@code idx_custom_reminders_enabled_time}）。</p>
 *
 * <h2>故障隔离（需求 3.8、6.7）</h2>
 * <p>逐条 {@code dispatch} 用独立 {@code try/catch} 包裹：单条抛异常只记<b>不含金额/邮箱/令牌</b>的告警日志、
 * 继续处理本轮其余提醒，<b>绝不中断整轮扫描</b>，也绝不向记账/登录/注销/结算等主路径传播。</p>
 *
 * <p>Feature: custom-reminder。覆盖需求 3.1、3.2、3.3、3.5、3.6、3.7、3.8、6.7。</p>
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    /** 追补窗口（需求 3.3、3.7）：触发时刻已过但仍纳入本轮扫描的最长时长，10 分钟。 */
    static final int CATCH_UP_WINDOW_MINUTES = 10;

    private final CustomReminderRepository reminderRepository;
    private final ReminderDispatchService dispatchService;
    private final Clock clock;

    public ReminderScheduler(CustomReminderRepository reminderRepository,
                             ReminderDispatchService dispatchService,
                             Clock clock) {
        this.reminderRepository = reminderRepository;
        this.dispatchService = dispatchService;
        this.clock = clock;
    }

    /**
     * 每分钟扫描到点提醒并逐条派发（顺序见类级 Javadoc）。
     *
     * <p>整轮扫描本身不抛异常：单条派发的异常在循环内就地捕获，保证一条坏数据不拖垮整轮（需求 3.8、6.7）。</p>
     */
    @Scheduled(cron = "5 * * * * *", zone = "Asia/Shanghai")
    public void scan() {
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        LocalTime windowStart = catchUpWindowStart(now);

        Set<ReminderFrequency> freqs = ReminderFrequencies.matching(today.getDayOfWeek());
        List<CustomReminder> due = reminderRepository.findDue(freqs, windowStart, now);

        for (CustomReminder reminder : due) {
            try {
                dispatchService.dispatch(reminder, today, now);
            } catch (RuntimeException ex) {
                // 需求 3.8、6.7：单条故障就地隔离，记告警日志（不含金额/邮箱/令牌），继续下一条，绝不中断整轮。
                log.warn("提醒派发失败，跳过该条继续处理其余, reminderId={}, userId={}",
                        reminder.getId(), reminder.getUserId(), ex);
            }
        }
    }

    /**
     * 追补窗口下界（需求 3.3、3.7）：{@code max(now-10min, 00:00)}。
     *
     * <p>当 {@code now} 早于 {@code 00:10} 时，{@code now.minusMinutes(10)} 会回卷到前一自然日的
     * 23:5x；此时夹到 {@code LocalTime.MIN}（{@code 00:00}），<b>不追补跨自然日</b>。</p>
     */
    private LocalTime catchUpWindowStart(LocalTime now) {
        if (now.isBefore(LocalTime.MIN.plusMinutes(CATCH_UP_WINDOW_MINUTES))) {
            // now < 00:10：减 10 分钟会跨到昨天，夹到当日 00:00，不追补跨自然日。
            return LocalTime.MIN;
        }
        return now.minusMinutes(CATCH_UP_WINDOW_MINUTES);
    }
}
