package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 首页「今日」账户维度现金流响应体：今日与昨日各自的实际流出/流入。
 *
 * <p>四项均为两位小数纯字符串（{@code DECIMAL(18,2)} 语义，无二进制浮点），暂不带货币符号
 * （留待多币种时统一加）。{@code yesterdayOutflow}/{@code yesterdayInflow} 供首页做
 * 「今天比昨天少花」等同口径对比。</p>
 */
public record TodayCashflowResponse(
        String todayOutflow,
        String todayInflow,
        String yesterdayOutflow,
        String yesterdayInflow) {

    /** 由账户维度金额（{@link BigDecimal}）构造，统一序列化为两位小数纯字符串（HALF_UP）。 */
    public static TodayCashflowResponse of(
            BigDecimal todayOutflow,
            BigDecimal todayInflow,
            BigDecimal yesterdayOutflow,
            BigDecimal yesterdayInflow) {
        return new TodayCashflowResponse(
                money(todayOutflow),
                money(todayInflow),
                money(yesterdayOutflow),
                money(yesterdayInflow));
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
