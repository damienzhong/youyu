package com.damien.youyu.api.dto;

import com.damien.youyu.service.InviteInfoView;

/**
 * 邀请信息响应体（GET /api/invite）。
 *
 * <p>字段<strong>是且仅是</strong>这三个（需求 1.10）：不含被邀请人明细，不含当前用户的
 * {@code email} / {@code wx_openid} / {@code wx_unionid}，也不含任何用于指定目标用户的字段
 * （{@code userId} / {@code inviterId} / {@code targetUserId} 之类）——数据归属只认令牌用户 id
 * （需求 7.8、8.3）。</p>
 *
 * @param inviteCode   当前用户的 8 位邀请码，非空（原为空时已在服务层惰性补齐）
 * @param inviteLink   {@code /pages/invitelanding/invitelanding?code={邀请码}}（需求 2.1）
 * @param invitedCount 已邀请人数：{@code status = REGISTERED} 的关系条数，≥ 0（需求 7.6）
 */
public record InviteInfoResponse(String inviteCode, String inviteLink, long invitedCount) {

    public static InviteInfoResponse from(InviteInfoView view) {
        return new InviteInfoResponse(view.inviteCode(), view.inviteLink(), view.invitedCount());
    }
}
