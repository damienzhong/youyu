package com.damien.youyu.service.recurring;

import java.util.List;

/**
 * 批量确认 / 批量跳过的<b>服务层</b>逐条处理结果（tasks 5.4，需求 5.4、5.5、5.6）。
 *
 * <p>这是一个纯粹的服务层值对象，<b>不是 HTTP DTO</b>——控制器层（tasks 7.2）再将其映射为对外响应。
 * 批量方法逐条在各自独立事务内处理待确认项：成功条目其 id 收入 {@link #succeededIds}，失败条目
 * （含已处理 {@code RECURRING_ITEM_ALREADY_PROCESSED}、目标缺失 {@code RECURRING_ITEM_TARGET_MISSING}、
 * 跨租户 {@code NOT_FOUND}、校验失败 {@code AMOUNT_INVALID}/{@code NOTE_TOO_LONG} 等）收入 {@link #failed}
 * 并携带其 {@link Failure#errorCode()}，从而让部分失败的处理结果可被调用方<b>逐条判定</b>（需求 5.6）。</p>
 *
 * <p>{@link #successCount} / {@link #failureCount} 分别等于 {@code succeededIds.size()} 与
 * {@code failed.size()}，其和等于本次批量请求的待处理条目数——单条失败仅回滚该条、不影响也不回滚其余
 * （需求 5.4、5.5）。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 *
 * @param succeededIds 成功处理（确认入账 / 跳过）的待确认项 id 列表，按请求顺序
 * @param failed       失败条目列表，每条携带其 id 与错误码，按请求顺序
 * @param successCount 成功条目数（{@code == succeededIds.size()}）
 * @param failureCount 失败条目数（{@code == failed.size()}）
 */
public record RecurringBatchResult(
        List<Long> succeededIds,
        List<Failure> failed,
        int successCount,
        int failureCount) {

    /**
     * 单条失败明细：待确认项 id 与其失败原因错误码（{@link com.damien.youyu.error.ApiException#getCode()}，
     * 如 {@code RECURRING_ITEM_ALREADY_PROCESSED}、{@code RECURRING_ITEM_TARGET_MISSING}、{@code NOT_FOUND}、
     * {@code AMOUNT_INVALID}、{@code NOTE_TOO_LONG}）。非预期运行时异常回退为
     * {@link #INTERNAL_ERROR_CODE}，其对应事务已回滚、不影响其余条目（需求 5.4、5.5）。
     */
    public record Failure(Long itemId, String errorCode) {
    }

    /** 非 {@link com.damien.youyu.error.ApiException} 运行时异常的回退错误码。 */
    public static final String INTERNAL_ERROR_CODE = "RECURRING_ITEM_PROCESS_FAILED";

    /** 由逐条结果聚合构造，自动填充成功 / 失败计数，保证计数与列表长度一致。 */
    public static RecurringBatchResult of(List<Long> succeededIds, List<Failure> failed) {
        return new RecurringBatchResult(succeededIds, failed, succeededIds.size(), failed.size());
    }
}
