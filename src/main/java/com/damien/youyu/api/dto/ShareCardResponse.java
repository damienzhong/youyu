package com.damien.youyu.api.dto;

import java.math.BigDecimal;

/**
 * 分享卡片数据包（需求 1、2、9、10、12）。纯只读派生，不对应任何数据库表、不落库（需求 13.3）。
 *
 * <p>按卡片类型返回一张成就卡片渲染所需的展示数据：卡片类型、卡片可用性、昵称、文字头像种子、标签（可空）、
 * 一句 AI 文案、品牌名与该卡片类型对应的核心数据 {@code core}。v1 恰好支持 6 种卡片类型（区分大小写）：
 * {@code STREAK_MILESTONE} / {@code MONTHLY_SUMMARY} / {@code ANNUAL_BILL} / {@code ACHIEVEMENT_BADGE} /
 * {@code BUDGET_ACHIEVED} / {@code LEVEL_UP}（需求 1.1）。金额一律 {@link BigDecimal} 保留 2 位小数
 * （HALF_UP），占比/百分比保留 2 位小数（HALF_UP）；自然日/月/年边界按 {@code Asia/Shanghai}（UTC+08:00）；
 * 所有金额统计排除 {@code type=transfer}，与既有 {@code /api/streak}、{@code /api/reports/*}、
 * {@code /api/budgets}、{@code /api/achievements}、{@code /api/growth} 逐值同口径（需求 1.10、13.5）。</p>
 *
 * <p><b>可用/不可用语义</b>：{@code available=true} ⟺ {@code core} 非空、{@code narrative} 非空、
 * {@code unavailableReason=null}；{@code available=false} ⟺ {@code core=null}、{@code narrative=null}、
 * {@code unavailableReason} 为非空原因串，且不返回任何核心数值（需求 3.4、4.5、5.5、6.3、7.4、8.3）。
 * 无论可用与否，{@code cardType}、{@code available}、{@code nickname}、{@code avatarSeed}、
 * {@code brandName} 恒在场；{@code label} 在无可用来源时省略为 {@code null}，且不使卡片出图失败
 * （需求 1.2、2.1、2.4、9.1）。</p>
 *
 * <p><b>隐私白名单（需求 6.6、10.3、12.3、12.4）</b>：本 DTO 的字段集合即隐私白名单，仅包含聚合派生统计
 * （连续天数、里程碑、金额合计、占比、等级、经验、成就名称与解锁日期）、昵称、文字头像种子、标签与由核心
 * 数据生成的一句中文文案 + 品牌名；显式<b>不包含</b>用户邮箱、任何访问/刷新令牌、用户套餐（{@code plan}）、
 * 微信标识（{@code wx_openid} / {@code wx_unionid}）、邀请码、任何不属于当前请求账本上下文的其它账本数据，
 * 也不包含 {@code external_id}、原始备注全文、商户原始标识或附件内容/链接，从结构上杜绝隐私外泄。</p>
 *
 * @param cardType          卡片类型键（6 种之一，区分大小写，需求 1.1）
 * @param available         卡片是否可用（需求 1.2）
 * @param unavailableReason 不可用原因（可用时为 {@code null}）：NO_MILESTONE_ACHIEVED / NO_TRANSACTIONS /
 *                          BADGE_NOT_UNLOCKED / NO_UNLOCKED_ACHIEVEMENT / NO_BUDGET_OR_OVER / LEVEL_TOO_LOW
 * @param nickname          昵称（去首尾空白后为空取「有余用户」，需求 2.3）
 * @param avatarSeed        文字头像种子（昵称首个 Unicode 码点，需求 2.2）
 * @param label             标签（无可用来源时省略为 {@code null}，需求 2.4）
 * @param narrative         一句 AI 文案（卡片可用时非空、1..60 字符；不可用时为 {@code null}，需求 9.1）
 * @param brandName         品牌名（默认「有余」，需求 1.2）
 * @param core              卡片核心数据；卡片不可用时为 {@code null}，且不返回任何核心数值
 *                          （需求 3.4、4.5、5.5、6.3、7.4、8.3）
 */
