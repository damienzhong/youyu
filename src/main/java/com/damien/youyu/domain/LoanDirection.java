package com.damien.youyu.domain;

/**
 * 借贷方向枚举。
 *
 * <p>数据库以枚举名(大写)存储，使用 {@code @Enumerated(EnumType.STRING)} 映射。</p>
 * <ul>
 *   <li>{@link #BORROW} 借入：我从别人处借入，未结即「待还」（负债性质）。</li>
 *   <li>{@link #LEND} 借出：我借给别人，未结即「待收」（债权性质）。</li>
 * </ul>
 */
public enum LoanDirection {
    BORROW,
    LEND
}
