package com.damien.youyu.domain;

/**
 * 账户分组：资产管理页按此分组展示与小计。
 *
 * <ul>
 *   <li>{@link #FUNDS} 资金账户（现金/储蓄卡/第三方钱包/公积金/医保/数字人民币等，余额计入资产）。</li>
 *   <li>{@link #CREDIT} 信贷账户（信用卡/花呗/白条等，余额通常为负=欠款，计入负债；可设授信额度）。</li>
 *   <li>{@link #PREPAID} 充值账户（公交卡/饭卡/会员卡/押金等）。</li>
 *   <li>{@link #INVESTMENT} 投资账户（股票/基金/虚拟货币/理财等）。</li>
 * </ul>
 */
public enum AccountGroup {
    FUNDS,
    CREDIT,
    PREPAID,
    INVESTMENT
}
