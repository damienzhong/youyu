package com.damien.youyu.service;

import java.util.List;

/**
 * 历史连续区间分页响应：顶层字段集<strong>恰好为 2 项</strong>——区间项列表与区间总条数（需求 6.2）。
 *
 * <p>{@code total} 为该用户的区间总条数，不受 {@code page} 与 {@code size} 影响；页码越界时
 * {@code items} 为空列表而 {@code total} 仍为真实总条数（需求 6.5、6.17）。</p>
 *
 * @param items 区间项列表，每项字段集恰好为 {@link StreakSegmentItem} 的 3 项，按起始日降序
 * @param total 区间总条数，≥ 0
 */
public record StreakSegmentPageResponse(List<StreakSegmentItem> items, long total) {
}
