package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AA 账本概览视图响应体（GET {@code /api/aa/{ledgerId}/overview}）。
 *
 * <p>纯派生、只读（需求 2.1、4.4、5.1、7.1、7.2、8.1）。首页 hero 三口径 + 成员净额 + 流水：</p>
 *
 * <h4>三口径（{@link Calibers}，均以当前用户视角、{@link BigDecimal} 2 位小数承载）：</h4>
 * <ul>
 *   <li><b>账户已支出（{@code accountPaid}）</b>：当前用户因本账本从其账户真实流出的<b>净现金</b>
 *       = Σ(自付款 AA 支出实付额) + Σ(作为付款方的结算付出) − Σ(作为收款方的结算收到)。
 *       等于当前用户账户余额因本账本产生的净下降（账户守恒，需求 7.1）——账户只反映真实进出账户的钱
 *       （付款扣款、结算收付），应收 / 应付不计入。</li>
 *   <li><b>我的消费（{@code myConsumption}）</b>：当前用户自身分摊份额之和
 *       = Σ(各 AA 支出中当前用户的 {@code share_amount})。仅计消费份额，应收 / 应付与结算均不计入
 *       （需求 4.4、7.2）。</li>
 *   <li><b>待收回（{@code receivable}）</b>：当前用户尚未收回的应收 = {@code max(net, 0)}
 *       （net &gt; 0 即别人还欠他的部分，需求 5.1、5.2）。</li>
 * </ul>
 *
 * <h4>成员净额（{@code memberNets}）：</h4>
 * 每个成员的净额（应收正 / 应付负；Σ 恒为 0，Property 2 / 需求 5.1），按 user_id 升序稳定排列。
 *
 * <h4>流水（{@code transactions}）：</h4>
 * 本账本 AA 支出（{@code aa_expense}）与未撤销结算（{@code aa_settlement}）合并、按发生时间倒序。
 * 支出条目标注付款人（{@code payerUserId}）与「我摊」（{@code myShare}，当前用户在该笔的分摊额，
 * 非参与人为空）；结算条目标注付款 / 收款成员（{@code fromUserId} / {@code toUserId}）。
 * 结算流水不计入消费统计（需求 6.4）。
 */
public record AaOverviewResponse(
        Long ledgerId,
        boolean archived,
        boolean allSettled,
        Calibers calibers,
        List<MemberNet> memberNets,
        List<TransactionItem> transactions) {

    /** 当前用户视角的三口径（2 位小数）。 */
    public record Calibers(BigDecimal accountPaid, BigDecimal myConsumption, BigDecimal receivable) {
    }

    /** 单个成员净额：应收为正、应付为负、已结清为 0。 */
    public record MemberNet(Long userId, BigDecimal net) {
    }

    /**
     * 一条流水条目：{@code type} 为 {@code aa_expense} 或 {@code aa_settlement}。
     *
     * <ul>
     *   <li>{@code aa_expense}：{@code payerUserId} 为付款人、{@code categoryId} 为分类、
     *       {@code myShare} 为当前用户在该笔的分摊额（非参与人为 {@code null}）；
     *       {@code fromUserId} / {@code toUserId} 为空。</li>
     *   <li>{@code aa_settlement}：{@code fromUserId} → {@code toUserId} 为付款 / 收款成员；
     *       {@code payerUserId} / {@code categoryId} / {@code myShare} 为空。</li>
     * </ul>
     *
     * <p>{@code id} 为来源记录主键（支出取交易 id、结算取 {@code aa_settlements} id），结合 {@code type}
     * 区分来源表。</p>
     */
    public record TransactionItem(
            Long id,
            String type,
            BigDecimal amount,
            LocalDateTime occurredAt,
            Long categoryId,
            String note,
            Long payerUserId,
            BigDecimal myShare,
            Long fromUserId,
            Long toUserId) {
    }
}
