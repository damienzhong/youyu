package com.damien.youyu.domain;

/**
 * 自定义提醒的重复频率，对应 {@code custom_reminders.frequency} 的三个取值（区分大小写）。
 *
 * <p>取值集合由两处共同锁定，缺一不可：数据库侧的 {@code ck_custom_reminders_frequency}
 * （{@code frequency IN ('DAILY','WEEKDAY','WEEKEND')}，写错取值直接被拒）与应用侧的本枚举。
 * 新增、改名或删除任一取值时两处必须一起改。</p>
 *
 * <ul>
 *   <li>{@link #DAILY}：每天——任一自然日均为触发日。</li>
 *   <li>{@link #WEEKDAY}：工作日——周一至周五为触发日（不接入法定节假日与调休）。</li>
 *   <li>{@link #WEEKEND}：周末——周六与周日为触发日。</li>
 * </ul>
 *
 * <p>Feature: custom-reminder。</p>
 */
public enum ReminderFrequency {
    DAILY,
    WEEKDAY,
    WEEKEND
}
