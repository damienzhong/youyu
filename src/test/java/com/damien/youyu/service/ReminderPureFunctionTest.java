package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.DayOfWeek;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.damien.youyu.domain.ReminderFrequency;

/**
 * 提醒纯函数单元测试（任务 2.3，关联需求 2.1、2.2、2.3、4.1、4.4）。
 *
 * <p>两组无外部依赖的静态纯函数，故不起 Spring 上下文：</p>
 * <ul>
 *   <li>{@link ReminderMessageResolver#pick(boolean)}：值域恰为两条、互斥、逐字符相等（需求 4.1、4.4）。</li>
 *   <li>{@link ReminderFrequencies#matching(DayOfWeek)}：7 天 × 三频率全覆盖——{@code DAILY} 恒命中；
 *       周一至周五含 {@code WEEKDAY}；周六周日含 {@code WEEKEND}（需求 2.1、2.2、2.3）。</li>
 * </ul>
 */
class ReminderPureFunctionTest {

    @Nested
    class MessageResolver {

        // ---- pick(true) 恒为 MSG_DONE，逐字符相等 ----

        @Test
        void pickTrueReturnsDoneMessageCharForChar() {
            assertThat(ReminderMessageResolver.pick(true)).isEqualTo(ReminderMessageResolver.MSG_DONE);
            assertThat(ReminderMessageResolver.pick(true)).isEqualTo("今天已经完成啦~");
        }

        // ---- pick(false) 恒为 MSG_NOT_YET，逐字符相等 ----

        @Test
        void pickFalseReturnsNotYetMessageCharForChar() {
            assertThat(ReminderMessageResolver.pick(false)).isEqualTo(ReminderMessageResolver.MSG_NOT_YET);
            assertThat(ReminderMessageResolver.pick(false)).isEqualTo("今天还没记账哦~");
        }

        // ---- 两条文案互斥（需求 4.4）----

        @Test
        void twoMessagesAreMutuallyExclusive() {
            assertThat(ReminderMessageResolver.pick(true)).isNotEqualTo(ReminderMessageResolver.pick(false));
            assertThat(ReminderMessageResolver.MSG_DONE).isNotEqualTo(ReminderMessageResolver.MSG_NOT_YET);
        }

        // ---- 值域恰为两条，不返回集合以外的任何文案（需求 4.1）----

        @Test
        void rangeIsExactlyTwoMessages() {
            assertThat(ReminderMessageResolver.pick(true))
                    .isIn(ReminderMessageResolver.MSG_DONE, ReminderMessageResolver.MSG_NOT_YET);
            assertThat(ReminderMessageResolver.pick(false))
                    .isIn(ReminderMessageResolver.MSG_DONE, ReminderMessageResolver.MSG_NOT_YET);
        }
    }

    @Nested
    class FrequencyMatching {

        // ---- 7 天全覆盖：DAILY 恒命中 ----

        @ParameterizedTest
        @EnumSource(DayOfWeek.class)
        void dailyAlwaysMatchesEveryDay(DayOfWeek day) {
            assertThat(ReminderFrequencies.matching(day)).contains(ReminderFrequency.DAILY);
        }

        // ---- 周一至周五含 WEEKDAY、不含 WEEKEND ----

        @ParameterizedTest
        @EnumSource(value = DayOfWeek.class,
                names = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"})
        void weekdaysMatchWeekdayNotWeekend(DayOfWeek day) {
            assertThat(ReminderFrequencies.matching(day))
                    .containsExactlyInAnyOrder(ReminderFrequency.DAILY, ReminderFrequency.WEEKDAY);
        }

        // ---- 周六周日含 WEEKEND、不含 WEEKDAY ----

        @ParameterizedTest
        @EnumSource(value = DayOfWeek.class, names = {"SATURDAY", "SUNDAY"})
        void weekendDaysMatchWeekendNotWeekday(DayOfWeek day) {
            assertThat(ReminderFrequencies.matching(day))
                    .containsExactlyInAnyOrder(ReminderFrequency.DAILY, ReminderFrequency.WEEKEND);
        }

        // ---- 返回集合恒含两个元素（DAILY + 其一）----

        @ParameterizedTest
        @EnumSource(DayOfWeek.class)
        void alwaysExactlyTwoFrequencies(DayOfWeek day) {
            Set<ReminderFrequency> freqs = ReminderFrequencies.matching(day);
            assertThat(freqs).hasSize(2).contains(ReminderFrequency.DAILY);
        }

        // ---- null 入参抛 NPE（防御性契约）----

        @Test
        void nullDayOfWeekThrows() {
            assertThatNullPointerException().isThrownBy(() -> ReminderFrequencies.matching(null));
        }
    }
}
