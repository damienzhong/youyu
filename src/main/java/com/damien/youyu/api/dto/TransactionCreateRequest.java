package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建交易请求体（支出/收入/转账）。
 *
 * <p>{@code type} 以字符串接收（expense/income/transfer），由服务层校验；{@code amount} 用
 * {@link BigDecimal} 承载金额（恒为正、最多两位小数，服务层校验范围，需求 4.4）。</p>
 *
 * <ul>
 *   <li>支出/收入：使用 {@code accountId} + {@code categoryId}。</li>
 *   <li>转账：使用 {@code sourceAccountId} + {@code destinationAccountId}（源≠目标，需求 4.5）。</li>
 * </ul>
 *
 * <p>{@code occurredAt} 缺省取当前时间；{@code note} 可选（<=200）。任何请求体传入的 user_id
 * 一律被忽略，落库以会话用户为准（需求 2.2）。</p>
 *
 * <p>{@code createdBy}（可选）：协作账本代记场景下指定记账人。仅当当前账本为 COLLABORATIVE 且该用户为
 * 账本成员时生效，否则忽略、以会话用户为记账人。</p>
 */
public record TransactionCreateRequest(
        String type,
        BigDecimal amount,
        Long accountId,
        Long categoryId,
        Long sourceAccountId,
        Long destinationAccountId,
        LocalDateTime occurredAt,
        String note,
        Long createdBy,
        Long projectId,
        Long merchantId,
        java.util.List<Long> tagIds,
        @jakarta.validation.constraints.Size(max = 64) String clientToken) {
}
