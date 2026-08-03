package com.damien.youyu.service;

import com.damien.youyu.domain.User;

/**
 * 一次登录/注册请求的完整结果：用户 + 邀请关系绑定结果 + 本次是否建号。
 *
 * <p>{@link AuthService#emailLogin} 与 {@link AuthService#wxLogin} 的返回类型。刻意把绑定结果与
 * {@code isNewUser} 一并返回，而不是让控制器再去查一次：绑定判定发生在登录事务内，事务提交后
 * 已无从复原「本次请求是否新插入了 users 行」这个事实（同一账号第二次登录与第一次注册在库里看起来
 * 一模一样）。</p>
 *
 * <p>令牌由调用方（{@code AuthController}）在事务提交后签发；本记录不含任何令牌信息。</p>
 *
 * @param user       本次登录/注册对应的用户，非空
 * @param inviteBind 邀请关系绑定结果，非空（未携带邀请码时为 {@code NO_CODE}）
 * @param isNewUser  本次请求是否在 {@code users} 表新插入了一行
 */
public record LoginOutcome(User user, InviteBindResult inviteBind, boolean isNewUser) {
}
