package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 11：时区无关性</b>的属性测试（任务 8.5）。
 *
 * <p>把 JVM 默认时区依次设为 {@code UTC}、{@code America/New_York}、{@code Australia/Sydney}、
 * {@code Asia/Kolkata}，对<i>同一份记账日历</i>与<i>同一请求时刻</i>：连续记账读取侧的三项——
 * {@link StreakJudgment#todayDone}、{@link StreakJudgment#currentStreakDays} 与
 * {@link GrowthCalendarService#segments} 的段序列——取值<b>逐项不变</b>（需求 1.5）。
 * 判定日只在按 {@code Asia/Shanghai} 折算的 {@code 23:59:59.999} 与次日 {@code 00:00:00.000}
 * 之间切换<b>恰好一次</b>，同一自然日内任意两个时刻算出的判定日相同（需求 1.13）。</p>
 *
 * <h2>为什么与时区无关（构造性）</h2>
 *
 * <p>判定日一律由 {@code LocalDate.now(clock)} 取得，其中 {@code clock} 是固定 {@code Asia/Shanghai}
 * 的 {@link Clock}（生产由 {@code TimeConfig} 注入）。{@code LocalDate.now(Clock)} 只读 {@code Clock}
 * 自带的时区，<b>不读 JVM / 数据库会话 / 操作系统默认时区</b>；三项判定又都是只吃 {@link LocalDate}
 * 的纯函数。因此无论把 JVM 默认时区设成哪个，同一请求时刻算出的判定日、进而三项取值都恒定。
 * 本测试先用「请求时刻在 {@code Asia/Shanghai} 的挂钟自然日」算出一份与任何时区无关的参照，
 * 再在<b>每一个</b>默认时区下重算并逐项断言等于参照——既然对全部四个时区都相等于同一份参照，
 * 它们彼此也必然相等。</p>
 *
 * <h2>本测试同时是「不设 {@code hibernate.jdbc.time_zone}」的回归锁</h2>
 *
 * <p>本项目全库 {@code DATETIME} 列存的都是 {@code Asia/Shanghai} 的挂钟时刻，读写路径<b>刻意不设
 * {@code hibernate.jdbc.time_zone}</b>、挂钟值逐字进出（详见
 * {@link GrowthTimezoneIndependencePropertyTest} 的类级说明，那是数据库侧的回归锁）。连续记账的
 * 读取侧则更进一步——判定完全走 {@code Clock(Asia/Shanghai)} + {@code LocalDate} 层算术，不经
 * {@code Instant + ZoneId} 往返、不经 {@code java.sql.Date/Timestamp} 的默认时区换算。本类锁住的正是
 * 「一旦有人把判定改成读默认时区（如 {@code LocalDate.now()} 不带 {@code Clock}、或引入
 * {@code hibernate.jdbc.time_zone} 之类的时区换算），非 {@code Asia/Shanghai} 的默认时区下判定日就会
 * 整日平移」这条回归——届时本属性在某个默认时区下必然变红。</p>
 *
 * <h2>时区还原与串行执行（必读）</h2>
 *
 * <p>{@link TimeZone#setDefault(TimeZone)} 改的是<b>整个 JVM 的全局默认时区</b>，会污染同一 JVM 内
 * 其它测试。为此本类：① 在 {@link #restoreDefaultTimeZone()}（{@code @AfterTry}，形同 finally）里
 * <b>无条件还原</b>进入本类前捕获的原始默认时区，即便某次 try 在断言处抛出也照常还原；
 * ② <b>必须串行执行</b>——jqwik 默认串行跑各次 try，本项目也未开启任何 surefire / junit-platform
 * 并行配置；一旦将来引入测试并行，本类必须被显式排除或加互斥锁，否则它中途改掉的默认时区会被
 * 并行的其它测试读到。</p>
 *
 * <p>Feature: streak-system, Property 11: 时区无关性</p>
 * <p>Validates: Requirements 1.5, 1.13, 9.15</p>
 */
class StreakTimezonePropertyTest {

    /** 基准时区：判定日一律按这个时区的挂钟自然日计算（与生产注入的 Clock 一致）。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 待轮换的 JVM 默认时区：横跨西半球、南半球与南亚半时区（需求 1.5）。 */
    private static final List<ZoneId> DEFAULT_ZONES = List.of(
            ZoneId.of("UTC"),
            ZoneId.of("America/New_York"),
            ZoneId.of("Australia/Sydney"),
            ZoneId.of("Asia/Kolkata"));

    /** 一天内的时刻，必含当日第一毫秒 {@code 00:00:00.000} 与最后一毫秒 {@code 23:59:59.999}。 */
    private static final List<LocalTime> TIMES_OF_DAY = List.of(
            LocalTime.of(0, 0, 0, 0),
            LocalTime.of(0, 0, 0, 1_000_000),
            LocalTime.of(6, 30, 0),
            LocalTime.of(12, 0, 0),
            LocalTime.of(18, 45, 12),
            LocalTime.of(23, 59, 59, 999_000_000));

    /** 生成器刻意包含的高风险日期：闰日、闰日次日、月末、年末、跨年首日。 */
    private static final List<LocalDate> INTERESTING_DATES = List.of(
            LocalDate.of(2024, 2, 29),   // 闰日
            LocalDate.of(2024, 3, 1),    // 闰日次日
            LocalDate.of(2025, 2, 28),   // 平年 2 月末
            LocalDate.of(2024, 12, 31),  // 年末
            LocalDate.of(2025, 1, 1),    // 跨年首日
            LocalDate.of(2025, 12, 31)); // 年末

    private static final long MIN_EPOCH_DAY = LocalDate.of(2024, 1, 1).toEpochDay();
    private static final long MAX_EPOCH_DAY = LocalDate.of(2025, 12, 31).toEpochDay();

    /** 进入本类前的默认时区，{@code @AfterTry} 无条件还原到它，避免污染同一 JVM 的其它测试。 */
    private static final TimeZone ORIGINAL_TIME_ZONE = TimeZone.getDefault();

    /** 无条件还原默认时区（形同 finally）：即便某次 try 在断言处抛出也执行。 */
    @AfterTry
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(ORIGINAL_TIME_ZONE);
    }

    /** 每次 try 前先归位默认时区，杜绝上一次 try 的残留影响本次的参照计算。 */
    @BeforeTry
    void resetDefaultTimeZone() {
        TimeZone.setDefault(ORIGINAL_TIME_ZONE);
    }

    /** 判定日 = 请求时刻在 {@code Asia/Shanghai} 的挂钟自然日，一律经 {@code Clock(Asia/Shanghai)} 取得。 */
    private static LocalDate judgmentDayAt(LocalDateTime shanghaiWallClock) {
        Instant instant = shanghaiWallClock.atZone(SHANGHAI).toInstant();
        return LocalDate.now(Clock.fixed(instant, SHANGHAI));
    }

    // ---------------- 生成器 ----------------

    @Provide
    Arbitrary<LocalDate> anyDate() {
        Arbitrary<LocalDate> interesting = Arbitraries.of(INTERESTING_DATES);
        Arbitrary<LocalDate> random = Arbitraries.longs().between(MIN_EPOCH_DAY, MAX_EPOCH_DAY)
                .map(LocalDate::ofEpochDay);
        return Arbitraries.oneOf(interesting, random);
    }

    /** 记账日历（长度 0–200，刻意不去重不排序：normalize 与相邻判定要被测到，含空集/单点/跨段）。 */
    @Provide
    Arbitrary<List<LocalDate>> calendars() {
        return anyDate().list().ofMaxSize(200);
    }

    @Provide
    Arbitrary<LocalTime> timesOfDay() {
        return Arbitraries.of(TIMES_OF_DAY);
    }

    @Provide
    Arbitrary<ZoneId> defaultZones() {
        return Arbitraries.of(DEFAULT_ZONES);
    }

    // ---------------- Property 11：三项取值与默认时区无关 ----------------

    /**
     * Feature: streak-system, Property 11: 时区无关性
     *
     * <p>同一日历 + 同一请求时刻下，{@code todayDone} / {@code currentStreakDays} / 段序列三项在四个
     * JVM 默认时区下逐项等于时区无关的参照（需求 1.5）。</p>
     *
     * <p>Validates: Requirements 1.5, 9.15</p>
     */
    @Property(tries = 60)
    void judgmentAndSegmentsAreIndependentOfDefaultTimeZone(
            @ForAll("calendars") List<LocalDate> calendar,
            @ForAll("anyDate") LocalDate requestDay,
            @ForAll("timesOfDay") LocalTime time) {

        LocalDateTime wallClock = requestDay.atTime(time);

        // ── 时区无关的参照：判定日 = 请求时刻的 Asia/Shanghai 挂钟自然日 = requestDay ──────────────
        CalendarScan scan = GrowthCalendarService.scan(calendar);
        LocalDate lastRecordDate = scan.lastDate();
        int currentSegmentDays = scan.currentSegment();
        List<StreakSegmentView> refSegments = GrowthCalendarService.segments(calendar);

        boolean refTodayDone = StreakJudgment.todayDone(lastRecordDate, requestDay);
        int refCurrentStreak =
                StreakJudgment.currentStreakDays(lastRecordDate, currentSegmentDays, requestDay);

        for (ZoneId zone : DEFAULT_ZONES) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));

            LocalDate judgmentDay = judgmentDayAt(wallClock);
            String because = "默认时区 " + zone + " 下判定日与三项取值应与时区无关";

            // 判定日本身与默认时区无关（等于 Asia/Shanghai 挂钟自然日）。
            assertThat(judgmentDay).as(because).isEqualTo(requestDay);

            assertThat(StreakJudgment.todayDone(lastRecordDate, judgmentDay))
                    .as(because).isEqualTo(refTodayDone);
            assertThat(StreakJudgment.currentStreakDays(lastRecordDate, currentSegmentDays, judgmentDay))
                    .as(because).isEqualTo(refCurrentStreak);
            assertThat(GrowthCalendarService.segments(calendar))
                    .as(because).isEqualTo(refSegments);
        }
    }

    // ---------------- Property 11：判定日在午夜切换恰好一次 ----------------

    /**
     * Feature: streak-system, Property 11: 时区无关性
     *
     * <p>在任一默认时区下，判定日只在按 {@code Asia/Shanghai} 折算的 {@code D 23:59:59.999} 与
     * {@code (D+1) 00:00:00.000} 之间切换<b>恰好一次</b>：同一自然日内任意两个时刻的判定日相同，
     * 跨过午夜恰好 +1 天（需求 1.13）。</p>
     *
     * <p>Validates: Requirements 1.13, 1.5</p>
     */
    @Property(tries = 60)
    void judgmentDaySwitchesExactlyOnceAtShanghaiMidnight(
            @ForAll("anyDate") LocalDate day,
            @ForAll("defaultZones") ZoneId zone) {

        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        LocalDate nextDay = day.plusDays(1);

        // 两个精确边界：当日最后一毫秒仍属 D，次日第一毫秒切到 D+1。
        assertThat(judgmentDayAt(day.atTime(23, 59, 59, 999_000_000)))
                .as("默认时区 %s 下 D 的 23:59:59.999 判定日应为 D", zone).isEqualTo(day);
        assertThat(judgmentDayAt(nextDay.atTime(0, 0, 0, 0)))
                .as("默认时区 %s 下 D+1 的 00:00:00.000 判定日应为 D+1", zone).isEqualTo(nextDay);

        // 跨 D 与 D+1 的时序时刻列表，逐格算判定日，断言值变化恰好一次且发生在午夜边界。
        List<LocalDateTime> chronological = new ArrayList<>();
        for (LocalTime t : TIMES_OF_DAY) {
            chronological.add(day.atTime(t));
        }
        for (LocalTime t : TIMES_OF_DAY) {
            chronological.add(nextDay.atTime(t));
        }

        int transitions = 0;
        LocalDate previous = null;
        for (LocalDateTime moment : chronological) {
            LocalDate jd = judgmentDayAt(moment);
            // 判定日只取 D 或 D+1，不出现第三个取值。
            assertThat(jd).as("默认时区 %s 下判定日只应为 D 或 D+1", zone).isIn(day, nextDay);
            if (previous != null && !jd.equals(previous)) {
                transitions++;
                // 每一次变化都必须是「同一自然日内不变、跨午夜 +1」，不出现回退或跳变。
                assertThat(jd).as("判定日切换只能是 D → D+1").isEqualTo(nextDay);
                assertThat(previous).as("判定日切换前应为 D").isEqualTo(day);
            }
            previous = jd;
        }
        assertThat(transitions)
                .as("默认时区 %s 下判定日在 D 与 D+1 之间应恰好切换一次", zone)
                .isEqualTo(1);
    }
}
