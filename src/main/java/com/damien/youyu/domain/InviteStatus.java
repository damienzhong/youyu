package com.damien.youyu.domain;

/**
 * 邀请关系状态枚举，**仅描述被邀请人**的账号状态。
 *
 * <p>数据库以枚举名(大写)存储，使用 {@code @Enumerated(EnumType.STRING)} 映射；
 * 枚举名即 {@code invite_relations.status} 列中的取值，与迁移脚本的
 * {@code ck_invite_relations_status} CHECK 约束（区分大小写）保持一致，
 * 因此不得改名、不得新增小写别名。</p>
 *
 * <ul>
 *   <li>{@link #REGISTERED} 在册：被邀请人账号仍存在。</li>
 *   <li>{@link #INVALID} 已失效：被邀请人已注销（邀请人注销**不**改任何行的状态）。</li>
 * </ul>
 */
public enum InviteStatus {
    REGISTERED,
    INVALID
}
