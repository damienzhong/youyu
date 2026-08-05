package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * {@link StreakJudgment} 的真值表单元测试（关联需求 1.1、1.2、1.3、1.12；2.1、2.2、2.4、2.7、2.10）。
 *
 * <p>纯静态函数、无外部依赖，故不起 Spring 上下文。以判定日 J 为基准，让最近记账日分别取
 * J、J−1、J−2、J+1 与 {@code null} 五种，对 {@code currentStreakDays} / {@code todayDone} /
 * {@code broken} 三个方法的每一格逐条断言。</p>
 *
 * <p>真值表（{@code currentSegmentDays = 5}）：</p>
 * <pre>
 * lastRecordDate  currentStreakDays  todayDone  broken
 * ----------------------------------------------------
 * J               5                  true       false
 * J−1             5                  false      false
 * J−2             0                  false      true
 * J+1             0                  true       false
 * null            0                  false      false
 * </pre>
 */
class StreakJudgmentTest {

    private static final LocalDate J = LocalDate.of(2024, 6, 15);
    private static final int SEG = 5;

    // ---- 最近记账日 == 判定日 ----

    @Test
    void lastAtJudgmentDay() {
        assertThat(StreakJudgment.currentStreakDays(J, SEG, J)).isEqualTo(SEG);
        assertThat(StreakJudgment.todayDone(J, J)).isTrue();
        assertThat(StreakJudgment.broken(J, J)).isFalse();
    }

    // ---- 最近记账日 == 判定日 − 1（昨天记了今天没记，不算断）----

    @Test
    void lastAtJudgmentDayMinusOne() {
        LocalDate last = J.minusDays(1);
        assertThat(StreakJudgment.currentStreakDays(last, SEG, J)).isEqualTo(SEG);
        assertThat(StreakJudgment.todayDone(last, J)).isFalse();
        assertThat(StreakJudgment.broken(last, J)).isFalse();
    }

    // ---- 最近记账日 == 判定日 − 2（已中断）----

    @Test
    void lastAtJudgmentDayMinusTwo() {
        LocalDate last = J.minusDays(2);
        assertThat(StreakJudgment.currentStreakDays(last, SEG, J)).isZero();
        assertThat(StreakJudgment.todayDone(last, J)).isFalse();
        assertThat(StreakJudgment.broken(last, J)).isTrue();
    }

    // ---- 最近记账日 == 判定日 + 1（时钟偏移/数据异常）----

    @Test
    void lastAtJudgmentDayPlusOne() {
        LocalDate last = J.plusDays(1);
        // 当前连续天数取 0（与成长概览逐项相等，需求 2.3）
        assertThat(StreakJudgment.currentStreakDays(last, SEG, J)).isZero();
        // 今日已完成为真（需求 1.12：晚于判定日返回真）
        assertThat(StreakJudgment.todayDone(last, J)).isTrue();
        assertThat(StreakJudgment.broken(last, J)).isFalse();
    }

    // ---- 最近记账日 == null（记账日历为空，从未开始）----

    @Test
    void lastIsNull() {
        assertThat(StreakJudgment.currentStreakDays(null, SEG, J)).isZero();
        assertThat(StreakJudgment.todayDone(null, J)).isFalse();
        // 从未开始不等于已中断（需求 2.7）
        assertThat(StreakJudgment.broken(null, J)).isFalse();
    }

    // ---- currentStreakDays 的钳制：段天数为负时钳到 0 ----

    @Test
    void currentStreakDaysClampsNegativeSegmentToZero() {
        assertThat(StreakJudgment.currentStreakDays(J, -3, J)).isZero();
    }
}
