package com.damien.youyu.service;

/**
 * 一次 {@link GrowthSettlementService#settle} 调用的结果（需求 9.15、10.14）。
 *
 * <p>这个返回值<b>不对外暴露、不进任何 HTTP 响应</b>：结算成败与是否被节流对记账接口与成长概览
 * 都不可见（需求 9.6、9.10、10.14）。它只用于两处内部判定：</p>
 * <ul>
 *   <li>{@link #SKIPPED_THROTTLED} 让调用侧知道本次「什么都没做」，从而可以据此写日志或跳过
 *       {@code markSettled}；</li>
 *   <li>供测试断言「节流命中时确实没有开事务、没有写任何行」（需求 9.15、10.14）。</li>
 * </ul>
 *
 * <p>注意<b>没有 {@code FAILED} 取值</b>：结算失败一律以异常穿出 {@code settle}
 * （{@code @Transactional(REQUIRES_NEW)} 靠异常穿出回滚），由事务边界<b>之外</b>的
 * {@code GrowthSettlementTrigger} / {@code GrowthQueryService} 吞掉并记日志（需求 9.5、9.7）。
 * 用返回码表达失败会诱导调用方在事务方法内部 catch，那正是设计明令禁止的。</p>
 */
public enum SettleOutcome {

    /** 结算已执行并提交（可能实际未写入任何新事件，但走完了取锁—读事实源—重算—写回全流程）。 */
    SETTLED,

    /** 命中节流被跳过：未开启结算事务、未写入任何成长事件与成长档案列（需求 9.15、10.14）。 */
    SKIPPED_THROTTLED
}
