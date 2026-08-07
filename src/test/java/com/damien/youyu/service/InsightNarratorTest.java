package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.damien.youyu.api.dto.AiInsightsResponse.AiInsight;

/**
 * {@link InsightNarrator} 纯函数单元测试（任务 3.2，关联需求 8.2、8.4、8.5、8.6、8.7、8.8）。
 *
 * <p>{@code render(AiInsight)} 无任何 I/O、不接外部服务，故不起 Spring 上下文，直接实例化断言：</p>
 * <ul>
 *   <li>逐类逐方向断言<b>数值一致</b>（需求 8.4）：文案内出现的每个数字都等于机器字段按同口径
 *       格式化（金额 2dp HALF_UP、变化率百分比 2dp HALF_UP、次数/连续月数为整数）后的值，
 *       且不含任何额外的、不属于机器字段的数字。</li>
 *   <li>断言文案<b>长度 ≤ 100 个中文字符</b>（需求 8.5）。</li>
 *   <li>断言<b>措辞极性</b>正确（需求 8.6、8.7）：DOWN / {@code role=IMPROVE} → 正向或中性、
 *       不含任何提醒/警示词；UP / {@code role=OVERSPEND} → 提醒性措辞。</li>
 *   <li>覆盖<b>回退名</b>分支（需求 2.7、4.6）：分类名缺失/空白 → {@code 已删除分类}；
 *       商户名缺失/空白 → {@code 已删除商户}。</li>
 *   <li>覆盖<b>生成失败</b>分支（需求 8.8）：缺全部关键数值 → {@code render} 返回 {@code null}，不抛错。</li>
 * </ul>
 */
class InsightNarratorTest {

    private static final int MAX_CHINESE_CHARS = 100;

    /** 提醒/警示性措辞标记词（需求 8.6 要求正向文案不得含之，需求 8.7 要求提醒文案至少含其一）。 */
    private static final List<String> REMINDER_WORDS = List.of("留意", "关注", "记得");

    private final InsightNarrator narrator = new InsightNarrator();

    // ============================== CATEGORY_DELTA ==============================

    @Nested
    class CategoryDelta {

        @Test
        void downUsesPositiveWordingAndConsistentNumbers() {
            AiInsight in = insight("CATEGORY_DELTA")
                    .dimension("CATEGORY").dimensionName("餐饮")
                    .deltaAmount(new BigDecimal("-123.456")) // 绝对值 2dp -> 123.46
                    .changeRate(new BigDecimal("-18.005"))   // 绝对值 2dp -> 18.01 (HALF_UP)
                    .direction("DOWN")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull().contains("餐饮");
            assertLengthWithinLimit(text);
            assertPositiveWording(text);
            assertNumbersConsistent(text, money(in.deltaAmount()), pct(in.changeRate()));
            assertThat(text).contains(money(in.deltaAmount())).contains(pct(in.changeRate()));
        }

        @Test
        void upUsesReminderWordingAndConsistentNumbers() {
            AiInsight in = insight("CATEGORY_DELTA")
                    .dimension("CATEGORY").dimensionName("购物")
                    .deltaAmount(new BigDecimal("200.00"))
                    .changeRate(new BigDecimal("35.50"))
                    .direction("UP")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull().contains("购物");
            assertLengthWithinLimit(text);
            assertReminderWording(text);
            assertNumbersConsistent(text, money(in.deltaAmount()), pct(in.changeRate()));
        }

        @Test
        void amountOnlyRendersWithoutRate() {
            AiInsight in = insight("CATEGORY_DELTA")
                    .dimension("CATEGORY").dimensionName("餐饮")
                    .deltaAmount(new BigDecimal("-88.80"))
                    .direction("DOWN")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull();
            assertNumbersConsistent(text, money(in.deltaAmount()));
        }

        @Test
        void directionInferredFromAmountSignWhenDirectionNull() {
            AiInsight down = insight("CATEGORY_DELTA")
                    .dimension("CATEGORY").dimensionName("餐饮")
                    .deltaAmount(new BigDecimal("-50.00"))
                    .changeRate(new BigDecimal("12.00"))
                    .build();
            AiInsight up = insight("CATEGORY_DELTA")
                    .dimension("CATEGORY").dimensionName("餐饮")
                    .deltaAmount(new BigDecimal("50.00"))
                    .changeRate(new BigDecimal("12.00"))
                    .build();

            assertPositiveWording(narrator.render(down));
            assertReminderWording(narrator.render(up));
        }
    }

    // ============================== SAVINGS_TOTAL ==============================

    @Nested
    class SavingsTotal {

