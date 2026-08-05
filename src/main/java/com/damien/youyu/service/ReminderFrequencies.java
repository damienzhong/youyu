package com.damien.youyu.service;

import com.damien.youyu.domain.ReminderFrequency;

import java.time.DayOfWeek;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 频率↔星期几映射的<strong>唯一实现</strong>：由某自然日的星期几得出当日命中的
 * {@link ReminderFrequency} 集合，供 {@code ReminderScheduler} 作为
 * {@code CustomReminderRepository.findDue} 的 {@code :freqs} 参数传入。
 *
 * <p>不接入国务院法定节假日与调休安排：{@link ReminderFrequency#WEEKDAY} 恒为周一至周五、
 * {@link ReminderFrequency#WEEKEND} 恒为周六与周日（需求 2.4）。</p>
 *
 * <p>纯函数：判定只依赖传入的 {@link DayOfWeek}，不读时钟、不查库、不触碰 JVM/数据库会话/操作系统
 * 默认时区（需求 2.8）。调用方以注入的 {@code Clock}（{@code TimeConfig} 提供，固定
 * {@code Asia/Shanghai}）取 {@code LocalDate.now(clock).getDayOfWeek()} 后传入，星期几判定因此
 * 不随环境默认时区漂移。</p>
 *
 * <p>Feature: custom-reminder。覆盖需求 2.1、2.2、2.3、2.4、2.8。</p>
 */
public final class ReminderFrequencies {

    private ReminderFrequencies() {
        // 纯函数工具类，不允许实例化。
    }

    /**
     * 给定某自然日的星期几，返回当日命中的频率集合。
     *
     * <p>{@link ReminderFrequency#DAILY} 恒包含（每天都是其触发日）；星期一至星期五额外包含
     * {@link ReminderFrequency#WEEKDAY}；星期六与星期日额外包含 {@link ReminderFrequency#WEEKEND}。
     * 因此返回集合恒含两个元素：{@code DAILY} 与「{@code WEEKDAY} 或 {@code WEEKEND}」之一。</p>
     *
     * @param dayOfWeek 某自然日按 {@code Asia/Shanghai} 折算所得的星期几，不能为 {@code null}
     * @return 当日命中的不可变频率集合（供 {@code findDue} 的 {@code IN} 参数使用）
     * @throws NullPointerException 当 {@code dayOfWeek} 为 {@code null}
     */
    public static Set<ReminderFrequency> matching(DayOfWeek dayOfWeek) {
        if (dayOfWeek == null) {
            throw new NullPointerException("dayOfWeek must not be null");
        }
        EnumSet<ReminderFrequency> freqs = EnumSet.of(ReminderFrequency.DAILY);
        if (isWeekend(dayOfWeek)) {
            freqs.add(ReminderFrequency.WEEKEND);
        } else {
            freqs.add(ReminderFrequency.WEEKDAY);
        }
        return Collections.unmodifiableSet(freqs);
    }

    private static boolean isWeekend(DayOfWeek dayOfWeek) {
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }
}
