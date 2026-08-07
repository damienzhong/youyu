package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.damien.youyu.api.dto.AiInsightsResponse.AiInsight;

/**
 * AI 趣味分析中文叙事渲染组件（需求 8）。把一条洞察的机器可读字段套入内置中文模板，渲染成一段
 * 暖心、俏皮、好读的中文叙事文案。
 *
 * <p><b>纯函数、不接外部服务</b>（需求 8.3、12.1、12.2）：{@link #render(AiInsight)} 不做任何 I/O、
 * 不查库、不注入任何 HTTP 客户端或外部大语言模型/文本生成服务，输入洞察机器字段、输出中文串，
 * 因此可被单测/属性测试直接覆盖「文案数值 == 机器字段」（需求 8.4）。设计为 {@code @Component} 便于
 * 由 {@code AiInsightService} 注入编排（design.md「InsightNarrator」）。</p>
 *
 * <p><b>数值一致</b>（需求 8.4）：模板中出现的每个数值都直接取自该洞察机器字段并按同口径格式化
 * （金额 2dp HALF_UP、变化率百分比 2dp HALF_UP、次数/连续月数为整数），保证逐一相等。金额、变化率、
 * 次数在文案中以其绝对值呈现（涨/降、增/减的方向由措辞表达），与设计模板一致。</p>
 *
 * <p><b>至少含维度名 + 一项关键数值</b>（需求 8.2）：每条文案至少包含维度名称（{@code SAVINGS_TOTAL}
 * 为账本总额、无维度名，改由「这个月/比上月」承载语境），以及变化率/金额/次数三者中的至少一项。</p>
 *
 * <p><b>措辞极性</b>（需求 8.6、8.7）：方向为下降/减少/改善（{@code direction=DOWN} 或
 * {@code role=IMPROVE}）→ 正向或中性措辞，不含任何提醒/警示词；方向为上升/增加/超支
 * （{@code direction=UP} 或 {@code role=OVERSPEND}）→ 提醒性措辞。</p>
 *
 * <p><b>回退名</b>（需求 2.7、4.6）：分类名缺失/空白 → {@link #DELETED_CATEGORY_NAME}；商户名缺失/空白
 * → {@link #DELETED_MERCHANT_NAME}；固定、可复现，且不因名称缺失丢弃洞察。</p>
 *
 * <p><b>生成失败</b>（需求 8.8）：某洞察缺全部关键数值时 → 返回 {@code null}（由服务标记生成失败、
 * 保留机器字段），整体不抛错。</p>
 */
@Component
public class InsightNarrator {

    /** 已删除/无名分类的固定回退名（需求 2.7）。 */
    static final String DELETED_CATEGORY_NAME = "已删除分类";

    /** 已删除/无名商户的固定回退名（需求 4.6）。 */
    static final String DELETED_MERCHANT_NAME = "已删除商户";

    /** 金额/变化率统一保留的小数位。 */
    private static final int SCALE = 2;

    /**
     * 把一条洞察渲染为中文叙事文案（需求 8.1、8.4）。纯函数：相同输入恒得相同输出。
     *
     * @param insight 一条趣味洞察的机器字段
     * @return 渲染好的中文叙事文案（≤100 个中文字符）；缺全部关键数值等无法生成时返回 {@code null}（需求 8.8）
     */
    public String render(AiInsight insight) {
        if (insight == null || insight.type() == null) {
            return null;
        }
        return switch (insight.type()) {
            case "CATEGORY_DELTA" -> renderCategoryDelta(insight);
            case "SAVINGS_TOTAL" -> renderSavingsTotal(insight);
            case "FREQUENCY_DELTA" -> renderFrequencyDelta(insight);
            case "TREND_STREAK" -> renderTrendStreak(insight);
            case "TOP_MOVER" -> renderTopMover(insight);
            default -> null;
        };
    }

    /** 分类消费涨跌（需求 2.5、8.6、8.7）：下降 → 正向；上升 → 提醒。 */
    private String renderCategoryDelta(AiInsight in) {
        String amt = absMoney(in.deltaAmount());
        String rate = absPct(in.changeRate());
        if (amt == null && rate == null) {
            return null;
        }
        String name = categoryName(in.dimensionName());
        boolean down = isDown(in);
        List<String> parts = new ArrayList<>();
        if (down) {
            if (amt != null) {
                parts.add("少花了 " + amt + " 元");
            }
            if (rate != null) {
                parts.add("降了 " + rate + "%");
            }
            return "你的" + name + String.join("，", parts) + "，省钱有一手～";
        }
        if (amt != null) {
            parts.add("多花了 " + amt + " 元");
        }
        if (rate != null) {
            parts.add("涨了 " + rate + "%");
        }
        return name + "这个月" + String.join("，", parts) + "，留意一下哦。";
    }

