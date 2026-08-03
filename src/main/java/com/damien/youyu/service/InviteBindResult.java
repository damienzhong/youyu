package com.damien.youyu.service;

/**
 * 一次登录/注册请求的邀请关系绑定结果：恰好 1 个绑定结果标识与至多 1 个未绑定原因（需求 5.4）。
 *
 * <p>不变式：{@code bound == true} 时 {@code reason} 必为 {@code null}；
 * {@code bound == false} 时 {@code reason} 必非 {@code null}。由构造器强制，
 * 避免出现「已绑定却带着原因」或「未绑定却没有原因」这类无法映射到响应的中间态。</p>
 *
 * @param bound  本次是否建立了邀请关系
 * @param reason 未绑定原因；已绑定时为 {@code null}
 */
public record InviteBindResult(boolean bound, UnboundReason reason) {

    public InviteBindResult {
        if (bound && reason != null) {
            throw new IllegalArgumentException("已绑定时未绑定原因必须为空");
        }
        if (!bound && reason == null) {
            throw new IllegalArgumentException("未绑定时必须给出未绑定原因");
        }
    }

    /**
     * 已建立邀请关系。
     *
     * <p>名字带 {@code of} 前缀是语言约束而非风格选择：记录组件 {@code bound} 已经占用了
     * {@code bound()} 这个方法名（存取方法），静态工厂不能同名。</p>
     */
    public static InviteBindResult ofBound() {
        return new InviteBindResult(true, null);
    }

    /** 未建立邀请关系，携带唯一原因。 */
    public static InviteBindResult ofUnbound(UnboundReason reason) {
        return new InviteBindResult(false, reason);
    }
}
