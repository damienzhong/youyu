package com.damien.youyu.domain;

/**
 * 账户类型枚举，参考主流记账产品的账户分类（资金/信贷/充值/投资）。
 *
 * <p>数据库以枚举名(大写)存储，故直接使用 {@code @Enumerated(EnumType.STRING)} 映射。新增类型为向后兼容的
 * 追加，不影响既有数据。信贷组（{@link #isCredit()}）允许当前余额为负（欠款），并可设置授信额度；
 * 其余组余额为正计入资产。历史保留的 {@link #INVESTMENT} 归入投资组。</p>
 */
public enum AccountType {
    // 资金账户
    CASH(AccountGroup.FUNDS),
    BANK_CARD(AccountGroup.FUNDS),
    WECHAT(AccountGroup.FUNDS),
    ALIPAY(AccountGroup.FUNDS),
    QQ_WALLET(AccountGroup.FUNDS),
    JD_FINANCE(AccountGroup.FUNDS),
    HOUSING_FUND(AccountGroup.FUNDS),
    MEDICAL_INSURANCE(AccountGroup.FUNDS),
    DIGITAL_RMB(AccountGroup.FUNDS),
    OTHER_FUNDS(AccountGroup.FUNDS),

    // 信贷账户
    CREDIT_CARD(AccountGroup.CREDIT),
    HUABEI(AccountGroup.CREDIT),
    JD_BAITIAO(AccountGroup.CREDIT),
    OTHER_CREDIT(AccountGroup.CREDIT),

    // 充值账户
    TRANSIT_CARD(AccountGroup.PREPAID),
    MEAL_CARD(AccountGroup.PREPAID),
    MEMBER_CARD(AccountGroup.PREPAID),
    DEPOSIT(AccountGroup.PREPAID),
    OTHER_PREPAID(AccountGroup.PREPAID),

    // 投资账户
    STOCK(AccountGroup.INVESTMENT),
    FUND(AccountGroup.INVESTMENT),
    CRYPTO(AccountGroup.INVESTMENT),
    INVESTMENT(AccountGroup.INVESTMENT),
    OTHER_INVESTMENT(AccountGroup.INVESTMENT);

    private final AccountGroup group;

    AccountType(AccountGroup group) {
        this.group = group;
    }

    public AccountGroup getGroup() {
        return group;
    }

    /** 是否信贷账户（余额可为负、可设授信额度、显示可用余额）。 */
    public boolean isCredit() {
        return group == AccountGroup.CREDIT;
    }
}
