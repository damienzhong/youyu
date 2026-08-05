package com.damien.youyu.service;

import java.time.LocalDate;

/**
 * 连续记账概览响应：字段集<strong>恰好等于</strong>需求 6.1 的 14 项，顺序与
 * design.md「7. 接口设计」的字段表逐项对齐（需求 6.1、6.14）。
 *
 * <p>这 14 个字段的键在每次成功响应中<strong>恒存在</strong>，取值为空时序列化为 {@code null}
 * 而不省略键。为此，全部可空项——六个日期（{@code currentSegmentStart} / {@code currentSegmentEnd}
 * / {@code lastStreakEnd} / {@code longestSegmentStart} / {@code longestSegmentEnd}）、
 * {@code lastStreakDays} 与两个里程碑字段（{@code nextMilestone} / {@code daysToNextMilestone}）——
 * 一律以包装类型（{@link Integer} / {@link LocalDate}）声明，使 {@code null} 序列化为 JSON {@code null}，
 * 而不是像基本类型那样退化为 {@code 0}、也不是被 {@code NON_NULL} 策略省略键（需求 6.1）。</p>
 *
 * <p>本响应<strong>不含</strong> {@code email} / {@code wx_openid} / {@code wx_unionid}
 * / {@code invite_code} / {@code plan} / {@code role} 六个字段的键与取值，<strong>不含</strong>
 * 任何金额字段与任何交易标识，也<strong>不含</strong>任何成就编码、成就解锁状态与解锁时刻
 * （需求 6.14、3.9）。里程碑是激励语义（按当前连续天数算进度），成就是收集语义（按历史最长连续
 * 天数解锁），两者刻意不同，故本响应不暴露成就任何信息，以免用户把「还差 N 天」误读成「成就没拿到」。</p>
 *
 * <p><strong>不新增第 15 个字段（已知偏差 ②）</strong>：需求 3.7 / 3.11 提到「全部里程碑已达成标识」，
 * 而需求 6.1 把顶层字段集钉死为<strong>恰好 14 项</strong>且不含该标识。本响应不加该字段——
 * 「全部里程碑已达成」由 {@code nextMilestone == null} 完全等价推出，前端直接据此渲染
 * （详见 design.md「已知偏差与残留风险」的定案裁决）。</p>
 *
 * @param todayDone            今日已完成
 * @param currentStreakDays    当前连续天数，∈ [0, {@code maxStreakDays}]
 * @param broken               连续中断标识（记账日历为空时为 {@code false}）
 * @param currentSegmentStart  当前段起始日；无当前段时为 {@code null}
 * @param currentSegmentEnd    当前段结束日（= 最近记账日）；无当前段时为 {@code null}
 * @param lastStreakDays       上次连续天数，仅 {@code broken} 为真且记账日历非空时非空，否则 {@code null}
 * @param lastStreakEnd        上次连续结束日，同 {@code lastStreakDays} 的空/非空条件
 * @param maxStreakDays        历史最长连续天数（= {@code user_growth.max_streak_days}）
 * @param longestSegmentStart  最长段起始日；记账日历为空时为 {@code null}
 * @param longestSegmentEnd    最长段结束日；记账日历为空时为 {@code null}
 * @param totalRecordDays      累计记账天数，≥ 0
 * @param segmentCount         段总数，≥ 0
 * @param nextMilestone        下一里程碑；为空即全部里程碑已达成
 * @param daysToNextMilestone  距下一里程碑还需天数；非空时 ∈ [1, 里程碑最大值]，{@code nextMilestone}
 *                             为空时为 {@code null}
 */
public record StreakOverviewResponse(boolean todayDone, int currentStreakDays, boolean broken,
                                     LocalDate currentSegmentStart, LocalDate currentSegmentEnd,
                                     Integer lastStreakDays, LocalDate lastStreakEnd,
                                     int maxStreakDays, LocalDate longestSegmentStart,
                                     LocalDate longestSegmentEnd, int totalRecordDays,
                                     long segmentCount, Integer nextMilestone,
                                     Integer daysToNextMilestone) {
}
