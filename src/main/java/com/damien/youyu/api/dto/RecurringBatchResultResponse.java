package com.damien.youyu.api.dto;

import java.util.List;

import com.damien.youyu.service.recurring.RecurringBatchResult;

/**
 * 批量确认 / 批量跳过的对外响应体（{@code POST /api/recurring/pending-items/batch-confirm} 与
 * {@code batch-skip}，tasks 7.2）。
 *
 * <p>由服务层值对象 {@link RecurringBatchResult} 一对一映射：{@link #succeededIds} 为成功处理
 * （确认入账 / 跳过）的项 id，{@link #failed} 为逐条失败明细（每条携带 {@code itemId} 与其
 * {@code errorCode}，如 {@code RECURRING_ITEM_ALREADY_PROCESSED}、{@code RECURRING_ITEM_TARGET_MISSING}、
 * {@code NOT_FOUND} 等），{@link #successCount} / {@link #failureCount} 为成功 / 失败计数——使部分失败的
 * 处理结果可被调用方逐条判定（需求 5.4、5.5、5.6）。</p>
 */
public record RecurringBatchResultResponse(
        List<Long> succeededIds,
        List<FailureItem> failed,
        int successCount,
        int failureCount) {

    /** 单条失败明细：待确认项 id 与其失败原因错误码。 */
    public record FailureItem(Long itemId, String errorCode) {
    }

    /** 由服务层批量结果装配对外响应体。 */
    public static RecurringBatchResultResponse from(RecurringBatchResult result) {
        List<FailureItem> failures = result.failed().stream()
                .map(f -> new FailureItem(f.itemId(), f.errorCode()))
                .toList();
        return new RecurringBatchResultResponse(
                result.succeededIds(),
                failures,
                result.successCount(),
                result.failureCount());
    }
}
