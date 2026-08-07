package com.damien.youyu.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 趣味分析（ai-fun-analysis）可配置阈值载体。
 *
 * <p>前缀 {@code youyu.ai-insight}。集中承载显著变化阈值与展示上限 N，缺省值即需求默认值；
 * 本类为纯配置载体，不落库、不新增数据库表、不新增错误码。金额与变化率阈值一律用
 * {@link BigDecimal}（元 / 百分比），与既有报表口径（2 位小数）保持一致。</p>
 *
 * <p>通过 {@link Component} + {@link ConfigurationProperties} 由组件扫描注册为 Bean，
 * 采用 JavaBean（getter/setter）绑定；未在配置文件中显式覆盖时使用下方字段默认值。</p>
 */
@Component
@ConfigurationProperties(prefix = "youyu.ai-insight")
public class AiInsightProperties {

    /** {@code maxCount} 的下限（含）。需求 7.2：N 取值范围 1–20。 */
    public static final int MAX_COUNT_LOWER_BOUND = 1;

    /** {@code maxCount} 的上限（含）。需求 7.2：N 取值范围 1–20。 */
    public static final int MAX_COUNT_UPPER_BOUND = 20;

    /**
     * 展示数量上限 N：一次最多返回的趣味洞察条数，默认 5。
     *
     * <p>读取时应经 {@link #maxCountClamped()} 钳制到 1–20（越界向边界取整）。
     * 对应需求 7.2（N 可配置，取值范围 1–20，默认 5）、需求 10.1（接口一次返回不超过 N 条）。</p>
     */
    private int maxCount = 5;

    /**
     * 分类涨跌变化率下限（绝对值，百分比），默认 10.00。
     *
     * <p>对应需求 2.3：仅当变化率绝对值不小于该下限时才生成 {@code CATEGORY_DELTA} 候选。</p>
     */
    private BigDecimal categoryRatePctMin = new BigDecimal("10.00");

    /**
     * 分类涨跌金额下限（绝对值，元），默认 20.00。
     *
     * <p>对应需求 2.3：仅当变化量绝对值不小于该下限时才生成 {@code CATEGORY_DELTA} 候选。</p>
     */
    private BigDecimal categoryAmountMin = new BigDecimal("20.00");

    /**
     * 节省额下限（绝对值，元），默认 50.00。
     *
     * <p>对应需求 3.4、3.5：仅当上月总支出 &gt; 0 且节省额绝对值不小于该下限时才生成
     * {@code SAVINGS_TOTAL} 候选。</p>
     */
    private BigDecimal savingsAmountMin = new BigDecimal("50.00");

    /**
     * 频次变化率下限（绝对值，百分比），默认 20.00。
     *
     * <p>对应需求 4.4：仅当笔数变化率绝对值不小于该下限时才生成 {@code FREQUENCY_DELTA} 候选。</p>
     */
    private BigDecimal frequencyRatePctMin = new BigDecimal("20.00");

    /**
     * 频次变化量下限（绝对值，笔），默认 2。
     *
     * <p>对应需求 4.4：仅当笔数变化量绝对值不小于该下限时才生成 {@code FREQUENCY_DELTA} 候选。</p>
     */
    private int frequencyCountMin = 2;

    /**
     * 连续涨跌趋势的连续月数下限，默认 3。
     *
     * <p>对应需求 5.4、5.6：仅当连续递减或递增月数不小于该下限时才生成 {@code TREND_STREAK} 候选。</p>
     */
    private int streakMinMonths = 3;

    /**
     * 返回展示数量上限 N 的已钳制取值：越界（&lt;1 或 &gt;20）向最近边界取整。
     *
     * <p>对应需求 7.2、10.1。读取 N 时应一律使用本方法而非直接读 {@link #getMaxCount()}，
     * 以保证 N 落在 [1, 20] 区间内。</p>
     *
     * @return 钳制到 [1, 20] 的展示数量上限
     */
    public int maxCountClamped() {
        return Math.max(MAX_COUNT_LOWER_BOUND, Math.min(MAX_COUNT_UPPER_BOUND, maxCount));
    }

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }

    public BigDecimal getCategoryRatePctMin() {
        return categoryRatePctMin;
    }

    public void setCategoryRatePctMin(BigDecimal categoryRatePctMin) {
        this.categoryRatePctMin = categoryRatePctMin;
    }

    public BigDecimal getCategoryAmountMin() {
        return categoryAmountMin;
    }

    public void setCategoryAmountMin(BigDecimal categoryAmountMin) {
        this.categoryAmountMin = categoryAmountMin;
    }

    public BigDecimal getSavingsAmountMin() {
        return savingsAmountMin;
    }

    public void setSavingsAmountMin(BigDecimal savingsAmountMin) {
        this.savingsAmountMin = savingsAmountMin;
    }

    public BigDecimal getFrequencyRatePctMin() {
        return frequencyRatePctMin;
    }

    public void setFrequencyRatePctMin(BigDecimal frequencyRatePctMin) {
        this.frequencyRatePctMin = frequencyRatePctMin;
    }

    public int getFrequencyCountMin() {
        return frequencyCountMin;
    }

    public void setFrequencyCountMin(int frequencyCountMin) {
        this.frequencyCountMin = frequencyCountMin;
    }

    public int getStreakMinMonths() {
        return streakMinMonths;
    }

    public void setStreakMinMonths(int streakMinMonths) {
        this.streakMinMonths = streakMinMonths;
    }
}
