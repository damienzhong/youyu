package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 趣味人格标签聚合响应（需求 1、8、9、10、11）。纯只读派生，不对应任何数据库表、不落库（需求 13.2）。
 *
 * <p>一次针对某一自然月，根据用户的记账与预算数据贴上一组轻松、俏皮、有温度的人格标签
 * （省钱达人、理财新星、预算大师、外卖探索家、咖啡收藏家、夜宵王、旅行狂人、购物生活家），返回目标月
 * 挑选后不超过 N（默认 4）枚人格标签，或一条鼓励性兜底文案。金额一律 {@link BigDecimal} 保留 2 位小数
 * （HALF_UP），占比/变化率（百分比）保留 2 位小数（HALF_UP）；自然月边界按 {@code Asia/Shanghai}
 * （UTC+08:00）；所有金额/笔数统计排除 {@code type=transfer}，与既有 {@code /api/reports/*}、
 * {@code /api/budgets} 逐值同口径（需求 14.5）。</p>
 *
 * <p><b>空/兜底语义</b>：兜底态 {@code isFallback=true}、{@code fallbackText} 为一条非空鼓励文案
 * （1..60 字符）、{@code tags} 为空列表；非兜底态 {@code isFallback=false}、{@code fallbackText}
 * 为 {@code null}、{@code tags} 为 1..N 枚（需求 10.1–10.6）。</p>
 *
 * <p><b>隐私白名单（需求 13.3、13.4、13.5）</b>：本 DTO 的字段集合即隐私白名单，仅包含聚合派生统计
 * （金额、笔数、占比、结余率、预算使用率、强度分）与由其生成的中文标签文案 + 标题/表情/维度名；显式
 * <b>不包含</b>用户邮箱、任何访问/刷新令牌、任何不属于当前请求账本的数据，也不包含 {@code external_id}、
 * 原始备注全文、商户原始标识或附件内容/链接，从结构上杜绝隐私外泄。</p>
 *
 * @param month        目标月 YYYY-MM（Asia/Shanghai 边界，需求 1.1、10.5、11.7）
 * @param monthStatus  月状态：{@code partial}（进行中）/ {@code final}（已完结）（需求 1.3、1.4、10.5）
 * @param isFallback   兜底态标识：true=返回鼓励文案、tags 为空；false=返回 1..N 枚标签（需求 10.3、10.4）
 * @param fallbackText 鼓励性兜底文案（1..60 字符）；isFallback=false 时为 {@code null}（需求 10.1、10.2、10.6）
 * @param tags         挑选后的人格标签（0..N 枚，按强度分降序 + 固定标签优先级决胜排序）；兜底态为空列表（需求 9、10）
 */
public record PersonalityTagsResponse(
        String month,
        String monthStatus,
        boolean isFallback,
        String fallbackText,
        List<PersonalityTag> tags) {

    /**
     * 单枚人格标签：机器可读字段 + 渲染好的中文标签文案（需求 2.7、8.1）。
     *
     * <p>因 8 枚标签（{@code SAVINGS_MASTER} / {@code FINANCE_STAR} / {@code BUDGET_MASTER} /
     * {@code TAKEOUT_EXPLORER} / {@code COFFEE_COLLECTOR} / {@code LATE_NIGHT_KING} /
     * {@code TRAVEL_ENTHUSIAST} / {@code SHOPPING_LIFER}）的机器字段异构，此处采用一个扁平 record +
     * 明确的 null 语义表达，未用到的字段以 {@code null} 表达，各字段 null 语义见下：</p>
     *
     * <ul>
     *   <li>{@code saveRate}：基线为 0（上月总支出 / 目标月总收入为 0）时节省率/结余率无定义 →
     *       {@code null}（需求 3.2、4.3）。</li>
     *   <li>{@code usedRate}：未设预算或本月预算 ≤ 0 时预算使用率无定义 → {@code null}（需求 5.5）。</li>
     *   <li>聚合类标签（{@code SAVINGS_MASTER} / {@code FINANCE_STAR} / {@code BUDGET_MASTER} /
     *       {@code LATE_NIGHT_KING}）的维度字段 {@code dimension} / {@code dimensionId} /
     *       {@code dimensionName} 均为 {@code null}。</li>
     *   <li>仅行为类标签（外卖 / 咖啡 / 旅行 / 购物）携带 {@code matchCount} / {@code matchAmount} /
     *       {@code matchPercent}，其余类型这三个字段为 {@code null}（需求 6.3、6.6）。</li>
     *   <li>仅 {@code LATE_NIGHT_KING} 携带 {@code lateNightCount} / {@code lateNightWindow}，其余
     *       类型这两个字段为 {@code null}（需求 7.4）。</li>
     *   <li>{@code narrativeText}：文案生成失败（缺标题或缺全部关键数值）→ {@code null}，此时保留机器
     *       字段、整体不报错（需求 8.9）。</li>
     * </ul>
     *
     * @param tagKey          标签键：SAVINGS_MASTER / FINANCE_STAR / BUDGET_MASTER / TAKEOUT_EXPLORER /
     *                        COFFEE_COLLECTOR / LATE_NIGHT_KING / TRAVEL_ENTHUSIAST / SHOPPING_LIFER
     * @param title           标签标题（正向/中性，禁用词零命中，需求 2.1、8.3、8.4）
     * @param emoji           标签表情符号（需求 2.1）
     * @param dimension       判定维度：CATEGORY / MERCHANT；非行为类标签为 {@code null}
     * @param dimensionId     维度对象 id（分类 id / 商户 id）；非行为类或聚合类标签为 {@code null}
     * @param dimensionName   维度名称（回退：分类→{@code "已删除分类"}、商户→{@code "已删除商户"}）；非行为类标签为 {@code null}（需求 6.8）
     * @param currentValue    目标月总支出（元，2dp）；SAVINGS_MASTER/FINANCE_STAR 在场，其余为 {@code null}
     * @param previousValue   上月总支出（元，2dp）；仅 SAVINGS_MASTER 在场，其余为 {@code null}
     * @param income          目标月总收入（元，2dp）；仅 FINANCE_STAR 在场，其余为 {@code null}
     * @param savings         节省额 = 上月总支出 − 目标月总支出（元，2dp，可负）；仅 SAVINGS_MASTER 在场（需求 3.1）
     * @param saveRate        节省率或结余率（%，2dp）；SAVINGS_MASTER=节省率、FINANCE_STAR=结余率；无定义为 {@code null}（需求 3.2、4.3）
     * @param budget          本月预算（元，2dp）；仅 BUDGET_MASTER 在场，其余为 {@code null}（需求 5.3）
     * @param used            本月已用支出（元，2dp）；仅 BUDGET_MASTER 在场（需求 5.3）
     * @param usedRate        预算使用率（%，2dp）；仅 BUDGET_MASTER 在场，无定义为 {@code null}（需求 5.2、5.3、5.5）
     * @param matchCount      行为类标签匹配笔数（整数 ≥0）；仅行为类标签在场，其余为 {@code null}（需求 6.3、6.6）
     * @param matchAmount     行为类标签匹配金额（元，2dp ≥0.00）；仅行为类标签在场（需求 6.3、6.6）
     * @param matchPercent    行为类标签匹配占比（%，2dp）；仅行为类标签在场（需求 6.3、6.6）
     * @param lateNightCount  夜宵时段支出笔数（整数 ≥0）；仅 LATE_NIGHT_KING 在场，其余为 {@code null}（需求 7.4）
     * @param lateNightWindow 夜宵时段描述（如 {@code "22:00-04:00"}）；仅 LATE_NIGHT_KING 在场（需求 7.4）
     * @param threshold       主判定阈值取值（元/占比/笔数，随标签类型语义不同）；用于展示与追溯（需求 2.7、3.4、5.3、6.6、7.4）
     * @param strengthScore   强度分（有限非负，6dp，确定性）；用于排序，前端可忽略（需求 9.1）
     * @param narrativeText   渲染好的中文标签文案（1..60 字符）；生成失败时为 {@code null}（需求 8.1、8.7、8.8、8.9）
     */
    public record PersonalityTag(
            String tagKey,
            String title,
            String emoji,
            String dimension,
            Long dimensionId,
            String dimensionName,
            BigDecimal currentValue,
            BigDecimal previousValue,
            BigDecimal income,
            BigDecimal savings,
            BigDecimal saveRate,
            BigDecimal budget,
            BigDecimal used,
            BigDecimal usedRate,
            Integer matchCount,
            BigDecimal matchAmount,
            BigDecimal matchPercent,
            Integer lateNightCount,
            String lateNightWindow,
            BigDecimal threshold,
            BigDecimal strengthScore,
            String narrativeText) {
    }
}
