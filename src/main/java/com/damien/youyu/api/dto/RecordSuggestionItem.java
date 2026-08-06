package com.damien.youyu.api.dto;

import java.math.BigDecimal;

/**
 * 单条记账推荐候选。字段取自该形态在窗口期内的代表历史流水。
 *
 * <ul>
 *   <li>{@code type}：交易类型，取值 {@code expense}（支出）或 {@code income}（收入）。</li>
 *   <li>{@code amount}：金额，恒为正、2 位小数。</li>
 *   <li>{@code categoryId}：代表流水的分类 id（可能已被删除）。</li>
 *   <li>{@code accountId}：代表流水的账户 id（可能已被删除）。</li>
 *   <li>{@code note}：规整后备注，可空。</li>
 *   <li>{@code categoryName}：展示用分类名；分类已删则为 null。</li>
 *   <li>{@code categoryIcon}：展示用分类图标；缺省为 null，前端回退。</li>
 * </ul>
 */
public record RecordSuggestionItem(
        String type,
        BigDecimal amount,
        Long categoryId,
        Long accountId,
        String note,
        String categoryName,
        String categoryIcon) {
}