        @Test
        void improveUsesPositiveWordingAndConsistentNumbers() {
            AiInsight in = insight("SAVINGS_TOTAL")
                    .deltaAmount(new BigDecimal("532.00"))
                    .changeRate(new BigDecimal("21.34"))
                    .role("IMPROVE")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull();
            assertLengthWithinLimit(text);
            assertPositiveWording(text);
            assertNumbersConsistent(text, money(in.deltaAmount()), pct(in.changeRate()));
        }

        @Test
        void overspendUsesReminderWordingAndConsistentNumbers() {
            AiInsight in = insight("SAVINGS_TOTAL")
                    .deltaAmount(new BigDecimal("-410.20"))
                    .changeRate(new BigDecimal("-15.00"))
                    .role("OVERSPEND")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull();
            assertLengthWithinLimit(text);
            assertReminderWording(text);
            assertNumbersConsistent(text, money(in.deltaAmount()), pct(in.changeRate()));
        }

        @Test
        void rateOnlyImproveRendersWithoutAmount() {
            AiInsight in = insight("SAVINGS_TOTAL")
                    .changeRate(new BigDecimal("9.99"))
                    .role("IMPROVE")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull();
            assertPositiveWording(text);
            assertNumbersConsistent(text, pct(in.changeRate()));
        }
    }

    // ============================== FREQUENCY_DELTA ==============================

    @Nested
    class FrequencyDelta {

        @Test
        void merchantDownUsesPositiveWordingAndConsistentNumbers() {
            AiInsight in = insight("FREQUENCY_DELTA")
                    .dimension("MERCHANT").dimensionName("喜茶")
                    .deltaCount(-4)
                    .changeRate(new BigDecimal("40.00"))
                    .direction("DOWN")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull().contains("喜茶");
            assertLengthWithinLimit(text);
            assertPositiveWording(text);
            assertNumbersConsistent(text, count(in.deltaCount()), pct(in.changeRate()));
        }

        @Test
        void categoryUpUsesReminderWordingAndConsistentNumbers() {
            AiInsight in = insight("FREQUENCY_DELTA")
                    .dimension("CATEGORY").dimensionName("外卖")
                    .deltaCount(6)
                    .changeRate(new BigDecimal("50.00"))
                    .direction("UP")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull().contains("外卖");
            assertLengthWithinLimit(text);
            assertReminderWording(text);
            assertNumbersConsistent(text, count(in.deltaCount()), pct(in.changeRate()));
        }

        @Test
        void countUsesAbsoluteValue() {
            AiInsight in = insight("FREQUENCY_DELTA")
                    .dimension("CATEGORY").dimensionName("外卖")
                    .deltaCount(-3)
                    .direction("DOWN")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull().contains("3").doesNotContain("-3");
            assertNumbersConsistent(text, count(in.deltaCount()));
        }
    }

    // ============================== TREND_STREAK ==============================

    @Nested
    class TrendStreak {

        @Test
        void downUsesPositiveWordingAndConsistentNumbers() {
            AiInsight in = insight("TREND_STREAK")
                    .dimension("CATEGORY").dimensionName("外卖")
                    .streakMonths(4)
                    .direction("DOWN")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull().contains("外卖");
            assertLengthWithinLimit(text);
            assertPositiveWording(text);
            assertNumbersConsistent(text, String.valueOf(in.streakMonths()));
        }

        @Test
        void upUsesReminderWordingAndConsistentNumbers() {
            AiInsight in = insight("TREND_STREAK")
                    .dimension("CATEGORY").dimensionName("购物")
                    .streakMonths(3)
                    .direction("UP")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull().contains("购物");
            assertLengthWithinLimit(text);
            assertReminderWording(text);
            assertNumbersConsistent(text, String.valueOf(in.streakMonths()));
        }
    }

    // ============================== TOP_MOVER ==============================

    @Nested
    class TopMover {

        @Test
        void improveUsesPositiveWordingAndConsistentNumbers() {
            AiInsight in = insight("TOP_MOVER")
                    .dimension("CATEGORY").dimensionName("餐饮")
                    .deltaAmount(new BigDecimal("-300.00"))
                    .changeRate(new BigDecimal("-25.00"))
                    .role("IMPROVE")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull().contains("餐饮");
            assertLengthWithinLimit(text);
            assertPositiveWording(text);
            assertNumbersConsistent(text, money(in.deltaAmount()), pct(in.changeRate()));
        }

        @Test
        void overspendUsesReminderWordingAndConsistentNumbers() {
            AiInsight in = insight("TOP_MOVER")
                    .dimension("CATEGORY").dimensionName("购物")
                    .deltaAmount(new BigDecimal("450.00"))
                    .changeRate(new BigDecimal("60.00"))
                    .role("OVERSPEND")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull().contains("购物");
            assertLengthWithinLimit(text);
            assertReminderWording(text);
            assertNumbersConsistent(text, money(in.deltaAmount()), pct(in.changeRate()));
        }