    /** 比上月节省/多花总额（需求 3.6、3.7）：节省 → 正向；多花 → 提醒。账本总额无维度名。 */
    private String renderSavingsTotal(AiInsight in) {
        String amt = absMoney(in.deltaAmount());
        String rate = absPct(in.changeRate());
        if (amt == null && rate == null) {
            return null;
        }
        boolean improve = isImprove(in);
        if (improve) {
            if (amt != null) {
                String tail = rate != null ? "（" + rate + "%）" : "";
                return "这个月比上月省下 " + amt + " 元" + tail + "，钱包稳稳的～";
            }
            return "这个月支出比上月降了 " + rate + "%，钱包稳稳的～";
        }
        if (amt != null) {
            String tail = rate != null ? "（" + rate + "%）" : "";
            return "这个月比上月多花了 " + amt + " 元" + tail + "，记得关注下节奏。";
        }
        return "这个月支出比上月涨了 " + rate + "%，记得关注下节奏。";
    }

    /** 商户或分类频次变化（需求 4.5）：减少 → 正向；增加 → 提醒。回退名按维度区分。 */
    private String renderFrequencyDelta(AiInsight in) {
        String cnt = absCount(in.deltaCount());
        String rate = absPct(in.changeRate());
        if (cnt == null && rate == null) {
            return null;
        }
        String name = dimensionName(in);
        boolean down = isDown(in);
        String rateTail = rate != null ? "（" + rate + "%）" : "";
        if (down) {
            String body = cnt != null ? "次数减少了 " + cnt + " 次" + rateTail : "次数降了 " + rate + "%";
            return "本月" + name + body + "，节制得不错～";
        }
        String body = cnt != null ? "次数增加了 " + cnt + " 次" + rateTail : "次数涨了 " + rate + "%";
        return "本月" + name + body + "，留意一下频率哦。";
    }

    /** 连续涨跌趋势（需求 5.5）：连续下降 → 正向；连续上升 → 提醒。关键数值为连续月数。 */
    private String renderTrendStreak(AiInsight in) {
        Integer months = in.streakMonths();
        if (months == null) {
            return null;
        }
        String name = categoryName(in.dimensionName());
        boolean down = isDown(in);
        if (down) {
            return name + "已连续 " + months + " 个月下降，坚持得很棒～";
        }
        return name + "连续 " + months + " 个月上升了，记得关注下。";
    }

    /** 最大改善/最超支分类（需求 6.2）：改善 → 正向；超支 → 提醒。 */
    private String renderTopMover(AiInsight in) {
        String amt = absMoney(in.deltaAmount());
        String rate = absPct(in.changeRate());
        if (amt == null && rate == null) {
            return null;
        }
        String name = categoryName(in.dimensionName());
        boolean improve = isImprove(in);
        String rateTail = rate != null ? "（" + rate + "%）" : "";
        if (improve) {
            String body = amt != null ? "少花 " + amt + " 元" + rateTail : "降了 " + rate + "%";
            return "这个月" + name + "省得最多，" + body + "，最大功臣就是它～";
        }
        String body = amt != null ? "多花 " + amt + " 元" + rateTail : "涨了 " + rate + "%";
        return "这个月" + name + "超得最多，" + body + "，下月留意一下。";
    }

    /**
     * 方向是否为「下降/减少」（需求 8.6）。优先取 {@code direction}；缺省时以变化量符号推断
     * （金额或笔数为负记为下降），保证纯函数确定性。
     */
    private boolean isDown(AiInsight in) {
        if (in.direction() != null) {
            return "DOWN".equals(in.direction());
        }
        if (in.deltaAmount() != null) {
            return in.deltaAmount().signum() < 0;
        }
        if (in.deltaCount() != null) {
            return in.deltaCount() < 0;
        }
        return true;
    }

    /**
     * 角色是否为「改善/节省」（需求 8.6）。优先取 {@code role}；缺省时以节省额（{@code deltaAmount}）
     * 符号推断（大于 0 记为改善）。
     */
    private boolean isImprove(AiInsight in) {
        if (in.role() != null) {
            return "IMPROVE".equals(in.role());
        }
        if (in.deltaAmount() != null) {
            return in.deltaAmount().signum() > 0;
        }
        return true;
    }

    /** 维度名称回退（需求 4.6）：商户维度 → {@link #DELETED_MERCHANT_NAME}，否则按分类回退。 */
    private String dimensionName(AiInsight in) {
        if ("MERCHANT".equals(in.dimension())) {
            return merchantName(in.dimensionName());
        }
        return categoryName(in.dimensionName());
    }

    /** 分类名回退（需求 2.7）。 */
    private String categoryName(String raw) {
        return isBlank(raw) ? DELETED_CATEGORY_NAME : raw;
    }

    /** 商户名回退（需求 4.6）。 */
    private String merchantName(String raw) {
        return isBlank(raw) ? DELETED_MERCHANT_NAME : raw;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 金额绝对值，2dp HALF_UP；{@code null} → {@code null}（需求 8.4）。 */
    private static String absMoney(BigDecimal v) {
        return v == null ? null : v.abs().setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 变化率绝对值，百分比 2dp HALF_UP；{@code null} → {@code null}（需求 8.4）。 */
    private static String absPct(BigDecimal v) {
        return v == null ? null : v.abs().setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 次数变化量绝对值（整数）；{@code null} → {@code null}（需求 8.4）。 */
    private static String absCount(Integer v) {
        return v == null ? null : String.valueOf(Math.abs((long) v));
    }
}
