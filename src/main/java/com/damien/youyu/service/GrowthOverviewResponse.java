package com.damien.youyu.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成长概览响应：字段集<strong>恰好等于</strong>需求 10.1 的 15 项（需求 10.13）。
 *
 * <p>由该相等性，本响应<strong>不含</strong> {@code email} / {@code wx_openid} / {@code wx_unionid}
 * / {@code invite_code} / {@code plan} / {@code role} 六个字段的键与取值，也<strong>不含</strong>任何
 * 用于指定目标用户身份的字段——数据范围硬性限定为令牌所标识的用户本人（需求 10.8、10.13）。</p>
 *
 * <p>金额一律以 {@link BigDecimal} 承载（需求 7 的累计金额口径）。等级换算的六个字段
 * （{@code currentLevelExp} / {@code nextLevelExp} / {@code expInCurrentLevel} / {@code expToNextLevel}
 * / {@code maxLevel} / {@code maxLevelReached}）中，满级时 {@code nextLevelExp} 与 {@code expToNextLevel}
 * 为 {@code null}（无下一级），故声明为 {@link Long} 而非 {@code long}（需求 2.10）。</p>
 *
 * @param level             当前等级，1–100
 * @param exp               经验值，等于该用户全部成长事件 {@code exp_amount} 之和，只增不减（需求 1.1）
 * @param currentLevelExp   当前等级的起始经验（该等级阈值）
 * @param nextLevelExp      下一等级所需经验；满级时为 {@code null}
 * @param expInCurrentLevel 本级内已获得经验
 * @param expToNextLevel    升级还需经验；满级时为 {@code null}
 * @param maxLevel          最高等级（100）
 * @param maxLevelReached   是否已满级
 * @param totalRecordCount  累计记账笔数，≥ 0
 * @param totalExpense      累计支出金额，保留 2 位，非负（需求 7.15）
 * @param totalIncome       累计收入金额，保留 2 位，非负（需求 7.15）
 * @param totalRecordDays   累计记账天数，≥ 0
 * @param currentStreakDays 当前连续天数，按判定日校正后 ≥ 0
 * @param maxStreakDays     历史最长连续天数，≥ 0 且 ≥ {@code currentStreakDays}
 * @param badges            徽章列表，恒为 9 枚、按 {@link GrowthBadgeCatalog} 顺序（需求 8.5、8.8）
 */
public record GrowthOverviewResponse(int level, long exp, long currentLevelExp, Long nextLevelExp,
                                     long expInCurrentLevel, Long expToNextLevel, int maxLevel,
                                     boolean maxLevelReached, long totalRecordCount,
                                     BigDecimal totalExpense, BigDecimal totalIncome,
                                     int totalRecordDays, int currentStreakDays, int maxStreakDays,
                                     List<BadgeView> badges) {
}
