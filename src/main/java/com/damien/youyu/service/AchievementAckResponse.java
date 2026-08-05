package com.damien.youyu.service;

/**
 * 游标推进响应：顶层字段集<strong>恰好为</strong>「推进后的游标取值」1 项（需求 5.7）。
 *
 * <p>取值是<strong>推进后</strong>库中的实际取值，由服务层在执行
 * {@code ON DUPLICATE KEY UPDATE ... GREATEST(...)} 之后重新读取游标行得到——
 * {@code GREATEST} 的结果只有数据库知道，不能用请求入参充当返回值。重复确认（传入取值
 * 小于或等于当前游标）时不改动该行，并按同一字段集返回<strong>当前</strong>游标取值、
 * 不返回错误（需求 5.8）。</p>
 *
 * <p>声明为原始 {@code long} 而非 {@link Long}：该取值恒存在且恒非负（无游标行时按 0 计，
 * 需求 5.1、5.3），没有空值语义。</p>
 *
 * @param lastNotifiedEventId 推进后的播报游标取值，≥ 0 且相对本次请求前单调不减（需求 5.9）
 */
public record AchievementAckResponse(long lastNotifiedEventId) {
}
