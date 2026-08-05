package com.damien.youyu.service;

import java.time.LocalDate;

/**
 * 连续记账的三项读取侧判定——「今日已完成 / 当前连续天数 / 是否中断」——的<strong>唯一实现</strong>。
 *
 * <p>三个方法全部为不读时钟、不查库的静态纯函数：判定日一律由调用方以注入的
 * {@code Clock}（{@code TimeConfig} 提供，固定 {@code Asia/Shanghai}）取 {@code LocalDate.now(clock)}
 * 后传入，本类内部不触碰任何时钟、JVM/数据库会话/操作系统默认时区。</p>
 *
 * <p>这三项在两处被消费：成长概览（{@code GrowthQueryService.correctedCurrentStreak}）与
 * 连续记账概览（{@code StreakQueryService.getOverview}）。需求 2.3 要求两处的当前连续天数取值相等、
 * 需求 10.5 要求同名两项相等。做法是把判定收敛到本类，两条读取路径都<strong>委托</strong>它——
 * 相等性因此<strong>构造性成立</strong>，而不是靠两份实现靠测试凑巧对上。任何一处若绕开本类自行判定，
 * 需求 2.3 / 10.5 的相等性就会失去这道构造性保证。</p>
 *
 * <p>Feature: streak-system。覆盖需求 1.1、1.2、1.3、1.12；2.1、2.2、2.3、2.4、2.7、2.10。</p>
 */
public final class StreakJudgment {

    private StreakJudgment() {
        // 纯函数工具类，不允许实例化。
    }

    /**
     * 当前连续天数（沿用 growth-level-system 需求 4.11、4.15 的口径，逐字不变）。
     *
     * <p>最近记账日等于判定日或判定日的前一日时取 {@code max(0, currentSegmentDays)}，其余情形取 0。
     * 「其余情形」<strong>包含</strong>最近记账日晚于判定日的时钟偏移情形——此时同样返回 0，
     * 与成长概览逐项相等（需求 2.3）。</p>
     *
     * @param lastRecordDate     最近记账日；记账日历为空时为 {@code null}
     * @param currentSegmentDays 当前段的段天数（{@code user_growth.current_streak_days}）
     * @param judgmentDay        判定日
     * @return 当前连续天数，落在 {@code [0, maxStreakDays]}
     */
    public static int currentStreakDays(LocalDate lastRecordDate, int currentSegmentDays,
                                        LocalDate judgmentDay) {
        if (lastRecordDate == null) {
            return 0;
        }
        if (lastRecordDate.equals(judgmentDay) || lastRecordDate.equals(judgmentDay.minusDays(1))) {
            return Math.max(0, currentSegmentDays);
        }
        return 0;                                   // 含 lastRecordDate 晚于判定日的时钟偏移情形
    }

    /**
     * 今日已完成：判定日在记账日历中，等价于最近记账日不早于判定日（需求 1.1、1.12）。
     *
     * <p>用 {@code !isBefore} 而不是 {@code equals}：需求 1.1 说「等于判定日」，需求 1.12 又要求
     * 「最近记账日晚于判定日（时钟偏移或数据异常）时今日已完成为真」。{@code !isBefore} 一次覆盖两条，
     * 不需要在服务层再补一个分支。</p>
     *
     * @param lastRecordDate 最近记账日；记账日历为空时为 {@code null}
     * @param judgmentDay    判定日
     * @return 今日是否已完成
     */
    public static boolean todayDone(LocalDate lastRecordDate, LocalDate judgmentDay) {
        return lastRecordDate != null && !lastRecordDate.isBefore(judgmentDay);
    }

    /**
     * 连续中断：日历非空、且最近记账日早于判定日的前一日（需求 2.2、2.7）。
     *
     * <p>日历为空（{@code lastRecordDate == null}）时返回 {@code false}：从未开始不等于已中断。</p>
     *
     * @param lastRecordDate 最近记账日；记账日历为空时为 {@code null}
     * @param judgmentDay    判定日
     * @return 连续是否中断
     */
    public static boolean broken(LocalDate lastRecordDate, LocalDate judgmentDay) {
        return lastRecordDate != null && lastRecordDate.isBefore(judgmentDay.minusDays(1));
    }
}
