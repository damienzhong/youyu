package com.damien.youyu.repository;

import java.time.LocalDateTime;

import com.damien.youyu.domain.TransactionType;

/**
 * 自动分类「按习惯」用的只读投影：某账本近一段时间的收支流水，只取统计商户 → 分类所需的字段。
 * 由 {@link TransactionRepository#findClassifyHistoryRows} 返回。软删记录已被实体的
 * {@code @SQLRestriction} 排除。
 */
public interface ClassifyHistoryRow {

    /** 交易类型（仅 EXPENSE/INCOME）。返回枚举以让类型转换器生效，理由同 {@link SuggestionRow#getType()}。 */
    TransactionType getType();

    Long getCategoryId();

    /** 商家 id（手动记账选了商家时非空）。 */
    Long getMerchantId();

    /** 备注（账单导入的备注形如「交易对方 · 商品」）。 */
    String getNote();

    LocalDateTime getOccurredAt();
}
