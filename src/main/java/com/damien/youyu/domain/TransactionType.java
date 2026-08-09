package com.damien.youyu.domain;

/**
 * 交易类型枚举：支出 / 收入 / 转账。
 *
 * <p>数据库列以小写字符串(expense/income/transfer)存储，故通过
 * {@link com.damien.youyu.domain.TransactionTypeConverter} 在枚举与小写编码之间转换。</p>
 */
public enum TransactionType {
    EXPENSE("expense"),
    INCOME("income"),
    TRANSFER("transfer"),
    /** AA 账本支出：付款人实付、按分摊拆分；不计入普通收支报表。 */
    AA_EXPENSE("aa_expense"),
    /** AA 账本结算：成员间清账转账，驱动账户增减、递减应收/应付；不计入消费。 */
    AA_SETTLEMENT("aa_settlement");

    private final String code;

    TransactionType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static TransactionType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TransactionType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知交易类型: " + code);
    }
}
