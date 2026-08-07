package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Component;

import com.damien.youyu.api.dto.PersonalityTagsResponse.PersonalityTag;

/**
 * 趣味人格标签中文文案渲染组件（需求 8）。把一枚标签的机器可读字段套入一套内置中文模板，渲染成一段
 * 轻松、俏皮、有温度的中文标签文案（省钱达人、理财新星、预算大师、外卖探索家、咖啡收藏家、夜宵王、
 * 旅行狂人、购物生活家）。
 *
 * <p><b>纯函数、不接外部服务/LLM</b>（需求 8.2、13.1、13.2）：{@link #render(PersonalityTag)} 不做任何
 * I/O、不查库、不注入任何 HTTP 客户端或外部大语言模型/文本生成服务，输入标签机器字段、输出中文串，
 * 因此可被单测/属性测试直接覆盖「文案数值 == 机器字段」（需求 8.7）与「禁用词零命中」（需求 8.3）。
 * 设计为 {@code @Component} 便于由 {@code PersonalityTagService} 注入编排，但本身保持无副作用的纯函数
 * （design.md「TagNarrator」）。</p>
 *
 * <p><b>正向包装，绝不评判</b>（需求 8.3、8.4、8.5）：本组件内置一份<b>可逐条枚举核对</b>的
 * 「负面/评判/羞辱/警示词汇表」（{@link #FORBIDDEN_WORDS}），并提供 {@link #containsForbiddenWord(String)}
 * 供自检与测试逐条核对；每枚标签的标题与文案中出现的每个词均不得命中该表（仅采用正向或中性措辞）。
 * 渲染后若自检命中禁用词，视为无法生成安全文案而返回 {@code null}，绝不输出评判式文案。</p>
 *
 * <p><b>数值一致</b>（需求 8.7）：模板中出现的每个数值都直接取自该标签机器字段并按同口径格式化
 * （金额 2dp HALF_UP、占比/比率百分比 2dp HALF_UP、笔数/月数为整数），保证「文案数值 == 机器字段」
 * 逐一相等。</p>
 *
 * <p><b>至少含标题 + 一项关键数值</b>（需求 8.6）：每段文案至少包含该标签<b>标题</b>，以及金额、占比、
 * 笔数、月数四类关键数值中的至少一项；文案长度为 1..60 个中文字符（需求 8.8）。</p>
 *
 * <p><b>回退名</b>（需求 6.8）：行为类标签的维度名缺失/空白 → 分类回退 {@link #DELETED_CATEGORY_NAME}、
 * 商户回退 {@link #DELETED_MERCHANT_NAME}（固定、可复现）。本组件的行为类模板不直接嵌入用户可控的维度
 * 名称（以规避维度名过长撑破长度上限、或用户自命名意外命中禁用词等风险），因此维度名缺失<b>不会</b>
 * 导致标签被丢弃；{@link #categoryDisplayName(String)}、{@link #merchantDisplayName(String)} 提供固定、
 * 可复现的回退名以供服务层与测试逐条核对。</p>
 *
 * <p><b>生成失败</b>（需求 8.9）：某标签缺标题或缺全部关键数值时 → 返回 {@code null}（由服务标记生成
 * 失败并附失败原因、保留机器字段），整体不抛错。</p>
 */
@Component
public class TagNarrator {

    /** 已删除/无名分类的固定回退名（需求 6.8）。 */
    static final String DELETED_CATEGORY_NAME = "已删除分类";

    /** 已删除/无名商户的固定回退名（需求 6.8）。 */
    static final String DELETED_MERCHANT_NAME = "已删除商户";

    /** 金额与占比/比率统一保留的小数位。 */
    private static final int SCALE = 2;

    /** 标签文案长度下限（含），单位为中文字符（码点）。需求 8.8。 */
    static final int MIN_LENGTH = 1;

    /** 标签文案长度上限（含），单位为中文字符（码点）。需求 8.8。 */
    static final int MAX_LENGTH = 60;

