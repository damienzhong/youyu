package com.damien.youyu.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.damien.youyu.domain.TransactionType;

/**
 * 一条去重后的候选「形态」及其聚合结果，{@link RecordSuggestionRanker#rank} 的输出元素
 * （record-suggestion 需求 2.3、3.2、3.3、3.4）。
 *
 * <p>五项形态字段（{@code type}、{@code amount}、{@code categoryId}、{@code accountId}、
 * {@code note}）取自该形态在窗口期内的<strong>代表流水</strong>——组内 {@code occurredAt} 最大者，
 * 并列时取 {@code id} 最大者（确定性选取，需求 2.3）。另附三项排序用聚合量：</p>
 *
 * <ul>
 *   <li>{@code frequency}：该形态在窗口期内的出现次数（组内行数）。</li>
 *   <li>{@code recency}：代表流水的 {@code occurredAt}，作近因排序键（越晚越靠前）。</li>
 *   <li>{@code repId}：代表流水的 {@code id}，作最终决胜键（越大越靠前），保证候选构成全序。</li>
 * </ul>
 *
 * <p>{@code note} 为规整后备注（首尾空白已去除，null/空白归一为空串，见
 * {@link RecordSuggestionRanker#normalizeNote}）；{@code amount} 为代表流水的原始金额值
 * （形态分组时以 {@code stripTrailingZeros} 归一，故 {@code 35} 与 {@code 35.00} 同组，
 * 但此处保留代表流水实际取值供展示与预填）。</p>
 */
public record RankedShape(
        TransactionType type,
        BigDecimal amount,
        Long categoryId,
        Long accountId,
        String note,
        int frequency,
        LocalDateTime recency,
        Long repId) {
}
