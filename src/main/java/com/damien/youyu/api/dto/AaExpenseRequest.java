package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建 / 编辑 AA 支出请求体（POST / PUT {@code /api/aa/expenses}）。
 *
 * <p>字段语义（对应设计文档 POST {@code /api/aa/expenses} schema 与需求 3.1-3.7）：</p>
 * <ul>
 *   <li>{@code amount}：该笔总额（&gt;0、最多两位小数，服务层校验范围）。</li>
 *   <li>{@code categoryId}：分类 id（须属于当前账本）。</li>
 *   <li>{@code payerUserId}：付款人 user_id；缺省取会话用户（需求 3.1）。</li>
 *   <li>{@code payerAccountId}：付款账户 id；仅当付款人为会话用户时必填并按实付全额扣款（需求 3.2）。</li>
 *   <li>{@code occurredAt}：交易时间；缺省取当前时间。</li>
 *   <li>{@code note}：备注（≤200，可选）。</li>
 *   <li>{@code splitMode}：分摊方式，取值 {@code even}（均分）或 {@code custom}（自定义金额）。</li>
 *   <li>{@code participants}：参与分摊成员 user_id 列表（非空，均须为本账本成员）。</li>
 *   <li>{@code customShares}：自定义分摊明细，仅 {@code splitMode=custom} 时必填，每位参与人一条且 Σ=总额。</li>
 * </ul>
 *
 * <p>请求体不承载 ledgerId：账本按请求头 {@code X-Ledger-Id} 隔离；也不承载记账人，落库以会话用户为准。</p>
 */
public record AaExpenseRequest(
        BigDecimal amount,
        Long categoryId,
        Long payerUserId,
        Long payerAccountId,
        LocalDateTime occurredAt,
        String note,
        String splitMode,
        List<Long> participants,
        List<CustomShare> customShares) {

    /** 单个参与人的自定义分摊额。 */
    public record CustomShare(Long userId, BigDecimal amount) {
    }
}
