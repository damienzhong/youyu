package com.damien.youyu.api.dto;

import java.util.List;

/**
 * 批量确认 / 批量跳过请求体（{@code POST /api/recurring/pending-items/batch-confirm} 与
 * {@code batch-skip}，tasks 7.2）。
 *
 * <p>{@code ids} 为待处理的待确认项 id 列表，逐条在各自独立事务内按单条口径处理（需求 5.4、5.5）。
 * {@code null} / 空列表视为无待处理条目，返回空批量结果（成功 / 失败计数均为 0）而不报错。</p>
 */
public record RecurringBatchRequest(List<Long> ids) {
}