    /**
     * 「负面/评判/羞辱/警示词汇表」（需求 8.3、8.4、8.5）——<b>可逐条枚举核对</b>的不可变清单。
     *
     * <p>标签目录的每个标题与每段文案中出现的词均不得命中本表；对应可能让用户不舒服的消费行为
     * （如「冲动购物」）一律以正向/中性命名的标签（如 {@code SHOPPING_LIFER} 购物生活家）表达。
     * 采用多字词条以避免与正向措辞中的常见单字发生误伤式匹配。</p>
     */
    static final List<String> FORBIDDEN_WORDS = List.of(
            "乱花", "挥霍", "超支", "警告", "警示", "报警", "冲动", "剁手", "败家", "败光",
            "后悔", "失控", "浪费", "月光族", "负债", "透支", "入不敷出", "大手大脚", "铺张",
            "吝啬", "抠门", "亏空", "赤字", "糟糕", "严重", "危险", "差劲", "罪过");

    /**
     * 把一枚标签渲染为中文标签文案（需求 8.1、8.6、8.7、8.8）。纯函数：相同输入恒得相同输出。
     *
     * @param tag 一枚人格标签的机器字段
     * @return 渲染好的中文标签文案（1..60 个中文字符）；缺标题、缺全部关键数值、或自检命中禁用词等
     *         无法生成安全文案时返回 {@code null}（需求 8.9）
     */
    public String render(PersonalityTag tag) {
        if (tag == null || tag.tagKey() == null) {
            return null;
        }
        String title = tag.title();
        if (isBlank(title)) {
            // 缺标题 → 生成失败（需求 8.9）。
            return null;
        }
        String body = switch (tag.tagKey()) {
            case "SAVINGS_MASTER" -> renderSavingsMaster(tag);
            case "FINANCE_STAR" -> renderFinanceStar(tag);
            case "BUDGET_MASTER" -> renderBudgetMaster(tag);
            case "TAKEOUT_EXPLORER" -> renderTakeoutExplorer(tag);
            case "COFFEE_COLLECTOR" -> renderCoffeeCollector(tag);
            case "LATE_NIGHT_KING" -> renderLateNightKing(tag);
            case "TRAVEL_ENTHUSIAST" -> renderTravelEnthusiast(tag);
            case "SHOPPING_LIFER" -> renderShoppingLifer(tag);
            default -> null;
        };
        if (body == null) {
            // 缺全部关键数值 → 生成失败（需求 8.9）。
            return null;
        }
        String text = prefix(title, tag.emoji()) + body;
        // 自检：命中禁用词或长度越界一律视为无法生成安全文案（需求 8.3、8.4、8.8）。
        if (containsForbiddenWord(text) || !isLengthValid(text)) {
            return null;
        }
        return text;
    }

