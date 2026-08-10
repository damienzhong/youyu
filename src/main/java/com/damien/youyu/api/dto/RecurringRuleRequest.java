package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * 创建 / 编辑周期规则请求体（POST / PUT {@code /api/recurring/rules}）。
 *
 * <p>字段语义（对应 design.md「规则接口」与需求 1、2）：</p>
 * <ul>
 *   <li>{@code amount}：模板金额（{@link BigDecimal}，0.01–999,999,999.99、最多 2 位小数，服务层校验）。</li>
 *   <li>{@code categoryId}：模板分类 id（须属当前账本）。</li>
 *   <li>{@code accountId}：模板账户 id（须为当前用户在当前账本可用的账户）。</li>
 *   <li>{@code type}：模板类型，取值 {@code expense} / {@code income}（不含 transfer）。</li>
 *   <li>{@code note}：模板备注（≤200，可空）。</li>
 *   <li>{@code frequency}：频率节律，取值 {@code DAILY} / {@code WEEKLY} / {@code MONTHLY} / {@code YEARLY}。</li>
 *   <li>{@code weeklyDays}：{@code WEEKLY} 的星期几集合（1=周一..7=周日）；其余频率忽略。</li>
 *   <li>{@code monthDay}：{@code MONTHLY} 指定日（1–31）；{@code monthEnd=true} 或其余频率时可空。</li>
 *   <li>{@code monthEnd}：{@code MONTHLY}「月末」标记（真时每月取实际最后一日，忽略 {@code monthDay}）。</li>
 *   <li>{@code yearMonth}：{@code YEARLY} 指定月（1–12）；其余频率忽略。</li>
 *   <li>{@code yearDay}：{@code YEARLY} 指定日（1–31）；其余频率忽略。</li>
 *   <li>{@code startDate}：开始日期（{@code Asia/Shanghai} 自然日）；为空取创建当日（创建）或保留原值（编辑）。</li>
 *   <li>{@code endCondition}：结束条件，取值 {@code NEVER} / {@code UNTIL_DATE} / {@code COUNT}。</li>
 *   <li>{@code untilDate}：{@code UNTIL_DATE} 结束日期（不早于开始日期，含端点）。</li>
 *   <li>{@code countN}：{@code COUNT} 总期次数（1–9999）。</li>
 * </ul>
 *
 * <p>请求体不承载 userId / ledgerId：身份由 {@code CurrentUser} 解析、账本按请求头 {@code X-Ledger-Id}
 * 隔离。{@code type} / {@code frequency} / {@code endCondition} 以原文字符串接收，由控制器宽松解析为枚举
 * （非法取值收敛为 {@code null} 交由服务层按需求 1.8 / 1.6 映射为对应的周期记账错误码，而非被框架提前
 * 变成另一套错误码），全部字段校验一律下沉到 {@code RecurringRuleService}。</p>
 */
public record RecurringRuleRequest(
        BigDecimal amount,
        Long categoryId,
        Long accountId,
        String type,
        String note,
        String frequency,
        Set<Integer> weeklyDays,
        Integer monthDay,
        boolean monthEnd,
        Integer yearMonth,
        Integer yearDay,
        LocalDate startDate,
        String endCondition,
        LocalDate untilDate,
        Integer countN) {
}
