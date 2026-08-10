package com.damien.youyu.service.recurring;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;

/**
 * 期次计算所需的<strong>不可变值对象</strong>：仅承载频率配置与开始 / 结束边界，
 * 不含金额 / 分类 / 账户等记账模板字段，也不依赖 Spring / JPA。
 *
 * <p>它是 {@link OccurrenceCalculator} 的唯一输入，从 {@code recurring_rules} 行的频率相关列
 * （{@code frequency} / {@code weekly_days} / {@code month_day} / {@code month_end} /
 * {@code year_month} / {@code year_day} / {@code start_date} / {@code end_condition} /
 * {@code until_date} / {@code count_n}）投影而来。将其独立为纯值对象，使期次算法可脱离数据库
 * 被单元 / 属性测试完整覆盖（见 design.md「期次计算算法」）。</p>
 *
 * <h2>字段语义（按 {@link #frequency} 取值必填其一组）</h2>
 * <ul>
 *   <li>{@link Frequency#DAILY}：无附加字段——自 {@link #startDate}（含）起每个连续自然日各一期。</li>
 *   <li>{@link Frequency#WEEKLY}：{@link #weeklyDays} 为 1–7（1=周一..7=周日）的非空集合。</li>
 *   <li>{@link Frequency#MONTHLY}：{@link #monthEnd} 为真时取每月实际最后一日（忽略 {@link #monthDay}）；
 *       否则取每月第 {@link #monthDay} 日（1–31），小月不存在该日时落该月最后一日。</li>
 *   <li>{@link Frequency#YEARLY}：{@link #yearMonth}（1–12）+ {@link #yearDay}（1–31）；
 *       目标日在该年该月不存在时落该月最后一日。</li>
 * </ul>
 *
 * <p>结束边界：{@link EndCondition#NEVER} 不限；{@link EndCondition#UNTIL_DATE} 取
 * {@link #untilDate}（含端点）；{@link EndCondition#COUNT} 取 {@link #countN}（按到期日升序累计至 N）。</p>
 *
 * <p>字段合法性（枚举取值、集合范围、结束条件参数区间等）由服务层校验（见 tasks 3.1）；本值对象只做
 * 空安全归一化：{@link #weeklyDays} 归一为按升序排列的<strong>不可变集合</strong>（{@code null} → 空集）。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 *
 * @param frequency     频率节律（不可为 {@code null}）
 * @param weeklyDays    WEEKLY 星期几集合（1=周一..7=周日）；归一为不可变升序集合，{@code null} → 空集
 * @param monthDay      MONTHLY 指定日（1–31）；{@code monthEnd} 为真或非 MONTHLY 时可为 {@code null}
 * @param monthEnd      MONTHLY「月末」标记：真时每月取实际最后一日
 * @param yearMonth     YEARLY 指定月（1–12）；非 YEARLY 时可为 {@code null}
 * @param yearDay       YEARLY 指定日（1–31）；非 YEARLY 时可为 {@code null}
 * @param startDate     开始日期（{@code Asia/Shanghai} 自然日，含）；不可为 {@code null}
 * @param endCondition  结束方式（不可为 {@code null}）
 * @param untilDate     UNTIL_DATE 的结束日期（含端点）；其余结束方式可为 {@code null}
 * @param countN        COUNT 的总期次数；其余结束方式可为 {@code null}
 */
public record RuleSpec(
        Frequency frequency,
        Set<Integer> weeklyDays,
        Integer monthDay,
        boolean monthEnd,
        Integer yearMonth,
        Integer yearDay,
        LocalDate startDate,
        EndCondition endCondition,
        LocalDate untilDate,
        Integer countN) {

    /**
     * 规范构造器：做空安全归一化，不做业务合法性校验（后者属服务层职责）。
     *
     * <p>{@code weeklyDays} 归一为按自然升序排列的<strong>不可变</strong>集合，{@code null} 视为空集，
     * 使同一星期几集合无论传入顺序如何都得到确定、可复现的迭代次序与相等语义。</p>
     */
    public RuleSpec {
        if (frequency == null) {
            throw new IllegalArgumentException("frequency must not be null");
        }
        if (startDate == null) {
            throw new IllegalArgumentException("startDate must not be null");
        }
        if (endCondition == null) {
            throw new IllegalArgumentException("endCondition must not be null");
        }
        weeklyDays = weeklyDays == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new TreeSet<>(weeklyDays));
    }

    /** 构造 DAILY 规则。 */
    public static RuleSpec daily(LocalDate startDate, EndCondition endCondition,
                                 LocalDate untilDate, Integer countN) {
        return new RuleSpec(Frequency.DAILY, null, null, false, null, null,
                startDate, endCondition, untilDate, countN);
    }

    /** 构造 WEEKLY 规则；{@code weeklyDays} 为星期几集合（1=周一..7=周日）。 */
    public static RuleSpec weekly(Set<Integer> weeklyDays, LocalDate startDate, EndCondition endCondition,
                                  LocalDate untilDate, Integer countN) {
        return new RuleSpec(Frequency.WEEKLY, weeklyDays, null, false, null, null,
                startDate, endCondition, untilDate, countN);
    }

    /** 构造 MONTHLY「指定日」规则；{@code monthDay} 为 1–31。 */
    public static RuleSpec monthlyOnDay(int monthDay, LocalDate startDate, EndCondition endCondition,
                                        LocalDate untilDate, Integer countN) {
        return new RuleSpec(Frequency.MONTHLY, null, monthDay, false, null, null,
                startDate, endCondition, untilDate, countN);
    }

    /** 构造 MONTHLY「月末」规则（每月取实际最后一日，等价指定日=31）。 */
    public static RuleSpec monthlyOnLastDay(LocalDate startDate, EndCondition endCondition,
                                            LocalDate untilDate, Integer countN) {
        return new RuleSpec(Frequency.MONTHLY, null, null, true, null, null,
                startDate, endCondition, untilDate, countN);
    }

    /** 构造 YEARLY 规则；{@code yearMonth} 为 1–12、{@code yearDay} 为 1–31。 */
    public static RuleSpec yearly(int yearMonth, int yearDay, LocalDate startDate, EndCondition endCondition,
                                  LocalDate untilDate, Integer countN) {
        return new RuleSpec(Frequency.YEARLY, null, null, false, yearMonth, yearDay,
                startDate, endCondition, untilDate, countN);
    }
}