    /**
     * 逐条核对某段文本是否命中「负面/评判/羞辱/警示词汇表」（需求 8.3、8.4）。
     *
     * @param text 待核对文本（标题或文案）
     * @return 文本包含 {@link #FORBIDDEN_WORDS} 中任一词条时返回 {@code true}
     */
    public boolean containsForbiddenWord(String text) {
        if (text == null) {
            return false;
        }
        for (String word : FORBIDDEN_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 逐枚标签模板（正向/中性，见 design.md「TagNarrator 模板示例」） ----------------

    /** 省钱达人 🏆（需求 3、8.6、8.7）：关键数值 = 节省额（金额）/ 节省率（占比）。 */
    private String renderSavingsMaster(PersonalityTag tag) {
        String savings = money(tag.savings());
        String rate = pct(tag.saveRate());
        if (savings == null && rate == null) {
            return null;
        }
        if (savings != null) {
            String tail = rate != null ? "（" + rate + "%）" : "";
            return "这个月比上月省下 " + savings + " 元" + tail + "，钱包稳稳的～";
        }
        return "这个月支出比上月降了 " + rate + "%，钱包稳稳的～";
    }

    /** 理财新星 🌟（需求 4、8.6、8.7）：关键数值 = 总收入（金额）/ 结余率（占比）。 */
    private String renderFinanceStar(PersonalityTag tag) {
        String income = money(tag.income());
        String rate = pct(tag.saveRate());
        if (income == null && rate == null) {
            return null;
        }
        if (income != null && rate != null) {
            return "本月收入 " + income + " 元、结余率 " + rate + "%，攒钱有一套～";
        }
        if (rate != null) {
            return "本月结余率 " + rate + "%，攒钱有一套～";
        }
        return "本月收入 " + income + " 元，攒钱有一套～";
    }

    /** 预算大师 🎯（需求 5、8.6、8.7）：关键数值 = 预算使用率（占比）。 */
    private String renderBudgetMaster(PersonalityTag tag) {
        String rate = pct(tag.usedRate());
        if (rate == null) {
            return null;
        }
        return "本月只用了预算的 " + rate + "%，把控得刚刚好～";
    }

    /** 外卖探索家 🍱（需求 6、8.6、8.7）：关键数值 = 匹配笔数。 */
    private String renderTakeoutExplorer(PersonalityTag tag) {
        String count = count(tag.matchCount());
        if (count == null) {
            return null;
        }
        return "本月点了 " + count + " 次外卖，人间烟火气拉满～";
    }

    /** 咖啡收藏家 ☕（需求 6、8.6、8.7）：关键数值 = 匹配笔数。 */
    private String renderCoffeeCollector(PersonalityTag tag) {
        String count = count(tag.matchCount());
        if (count == null) {
            return null;
        }
        return "本月喝了 " + count + " 杯咖啡，续命全靠它～";
    }

    /** 夜宵王 🌙（需求 7、8.6、8.7）：关键数值 = 夜宵时段笔数。 */
    private String renderLateNightKing(PersonalityTag tag) {
        String count = count(tag.lateNightCount());
        if (count == null) {
            return null;
        }
        return "本月有 " + count + " 次夜宵时光，深夜也要好好犒劳自己～";
    }

    /** 旅行狂人 ✈️（需求 6、8.6、8.7）：关键数值 = 匹配金额；金额缺失时退用匹配笔数。 */
    private String renderTravelEnthusiast(PersonalityTag tag) {
        String amount = money(tag.matchAmount());
        if (amount != null) {
            return "本月旅行花了 " + amount + " 元，去看更大的世界～";
        }
        String count = count(tag.matchCount());
        if (count != null) {
            return "本月出行 " + count + " 次，去看更大的世界～";
        }
        return null;
    }

    /** 购物生活家 🛍️（需求 6、8.6、8.7）：关键数值 = 匹配笔数；笔数缺失时退用匹配金额。 */
    private String renderShoppingLifer(PersonalityTag tag) {
        String count = count(tag.matchCount());
        if (count != null) {
            return "本月置办了 " + count + " 件好物，把日子过得有滋有味～";
        }
        String amount = money(tag.matchAmount());
        if (amount != null) {
            return "本月置办好物花了 " + amount + " 元，把日子过得有滋有味～";
        }
        return null;
    }

    // ---------------- 回退名（需求 6.8，供服务层与测试逐条核对） ----------------

    /**
     * 分类维度展示名回退（需求 6.8）：名称缺失/空白 → {@link #DELETED_CATEGORY_NAME}（固定、可复现）。
     *
     * @param raw 分类原始名称，可能为 {@code null} 或空白
     * @return 非空白时原样返回，否则返回固定回退名
     */
    static String categoryDisplayName(String raw) {
        return isBlank(raw) ? DELETED_CATEGORY_NAME : raw;
    }

    /**
     * 商户维度展示名回退（需求 6.8）：名称缺失/空白 → {@link #DELETED_MERCHANT_NAME}（固定、可复现）。
     *
     * @param raw 商户原始名称，可能为 {@code null} 或空白
     * @return 非空白时原样返回，否则返回固定回退名
     */
    static String merchantDisplayName(String raw) {
        return isBlank(raw) ? DELETED_MERCHANT_NAME : raw;
    }

    // ---------------- 工具方法 ----------------

    /** 组装「标题 + 表情 + 」前缀；表情缺失时省略（需求 8.6）。 */
    private static String prefix(String title, String emoji) {
        if (isBlank(emoji)) {
            return title + " ";
        }
        return title + " " + emoji + " ";
    }

    /** 文案长度（中文字符/码点）落在 [1, 60] 视为合法（需求 8.8）。 */
    private static boolean isLengthValid(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int len = text.codePointCount(0, text.length());
        return len >= MIN_LENGTH && len <= MAX_LENGTH;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 金额 2dp HALF_UP；{@code null} → {@code null}（需求 8.7）。 */
    private static String money(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 占比/比率 2dp HALF_UP；{@code null} → {@code null}（需求 8.7）。 */
    private static String pct(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 笔数（整数）；{@code null} → {@code null}（需求 8.7）。 */
    private static String count(Integer v) {
        return v == null ? null : String.valueOf(v.intValue());
    }
}
