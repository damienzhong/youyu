package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 确认待确认项请求体（{@code POST /api/recurring/pending-items/{id}/confirm}，tasks 7.2）。
 *
 * <p>全部字段可选，用于「修改后确认」：任一字段非 {@code null} 即以其覆盖该项生成时的模板快照，
 * {@code null} 则沿用快照（需求 4.3）。整个请求体缺省（直接确认，不修改）时各字段均为 {@code null}，
 * 服务层即以快照入账、记账时间取该项期次到期日 00:00（{@code Asia/Shanghai}）。</p>
 *
 * <ul>
 *   <li>{@code amount}：覆盖金额（0.01–999999999.99，2 位小数），非法由服务层返回 {@code AMOUNT_INVALID}。</li>
 *   <li>{@code categoryId}：覆盖分类 id（须属当前账本），缺失 / 不存在返回 {@code RECURRING_ITEM_TARGET_MISSING}。</li>
 *   <li>{@code accountId}：覆盖账户 id（须为当前用户在当前账本可用账户），同上。</li>
 *   <li>{@code note}：覆盖备注（≤200），超长返回 {@code NOTE_TOO_LONG}。</li>
 *   <li>{@code occurredAt}：覆盖记账时间；缺省取期次到期日 00:00（{@code Asia/Shanghai}）。</li>
 * </ul>
 *
 * <p>类型不可改：确认沿用快照 {@code type}（{@code expense} / {@code income}）。请求体不承载 ledgerId：
 * 账本按请求头 {@code X-Ledger-Id} 隔离。</p>
 */
public record RecurringItemConfirmRequest(
        BigDecimal amount,
        Long categoryId,
        Long accountId,
        String note,
        LocalDateTime occurredAt) {
}
