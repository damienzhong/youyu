package com.damien.youyu.api.dto;

/**
 * 登录成功响应：返回会话令牌、用户摘要与本次邀请关系绑定结果。
 *
 * <p>前端以 {@code Authorization: Bearer <token>} 携带令牌访问受保护接口。</p>
 *
 * <p>{@code token} / {@code tokenType} / {@code user} 三个字段与语义保持不变，
 * {@code inviteBound} / {@code inviteUnboundReason} 是邀请系统新增的两个只增字段
 * （需求 5.4），老客户端忽略即可，不影响登录主路径。</p>
 *
 * @param token               会话令牌
 * @param tokenType           令牌类型，恒为 {@code Bearer}
 * @param user                用户摘要
 * @param inviteBound         本次请求是否建立了邀请关系（恰好 1 个标识）
 * @param inviteUnboundReason 未绑定原因；{@code inviteBound=true} 时为 {@code null}，
 *                            否则取 {@code NO_CODE} / {@code NOT_NEW_USER} /
 *                            {@code CODE_NOT_FOUND} / {@code SELF_INVITE} /
 *                            {@code ALREADY_BOUND} 之一
 */
public record LoginResponse(String token, String tokenType, UserSummaryResponse user,
                            boolean inviteBound, String inviteUnboundReason) {

    /**
     * 组装登录响应。
     *
     * <p>调用方负责把服务层的绑定结果拍平为 {@code inviteBound} 与原因名称：本 DTO 刻意不引用
     * 服务层类型，避免 api.dto 反向依赖 service 包。</p>
     */
    public static LoginResponse of(String token, UserSummaryResponse user,
                                   boolean inviteBound, String inviteUnboundReason) {
        return new LoginResponse(token, "Bearer", user, inviteBound, inviteUnboundReason);
    }
}
