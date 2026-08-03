package com.damien.youyu.api.dto;

import java.time.LocalDateTime;

import com.damien.youyu.service.InviteeItemView;

/**
 * 被邀请人列表项响应体：邀请关系主键、被邀请人昵称、注册时刻、关系状态（需求 7.4）。
 *
 * <p>{@code nickname} 允许为 {@code null}：被邀请人昵称为 NULL、去空白后为空、或其 {@code users}
 * 行已因注销而不存在，一律以 {@code null} 返回，<strong>不使用占位文本</strong>（需求 7.7、10.8）。</p>
 *
 * <p>刻意不含被邀请人的 {@code email} / {@code wx_openid} / {@code wx_unionid} /
 * {@code invite_code}，也不含被邀请人的用户 id（需求 7.8）。</p>
 *
 * @param inviteId     邀请关系主键
 * @param nickname     被邀请人昵称，缺失或空白一律为 {@code null}
 * @param registerTime 被邀请人注册时刻，等于其 {@code users.created_at}
 * @param status       关系状态，{@code REGISTERED} / {@code INVALID}
 */
public record InviteeItemResponse(Long inviteId, String nickname,
                                 LocalDateTime registerTime, String status) {

    public static InviteeItemResponse from(InviteeItemView view) {
        return new InviteeItemResponse(
                view.inviteId(),
                view.nickname(),
                view.registerTime(),
                view.status());
    }
}
