package com.damien.youyu.service;

/**
 * 自定义提醒文案选择的<strong>唯一实现</strong>——由「今日已记账」映射到恰好两条固定文案之一。
 *
 * <p>取值集合恰为两条、互斥、逐字符固定（需求 4.1、4.4）：今日已记账时为
 * {@link #MSG_DONE}「今天已经完成啦~」、今日未记账时为 {@link #MSG_NOT_YET}「今天还没记账哦~」。
 * {@link #pick(boolean)} 是不读时钟、不查库的静态纯函数，与 {@code StreakJudgment} 同一静态工具风格。</p>
 *
 * <p>「今日已记账」的判定不在本类完成，而由调用方以
 * {@code StreakJudgment.todayDone(user_growth.last_record_date, 判定日)} 求得后传入
 * （需求 4.2、4.3、4.5）——单一事实源，本类只负责把布尔映射到文案。</p>
 *
 * <p>Feature: custom-reminder。覆盖需求 4.1、4.2、4.3、4.4。</p>
 */
public final class ReminderMessageResolver {

    /** 今日已记账时的提醒文案（逐字符固定，需求 4.2）。 */
    public static final String MSG_DONE = "今天已经完成啦~";

    /** 今日未记账时的提醒文案（逐字符固定，需求 4.3）。 */
    public static final String MSG_NOT_YET = "今天还没记账哦~";

    private ReminderMessageResolver() {
        // 纯函数工具类，不允许实例化。
    }

    /**
     * 由「今日已记账」选用两条文案之一（需求 4.1、4.4）。
     *
     * <p>值域恰为 {@code {MSG_DONE, MSG_NOT_YET}}，二者互斥：为真取
     * {@link #MSG_DONE}、为假取 {@link #MSG_NOT_YET}，绝不返回集合以外的任何文案。</p>
     *
     * @param todayRecorded 今日是否已记账
     * @return 对应的提醒文案
     */
    public static String pick(boolean todayRecorded) {
        return todayRecorded ? MSG_DONE : MSG_NOT_YET;
    }
}
