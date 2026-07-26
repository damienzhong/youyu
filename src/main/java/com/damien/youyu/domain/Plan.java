package com.damien.youyu.domain;

/**
 * 套餐枚举：free / pro / lifetime。
 *
 * <p>本期仅预留存储，不做任何功能门控。数据库列以小写字符串存储，
 * 通过 {@link com.damien.youyu.domain.PlanConverter} 转换。</p>
 */
public enum Plan {
    FREE("free"),
    PRO("pro"),
    LIFETIME("lifetime");

    private final String code;

    Plan(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static Plan fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (Plan p : values()) {
            if (p.code.equals(code)) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知套餐类型: " + code);
    }
}
