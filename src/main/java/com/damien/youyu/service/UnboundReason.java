package com.damien.youyu.service;

/**
 * 一次登录/注册请求「没有建立邀请关系」的唯一原因（需求 5.4、5.11、6.10）。
 *
 * <p>枚举名称即对外响应中 {@code inviteUnboundReason} 的取值，客户端按名称判定，
 * 因此<b>不得改名、不得调整语义</b>。</p>
 *
 * <p>声明顺序即判定链的固定优先级：单次请求同时满足多个情形时，取首个成立者作为唯一原因
 * （{@link #NO_CODE} → {@link #NOT_NEW_USER} → {@link #CODE_NOT_FOUND} →
 * {@link #SELF_INVITE} → {@link #ALREADY_BOUND}）。特别注意
 * {@link #NOT_NEW_USER} <b>优先于</b>格式校验：老用户带一个畸形码登录，原因是
 * {@code NOT_NEW_USER} 而非 {@code CODE_NOT_FOUND}（需求 5.3、6.6）。</p>
 */
public enum UnboundReason {

    /** 请求未携带邀请码：字段缺失、取值为 NULL、或去空白后长度为 0（需求 5.1）。 */
    NO_CODE,

    /** 本次请求没有在 {@code users} 表新插入行（已注册用户登录），一律不绑定（需求 5.3）。 */
    NOT_NEW_USER,

    /**
     * 这个码没用：格式非法（长度不等于 8 / 含字母表以外字符 / 原始取值长度超过 64）
     * 或在 {@code users.invite_code} 中查不到（含原持有者已注销）。
     *
     * <p>两类情形刻意合并为同一原因（需求 5.6）：对客户端而言都只意味着「这个码没用」，
     * 区分它们只会把内部校验细节暴露成可枚举信号。</p>
     */
    CODE_NOT_FOUND,

    /** 邀请码持有者就是本次新建的用户，自己邀请自己（需求 6.2）。 */
    SELF_INVITE,

    /**
     * 插入邀请关系时 {@code invitee_id} 唯一约束冲突：该被邀请人已有邀请关系，
     * 邀请关系一次写定不可改绑（需求 6.3）。
     *
     * <p>与 {@link #NOT_NEW_USER} 的区别：后者是「本次没建号」，前者是「本次建了号，
     * 但插入时撞上了已存在的关系行」。在「绑定时机唯一」的设计下本原因近乎不可达，
     * 故其分支记 INFO 日志备事后核对。</p>
     */
    ALREADY_BOUND
}
