package com.damien.youyu.api.dto;

import java.util.List;

/**
 * 批量保存「以后都这样记」的分类规则。同一账本、同一收支、同一商户已有规则时覆盖。
 *
 * @param rules 规则列表
 */
public record CategoryRuleSaveRequest(List<Rule> rules) {

    /**
     * @param type         expense / income
     * @param counterparty 交易对方 / 商户（原文，服务端规整）
     * @param categoryId   以后该商户使用的分类（须属于当前账本且收支种类一致）
     */
    public record Rule(String type, String counterparty, Long categoryId) {
    }
}
