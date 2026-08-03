package com.damien.youyu.service;

import java.time.LocalDateTime;

/**
 * 被邀请人列表项：邀请关系主键、被邀请人昵称、注册时刻、关系状态（需求 7.4）。
 *
 * <p>{@code nickname} 允许为 {@code null}：被邀请人昵称为 NULL、去空白后为空、或其 {@code users}
 * 行已因注销而不存在，一律以 {@code null} 返回，<strong>不使用占位文本</strong>；其余三个字段
 * 始终返回真实取值（需求 7.7、10.8）。</p>
 *
 * <p>{@code status} 取 {@code com.damien.youyu.domain.InviteStatus} 的名称
 * （{@code REGISTERED} / {@code INVALID}），与库中取值一字不差。</p>
 *
 * <p>刻意不含被邀请人的 {@code email} / {@code wx_openid} / {@code wx_unionid} /
 * {@code invite_code}（需求 7.8）。</p>
 *
 * @param inviteId     邀请关系主键
 * @param nickname     被邀请人昵称，缺失或空白一律为 {@code null}
 * @param registerTime 被邀请人注册时刻，等于其 {@code users.created_at}
 * @param status       关系状态，{@code REGISTERED} / {@code INVALID}
 */
public record InviteeItemView(Long inviteId, String nickname,
                              LocalDateTime registerTime, String status) {
}
