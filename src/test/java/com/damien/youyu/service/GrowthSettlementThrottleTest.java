package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GrowthSettlementThrottle} 的示例/边界单元测试（关联需求 9.15、10.14）。
 *
 * <p>纯组件、只依赖注入的 {@link Clock}，故不起 Spring 上下文；全部用例由可推进的固定时钟驱动，
 * 不依赖真实时间，因此 10 秒窗口的边界可精确到毫秒断言。覆盖四组事实：</p>
 *
 * <ul>
 *   <li><b>概览侧窗口的半开区间边界</b>：距上次结算 9999ms 判为「最近已结算」应跳过，
 *       恰好 10000ms 即放行（需求 10.14）。</li>
 *   <li><b>进程启动后首次请求必放行</b>：新实例的映射表为空，任何 {@code userId} 首次判定恒放行
 *       （需求 10.14）。</li>
 *   <li><b>两个节流器互不干扰</b>：记账侧的 60 秒窗口<b>根本不在本类里</b>（它读
 *       {@code user_growth.last_settled_at} 列），因此这里用反射断言本类不含任何记账侧状态或
 *       60 秒窗口常量，并断言 10 秒窗口的判定只由 {@link GrowthSettlementThrottle#markSettled}
 *       驱动（需求 9.15）。这条断言是刻意的回归锁：若有人把记账侧的 60 秒窗口「顺手」搬进本类，
 *       多实例部署下该窗口会各自独立放行，本测试必然失败。</li>
 *   <li><b>不同 {@code userId} 互不影响</b>：统计维度是 {@code user_id}（需求 10.14）。</li>
 * </ul>
 */
class GrowthSettlementThrottleTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long USER = 42L;

    private MutableClock clock;
    private GrowthSettlementThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2025-06-01T04:00:00Z"), ZONE);
        throttle = new GrowthSettlementThrottle(clock);
    }

    // ---- 概览侧 10 秒窗口的边界（需求 10.14）----

    /** 半开区间：9999ms 仍在窗口内应跳过，恰好 10000ms 即放行。 */
    @Test
    void overviewWindowSkipsAt9999MillisAndPassesAt10000Millis() {
        throttle.markSettled(USER);

        clock.advance(Duration.ofMillis(GrowthSettlementThrottle.OVERVIEW_WINDOW_MILLIS - 1));
        assertThat(throttle.overviewRecentlySettled(USER))
                .as("距上次结算 9999ms 仍在 10 秒窗口内，应跳过本次结算")
                .isTrue();

        clock.advance(Duration.ofMillis(1));
        assertThat(throttle.overviewRecentlySettled(USER))
                .as("距上次结算恰好 10000ms 已滑出窗口，应放行")
                .isFalse();
    }

    /** 窗口常量就是需求 10.14 的 10 秒，且判定只读不写（连续判定不会自己续窗口）。 */
    @Test
    void overviewWindowIsTenSecondsAndCheckDoesNotRecordTime() {
        assertThat(GrowthSettlementThrottle.OVERVIEW_WINDOW_MILLIS).isEqualTo(10_000L);

        throttle.markSettled(USER);
        clock.advance(Duration.ofMillis(9_000));
        assertThat(throttle.overviewRecentlySettled(USER)).isTrue();

        clock.advance(Duration.ofMillis(1_000));
        assertThat(throttle.overviewRecentlySettled(USER))
                .as("中途的判定不得刷新时刻，否则窗口会被读请求无限续期")
                .isFalse();
    }

    /** 再次 {@code markSettled} 重开窗口。 */
    @Test
    void markSettledRestartsWindow() {
        throttle.markSettled(USER);
        clock.advance(Duration.ofMillis(GrowthSettlementThrottle.OVERVIEW_WINDOW_MILLIS));
        assertThat(throttle.overviewRecentlySettled(USER)).isFalse();

        throttle.markSettled(USER);
        clock.advance(Duration.ofMillis(GrowthSettlementThrottle.OVERVIEW_WINDOW_MILLIS - 1));
        assertThat(throttle.overviewRecentlySettled(USER)).isTrue();
    }

    // ---- 进程启动后首次请求必放行（需求 10.14）----

    @Test
    void firstRequestAfterProcessStartAlwaysPasses() {
        assertThat(throttle.overviewKeyCount())
                .as("新实例的映射表为空，等价于进程刚启动")
                .isZero();

        for (long userId = 1L; userId <= 5L; userId++) {
            assertThat(throttle.overviewRecentlySettled(userId))
                    .as("进程启动后用户 %d 的首次请求必放行", userId)
                    .isFalse();
        }
        assertThat(throttle.overviewKeyCount())
                .as("判定本身不写入映射表")
                .isZero();
    }

    /** 时刻从未被记录时，即使时钟推进很久也一律放行；{@code null} 用户按放行处理。 */
    @Test
    void unknownUserAndNullUserAlwaysPass() {
        clock.advance(Duration.ofDays(30));
        assertThat(throttle.overviewRecentlySettled(USER)).isFalse();

        assertThat(throttle.overviewRecentlySettled(null)).isFalse();
        throttle.markSettled(null);
        assertThat(throttle.overviewKeyCount())
                .as("null 用户不入表")
                .isZero();
    }

    // ---- 两个节流器互不干扰（需求 9.15）----

    /**
     * 记账侧的 60 秒窗口刻意不在本类：本类只有概览侧的一份内存状态与一个 10 秒窗口常量。
     *
     * <p>反射断言而非行为断言，是因为「不存在」只能这样锁住：一旦有人在本类里补一份记账侧的
     * 60 秒内存窗口，字段数、字段名或常量取值必有一项越界。</p>
     */
    @Test
    void classHoldsNoRecordSideThrottleState() {
        // 过滤合成字段：覆盖率插桩会注入 $jacocoData 之类的字段，与被测语义无关。
        Field[] fields = Arrays.stream(GrowthSettlementThrottle.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .toArray(Field[]::new);

        assertThat(Arrays.stream(fields).map(Field::getName))
                .as("只有时钟与概览侧映射表两个实例字段，加上两个常量")
                .containsExactlyInAnyOrder("clock", "overviewLastSettledAt",
                        "OVERVIEW_WINDOW_MILLIS", "MAX_KEYS");

        assertThat(Arrays.stream(fields).map(Field::getName))
                .as("本类不含任何记账侧（record / 60s）状态")
                .noneMatch(name -> name.toLowerCase().contains("record"));
        assertThat(Arrays.stream(GrowthSettlementThrottle.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .map(Method::getName))
                .noneMatch(name -> name.toLowerCase().contains("record"));

        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == long.class) {
                field.setAccessible(true);
                assertThat(getStaticLong(field))
                        .as("本类不得出现记账侧的 60 秒窗口常量：%s", field.getName())
                        .isNotEqualTo(60_000L);
            }
        }
    }

    /** 概览侧窗口只由 {@code markSettled} 驱动：不做记录时窗口恒不成立，做记录后恰好 10 秒。 */
    @Test
    void overviewWindowIsDrivenOnlyByMarkSettled() {
        // 模拟「记账触发的结算」发生了但概览侧未记录：概览请求仍应放行（60 秒窗口与本类无关）。
        clock.advance(Duration.ofSeconds(30));
        assertThat(throttle.overviewRecentlySettled(USER)).isFalse();

        throttle.markSettled(USER);
        clock.advance(Duration.ofSeconds(30));
        assertThat(throttle.overviewRecentlySettled(USER))
                .as("30 秒远超 10 秒窗口，概览侧不受任何 60 秒窗口影响")
                .isFalse();
    }

    // ---- 不同 userId 互不影响（需求 10.14）----

    @Test
    void differentUserIdsDoNotAffectEachOther() {
        throttle.markSettled(USER);

        assertThat(throttle.overviewRecentlySettled(USER)).isTrue();
        assertThat(throttle.overviewRecentlySettled(USER + 1))
                .as("另一个用户有独立窗口")
                .isFalse();

        clock.advance(Duration.ofMillis(5_000));
        throttle.markSettled(USER + 1);

        clock.advance(Duration.ofMillis(5_000));
        assertThat(throttle.overviewRecentlySettled(USER))
                .as("用户 %d 的窗口已满 10 秒", USER)
                .isFalse();
        assertThat(throttle.overviewRecentlySettled(USER + 1))
                .as("用户 %d 的窗口只过了 5 秒", USER + 1)
                .isTrue();
        assertThat(throttle.overviewKeyCount()).isEqualTo(2);
    }

    // ---- 键数达上限时回收已滑出窗口的条目 ----

    @Test
    void purgesExpiredEntriesWhenKeyCountReachesMax() {
        for (int i = 0; i < GrowthSettlementThrottle.MAX_KEYS; i++) {
            throttle.markSettled(1_000_000L + i);
        }
        assertThat(throttle.overviewKeyCount()).isEqualTo(GrowthSettlementThrottle.MAX_KEYS);

        clock.advance(Duration.ofMillis(GrowthSettlementThrottle.OVERVIEW_WINDOW_MILLIS));
        throttle.markSettled(USER);
        assertThat(throttle.overviewKeyCount())
                .as("已滑出窗口的条目被回收，只剩新写入的键")
                .isEqualTo(1);
    }

    private static long getStaticLong(Field field) {
        try {
            return field.getLong(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("读取静态常量失败：" + field.getName(), e);
        }
    }

    // ---- 可推进的时钟 ----

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
