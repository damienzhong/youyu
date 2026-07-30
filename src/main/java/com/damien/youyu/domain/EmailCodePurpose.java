package com.damien.youyu.domain;

/**
 * 邮箱验证码用途枚举：LOGIN / BIND / DELETE。
 *
 * <ul>
 *   <li>{@link #LOGIN}：邮箱验证码登录/注册（登录注册合一）。</li>
 *   <li>{@link #BIND}：为已登录用户绑定邮箱身份。</li>
 *   <li>{@link #DELETE}：注销账号的二次验证。</li>
 * </ul>
 *
 * <p>数据库 {@code verification_code.purpose} 列以大写字符串存储（VARCHAR(16)，
 * 取值受 {@code ck_vc_purpose} 约束限定为 LOGIN/BIND/DELETE），与枚举名一致，
 * 因此实体侧使用 {@code @Enumerated(EnumType.STRING)} 直接映射，无需额外转换器。</p>
 */
public enum EmailCodePurpose {
    LOGIN,
    BIND,
    DELETE
}
