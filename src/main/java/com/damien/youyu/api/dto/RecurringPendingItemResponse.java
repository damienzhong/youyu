package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.damien.youyu.domain.RecurringPendingItem;

/**
 * 待确认生成项响应体（{@code /api/recurring/pending-items} 系列端点，tasks 7.2）。
 *
 * <p>由 {@link RecurringPendingItem} 装配：既用于 {@code GET} 列表（每项携带来源规则 id、期次到期日与
 * 模板快照字段，需求 5.1），也用于 {@code {id}/confirm} 与 {@code {id}/skip} 的单条返回（携带处理后的
 * {@code status}，确认时附带 {@code confirmedTransactionId} 指向真实流水，需求 4.1、4.4）。</p>
 *
 * <p>金额沿用 {@link TransactionResponse} / {@link AaExpenseResponse} 风格以 {@link BigDecimal}
 * （2 位小数，HALF_UP）承载。{@code occurrenceDate} 为期次到期自然日（{@code Asia/Shanghai}）。
 * {@code type} 为模板快照类型（{@code expense} / {@code income}）。{@code note} 可空。
 * {@code confirmedTransactionId} 仅在已确认（{@code status=CONFIRMED}）时非空。</p>
 */
public record RecurringPendingItemResponse(
        Long id,
        Long ruleId,
        LocalDate occurrenceDate,
        String status,
        String type,
        BigDecimal amount,
        Long categoryId,
        Long accountId,
        String note,
        Long confirmedTransactionId) {

    /** 由待确认项实体装配响应体。 */
    public static RecurringPendingItemResponse from(RecurringPendingItem item) {
        return new RecurringPendingItemResponse(
                item.getId(),
                item.getRuleId(),
                item.getOccurrenceDate(),
                item.getStatus() == null ? null : item.getStatus().name(),
                item.getType(),
                item.getAmount(),
                item.getCategoryId(),
                item.getAccountId(),
                item.getNote(),
                item.getConfirmedTransactionId());
    }
}
