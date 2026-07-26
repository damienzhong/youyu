package com.damien.youyu.domain;

/**
 * 账户类型枚举。
 *
 * <p>数据库以枚举名(大写)存储，故直接使用 {@code @Enumerated(EnumType.STRING)} 映射。
 * 信用卡 {@link #CREDIT_CARD} 允许当前余额为负。</p>
 */
public enum AccountType {
    CASH,
    BANK_CARD,
    ALIPAY,
    WECHAT,
    CREDIT_CARD
}
