package com.damien.youyu.domain;

/**
 * 分类种类枚举：支出 / 收入。
 *
 * <p>支出与收入分类各自独立。数据库以枚举名(大写)存储，
 * 使用 {@code @Enumerated(EnumType.STRING)} 直接映射。</p>
 */
public enum CategoryKind {
    EXPENSE,
    INCOME
}
