package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * AA 账本结算视图响应体（GET {@code /api/aa/{ledgerId}/settlement}）。
 *
 * <p>纯派生、只读（需求 5.2、5.4、5.5）：</p>
 * <ul>
 *   <li>{@code nets}：每个成员的净额（应收为正、应付为负；Σ 恒为 0，见 Property 2 / 需求 5.1）。</li>
 *   <li>{@code suggestedTransfers}：依净额贪心配对得出的最少转账建议（笔数 ≤ 成员数 − 1；
 *       金额之和 = 总应付额，见需求 5.3、5.4）。</li>
 *   <li>{@code allSettled}：全体净额是否全为 0（已全部结清，见需求 8.1）。</li>
 * </ul>
 *
 * <p>金额沿用 {@link TransactionResponse} 风格以 {@link BigDecimal}（2 位小数）承载。该视图不落库
 * （结清动作除外，见需求 6），每次请求实时计算。</p>
 */
public record AaSettlementResponse(
        Long ledgerId,
        boolean allSettled,
        List<MemberNet> nets,
        List<SuggestedTransfer> suggestedTransfers) {

    /** 单个成员的净额：应收为正、应付为负、已结清为 0。 */
    public record MemberNet(Long userId, BigDecimal net) {
    }

    /** 一条建议转账：付款成员 → 收款成员，金额恒为正（2 位小数）。 */
    public record SuggestedTransfer(Long fromUserId, Long toUserId, BigDecimal amount) {
    }
}
