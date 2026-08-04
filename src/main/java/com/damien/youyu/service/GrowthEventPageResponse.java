package com.damien.youyu.service;

import java.util.List;

/**
 * 经验明细分页响应：顶层字段集<strong>恰好为</strong>「列表项 + 总条数」2 项（需求 10.13）。
 *
 * <p>{@code total} 是该用户成长事件的总条数，<strong>不受</strong> {@code page} / {@code size}
 * 影响；客户端以同一 {@code size} 逐页取完时，各页 {@code items} 条数之和恒等于 {@code total}
 * （需求 10.5）。页码越界时 {@code items} 为空列表、{@code total} 仍为真实总条数（需求 10.10）。</p>
 *
 * @param items 当前页列表项，条数 ≤ 生效的 {@code size}，按 {@code id} 倒序
 * @param total 成长事件总条数，≥ 0，不受分页参数影响
 */
public record GrowthEventPageResponse(List<GrowthEventItem> items, long total) {
}
