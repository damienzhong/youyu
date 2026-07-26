package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预算总览响应体（月度总预算 + 预算健康 + 分类预算）。
 *
 * <p>金额一律 {@link BigDecimal} 保留 2 位小数；百分比为整数（可超过 100 表示超支）。
 * 支出统计排除转账（需求 4.12/7.5），按 {@code Asia/Shanghai} 自然月边界聚合。</p>
 *
 * @param month        自然月 YYYY-MM
 * @param hasBudget    是否已设置月度总预算
 * @param totalBudget  月度总预算金额（未设置为 null）
 * @param spent        本月已支出（排除转账）
 * @param remaining    剩余可用 = 总预算 - 已支出（未设置预算时为 null）
 * @param usedPercent  已用百分比（未设置预算时为 0；可超过 100）
 * @param status       预算状态：OK / WARN(>=80%) / OVER(>100%)；未设置为 null
 * @param currentMonth 是否为当前自然月（决定是否给出前瞻健康信息）
 * @param health       预算健康（仅当前月且已设预算时非 null）
 * @param allocated    已分配分类预算之和（未设总预算时仍返回，便于前端展示）
 * @param unallocated  未分配额度 = 总预算 - 已分配（未设总预算为 null）
 * @param categories   分类预算明细（仅包含已设置分类预算的分类）
 */
public record BudgetOverviewResponse(
        String month,
        boolean hasBudget,
        BigDecimal totalBudget,
        BigDecimal spent,
        BigDecimal remaining,
        int usedPercent,
        String status,
        boolean currentMonth,
        BudgetHealth health,
        BigDecimal allocated,
        BigDecimal unallocated,
        List<CategoryBudgetItem> categories) {

    /**
     * 预算健康：帮助用户判断「能否撑到月底」（竞品缺失的前瞻信息）。
     *
     * @param daysLeft         本月剩余天数（含今天）
     * @param dailyAvailable   日均可用 = 剩余 / 剩余天数（剩余或天数<=0 时为 0）
     * @param projectedBalance 按当前日均支出速度预计到月底的结余（可为负=预计超支）
     * @param projectedOver    预计是否超支（projectedBalance < 0）
     */
    public record BudgetHealth(
            int daysLeft,
            BigDecimal dailyAvailable,
            BigDecimal projectedBalance,
            boolean projectedOver) {
    }

    /**
     * 单个分类预算明细。
     *
     * @param categoryId  分类 id
     * @param name        分类名称（父·子形式由前端组织，这里给分类自身名称）
     * @param budget      该分类预算
     * @param spent       该分类本月已支出
     * @param remaining   剩余 = 预算 - 已支出（可为负=超支）
     * @param usedPercent 已用百分比（可超过 100）
     * @param txCount     该分类本月支出笔数
     * @param status      OK / WARN(>=80%) / OVER(>100%)
     */
    public record CategoryBudgetItem(
            Long categoryId,
            String name,
            BigDecimal budget,
            BigDecimal spent,
            BigDecimal remaining,
            int usedPercent,
            int txCount,
            String status) {
    }
}