        @Test
        void improveRateOnlyRendersWithoutAmount() {
            AiInsight in = insight("TOP_MOVER")
                    .dimension("CATEGORY").dimensionName("餐饮")
                    .changeRate(new BigDecimal("-25.00"))
                    .role("IMPROVE")
                    .build();

            String text = narrator.render(in);

            assertThat(text).isNotNull();
            assertPositiveWording(text);
            assertNumbersConsistent(text, pct(in.changeRate()));
        }
    }

    // ============================== 回退名分支（需求 2.7、4.6） ==============================

    @Nested
    class FallbackNames {

        @Test
        void blankCategoryNameFallsBackToDeletedCategory() {
            AiInsight nullName = insight("CATEGORY_DELTA")
                    .dimension("CATEGORY").dimensionName(null)
                    .deltaAmount(new BigDecimal("-50.00")).changeRate(new BigDecimal("12.00"))
                    .direction("DOWN").build();
            AiInsight blankName = insight("CATEGORY_DELTA")
                    .dimension("CATEGORY").dimensionName("   ")
                    .deltaAmount(new BigDecimal("-50.00")).changeRate(new BigDecimal("12.00"))
                    .direction("DOWN").build();

            assertThat(narrator.render(nullName)).contains("已删除分类");
            assertThat(narrator.render(blankName)).contains("已删除分类");
        }

        @Test
        void blankMerchantNameFallsBackToDeletedMerchant() {
            AiInsight nullName = insight("FREQUENCY_DELTA")
                    .dimension("MERCHANT").dimensionName(null)
                    .deltaCount(-3).direction("DOWN").build();
            AiInsight blankName = insight("FREQUENCY_DELTA")
                    .dimension("MERCHANT").dimensionName("")
                    .deltaCount(-3).direction("DOWN").build();

            assertThat(narrator.render(nullName)).contains("已删除商户");
            assertThat(narrator.render(blankName)).contains("已删除商户");
        }

        @Test
        void blankCategoryFrequencyNameFallsBackToDeletedCategory() {
            AiInsight in = insight("FREQUENCY_DELTA")
                    .dimension("CATEGORY").dimensionName(" ")
                    .deltaCount(-3).direction("DOWN").build();

            assertThat(narrator.render(in)).contains("已删除分类");
        }

        @Test
        void insightIsNotDroppedWhenNameMissing() {
            AiInsight in = insight("TREND_STREAK")
                    .dimension("CATEGORY").dimensionName(null)
                    .streakMonths(3).direction("DOWN").build();

            String text = narrator.render(in);
            assertThat(text).isNotNull().contains("已删除分类");
            assertNumbersConsistent(text, "3");
        }
    }

    // ============================== 生成失败分支（需求 8.8） ==============================

    @Nested
    class GenerationFailure {

        @Test
        void nullInsightReturnsNull() {
            assertThat(narrator.render(null)).isNull();
        }

        @Test
        void nullTypeReturnsNull() {
            assertThat(narrator.render(insight(null).build())).isNull();
        }

        @Test
        void unknownTypeReturnsNull() {
            assertThat(narrator.render(insight("SOMETHING_ELSE").build())).isNull();
        }

        @Test
        void categoryDeltaMissingAllKeyValuesReturnsNull() {
            AiInsight in = insight("CATEGORY_DELTA")
                    .dimension("CATEGORY").dimensionName("餐饮")
                    .direction("DOWN").build(); // deltaAmount 与 changeRate 均缺失
            assertThat(narrator.render(in)).isNull();
        }

        @Test
        void savingsTotalMissingAllKeyValuesReturnsNull() {
            AiInsight in = insight("SAVINGS_TOTAL").role("IMPROVE").build();
            assertThat(narrator.render(in)).isNull();
        }

        @Test
        void frequencyDeltaMissingAllKeyValuesReturnsNull() {
            AiInsight in = insight("FREQUENCY_DELTA")
                    .dimension("MERCHANT").dimensionName("喜茶")
                    .direction("DOWN").build(); // deltaCount 与 changeRate 均缺失
            assertThat(narrator.render(in)).isNull();
        }

        @Test
        void trendStreakMissingMonthsReturnsNull() {
            AiInsight in = insight("TREND_STREAK")
                    .dimension("CATEGORY").dimensionName("外卖")
                    .direction("DOWN").build();
            assertThat(narrator.render(in)).isNull();
        }

