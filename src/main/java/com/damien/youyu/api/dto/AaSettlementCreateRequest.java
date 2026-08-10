package com.damien.youyu.api.dto;

import java.math.BigDecimal;

/**
 * 结清一条建议转账的请求体（POST {@code /api/aa/settlements}，需求 6.1-6.4、6.6）。
 *
 * <p>当前用户只结清<b>涉及本人</b>的一条转账，二选一表达方向（另一字段须为空）：</p>
 * <ul>
 *   <li>提供 {@code toUserId}：本人为<b>付款方</b>（{@code from=本人 → to=toUserId}），
 *       保存时本人所选账户 {@code −amount}、本人应付 {@code −amount}（需求 6.3）。</li>
 *   <li>提供 {@code fromUserId}：本人为<b>收款方</b>（{@code from=fromUserId → to=本人}），
 *       保存时本人所选账户 {@code +amount}、本人应收 {@code −amount}（需求 6.2）。</li>
 * </ul>
 *
 * <p>{@code amount}：结算金额（&gt;0、最多两位小数）。{@code myAccountId}：本人侧所选账户 id（必填，
 * 按本人加锁增减）。账本按请求头 {@code X-Ledger-Id} 隔离，请求体不承载 ledgerId。对手非成员 / 与本人相同、
 * 方向与净额不符、金额非法或超出可结净额一律拒以 {@code AA_SETTLEMENT_INVALID}。</p>
 */
public record AaSettlementCreateRequest(
        Long toUserId,
        Long fromUserId,
        BigDecimal amount,
        Long myAccountId) {
}
