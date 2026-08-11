package com.damien.youyu.service;

/**
 * 预算提醒文案选择的<strong>唯一实现</strong>——由「预警级别 + 预算范围」映射到一条固定文案。
 *
 * <p>文案同时体现两项要素（需求 5.1）：预算范围（月度总预算或具体分类名称）与级别（预警 / 超支）。
 * {@link #pick} 是不读时钟、不查库的静态纯函数，与 {@code ReminderMessageResolver} 同一风格。</p>
 *
 * <p>范围表述（需求 5.4）：{@code scopeRef == 0} → 「月度总预算」；否则用分类当前名称；名称为空 / 不可得
 * （分类已删）→ 「该分类」占位，绝不因此抛异常或中止发送。级别文案（需求 5.2、5.3）：{@code OVER} →
 * 「{范围}本月已超支」、{@code WARN} → 「{范围}本月已接近预算上限」，按级别恰好选一条（需求 5.6）。</p>
 *
 * <p>文案长度受控（需求 5.5）：微信订阅消息 {@code thing} 型字段上限 20 个字符，故对范围表述做长度截断，
 * 保证整条文案落入字段限制内；文案只由级别与范围拼成，不含收件人邮箱 / 令牌 / 他人信息。</p>
 *
 * <p>Feature: subscribe-message-reminders。覆盖需求 5.1、5.2、5.3、5.4、5.5、5.6。</p>
 */
public final class BudgetReminderMessageResolver {

    /** 预警级别：超支。 */
    public static final String LEVEL_OVER = "OVER";

    /** 预警级别：预警（接近上限）。 */
    public static final String LEVEL_WARN = "WARN";

    /** 月度总预算范围的固定表述。 */
    static final String SCOPE_TOTAL = "月度总预算";

    /** 分类名称不可得时的占位表述（需求 5.4）。 */
    static final String SCOPE_CATEGORY_FALLBACK = "该分类";

    /**
     * 微信订阅消息 {@code thing} 型字段的字符上限（20）。范围表述超过此长度时截断，
     * 保证「{范围}本月已接近预算上限」整体不超字段限制（需求 5.5）。
     */
    static final int MAX_MESSAGE_CHARS = 20;

    /** 级别文案后缀最长为「本月已接近预算上限」（9 个字符），据此留给范围表述的字符预算。 */
    static final int MAX_SCOPE_CHARS = MAX_MESSAGE_CHARS - "本月已接近预算上限".length();

    private BudgetReminderMessageResolver() {
        // 纯函数工具类，不允许实例化。
    }

    /**
     * 由「级别 + 预算范围」选用一条预算提醒文案（需求 5.1~5.6）。
     *
     * @param level               预警级别，{@code OVER} 或 {@code WARN}（区分大小写）
     * @param scopeRef            预算范围：{@code 0} 表示月度总预算，大于 {@code 0} 表示分类 id
     * @param categoryNameOrNull  分类当前名称；范围为总预算或名称不可得时可为 {@code null}
     * @return 对应的预算提醒文案（落入微信模板字段长度限制内）
     */
    public static String pick(String level, long scopeRef, String categoryNameOrNull) {
        String scope = scopeText(scopeRef, categoryNameOrNull);
        if (LEVEL_OVER.equals(level)) {
            return scope + "本月已超支";
        }
        // 其余一律按预警处理（调用方只会传 OVER / WARN，此处对 WARN 与任何非 OVER 兜底为预警文案）。
        return scope + "本月已接近预算上限";
    }

    /** 范围表述：总预算固定文案；分类用当前名称（截断到字符预算内），名称不可得则占位。 */
    private static String scopeText(long scopeRef, String categoryNameOrNull) {
        if (scopeRef == 0L) {
            return SCOPE_TOTAL;
        }
        if (categoryNameOrNull == null || categoryNameOrNull.isBlank()) {
            return SCOPE_CATEGORY_FALLBACK;
        }
        String name = categoryNameOrNull.trim();
        if (name.length() > MAX_SCOPE_CHARS) {
            return name.substring(0, MAX_SCOPE_CHARS);
        }
        return name;
    }
}