public record ShareCardResponse(
        String cardType,
        boolean available,
        String unavailableReason,
        String nickname,
        String avatarSeed,
        String label,
        String narrative,
        String brandName,
        ShareCardCore core) {

    /**
     * 卡片核心数据：6 类卡片字段异构，未用到的字段以 {@code null} 表达（需求 1.2、10、12）。金额一律
     * {@link BigDecimal} 保留 2 位小数（HALF_UP），占比/百分比保留 2 位小数（HALF_UP），天数/等级/年月为整数
     * （{@link Integer}），经验为 {@link Long}。各类型字段归属与 null 语义见下：
     *
     * <ul>
     *   <li>{@code STREAK_MILESTONE}（连续记账里程碑）：{@code milestone} / {@code currentStreakDays} /
     *       {@code maxStreakDays}，其余类型这三个字段为 {@code null}（需求 3.3）。</li>
     *   <li>{@code MONTHLY_SUMMARY}（本月总结）：{@code month} / {@code monthStatus} / {@code income} /
     *       {@code expense} / {@code balance}，{@code topCategoryName} / {@code topCategoryPercent} 可空
     *       （无支出分类时为 {@code null}，需求 4.1、4.4）。</li>
     *   <li>{@code ANNUAL_BILL}（年度账单）：{@code year} / {@code yearStatus} / {@code annualIncome} /
     *       {@code annualExpense} / {@code annualBalance}，{@code topExpenseMonth} / {@code topCategoryName}
     *       可空（需求 5.1、5.4）。</li>
     *   <li>{@code ACHIEVEMENT_BADGE}（获得徽章）：{@code badgeName} / {@code badgeDescription} /
     *       {@code unlockedDate}（{@code YYYY-MM-DD}）；不下发成就编码之外的内部标识（需求 6.2、6.6）。</li>
     *   <li>{@code BUDGET_ACHIEVED}（预算达成）：{@code month} / {@code totalBudget} / {@code usedAmount} /
     *       {@code remaining} / {@code usedPercent} / {@code budgetStatus}（需求 7.1、7.3）。</li>
     *   <li>{@code LEVEL_UP}（成长升级）：{@code level} / {@code exp} / {@code expInCurrentLevel} /
     *       {@code maxLevelReached}；满级（等级 100）时 {@code nextLevelExp} / {@code expToNextLevel} 为
     *       {@code null}，未满级时二者非空（需求 8.2、8.4）。</li>
     * </ul>
     *
     * @param milestone          STREAK_MILESTONE 核心里程碑（已达成里程碑的最大取值，需求 3.3）
     * @param currentStreakDays  STREAK_MILESTONE 当前连续天数（需求 3.3）
     * @param maxStreakDays      STREAK_MILESTONE 历史最长连续天数（需求 3.3）
     * @param month              MONTHLY_SUMMARY / BUDGET_ACHIEVED 目标月 {@code YYYY-MM}（需求 4.4、7.3）
     * @param monthStatus        月状态 {@code partial}（进行中）/ {@code final}（已完结）（需求 4.3）
     * @param income             MONTHLY_SUMMARY 本月收入（2dp，需求 4.4）
     * @param expense            MONTHLY_SUMMARY 本月支出（2dp，需求 4.4）
     * @param balance            MONTHLY_SUMMARY 结余（2dp，可负，需求 4.4）
     * @param topCategoryName    MONTHLY_SUMMARY / ANNUAL_BILL 支出占比最高分类名（可空，需求 4.1、5.1）
     * @param topCategoryPercent MONTHLY_SUMMARY 支出占比最高分类占比（%，2dp，可空，需求 4.1）
     * @param year               ANNUAL_BILL 目标年 {@code YYYY}（需求 5.4）
     * @param yearStatus         年状态 {@code partial}（进行中）/ {@code final}（已完结）（需求 5.3）
     * @param annualIncome       ANNUAL_BILL 年度总收入（2dp，需求 5.4）
     * @param annualExpense      ANNUAL_BILL 年度总支出（2dp，需求 5.4）
     * @param annualBalance      ANNUAL_BILL 年度结余（2dp，需求 5.4）
     * @param topExpenseMonth    ANNUAL_BILL 支出最高的自然月 {@code YYYY-MM}（可空，需求 5.1）
     * @param badgeName          ACHIEVEMENT_BADGE 成就展示名称（需求 6.2）
     * @param badgeDescription   ACHIEVEMENT_BADGE 成就中文描述（需求 6.2）
     * @param unlockedDate       ACHIEVEMENT_BADGE 解锁日期 {@code YYYY-MM-DD}（需求 6.2）
     * @param totalBudget        BUDGET_ACHIEVED 本月总预算（2dp，需求 7.3）
     * @param usedAmount         BUDGET_ACHIEVED 已用支出（2dp，需求 7.3）
     * @param remaining          BUDGET_ACHIEVED 剩余（2dp，需求 7.3）
     * @param usedPercent        BUDGET_ACHIEVED 已用百分比（%，2dp，需求 7.1、7.3）
     * @param budgetStatus       BUDGET_ACHIEVED 预算状态 OK / WARN / OVER（需求 7.1）
     * @param level              LEVEL_UP 当前等级（需求 8.2）
     * @param exp                LEVEL_UP 经验值（需求 8.2）
     * @param expInCurrentLevel  LEVEL_UP 本级内已获得经验（需求 8.2）
     * @param maxLevelReached    LEVEL_UP 是否满级（需求 8.4）
     * @param nextLevelExp       LEVEL_UP 下一等级所需经验（满级为 {@code null}，需求 8.4）
     * @param expToNextLevel     LEVEL_UP 升级还需经验（满级为 {@code null}，需求 8.4）
     */
    public record ShareCardCore(
            Integer milestone,
            Integer currentStreakDays,
            Integer maxStreakDays,
            String month,
            String monthStatus,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal balance,
            String topCategoryName,
            BigDecimal topCategoryPercent,
            String year,
            String yearStatus,
            BigDecimal annualIncome,
            BigDecimal annualExpense,
            BigDecimal annualBalance,
            String topExpenseMonth,
            String badgeName,
            String badgeDescription,
            String unlockedDate,
            BigDecimal totalBudget,
            BigDecimal usedAmount,
            BigDecimal remaining,
            BigDecimal usedPercent,
            String budgetStatus,
            Integer level,
            Long exp,
            Long expInCurrentLevel,
            Boolean maxLevelReached,
            Long nextLevelExp,
            Long expToNextLevel) {
    }
}
