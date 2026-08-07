package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import com.damien.youyu.error.ApiException;

/**
 * 分享卡片按类型解析后的周期/标识值载体（Share_Card_System，需求 1.7、1.8、4.2、4.7、5.2、5.7、7.2、7.6、10.2、10.7）。
 *
 * <p>为内部只读辅助类型，不落库、非持久化实体。{@link #of} 静态工厂<strong>只解析当前 {@code cardType} 相关的
 * 可选参数、忽略无关参数</strong>（需求 10.7）：仅设置该类型用到的字段，其余字段为 {@code null}。</p>
 *
 * <ul>
 *   <li>{@code MONTHLY_SUMMARY} / {@code BUDGET_ACHIEVED}：解析 {@code month}（{@code YYYY-MM}），缺省取
 *       {@code Asia/Shanghai} 当前自然月（需求 4.2、7.2）；格式非法/月份非 01–12 →
 *       {@code REPORT_PARAM_INVALID}（需求 4.7、7.6）。</li>
 *   <li>{@code ANNUAL_BILL}：解析 {@code year}（{@code YYYY}），缺省取 {@code Asia/Shanghai} 当前自然年
 *       （需求 5.2）；非 4 位数字年份 → {@code REPORT_PARAM_INVALID}（需求 5.7）。</li>
 *   <li>{@code ACHIEVEMENT_BADGE}：透传可选成就编码 {@code code}（缺省取最近解锁，需求 6.4）。</li>
 *   <li>{@code STREAK_MILESTONE}：解析可选里程碑天数 {@code milestone}（缺省/无法解析取已达成最高里程碑，
 *       未达成或不属集合则回退，需求 3.5）。</li>
 *   <li>{@code LEVEL_UP}：无可选周期/标识参数。</li>
 * </ul>
 *
 * <p>{@code month}/{@code year} 解析沿用 {@code ReportController.parseMonth} 同款逻辑（{@link YearMonth#parse}
 * 校验格式与月份，失败抛 {@link ApiException#reportParamInvalid(String, String)}），复用既有错误码，不新增错误码
 * （需求 10.9、13.3）。</p>
 *
 * @param cardType  卡片类型（恒非空）
 * @param month     目标月（{@code MONTHLY_SUMMARY}/{@code BUDGET_ACHIEVED} 用；其余类型为 null）
 * @param year      目标年（{@code ANNUAL_BILL} 用；其余类型为 null）
 * @param code      成就编码（{@code ACHIEVEMENT_BADGE} 用；其余类型或未提供为 null）
 * @param milestone 里程碑天数（{@code STREAK_MILESTONE} 用；其余类型或无法解析为 null）
 */
public record ShareCardQuery(
        ShareCardType cardType,
        YearMonth month,
        Integer year,
        String code,
        Integer milestone) {

    /** 4 位数字年份（需求 5.7）。 */
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");

    /**
     * 按 {@code cardType} 解析当前类型相关的可选参数、忽略无关参数（需求 10.7）。
     *
     * @param cardType  卡片类型（必填）
     * @param month     原始 {@code month} 参数（{@code YYYY-MM}，可空）
     * @param year      原始 {@code year} 参数（{@code YYYY}，可空）
     * @param code      原始成就编码参数（可空）
     * @param milestone 原始里程碑天数参数（可空）
     * @param clock     {@code Asia/Shanghai} 时钟，用于 {@code month}/{@code year} 缺省取值
     * @return 解析后的查询载体
     * @throws ApiException {@code REPORT_PARAM_INVALID} 当账本相关卡片的 {@code month}/{@code year} 格式非法
     */
    public static ShareCardQuery of(ShareCardType cardType, String month, String year,
            String code, String milestone, Clock clock) {
        return switch (cardType) {
            case MONTHLY_SUMMARY, BUDGET_ACHIEVED ->
                    new ShareCardQuery(cardType, resolveMonth(month, clock), null, null, null);
            case ANNUAL_BILL ->
                    new ShareCardQuery(cardType, null, resolveYear(year, clock), null, null);
            case ACHIEVEMENT_BADGE ->
                    new ShareCardQuery(cardType, null, null, normalizeCode(code), null);
            case STREAK_MILESTONE ->
                    new ShareCardQuery(cardType, null, null, null, parseMilestone(milestone));
            case LEVEL_UP ->
                    new ShareCardQuery(cardType, null, null, null, null);
        };
    }

    /** 目标月：缺省取当前自然月，否则沿用 {@code ReportController.parseMonth} 同款逻辑（需求 4.2、4.7、7.2、7.6）。 */
    private static YearMonth resolveMonth(String raw, Clock clock) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.now(clock);
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.reportParamInvalid("month", "月份格式应为 YYYY-MM");
        }
    }

    /** 目标年：缺省取当前自然年，否则要求 4 位数字年份（需求 5.2、5.7）。 */
    private static Integer resolveYear(String raw, Clock clock) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now(clock).getYear();
        }
        String trimmed = raw.trim();
        if (!YEAR_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.reportParamInvalid("year", "年份格式应为 YYYY（4 位数字年份）");
        }
        return Integer.valueOf(trimmed);
    }

    /** 成就编码：去空白后为空取 null（缺省取最近解锁，需求 6.4）。 */
    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    /**
     * 里程碑天数：宽松解析，缺省或无法解析为整数一律取 null（由评估器回退核心里程碑，需求 3.5）。
     * 里程碑参数不属于「格式非法则拒绝」的周期参数，故不抛错。
     */
    private static Integer parseMilestone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