        @Test
        void topMoverMissingAllKeyValuesReturnsNull() {
            AiInsight in = insight("TOP_MOVER")
                    .dimension("CATEGORY").dimensionName("餐饮")
                    .role("IMPROVE").build();
            assertThat(narrator.render(in)).isNull();
        }
    }

    // ============================== 断言辅助 ==============================

    /** 文案长度 ≤ 100 个中文字符（BMP 字符 length() 即字符数，需求 8.5）。 */
    private static void assertLengthWithinLimit(String text) {
        assertThat(text.length())
                .as("叙事文案长度应 ≤ %d 个中文字符，实际=%d：%s", MAX_CHINESE_CHARS, text.length(), text)
                .isLessThanOrEqualTo(MAX_CHINESE_CHARS);
    }

    /** 正向/中性措辞：不得包含任何提醒/警示词（需求 8.6）。 */
    private static void assertPositiveWording(String text) {
        assertThat(text).isNotNull();
        for (String word : REMINDER_WORDS) {
            assertThat(text)
                    .as("正向/中性文案不得含提醒词「%s」：%s", word, text)
                    .doesNotContain(word);
        }
    }

    /** 提醒性措辞：至少包含一个提醒/警示词（需求 8.7）。 */
    private static void assertReminderWording(String text) {
        assertThat(text).isNotNull();
        boolean hasReminder = REMINDER_WORDS.stream().anyMatch(text::contains);
        assertThat(hasReminder)
                .as("提醒性文案应至少含一个提醒词 %s：%s", REMINDER_WORDS, text)
                .isTrue();
    }

    /**
     * 数值一致（需求 8.4）：文案中出现的每个数字 token 都必须等于机器字段格式化后的期望值之一，
     * 且每个期望值都实际出现在文案里，杜绝出现与机器字段无关的多余数字。
     */
    private static void assertNumbersConsistent(String text, String... expectedNumbers) {
        List<String> expected = List.of(expectedNumbers);
        // 每个期望值都必须出现
        for (String exp : expected) {
            assertThat(text).as("文案应包含机器字段数值 %s：%s", exp, text).contains(exp);
        }
        // 文案中出现的每个数字 token 都必须属于期望集合（不允许出现额外/篡改的数字）
        List<String> found = extractNumbers(text);
        assertThat(found)
                .as("文案中出现的数字应全部来自机器字段 %s，实际提取=%s：%s", expected, found, text)
                .allMatch(expected::contains);
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static List<String> extractNumbers(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** 金额绝对值，2dp HALF_UP（与生产口径一致）。 */
    private static String money(BigDecimal v) {
        return v.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 变化率绝对值，百分比 2dp HALF_UP（与生产口径一致）。 */
    private static String pct(BigDecimal v) {
        return v.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 次数变化量绝对值（整数，与生产口径一致）。 */
    private static String count(Integer v) {
        return String.valueOf(Math.abs((long) v));
    }

    // ============================== AiInsight 构造辅助 ==============================

    private static Builder insight(String type) {
        return new Builder(type);
    }

    /** 只设置被测分支所需字段，其余字段留 null，构造异构 {@link AiInsight} record。 */
    private static final class Builder {
        private final String type;
        private String dimension;
        private Long dimensionId;
        private String dimensionName;
        private BigDecimal currentValue;
        private BigDecimal previousValue;
        private Integer currentCount;
        private Integer previousCount;
        private BigDecimal deltaAmount;
        private Integer deltaCount;
        private BigDecimal changeRate;
        private Integer streakMonths;
        private String streakStartMonth;
        private String streakEndMonth;
        private String direction;
        private String role;
        private BigDecimal score;
        private String narrativeText;

        private Builder(String type) {
            this.type = type;
        }

        Builder dimension(String v) { this.dimension = v; return this; }
        Builder dimensionId(Long v) { this.dimensionId = v; return this; }
        Builder dimensionName(String v) { this.dimensionName = v; return this; }
        Builder deltaAmount(BigDecimal v) { this.deltaAmount = v; return this; }
        Builder deltaCount(Integer v) { this.deltaCount = v; return this; }
        Builder changeRate(BigDecimal v) { this.changeRate = v; return this; }
        Builder streakMonths(Integer v) { this.streakMonths = v; return this; }
        Builder direction(String v) { this.direction = v; return this; }
        Builder role(String v) { this.role = v; return this; }

        AiInsight build() {
            return new AiInsight(
                    type, dimension, dimensionId, dimensionName,
                    currentValue, previousValue, currentCount, previousCount,
                    deltaAmount, deltaCount, changeRate,
                    streakMonths, streakStartMonth, streakEndMonth,
                    direction, role, score, narrativeText);
        }
    }
}
