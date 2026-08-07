package com.damien.youyu.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 分享卡片（share-card）可配置品牌名 / Logo 面积占比上限载体。
 *
 * <p>前缀 {@code youyu.share-card}。集中承载卡片渲染所需的少量可配置项——品牌名与品牌 Logo 在卡片可见
 * 区域内的最大占用面积占比，缺省值即需求默认值；本类为纯配置载体，不落库、不新增数据库表、不新增错误码。</p>
 *
 * <p>通过 {@link Component} + {@link ConfigurationProperties} 由组件扫描注册为 Bean，采用 JavaBean
 * （getter/setter）绑定，镜像既有 {@link AiInsightProperties} / {@link PersonalityTagProperties} 的
 * 绑定风格；未在配置文件中显式覆盖时使用下方字段默认值。</p>
 *
 * <p>任一取值未配置或非法（品牌名去空白后为空、Logo 面积占比越界或为 null）时，读取方应经
 * {@link #brandNameOrDefault()}、{@link #logoMaxAreaRatioClamped()} 回退该项默认值继续使用，不报错
 * （需求 2.5、2.6）。</p>
 */
@Component
@ConfigurationProperties(prefix = "youyu.share-card")
public class ShareCardProperties {

    /** {@code brandName} 默认值（品牌名）。需求 1.2、2.5：品牌名默认「有余」，去空白后为空回退此值。 */
    public static final String BRAND_NAME_DEFAULT = "有余";

    /** {@code logoMaxAreaRatio} 默认值。需求 2.5：Logo 面积占比上限默认 0.05（卡片可见区域 5%）。 */
    public static final BigDecimal LOGO_MAX_AREA_RATIO_DEFAULT = new BigDecimal("0.05");

    /** {@code logoMaxAreaRatio} 的下限（含）。需求 2.5：面积占比须在 0.00–1.00。 */
    public static final BigDecimal LOGO_MAX_AREA_RATIO_LOWER_BOUND = new BigDecimal("0.00");

    /** {@code logoMaxAreaRatio} 的上限（含）。需求 2.5：面积占比须在 0.00–1.00。 */
    public static final BigDecimal LOGO_MAX_AREA_RATIO_UPPER_BOUND = new BigDecimal("1.00");

    /**
     * 品牌名：卡片上小尺寸品牌 Logo 呈现的品牌标识文字，默认「有余」。
     *
     * <p>读取时应经 {@link #brandNameOrDefault()} 将去首尾空白后为空的取值回退默认「有余」。
     * 对应需求 1.2（卡片数据包含品牌名）、需求 2.5（品牌 Logo 呈现）。</p>
     */
    private String brandName = BRAND_NAME_DEFAULT;

    /**
     * 品牌 Logo 在卡片可见区域内的最大占用面积占比（0.00–1.00），默认 0.05（即 5%）。
     *
     * <p>读取时应经 {@link #logoMaxAreaRatioClamped()} 将越界（&lt;0.00 或 &gt;1.00 或为 null）回退默认
     * 0.05。供前端绘制与前端/契约测试断言「Logo 面积 ≤ 卡片可见区域 5%」使用。对应需求 2.5、2.6。</p>
     */
    private BigDecimal logoMaxAreaRatio = LOGO_MAX_AREA_RATIO_DEFAULT;

    /**
     * 返回品牌名的已校正取值：{@code null} 或去首尾空白后为空回退默认「有余」（需求 1.2、2.5）。
     *
     * <p>读取品牌名时应一律使用本方法而非直接读 {@link #getBrandName()}，以保证非空可展示。</p>
     *
     * @return 合法的品牌名（去空白后为空时为默认「有余」）
     */
    public String brandNameOrDefault() {
        if (brandName == null || brandName.strip().isEmpty()) {
            return BRAND_NAME_DEFAULT;
        }
        return brandName.strip();
    }

    /**
     * 返回 Logo 面积占比上限的已校正取值：{@code null} 或越界（&lt;0.00 或 &gt;1.00）回退默认 0.05
     * （需求 2.5、2.6）。
     *
     * <p>读取 Logo 面积占比上限时应一律使用本方法而非直接读 {@link #getLogoMaxAreaRatio()}，以保证取值
     * 落在 [0.00, 1.00] 区间内。</p>
     *
     * @return 合法的 Logo 面积占比上限（越界或缺省时为默认 0.05）
     */
    public BigDecimal logoMaxAreaRatioClamped() {
        if (logoMaxAreaRatio == null
                || logoMaxAreaRatio.compareTo(LOGO_MAX_AREA_RATIO_LOWER_BOUND) < 0
                || logoMaxAreaRatio.compareTo(LOGO_MAX_AREA_RATIO_UPPER_BOUND) > 0) {
            return LOGO_MAX_AREA_RATIO_DEFAULT;
        }
        return logoMaxAreaRatio;
    }

    // ---------------- getters / setters（JavaBean 绑定） ----------------

    public String getBrandName() {
        return brandName;
    }

    public void setBrandName(String brandName) {
        this.brandName = brandName;
    }

    public BigDecimal getLogoMaxAreaRatio() {
        return logoMaxAreaRatio;
    }

    public void setLogoMaxAreaRatio(BigDecimal logoMaxAreaRatio) {
        this.logoMaxAreaRatio = logoMaxAreaRatio;
    }
}
