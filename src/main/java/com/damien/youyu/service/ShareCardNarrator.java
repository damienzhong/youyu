package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Component;

import com.damien.youyu.api.dto.ShareCardResponse.ShareCardCore;

/**
 * 分享卡片「一句 AI 文案」中文文案渲染组件（需求 9）。把某张卡片的核心数据 {@link ShareCardCore} 套入一套
 * 内置中文模板，为 6 类卡片各渲染一段轻松、暖心、正向的中文文案：连续记账里程碑（{@code STREAK_MILESTONE}）、
 * 本月总结（{@code MONTHLY_SUMMARY}）、年度账单（{@code ANNUAL_BILL}）、获得徽章（{@code ACHIEVEMENT_BADGE}）、
 * 预算达成（{@code BUDGET_ACHIEVED}）、成长升级（{@code LEVEL_UP}）。
 *
 * <p><b>纯函数、不接外部服务/LLM</b>（需求 9.2、12.1、12.2）：{@link #render(ShareCardType, ShareCardCore)}
 * 不做任何 I/O、不查库、不注入任何 HTTP 客户端或外部大语言模型/文本生成服务，输入卡片机器字段、输出中文串，
 * 因此可被单测/属性测试直接覆盖「文案数值 == 机器字段」（需求 2.8、9.5）与「禁用词零命中」（需求 9.3）。
 * 设计为 {@code @Component} 便于由 {@code ShareCardService} 注入编排，但本身保持无副作用的纯函数
 * （镜像既有 {@code TagNarrator}，design.md「6. ShareCardNarrator」）。</p>
 *
 * <p><b>正向包装，绝不评判</b>（需求 9.3）：本组件内置一份<b>可逐条枚举核对</b>的「负面/评判/羞辱/警示词汇表」
 * （{@link #FORBIDDEN_WORDS}），并提供 {@link #containsForbiddenWord(String)} 供自检与测试逐条核对；文案中出现的
 * 每个词均不得命中该表（仅采用正向或中性措辞）。渲染后若自检命中禁用词或长度越界，则回退到该类型的内置默认
 * 文案，绝不输出评判式文案、绝不使请求失败。</p>
 *
 * <p><b>数值一致</b>（需求 2.8、9.5）：模板中出现的每个数值都直接取自 {@code core} 机器字段并按同口径格式化
 * （金额 2dp HALF_UP、占比 2dp HALF_UP、天数/等级/年月为整数、经验为整数），保证「文案数值 == 机器字段」逐一相等。</p>
 *
 * <p><b>至少含一项关键数值</b>（需求 9.4）：每段主文案至少包含该卡片核心数据中的一项关键数值（里程碑、连续天数、
 * 金额、等级、成就名之一）；文案长度为 1..60 个中文字符（需求 9.6）。</p>
 *
 * <p><b>生成失败兜底</b>（需求 9.7）：某类型主文案所需的关键数值缺失（或渲染结果命中禁用词/长度越界）时，
 * 返回该类型的内置默认文案（正向、含类型名、1..60 字符），不抛错、不使请求失败。</p>
 */
@Component
public class ShareCardNarrator {

    /** 金额与占比统一保留的小数位。 */
    private static final int SCALE = 2;

    /** 文案长度下限（含），单位为中文字符（码点）。需求 9.6。 */
    static final int MIN_LENGTH = 1;

    /** 文案长度上限（含），单位为中文字符（码点）。需求 9.6。 */
    static final int MAX_LENGTH = 60;

    /**
     * 「负面/评判/羞辱/警示词汇表」（需求 9.3）——<b>可逐条枚举核对</b>的不可变清单。
     *
     * <p>任一卡片文案中出现的词均不得命中本表；对应可能让用户不舒服的措辞（如「超支」「剁手」）一律以正向/
     * 中性表达替代（如「把控得刚刚好」）。采用多字词条以避免与正向措辞中的常见单字发生误伤式匹配。</p>
     */
    static final List<String> FORBIDDEN_WORDS = List.of(
            "超支", "警告", "警示", "报警", "挥霍", "剁手", "后悔", "失控", "乱花", "败家",
            "败光", "冲动", "浪费", "月光族", "负债", "透支", "入不敷出", "大手大脚", "铺张",
            "吝啬", "抠门", "亏空", "赤字", "糟糕", "严重", "危险", "差劲", "罪过");

    /**
     * 把某张卡片的核心数据渲染为一句中文 AI 文案（需求 9.1、9.4、9.5、9.6）。纯函数：相同输入恒得相同输出。
     *
     * <p>优先按该类型的正向/中性模板渲染主文案；当关键数值缺失、或渲染结果命中禁用词/长度越界时，回退到该
     * 类型的内置默认文案（正向、含类型名、1..60 字符），绝不返回 {@code null}、绝不抛错（需求 9.7）。</p>
     *
     * @param type 卡片类型（决定采用哪套模板）
     * @param core 该卡片的核心数据机器字段（可为 {@code null}，缺失时直接取默认文案）
     * @return 渲染好的一句中文文案（1..60 个中文字符，禁用词零命中）
     */
    public String render(ShareCardType type, ShareCardCore core) {
        if (type == null) {
            return null;
        }
        String primary = core == null ? null : renderPrimary(type, core);
        if (primary != null && !containsForbiddenWord(primary) && isLengthValid(primary)) {
            return primary;
        }
        // 关键数值缺失 / 命中禁用词 / 长度越界 → 内置默认文案兜底（需求 9.7）。
        return defaultNarrative(type);
    }

