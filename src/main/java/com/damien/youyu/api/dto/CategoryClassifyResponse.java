package com.damien.youyu.api.dto;

import java.util.List;

/**
 * 批量自动分类结果，与请求 {@code items} 一一对应。
 *
 * @param results 每笔的分类结果
 */
public record CategoryClassifyResponse(List<Result> results) {

    /**
     * @param categoryId 推荐分类（{@code source = NONE} 时为 null，由调用方用默认分类兜底）
     * @param source     分类来源：RULE（用户确认过的规则）/ HABIT（该账本历史习惯）/
     *                   MERCHANT（内置商户库）/ KEYWORD（分类名关键字）/ NONE（未匹配）
     */
    public record Result(Long categoryId, String source) {
    }
}
