package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import com.damien.youyu.domain.ReminderFrequency;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * 提醒两组无依赖静态纯函数的属性测试（任务 10.2）：
 * <b>Property 3（文案二选一）</b>与 <b>Property 4（频率↔星期几且时区稳定）</b>。
 *
 * <h2>Property 3：文案二选一（需求 4.1、4.4）</h2>
 * <p>{@link ReminderMessageResolver#pick(boolean)} 的值域恰为
 * {@code {「今天已经完成啦~」,「今天还没记账哦~」}}，且 {@code pick(true) ≠ pick(false)}，
 * 两条文案逐字符固定。对任意布尔输入断言：pick 只落在这两条里、为真恒等于 {@code MSG_DONE}、
 * 为假恒等于 {@code MSG_NOT_YET}、二者互斥且逐字符与字面量相等。</p>
 *
 * <h2>Property 4：频率↔星期几且时区稳定（需求 2.1、2.2、2.3、2.7）</h2>
 * <p>{@link ReminderFrequencies#matching(DayOfWeek)} 对任意自然日：{@code DAILY} 恒命中、
 * {@code WEEKDAY ⟺ MON..FRI}、{@code WEEKEND ⟺ SAT/SUN}，且返回集合恒为两元素。
 * 时区稳定性：把 <b>JVM 默认时区</b>轮换为任一其它时区（{@code UTC}、{@code America/New_York}、
 * {@code Asia/Tokyo}、{@code Pacific/Kiritimati}、{@code Asia/Shanghai}），同一自然日的星期几派生与
 * 频率判定结果与时区无关地保持不变——由「{@link LocalDate} 无时区、其
 * {@link LocalDate#getDayOfWeek()} 与 {@code matching} 均为纯函数」构造性成立。</p>
 *
 * <h2>并发与时区还原（必读）</h2>
 * <p>{@link TimeZone#setDefault(TimeZone)} 改的是<b>整个 JVM 的全局默认时区</b>，会污染同一 JVM 内
 * 其它测试。为此本测试类：</p>
 * <ul>
 *   <li><b>必须串行执行</b>，且<b>不得与其它测试类并行</b>。jqwik 的 {@code @Property} 默认串行执行
 *       各次 try，本项目也未开启任何 surefire / junit-platform 并行配置；一旦将来引入测试并行，
 *       本类（连同 {@code GrowthTimezoneIndependencePropertyTest}）必须被显式排除或加互斥锁，
 *       否则它在某次 try 中途改掉的默认时区会被并行的其它测试读到。</li>
 *   <li>在 {@link #restoreDefaultTimeZone()}（{@code @AfterTry}，形同 finally）里<b>无条件还原</b>
 *       进入本类前捕获的原始默认时区 {@link #ORIGINAL_TIME_ZONE}，即便某次 try 在断言处抛出也照常还原。</li>
 * </ul>
 *
 * <p>两组函数都无外部依赖，故走纯 jqwik，不引入 Spring 上下文、不落库、不 mock。</p>
 *
 * <p>Feature: custom-reminder, Property 3: 文案二选一; Property 4: 频率↔星期几且时区稳定</p>
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.7, 4.1, 4.4</p>
 */
class ReminderMessageAndFrequencyPropertyTest {

    /** 文案的两条字面量（与被测常量脱钩，独立写死一份作为参照，防止有人同时改常量与断言）。 */
    private static final String LITERAL_DONE = "今天已经完成啦~";
    private static final String LITERAL_NOT_YET = "今天还没记账哦~";

    /** 分段构造日期范围：跨闰年闰日，覆盖足够多的星期几分布。 */
    private static final long EPOCH_MIN = LocalDate.of(2000, 1, 1).toEpochDay();
    private static final long EPOCH_MAX = LocalDate.of(2035, 12, 31).toEpochDay();

    /** 待轮换的 JVM 默认时区：横跨西半球、东半球与国际日期变更线以东。 */
    private static final List<ZoneId> ZONES = List.of(
            ZoneId.of("UTC"),
            ZoneId.of("America/New_York"),
            ZoneId.of("Asia/Tokyo"),
            ZoneId.of("Pacific/Kiritimati"),
            ZoneId.of("Asia/Shanghai"));

    /** 进入本类前的默认时区，{@code @AfterTry} 无条件还原到它，避免污染同一 JVM 的其它测试。 */
    private static final TimeZone ORIGINAL_TIME_ZONE = TimeZone.getDefault();

    /** 无条件还原默认时区（形同 finally）：即便某次 try 在断言处抛出也执行。 */
    @AfterTry
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(ORIGINAL_TIME_ZONE);
    }

    // ---------------- 生成器 ----------------

    @Provide
    Arbitrary<LocalDate> anyDate() {
        return Arbitraries.longs().between(EPOCH_MIN, EPOCH_MAX).map(LocalDate::ofEpochDay);
    }

    // ---------------- Property 3：文案二选一 ----------------

    /**
     * Feature: custom-reminder, Property 3: 文案二选一
     *
     * <p>对任意布尔输入，{@code pick} 的取值恰落在两条文案集合内，为真取 {@code MSG_DONE}、为假取
     * {@code MSG_NOT_YET}，二者逐字符固定且互斥（需求 4.1、4.4）。</p>
     *
     * <p>Validates: Requirements 4.1, 4.4</p>
     */
    @Property
    void property3_pickIsExactlyOneOfTwoFixedMessages(@ForAll boolean todayRecorded) {
        String picked = ReminderMessageResolver.pick(todayRecorded);

        // 值域恰为两条。
        assertThat(picked)
                .as("pick 的取值必落在两条固定文案集合内")
                .isIn(ReminderMessageResolver.MSG_DONE, ReminderMessageResolver.MSG_NOT_YET);

        // 按输入互斥选择，且逐字符与独立写死的字面量相等。
        if (todayRecorded) {
            assertThat(picked)
                    .as("已记账应逐字符等于「今天已经完成啦~」")
                    .isEqualTo(ReminderMessageResolver.MSG_DONE)
                    .isEqualTo(LITERAL_DONE);
        } else {
            assertThat(picked)
                    .as("未记账应逐字符等于「今天还没记账哦~」")
                    .isEqualTo(ReminderMessageResolver.MSG_NOT_YET)
                    .isEqualTo(LITERAL_NOT_YET);
        }

        // pick(true) ≠ pick(false)，两条文案彼此不同。
        assertThat(ReminderMessageResolver.pick(true))
                .as("pick(true) 与 pick(false) 必不相等")
                .isNotEqualTo(ReminderMessageResolver.pick(false));
    }

    // ---------------- Property 4：频率↔星期几且时区稳定 ----------------

    /**
     * Feature: custom-reminder, Property 4: 频率↔星期几且时区稳定
     *
     * <p>对任意自然日：{@code DAILY} 恒命中、{@code WEEKDAY ⟺ MON..FRI}、{@code WEEKEND ⟺ SAT/SUN}、
     * 返回集合恒为两元素；且把 JVM 默认时区轮换为任一其它时区，同一自然日的星期几派生与频率判定结果
     * 保持不变（需求 2.1、2.2、2.3、2.7）。</p>
     *
     * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.7</p>
     */
    @Property
    void property4_frequencyMatchesWeekdayAndIsTimeZoneStable(@ForAll("anyDate") LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();

        // 与被测脱钩的独立参照：只按星期几区间判定，不复用 matching 的实现。
        boolean isWeekendRef = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        Set<ReminderFrequency> reference = EnumSet.of(ReminderFrequency.DAILY);
        reference.add(isWeekendRef ? ReminderFrequency.WEEKEND : ReminderFrequency.WEEKDAY);

        // 在每个 JVM 默认时区下，星期几派生与频率判定都与时区无关地等于参照。
        for (ZoneId zone : ZONES) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));

            // LocalDate 无时区：其星期几不随 JVM 默认时区漂移（需求 2.6、2.7 的底座）。
            assertThat(date.getDayOfWeek())
                    .as("默认时区 %s 下，同一自然日的星期几不应改变", zone)
                    .isEqualTo(dow);

            Set<ReminderFrequency> actual = ReminderFrequencies.matching(dow);

            // DAILY 恒命中；集合恒为两元素。
            assertThat(actual)
                    .as("默认时区 %s 下，%s 的命中频率应为两元素且含 DAILY", zone, dow)
                    .hasSize(2)
                    .contains(ReminderFrequency.DAILY);

            // WEEKDAY ⟺ MON..FRI、WEEKEND ⟺ SAT/SUN，二者互斥。
            assertThat(actual.contains(ReminderFrequency.WEEKDAY))
                    .as("默认时区 %s 下，WEEKDAY 命中当且仅当 %s ∈ MON..FRI", zone, dow)
                    .isEqualTo(!isWeekendRef);
            assertThat(actual.contains(ReminderFrequency.WEEKEND))
                    .as("默认时区 %s 下，WEEKEND 命中当且仅当 %s ∈ SAT/SUN", zone, dow)
                    .isEqualTo(isWeekendRef);

            // 与时区无关地等于参照集合（构造性证明「同一提醒对同一自然日判定不随时区变化」）。
            assertThat(actual)
                    .as("默认时区 %s 下，%s 的命中频率应与时区无关的参照相等", zone, dow)
                    .isEqualTo(reference);
        }
    }

    /**
     * 七个星期几逐一穷举的示例（补足 {@code @Property} 随机采样，确保每个星期几都被断言到一次）。
     *
     * <p>Validates: Requirements 2.1, 2.2, 2.3</p>
     */
    @Example
    void property4_allSevenDaysOfWeekCovered() {
        for (DayOfWeek dow : DayOfWeek.values()) {
            boolean isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            Set<ReminderFrequency> actual = ReminderFrequencies.matching(dow);
            assertThat(actual).hasSize(2).contains(ReminderFrequency.DAILY);
            assertThat(actual.contains(ReminderFrequency.WEEKDAY)).isEqualTo(!isWeekend);
            assertThat(actual.contains(ReminderFrequency.WEEKEND)).isEqualTo(isWeekend);
        }
    }
}
