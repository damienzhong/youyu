package com.damien.youyu.api.dto;

import java.util.List;

import com.damien.youyu.service.InviteeListView;

/**
 * 被邀请人列表响应体（GET /api/invite/invitees）：当前页列表项 + 两个口径不同的计数。
 *
 * <p><strong>两个计数刻意不可互相替代</strong>：{@code total} 是邀请关系总条数（含被邀请人已注销的
 * {@code INVALID} 行），不受 {@code page} / {@code size} 影响（需求 7.5）；{@code invitedCount}
 * 只数 {@code REGISTERED} 行，即对外展示的「已邀请人数」（需求 7.6）。</p>
 *
 * <p>不含任何用于指定目标用户的字段：数据范围硬性限定为 {@code inviter_id = 令牌用户}
 * （需求 8.3）。</p>
 *
 * @param items        当前页列表项，条数 ≤ 生效的 {@code size}；页码越界时为空列表（需求 7.10）
 * @param total        邀请关系总条数，含 {@code INVALID}
 * @param invitedCount 已邀请人数，仅 {@code REGISTERED}，≤ {@code total}
 */
public record InviteeListResponse(List<InviteeItemResponse> items, long total, long invitedCount) {

    public static InviteeListResponse from(InviteeListView view) {
        return new InviteeListResponse(
                view.items().stream().map(InviteeItemResponse::from).toList(),
                view.total(),
                view.invitedCount());
    }
}