    /**
     * 逐条核对某段文本是否命中「负面/评判/羞辱/警示词汇表」（需求 9.3）。
     *
     * @param text 待核对文本
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

    // ---------------- 逐类型主模板（正向/中性，见 design.md「6. ShareCardNarrator 模板示例」） ----------------

    private String renderPrimary(ShareCardType type, ShareCardCore core) {
        return switch (type) {
            case STREAK_MILESTONE -> renderStreakMilestone(core);
            case MONTHLY_SUMMARY -> renderMonthlySummary(core);
            case ANNUAL_BILL -> renderAnnualBill(core);
            case ACHIEVEMENT_BADGE -> renderAchievementBadge(core);
            case BUDGET_ACHIEVED -> renderBudgetAchieved(core);
            case LEVEL_UP -> renderLevelUp(core);
        };
    }

    /** 连续记账里程碑 🏆（需求 3、9.4、9.5）：关键数值 = 核心里程碑天数 / 当前连续天数。 */
    private String renderStreakMilestone(ShareCardCore core) {
        String milestone = intStr(core.milestone());
        if (milestone == null) {
            return null;
        }
        String current = intStr(core.currentStreakDays());
        if (current != null) {
            return "连续记账 " + milestone + " 天达成 🏆 一路坚持到第 " + current + " 天，稳稳的！";
        }
        return "连续记账 " + milestone + " 天达成 🏆 坚持的力量，稳稳的！";
    }

    /** 本月总结 📅（需求 4、9.4、9.5）：关键数值 = 收入 / 支出 / 结余（金额）。 */
    private String renderMonthlySummary(ShareCardCore core) {
        String income = money(core.income());
        String expense = money(core.expense());
        String balance = money(core.balance());
        if (income == null || expense == null || balance == null) {
            return null;
        }
        String month = blankToEmpty(core.month());
        String head = month.isEmpty() ? "本月小结 📅 " : month + " 小结 📅 ";
        return head + "收入 " + income + " 元、支出 " + expense + " 元、结余 " + balance + " 元，记账有条理～";
    }

    /** 年度账单 ✨（需求 5、9.4、9.5）：关键数值 = 年度结余（金额）。 */
    private String renderAnnualBill(ShareCardCore core) {
        String balance = money(core.annualBalance());
        if (balance == null) {
            return null;
        }
        String year = blankToEmpty(core.year());
        String head = year.isEmpty() ? "年度账单 ✨ " : year + " 年度账单 ✨ ";
        return head + "全年结余 " + balance + " 元，这一年过得明明白白～";
    }

    /** 获得徽章 🎖（需求 6、9.4、9.5）：关键数值 = 徽章名称。 */
    private String renderAchievementBadge(ShareCardCore core) {
        String badgeName = core.badgeName();
        if (isBlank(badgeName)) {
            return null;
        }
        String date = blankToEmpty(core.unlockedDate());
        if (!date.isEmpty()) {
            return "解锁徽章「" + badgeName + "」🎖 " + date + " 收入囊中，成就感满满～";
        }
        return "解锁徽章「" + badgeName + "」🎖 成就感满满～";
    }

    /** 预算达成 🎯（需求 7、9.4、9.5）：关键数值 = 预算已用百分比（占比）。 */
    private String renderBudgetAchieved(ShareCardCore core) {
        String percent = pct(core.usedPercent());
        if (percent == null) {
            return null;
        }
        String month = blankToEmpty(core.month());
        String head = month.isEmpty() ? "预算达成 🎯 " : "预算达成 🎯 " + month + " ";
        return head + "只用了预算的 " + percent + "%，把控得刚刚好～";
    }

    /** 成长升级 🚀（需求 8、9.4、9.5）：关键数值 = 当前等级 / 经验值。 */
    private String renderLevelUp(ShareCardCore core) {
        String level = intStr(core.level());
        if (level == null) {
            return null;
        }
        String exp = longStr(core.exp());
        if (exp != null) {
            return "升到 Lv." + level + " 🚀 已累计 " + exp + " 点成长值，继续向上！";
        }
        return "升到 Lv." + level + " 🚀 继续向上！";
    }

    // ---------------- 内置默认文案（需求 9.7；正向、含类型名、1..60 字符） ----------------

    private String defaultNarrative(ShareCardType type) {
        return switch (type) {
            case STREAK_MILESTONE -> "连续记账里程碑达成 🏆 坚持记账的每一天都值得纪念～";
            case MONTHLY_SUMMARY -> "本月总结 📅 这个月的每一笔都记得清清楚楚～";
            case ANNUAL_BILL -> "年度账单 ✨ 这一年的点滴都被认真记录～";
            case ACHIEVEMENT_BADGE -> "获得徽章 🎖 又收获一枚专属成就，真棒～";
            case BUDGET_ACHIEVED -> "预算达成 🎯 这个月的预算把控得稳稳的～";
            case LEVEL_UP -> "成长升级 🚀 又向上迈进了一步，继续加油～";
        };
    }

    // ---------------- 工具方法 ----------------

    /** 文案长度（中文字符/码点）落在 [1, 60] 视为合法（需求 9.6）。 */
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

    private static String blankToEmpty(String s) {
        return isBlank(s) ? "" : s;
    }

    /** 金额 2dp HALF_UP；{@code null} → {@code null}（需求 2.8、9.5）。 */
    private static String money(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 占比/百分比 2dp HALF_UP；{@code null} → {@code null}（需求 2.8、9.5）。 */
    private static String pct(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 整数（天数/等级/里程碑）；{@code null} → {@code null}（需求 2.8、9.5）。 */
    private static String intStr(Integer v) {
        return v == null ? null : String.valueOf(v.intValue());
    }

    /** 整数（经验值）；{@code null} → {@code null}（需求 2.8、9.5）。 */
    private static String longStr(Long v) {
        return v == null ? null : String.valueOf(v.longValue());
    }
}
