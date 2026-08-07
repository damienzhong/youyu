package com.damien.youyu.config;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 趣味人格标签（fun-personality-tags）可配置阈值 / 匹配集合 / 展示上限载体。
 *
 * <p>前缀 {@code youyu.personality-tags}。集中承载 8 枚标签的判定阈值、行为类标签的分类/商户匹配
 * 集合与展示上限 N，缺省值即需求默认值；本类为纯配置载体，不落库、不新增数据库表、不新增错误码。
 * 金额与占比/比率阈值一律用 {@link BigDecimal}（元 / 百分比），与既有报表口径（2 位小数）保持一致。</p>
 *
 * <p>通过 {@link Component} + {@link ConfigurationProperties} 由组件扫描注册为 Bean，采用 JavaBean
 * （getter/setter）绑定，镜像既有 {@link AiInsightProperties} 的绑定风格；未在配置文件中显式覆盖时使用
 * 下方字段默认值。</p>
 *
 * <p>任一阈值未配置或非法（金额/笔数为负、占比/比率不在 0.00–100.00 之间、夜宵时段无法解析）时，读取方应经
 * {@link #sanitize()}、{@link #maxCountClamped()}、{@link #lateNightWindow()} 回退该项默认值继续评估，
 * 不报错（需求 2.4、2.5、7.3、9.4）。</p>
 */
@Component
@ConfigurationProperties(prefix = "youyu.personality-tags")
public class PersonalityTagProperties {

    // ---------------- 展示上限 N（需求 1.1、9.3、9.4） ----------------

    /** {@code maxCount} 默认值（N）。需求 1.1、9.3、9.4：N 取值范围 1–8，默认 4。 */
    public static final int MAX_COUNT_DEFAULT = 4;

    /** {@code maxCount} 的下限（含）。需求 9.3、9.4：N 取值范围 1–8。 */
    public static final int MAX_COUNT_LOWER_BOUND = 1;

    /** {@code maxCount} 的上限（含）。需求 9.3、9.4：N 取值范围 1–8。 */
    public static final int MAX_COUNT_UPPER_BOUND = 8;

    // ---------------- 各阈值默认值常量（用于 sanitize 回退） ----------------

    /** 省钱达人节省额下限默认值（元）。需求 3.3。 */
    public static final BigDecimal DEFAULT_SAVINGS_AMOUNT_MIN = new BigDecimal("200.00");
    /** 省钱达人节省率下限默认值（%）。需求 3.3。 */
    public static final BigDecimal DEFAULT_SAVINGS_RATE_PCT_MIN = new BigDecimal("15.00");
    /** 理财新星结余率下限默认值（%）。需求 4.4。 */
    public static final BigDecimal DEFAULT_FINANCE_SAVE_RATE_PCT_MIN = new BigDecimal("20.00");
    /** 预算大师预算使用率上限默认值（%）。需求 5.3。 */
    public static final BigDecimal DEFAULT_BUDGET_USED_PCT_MAX = new BigDecimal("90.00");
    /** 外卖探索家笔数下限默认值。需求 6.6。 */
    public static final int DEFAULT_TAKEOUT_COUNT_MIN = 8;
    /** 外卖探索家占比下限默认值（%）。需求 6.6。 */
    public static final BigDecimal DEFAULT_TAKEOUT_PCT_MIN = new BigDecimal("20.00");
    /** 咖啡收藏家笔数下限默认值。需求 6.6。 */
    public static final int DEFAULT_COFFEE_COUNT_MIN = 5;
    /** 旅行狂人金额下限默认值（元）。需求 6.6。 */
    public static final BigDecimal DEFAULT_TRAVEL_AMOUNT_MIN = new BigDecimal("1000.00");
    /** 旅行狂人笔数下限默认值。需求 6.6。 */
    public static final int DEFAULT_TRAVEL_COUNT_MIN = 5;
    /** 购物生活家笔数下限默认值。需求 6.6。 */
    public static final int DEFAULT_SHOPPING_COUNT_MIN = 8;
    /** 购物生活家金额下限默认值（元）。需求 6.6。 */
    public static final BigDecimal DEFAULT_SHOPPING_AMOUNT_MIN = new BigDecimal("800.00");
    /** 夜宵王笔数下限默认值。需求 7.4。 */
    public static final int DEFAULT_LATE_NIGHT_COUNT_MIN = 5;
    /** 夜宵时段起始小时默认值（含）。需求 7.2、7.3。 */
    public static final int DEFAULT_LATE_NIGHT_START_HOUR = 22;
    /** 夜宵时段结束小时默认值（不含，跨零点）。需求 7.2、7.3。 */
    public static final int DEFAULT_LATE_NIGHT_END_HOUR = 4;

    /** 占比/比率合法区间下限（含）。需求 2.5：占比/比率须在 0.00–100.00。 */
    public static final BigDecimal PCT_LOWER_BOUND = new BigDecimal("0.00");
    /** 占比/比率合法区间上限（含）。需求 2.5：占比/比率须在 0.00–100.00。 */
    public static final BigDecimal PCT_UPPER_BOUND = new BigDecimal("100.00");

    // ---------------- 展示上限 ----------------

    /**
     * 展示数量上限 N：一次最多授予并展示的人格标签枚数，默认 4。
     *
     * <p>读取时应经 {@link #maxCountClamped()} 将越界（&lt;1 或 &gt;8）回退默认 4。
     * 对应需求 1.1（0..N 枚，N 为 1–8 的可配置整数，默认 4）、需求 9.3、9.4。</p>
     */
    private int maxCount = MAX_COUNT_DEFAULT;

    // ---------------- 省钱达人（SAVINGS_MASTER，需求 3.3） ----------------

    /** 省钱达人节省额下限（元，范围 0.01–999999999.99），默认 200.00。需求 3.3。 */
    private BigDecimal savingsAmountMin = DEFAULT_SAVINGS_AMOUNT_MIN;

    /** 省钱达人节省率下限（%，范围 0.01–100.00），默认 15.00。需求 3.3。 */
    private BigDecimal savingsRatePctMin = DEFAULT_SAVINGS_RATE_PCT_MIN;

    // ---------------- 理财新星（FINANCE_STAR，需求 4.4） ----------------

    /** 理财新星结余率下限（%，范围 0.00–100.00），默认 20.00。需求 4.4。 */
    private BigDecimal financeSaveRatePctMin = DEFAULT_FINANCE_SAVE_RATE_PCT_MIN;

    // ---------------- 预算大师（BUDGET_MASTER，需求 5.3） ----------------

    /** 预算大师预算使用率上限（%，范围 0.00–100.00），默认 90.00。需求 5.3。 */
    private BigDecimal budgetUsedPctMax = DEFAULT_BUDGET_USED_PCT_MAX;

    // ---------------- 行为类标签阈值（需求 6.6） ----------------

    /** 外卖探索家笔数下限，默认 8。需求 6.6。 */
    private int takeoutCountMin = DEFAULT_TAKEOUT_COUNT_MIN;

    /** 外卖探索家占比下限（%），默认 20.00。需求 6.6。 */
    private BigDecimal takeoutPctMin = DEFAULT_TAKEOUT_PCT_MIN;

    /** 咖啡收藏家笔数下限，默认 5。需求 6.6。 */
    private int coffeeCountMin = DEFAULT_COFFEE_COUNT_MIN;

    /** 旅行狂人金额下限（元），默认 1000.00。需求 6.6。 */
    private BigDecimal travelAmountMin = DEFAULT_TRAVEL_AMOUNT_MIN;

    /** 旅行狂人笔数下限，默认 5。需求 6.6。 */
    private int travelCountMin = DEFAULT_TRAVEL_COUNT_MIN;

    /** 购物生活家笔数下限，默认 8。需求 6.6。 */
    private int shoppingCountMin = DEFAULT_SHOPPING_COUNT_MIN;

    /** 购物生活家金额下限（元），默认 800.00。需求 6.6。 */
    private BigDecimal shoppingAmountMin = DEFAULT_SHOPPING_AMOUNT_MIN;

    // ---------------- 夜宵王（LATE_NIGHT_KING，需求 7.2、7.3、7.4） ----------------

    /** 夜宵笔数下限（范围 1–999999），默认 5。需求 7.4。 */
    private int lateNightCountMin = DEFAULT_LATE_NIGHT_COUNT_MIN;

    /** 夜宵时段起始小时（含，0–23），默认 22。需求 7.2、7.3。 */
    private int lateNightStartHour = DEFAULT_LATE_NIGHT_START_HOUR;

    /** 夜宵时段结束小时（不含，跨零点，0–23），默认 4。需求 7.2、7.3。 */
    private int lateNightEndHour = DEFAULT_LATE_NIGHT_END_HOUR;

    // ---------------- 行为类标签匹配集合（需求 6.1） ----------------

    /** 外卖探索家分类名称匹配集合。需求 6.1。 */
    private Set<String> takeoutCategories = new LinkedHashSet<>(Set.of("外卖", "餐饮", "美食", "快餐"));

    /** 外卖探索家商户名称匹配集合。需求 6.1。 */
    private Set<String> takeoutMerchants = new LinkedHashSet<>(Set.of("美团外卖", "饿了么", "麦当劳", "肯德基"));

    /** 咖啡收藏家分类名称匹配集合。需求 6.1。 */
    private Set<String> coffeeCategories = new LinkedHashSet<>(Set.of("咖啡", "饮品"));

    /** 咖啡收藏家商户名称匹配集合。需求 6.1。 */
    private Set<String> coffeeMerchants = new LinkedHashSet<>(Set.of("星巴克", "瑞幸咖啡", "瑞幸", "库迪咖啡", "Manner"));

    /** 旅行狂人分类名称匹配集合。需求 6.1。 */
    private Set<String> travelCategories = new LinkedHashSet<>(Set.of("旅行", "旅游", "酒店", "机票", "交通"));

    /** 旅行狂人商户名称匹配集合。需求 6.1。 */
    private Set<String> travelMerchants = new LinkedHashSet<>(Set.of("携程", "去哪儿", "飞猪", "12306"));

    /** 购物生活家分类名称匹配集合。需求 6.1。 */
    private Set<String> shoppingCategories = new LinkedHashSet<>(Set.of("购物", "服饰", "数码", "日用"));

    /** 购物生活家商户名称匹配集合。需求 6.1。 */
    private Set<String> shoppingMerchants = new LinkedHashSet<>(Set.of("淘宝", "天猫", "京东", "拼多多"));

    /**
     * 夜宵时段的不可变值对象（半开区间 {@code [startHour:00, endHour:00)}，跨零点）。
     *
     * @param startHour 起始小时（含，0–23）
     * @param endHour   结束小时（不含，0–23，跨零点即 {@code startHour > endHour}）
     */
    public record LateNightWindow(int startHour, int endHour) {

        /**
         * 判定某个本地小时是否落在夜宵时段内。跨零点时（{@code startHour > endHour}）落在
         * {@code [startHour, 24)} 或 {@code [0, endHour)} 均视为夜宵；不跨零点时落在
         * {@code [startHour, endHour)} 视为夜宵。
         *
         * @param hour 本地小时（0–23）
         * @return 落在夜宵时段返回 {@code true}
         */
        public boolean contains(int hour) {
            if (startHour == endHour) {
                return false;
            }
            if (startHour > endHour) {
                return hour >= startHour || hour < endHour;
            }
            return hour >= startHour && hour < endHour;
        }
    }

    /**
     * 返回展示数量上限 N 的已校正取值：越界（&lt;1 或 &gt;8）回退默认 4（需求 9.4）。
     *
     * <p>读取 N 时应一律使用本方法而非直接读 {@link #getMaxCount()}，以保证 N 落在 [1, 8] 区间内。</p>
     *
     * @return 合法的展示数量上限（越界时为默认 4）
     */
    public int maxCountClamped() {
        if (maxCount < MAX_COUNT_LOWER_BOUND || maxCount > MAX_COUNT_UPPER_BOUND) {
            return MAX_COUNT_DEFAULT;
        }
        return maxCount;
    }

    /**
     * 返回夜宵时段：非法配置（小时不在 0–23、或起止相等导致空区间）回退默认 {@code [22:00, 次日 04:00)}
     * （需求 7.3）。
     *
     * @return 合法的夜宵时段值对象
     */
    public LateNightWindow lateNightWindow() {
        if (isValidHour(lateNightStartHour) && isValidHour(lateNightEndHour)
                && lateNightStartHour != lateNightEndHour) {
            return new LateNightWindow(lateNightStartHour, lateNightEndHour);
        }
        return new LateNightWindow(DEFAULT_LATE_NIGHT_START_HOUR, DEFAULT_LATE_NIGHT_END_HOUR);
    }

    /**
     * 返回一份阈值经净化的副本：金额/笔数为负、占比/比率不在 0.00–100.00 之间的项，逐项回退其默认值
     * 继续评估，不报错（需求 2.5）。匹配集合、夜宵时段（经 {@link #lateNightWindow()} 处理）与展示上限
     * （经 {@link #maxCountClamped()} 处理）不在本方法处理范围内。
     *
     * @return 净化后的新实例（原实例不被修改）
     */
    public PersonalityTagProperties sanitize() {
        PersonalityTagProperties p = new PersonalityTagProperties();

        p.maxCount = this.maxCount;

        // 金额下限：为负回退默认（需求 2.5、3.3、6.6）。
        p.savingsAmountMin = sanitizeAmount(this.savingsAmountMin, DEFAULT_SAVINGS_AMOUNT_MIN);
        p.travelAmountMin = sanitizeAmount(this.travelAmountMin, DEFAULT_TRAVEL_AMOUNT_MIN);
        p.shoppingAmountMin = sanitizeAmount(this.shoppingAmountMin, DEFAULT_SHOPPING_AMOUNT_MIN);

        // 占比/比率：不在 0.00–100.00 回退默认（需求 2.5、3.3、4.4、5.3、6.6）。
        p.savingsRatePctMin = sanitizePct(this.savingsRatePctMin, DEFAULT_SAVINGS_RATE_PCT_MIN);
        p.financeSaveRatePctMin = sanitizePct(this.financeSaveRatePctMin, DEFAULT_FINANCE_SAVE_RATE_PCT_MIN);
        p.budgetUsedPctMax = sanitizePct(this.budgetUsedPctMax, DEFAULT_BUDGET_USED_PCT_MAX);
        p.takeoutPctMin = sanitizePct(this.takeoutPctMin, DEFAULT_TAKEOUT_PCT_MIN);

        // 笔数下限：为负回退默认（需求 2.5、6.6、7.4）。
        p.takeoutCountMin = sanitizeCount(this.takeoutCountMin, DEFAULT_TAKEOUT_COUNT_MIN);
        p.coffeeCountMin = sanitizeCount(this.coffeeCountMin, DEFAULT_COFFEE_COUNT_MIN);
        p.travelCountMin = sanitizeCount(this.travelCountMin, DEFAULT_TRAVEL_COUNT_MIN);
        p.shoppingCountMin = sanitizeCount(this.shoppingCountMin, DEFAULT_SHOPPING_COUNT_MIN);
        p.lateNightCountMin = sanitizeCount(this.lateNightCountMin, DEFAULT_LATE_NIGHT_COUNT_MIN);

        // 夜宵时段小时：原样带入，读取时经 lateNightWindow() 校正（需求 7.3）。
        p.lateNightStartHour = this.lateNightStartHour;
        p.lateNightEndHour = this.lateNightEndHour;

        // 匹配集合：原样带入（需求 6.1）。
        p.takeoutCategories = new LinkedHashSet<>(this.takeoutCategories);
        p.takeoutMerchants = new LinkedHashSet<>(this.takeoutMerchants);
        p.coffeeCategories = new LinkedHashSet<>(this.coffeeCategories);
        p.coffeeMerchants = new LinkedHashSet<>(this.coffeeMerchants);
        p.travelCategories = new LinkedHashSet<>(this.travelCategories);
        p.travelMerchants = new LinkedHashSet<>(this.travelMerchants);
        p.shoppingCategories = new LinkedHashSet<>(this.shoppingCategories);
        p.shoppingMerchants = new LinkedHashSet<>(this.shoppingMerchants);

        return p;
    }

    private static boolean isValidHour(int hour) {
        return hour >= 0 && hour <= 23;
    }

    /** 金额下限净化：null 或为负 → 回退默认。需求 2.5。 */
    private static BigDecimal sanitizeAmount(BigDecimal value, BigDecimal defaultValue) {
        if (value == null || value.signum() < 0) {
            return defaultValue;
        }
        return value;
    }

    /** 占比/比率净化：null 或不在 [0.00, 100.00] → 回退默认。需求 2.5。 */
    private static BigDecimal sanitizePct(BigDecimal value, BigDecimal defaultValue) {
        if (value == null
                || value.compareTo(PCT_LOWER_BOUND) < 0
                || value.compareTo(PCT_UPPER_BOUND) > 0) {
            return defaultValue;
        }
        return value;
    }

    /** 笔数下限净化：为负 → 回退默认。需求 2.5。 */
    private static int sanitizeCount(int value, int defaultValue) {
        if (value < 0) {
            return defaultValue;
        }
        return value;
    }

    // ---------------- getters / setters（JavaBean 绑定） ----------------

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }

    public BigDecimal getSavingsAmountMin() {
        return savingsAmountMin;
    }

    public void setSavingsAmountMin(BigDecimal savingsAmountMin) {
        this.savingsAmountMin = savingsAmountMin;
    }

    public BigDecimal getSavingsRatePctMin() {
        return savingsRatePctMin;
    }

    public void setSavingsRatePctMin(BigDecimal savingsRatePctMin) {
        this.savingsRatePctMin = savingsRatePctMin;
    }

    public BigDecimal getFinanceSaveRatePctMin() {
        return financeSaveRatePctMin;
    }

    public void setFinanceSaveRatePctMin(BigDecimal financeSaveRatePctMin) {
        this.financeSaveRatePctMin = financeSaveRatePctMin;
    }

    public BigDecimal getBudgetUsedPctMax() {
        return budgetUsedPctMax;
    }

    public void setBudgetUsedPctMax(BigDecimal budgetUsedPctMax) {
        this.budgetUsedPctMax = budgetUsedPctMax;
    }

    public int getTakeoutCountMin() {
        return takeoutCountMin;
    }

    public void setTakeoutCountMin(int takeoutCountMin) {
        this.takeoutCountMin = takeoutCountMin;
    }

    public BigDecimal getTakeoutPctMin() {
        return takeoutPctMin;
    }

    public void setTakeoutPctMin(BigDecimal takeoutPctMin) {
        this.takeoutPctMin = takeoutPctMin;
    }

    public int getCoffeeCountMin() {
        return coffeeCountMin;
    }

    public void setCoffeeCountMin(int coffeeCountMin) {
        this.coffeeCountMin = coffeeCountMin;
    }

    public BigDecimal getTravelAmountMin() {
        return travelAmountMin;
    }

    public void setTravelAmountMin(BigDecimal travelAmountMin) {
        this.travelAmountMin = travelAmountMin;
    }

    public int getTravelCountMin() {
        return travelCountMin;
    }

    public void setTravelCountMin(int travelCountMin) {
        this.travelCountMin = travelCountMin;
    }

    public int getShoppingCountMin() {
        return shoppingCountMin;
    }

    public void setShoppingCountMin(int shoppingCountMin) {
        this.shoppingCountMin = shoppingCountMin;
    }

    public BigDecimal getShoppingAmountMin() {
        return shoppingAmountMin;
    }

    public void setShoppingAmountMin(BigDecimal shoppingAmountMin) {
        this.shoppingAmountMin = shoppingAmountMin;
    }

    public int getLateNightCountMin() {
        return lateNightCountMin;
    }

    public void setLateNightCountMin(int lateNightCountMin) {
        this.lateNightCountMin = lateNightCountMin;
    }

    public int getLateNightStartHour() {
        return lateNightStartHour;
    }

    public void setLateNightStartHour(int lateNightStartHour) {
        this.lateNightStartHour = lateNightStartHour;
    }

    public int getLateNightEndHour() {
        return lateNightEndHour;
    }

    public void setLateNightEndHour(int lateNightEndHour) {
        this.lateNightEndHour = lateNightEndHour;
    }

    public Set<String> getTakeoutCategories() {
        return takeoutCategories;
    }

    public void setTakeoutCategories(Set<String> takeoutCategories) {
        this.takeoutCategories = takeoutCategories;
    }

    public Set<String> getTakeoutMerchants() {
        return takeoutMerchants;
    }

    public void setTakeoutMerchants(Set<String> takeoutMerchants) {
        this.takeoutMerchants = takeoutMerchants;
    }

    public Set<String> getCoffeeCategories() {
        return coffeeCategories;
    }

    public void setCoffeeCategories(Set<String> coffeeCategories) {
        this.coffeeCategories = coffeeCategories;
    }

    public Set<String> getCoffeeMerchants() {
        return coffeeMerchants;
    }

    public void setCoffeeMerchants(Set<String> coffeeMerchants) {
        this.coffeeMerchants = coffeeMerchants;
    }

    public Set<String> getTravelCategories() {
        return travelCategories;
    }

    public void setTravelCategories(Set<String> travelCategories) {
        this.travelCategories = travelCategories;
    }

    public Set<String> getTravelMerchants() {
        return travelMerchants;
    }

    public void setTravelMerchants(Set<String> travelMerchants) {
        this.travelMerchants = travelMerchants;
    }

    public Set<String> getShoppingCategories() {
        return shoppingCategories;
    }

    public void setShoppingCategories(Set<String> shoppingCategories) {
        this.shoppingCategories = shoppingCategories;
    }

    public Set<String> getShoppingMerchants() {
        return shoppingMerchants;
    }

    public void setShoppingMerchants(Set<String> shoppingMerchants) {
        this.shoppingMerchants = shoppingMerchants;
    }
}
