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
    TRANSFER("transfer");

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
