package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.damien.youyu.domain.RecurringRule;

/**
 * 周期规则响应体（{@code /api/recurring/rules} 各端点）。
 *
 * <p>金额沿用 {@link AaExpenseResponse} / {@link TransactionResponse} 风格以 {@link BigDecimal}（源自
 * {@code DECIMAL(18,2)}，2 位小数）承载。频率 / 结束条件 / 状态以枚举名字符串回显（如 {@code MONTHLY}、
 * {@code COUNT}、{@code ACTIVE}）。频率子字段按 {@code frequency} 取值有效：{@code WEEKLY} 回显
 * {@code weeklyDays}（由存储的稳定升序逗号串解析回整数列表），{@code MONTHLY} 回显 {@code monthDay} /
 * {@code monthEnd}，{@code YEARLY} 回显 {@code yearMonth} / {@code yearDay}；不适用的子字段为
 * {@code null} 或空列表。结束条件参数同理：{@code UNTIL_DATE} 回显 {@code untilDate}，{@code COUNT}
 * 回显 {@code countN}。</p>
 */
public record RecurringRuleResponse(
        Long id,
        String type,
        BigDecimal amount,
        Long categoryId,
        Long accountId,
        String note,
        String frequency,
        List<Integer> weeklyDays,
        Integer monthDay,
        boolean monthEnd,
        Integer yearMonth,
        Integer yearDay,
        LocalDate startDate,
        String endCondition,
        LocalDate untilDate,
        Integer countN,
        String status,
        String postMode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 由周期规则实体构建响应。 */
    public static RecurringRuleResponse from(RecurringRule rule) {
        return new RecurringRuleResponse(
                rule.getId(),
                rule.getType(),
                rule.getAmount(),
                rule.getCategoryId(),
                rule.getAccountId(),
                rule.getNote(),
                rule.getFrequency() == null ? null : rule.getFrequency().name(),
                parseWeeklyDays(rule.getWeeklyDays()),
                rule.getMonthDay(),
                rule.isMonthEnd(),
                rule.getYearMonth(),
                rule.getYearDay(),
                rule.getStartDate(),
                rule.getEndCondition() == null ? null : rule.getEndCondition().name(),
                rule.getUntilDate(),
                rule.getCountN(),
                rule.getStatus() == null ? null : rule.getStatus().name(),
                rule.getPostMode() == null ? null : rule.getPostMode().name(),
                rule.getCreatedAt(),
                rule.getUpdatedAt());
    }

    /**
     * 把存储的稳定升序逗号串（如 {@code "1,3,5"}）解析回整数列表；为空 / 空白返回空列表（非 {@code WEEKLY}
     * 规则的 {@code weekly_days} 为空）。
     */
    private static List<Integer> parseWeeklyDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Integer> days = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                days.add(Integer.valueOf(trimmed));
            }
        }
        return days;
    }
}
