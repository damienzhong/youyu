package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 趣味分析聚合响应（需求 1、8、9、10）。纯只读派生，不对应任何数据库表、不落库（需求 13.2）。
 *
 * <p>一次针对某一自然月，把用户最关心的消费变化算成有故事感的中文洞察，返回目标月挑选后不超过
 * N（默认 5）条趣味洞察，或一条鼓励性兜底文案。金额一律 {@link BigDecimal} 保留 2 位小数（HALF_UP），
 * 变化率（百分比）保留 2 位小数（HALF_UP）；自然月边界按 {@code Asia/Shanghai}（UTC+08:00）；所有金额/
 * 笔数统计排除 {@code type=transfer}，与既有 {@code /api/reports/*} 逐值同口径（需求 13.5）。</p>
 *
 * <p><b>空/兜底语义</b>：兜底态 {@code isFallback=true}、{@code fallbackText} 为一条非空鼓励文案
 * （1..100 字符）、{@code insights} 为空列表；非兜底态 {@code isFallback=false}、{@code fallbackText}
 * 为 {@code null}、{@code insights} 为 1..N 条（需求 9.1–9.6）。</p>
 *
 * <p><b>隐私白名单（需求 12.3、12.4、12.5）</b>：本 DTO 的字段集合即隐私白名单，仅包含聚合派生统计
 * （金额、笔数、变化率、连续月数、打分）与由其生成的中文叙事文案；显式<b>不包含</b>用户邮箱、任何访问/
 * 刷新令牌、任何不属于当前请求账本的数据，也不包含 {@code external_id}、原始备注全文、商户原始标识或
 * 附件内容/链接，从结构上杜绝隐私外泄。</p>
 *
 * @param month        目标月 YYYY-MM（Asia/Shanghai 边界，需求 1.1、9.6、10.4）
 * @param monthStatus  月状态：{@code partial}（进行中）/ {@code final}（已完结）（需求 1.3、1.4、9.6）
 * @param isFallback   兜底态标识：true=返回鼓励文案、insights 为空；false=返回 1..N 条洞察（需求 9.4、9.5）
 * @param fallbackText 鼓励性兜底文案（1..100 字符）；isFallback=false 时为 {@code null}（需求 9.1、9.2、9.3）
 * @param insights     挑选后的趣味洞察（0..N 条，按显著度降序 + 确定性决胜键排序）；兜底态为空列表（需求 7、9）
 */
public record AiInsightsResponse(
        String month,
        String monthStatus,
        boolean isFallback,
        String fallbackText,
        List<AiInsight> insights) {

    /**
     * 单条趣味洞察：机器可读字段 + 渲染好的中文叙事文案（需求 8.1）。
     *
     * <p>因五类洞察（{@code CATEGORY_DELTA} / {@code SAVINGS_TOTAL} / {@code FREQUENCY_DELTA} /
     * {@code TREND_STREAK} / {@code TOP_MOVER}）字段异构，此处采用一个扁平 record + 明确的 null 语义
     * 表达，未用到的字段以 {@code null} 表达，各字段 null 语义见下：</p>
     *
     * <ul>
     *   <li>{@code changeRate}：上月基线（分类支出/总支出/笔数）为 0 时变化率无定义 → {@code null}
     *       （需求 2.2、2.8、3.3、3.8、4.3、6.3）。</li>
     *   <li>{@code SAVINGS_TOTAL}（账本总额维度）：{@code dimension}、{@code dimensionId}、
     *       {@code dimensionName} 均为 {@code null}。</li>
     *   <li>仅 {@code FREQUENCY_DELTA} 携带 {@code currentCount}、{@code previousCount}、
     *       {@code deltaCount}，其余类型这三个字段为 {@code null}。</li>
     *   <li>仅 {@code TREND_STREAK} 携带 {@code streakMonths}、{@code streakStartMonth}、
     *       {@code streakEndMonth}，其余类型这三个字段为 {@code null}。</li>
     *   <li>{@code narrativeText}：叙事文案生成失败（缺维度名或缺全部关键数值）→ {@code null}，
     *       此时保留机器字段、整体不报错（需求 8.8）。</li>
     * </ul>
     *
     * @param type             洞察类型：CATEGORY_DELTA / SAVINGS_TOTAL / FREQUENCY_DELTA / TREND_STREAK / TOP_MOVER
     * @param dimension        维度：CATEGORY / MERCHANT；SAVINGS_TOTAL（账本总额）为 {@code null}
     * @param dimensionId      维度对象 id（分类 id / 商户 id）；SAVINGS_TOTAL 为 {@code null}
     * @param dimensionName    维度名称（回退：分类→{@code "已删除分类"}、商户→{@code "已删除商户"}）；SAVINGS_TOTAL 为 {@code null}（需求 2.7、4.6）
     * @param currentValue     目标月金额值（元，2dp）；金额类洞察在场；纯频次类（若无金额）为 {@code null}
     * @param previousValue    上月金额值（元，2dp）；同 currentValue 语义
     * @param currentCount     目标月笔数；仅 FREQUENCY_DELTA 在场，其余为 {@code null}
     * @param previousCount    上月笔数；仅 FREQUENCY_DELTA 在场，其余为 {@code null}
     * @param deltaAmount      金额变化量 = 目标月 − 上月（元，2dp，可负）；金额类在场，纯频次类为 {@code null}
     * @param deltaCount       笔数变化量 = 目标月 − 上月（整数，可负）；仅 FREQUENCY_DELTA 在场，其余为 {@code null}
     * @param changeRate       变化率（百分比，2dp，HALF_UP）；上月基线为 0（无定义）时为 {@code null}（需求 2.2、3.3、4.3、6.3）
     * @param streakMonths     连续月数（含两端）；仅 TREND_STREAK 在场，其余为 {@code null}（需求 5.4）
     * @param streakStartMonth 连续段起始自然月 YYYY-MM；仅 TREND_STREAK 在场（需求 5.4）
     * @param streakEndMonth   连续段结束自然月 YYYY-MM（= 目标月 M）；仅 TREND_STREAK 在场（需求 5.4）
     * @param direction        语义方向：DOWN（下降/减少）/ UP（上升/增加）；SAVINGS_TOTAL/TOP_MOVER 用 role 表达故为 {@code null}
     * @param role             角色：IMPROVE（改善/节省）/ OVERSPEND（超支/多花）；仅 SAVINGS_TOTAL、TOP_MOVER 在场（需求 3.6/3.7、6.2）
     * @param score            显著度打分（非负，2dp，确定性）；用于排序，前端可忽略（需求 7.1）
     * @param narrativeText    渲染好的中文叙事文案（≤100 字符）；生成失败时为 {@code null}（需求 8.1、8.4、8.5、8.8）
     */
    public record AiInsight(
            String type,
            String dimension,
            Long dimensionId,
            String dimensionName,
            BigDecimal currentValue,
            BigDecimal previousValue,
            Integer currentCount,
            Integer previousCount,
            BigDecimal deltaAmount,
            Integer deltaCount,
            BigDecimal changeRate,
            Integer streakMonths,
            String streakStartMonth,
            String streakEndMonth,
            String direction,
            String role,
            BigDecimal score,
            String narrativeText) {
    }
}
