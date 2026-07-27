package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账单批量导入请求体（支付宝/微信 CSV 前端解析归一化后提交）。
 *
 * <p>{@code accountId} 为导入目标账户（全部流水记入该账户）；{@code defaultExpenseCategoryId} /
 * {@code defaultIncomeCategoryId} 为未匹配到分类时的兜底分类。{@code entries} 为归一化后的逐笔流水。</p>
 */
public record BillImportRequest(
        Long accountId,
        Long defaultExpenseCategoryId,
        Long defaultIncomeCategoryId,
        List<BillEntry> entries) {

    /**
     * 单笔归一化账单。
     *
     * @param type       expense / income（中性行由前端过滤，不提交）
     * @param amount     金额（恒为正，最多两位小数）
     * @param occurredAt 交易时间
     * @param note       备注（对方 · 商品，<=200，前端已截断）
     * @param externalId 账单唯一标识（形如 "alipay:订单号"），用于去重；缺省则不参与去重
     * @param categoryId 前端关键字匹配到的分类（可空，空则用对应默认分类）
     */
    public record BillEntry(
            String type,
            BigDecimal amount,
            LocalDateTime occurredAt,
            String note,
            String externalId,
            Long categoryId) {
    }
}
