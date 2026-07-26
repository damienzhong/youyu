package com.damien.youyu.domain;

/**
 * 账户类型枚举。
 *
 * <p>数据库以枚举名(大写)存储，故直接使用 {@code @Enumerated(EnumType.STRING)} 映射。
 * 信用卡 {@link #CREDIT_CARD} 允许当前余额为负。{@link #INVESTMENT} 为投资理财账户
 * （基金/股票/理财等，余额为正计入资产）。</p>
 */
public enum AccountType {
    CASH,
    BANK_CARD,
    ALIPAY,
    WECHAT,
    CREDIT_CARD,
    INVESTMENT
}
