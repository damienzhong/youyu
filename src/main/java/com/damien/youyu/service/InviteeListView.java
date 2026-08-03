package com.damien.youyu.service;

import java.util.List;

/**
 * 被邀请人列表视图：当前页列表项 + 两个口径不同的计数。
 *
 * <p><strong>两个计数刻意不可互相替代</strong>：{@code total} 是邀请关系总条数（含被邀请人已注销的
 * {@code INVALID} 行），不受 {@code page} / {@code size} 影响（需求 7.5）；{@code invitedCount}
 * 只数 {@code REGISTERED} 行，即对外展示的「已邀请人数」（需求 7.6）。二者之差恒等于该用户名下
 * {@code INVALID} 行数——这条恒等式是「注销只改被邀请人状态、不动邀请人名下任何行」的可观测后果。</p>
 *
 * @param items        当前页列表项，条数 ≤ 生效的 {@code size}；页码越界时为空列表（需求 7.10）
 * @param total        邀请关系总条数，含 {@code INVALID}
 * @param invitedCount 已邀请人数，仅 {@code REGISTERED}，≤ {@code total}
 */
public record InviteeListView(List<InviteeItemView> items, long total, long invitedCount) {
}
