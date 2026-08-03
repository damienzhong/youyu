package com.damien.youyu.api.dto;

/**
 * 邀请人展示信息响应体（GET /api/invite/inviter，公开端点）。
 *
 * <p>字段<strong>有且仅有</strong>一个 {@code nickname}（需求 8.5）：不含邀请人的 {@code id} /
 * {@code email} / {@code wx_openid} / {@code wx_unionid} / {@code invite_code} / {@code plan} /
 * {@code role}，也不含任何已邀请人数、注册时刻或账号状态——这是公开端点，多一个字段就多一条
 * 可被枚举的信号。</p>
 *
 * <p>邀请人昵称为 NULL 或去空白后为空时，{@code nickname} 为 {@code null}（需求 4.4）。</p>
 *
 * @param nickname 邀请人昵称，可为 {@code null}
 */
public record InviterBriefResponse(String nickname) {

    public static InviterBriefResponse of(String nickname) {
        return new InviterBriefResponse(nickname);
    }
}
