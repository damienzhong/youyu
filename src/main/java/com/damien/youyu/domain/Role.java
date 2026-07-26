package com.damien.youyu.domain;

/**
 * 角色枚举：user / admin。
 *
 * <p>数据库列以小写字符串存储，通过 {@link com.damien.youyu.domain.RoleConverter} 转换。</p>
 */
public enum Role {
    USER("user"),
    ADMIN("admin");

    private final String code;

    Role(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static Role fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (Role r : values()) {
            if (r.code.equals(code)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知角色类型: " + code);
    }
}
