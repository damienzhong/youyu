package com.damien.youyu.service;

import java.time.LocalDate;
import java.util.List;

/**
 * 一次有界追补的推导结果（需求 4.6、4.14）。
 *
 * <p>由 {@link GrowthCalendarService#backfillDates(Long, LocalDate, LocalDate)} 产出，供
 * 结算编排据此写入 {@code DAILY_RECORD} 事件并推进 {@code last_record_date}。</p>
 *
 * <p>{@code windowEnd} 是否等于结算日，是结算侧判断「本次是否应为结算日写入
 * {@code DAILY_RECORD:<结算日>}」的依据（需求 4.2、4.14）：窗口末日早于结算日说明该用户仍有
 * 未补发的历史记账日，此时不能让 {@code last_record_date} 越过尚未补发的日期。</p>
 *
 * @param windowStart 追补起点（追补窗口首日）；本次无可追补日期时为 {@code null}
 * @param windowEnd   追补窗口末日，等于「追补起点加 999 天」与结算日两者中的较小者；
 *                    本次无可追补日期时为 {@code null}
 * @param dates       窗口内的记账日集合，按日期升序去重，行数 ≤1000；无可追补日期时为空列表
 */
public record BackfillResult(LocalDate windowStart, LocalDate windowEnd, List<LocalDate> dates) {
}
