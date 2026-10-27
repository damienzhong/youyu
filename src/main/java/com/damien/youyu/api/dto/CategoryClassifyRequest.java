package com.damien.youyu.api.dto;

import java.util.List;

/**
 * 批量自动分类请求（账单导入预览用，后续截图识别复用）。
 *
 * @param items 待分类的流水，顺序与响应一一对应
 */
public record CategoryClassifyRequest(List<Item> items) {

    /**
     * @param type         expense / income
     * @param counterparty 交易对方 / 商户（用于规则与历史习惯匹配，可空）
     * @param text         其余可用于匹配的文本：商品说明、账单自带的交易分类等（可空）
     */
    public record Item(String type, String counterparty, String text) {
    }
}
