package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 修改交易请求体（支出/收入/转账）。
 *
 * <p>语义与 {@link TransactionCreateRequest} 一致：以完整新形态提交，服务层先回滚原交易对余额的
 * 影响再应用新影响（需求 4.6）。{@code type} 可与原交易不同（如支出→转账）。</p>
 *
 * <ul>
 *   <li>支出/收入：使用 {@code accountId} + {@code categoryId}。</li>
 *   <li>转账：使用 {@code sourceAccountId} + {@code destinationAccountId}（源≠目标，需求 4.5）。</li>
 * </ul>
 *
 * <p>{@code occurredAt} 缺省取当前时间；{@code note} 可选（<=200）。任何请求体传入的 user_id
 * 一律被忽略，落库以会话用户为准（需求 2.2）。</p>
 */
public record TransactionUpdateRequest(
        String type,
        BigDecimal amount,
        Long accountId,
        Long categoryId,
        Long sourceAccountId,
        Long destinationAccountId,
        LocalDateTime occurredAt,
        String note,
        Long projectId,
        Long merchantId,
        java.util.List<Long> tagIds) {
}
