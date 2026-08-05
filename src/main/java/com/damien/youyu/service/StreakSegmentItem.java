package com.damien.youyu.service;

import java.time.LocalDate;

/**
 * 历史连续区间列表项：字段集<strong>恰好为 3 项</strong>——起始日、结束日、段天数（需求 6.3）。
 *
 * <p>不含任何金额字段、交易标识、成就编码与解锁状态，也不含段行的 {@code id} / {@code user_id}
 * / {@code created_at} / {@code updated_at} 等派生数据的内部列（需求 6.14）。</p>
 *
 * @param startDate 该连续区间的起始日
 * @param endDate   该连续区间的结束日
 * @param days      段天数，等于结束日与起始日的自然日之差加 1，≥ 1
 */
public record StreakSegmentItem(LocalDate startDate, LocalDate endDate, int days) {
}
