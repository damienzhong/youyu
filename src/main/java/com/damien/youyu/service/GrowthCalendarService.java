package com.damien.youyu.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.damien.youyu.repository.TransactionRepository;

/**
 * 记账日历相关计算：连续段扫描（{@link #scan}，纯函数）与有界追补窗口推导
 * （{@link #backfillDates}，两次有界查询）。
 *
 * <p>{@link #scan} 是无依赖的静态纯函数；{@link #backfillDates} 需要读交易事实源，故本类注入
 * {@link TransactionRepository}。除该只读仓储外本类不持有任何用户态。</p>
 *
 * <p>{@link #segments} 与 {@link #scan} 不是两套算法：两者<b>共用同一个</b> {@link #normalize}
 * （升序去重、含 {@code null} 即抛）与<b>同一条</b> {@code toEpochDay} 相邻判定
 * （相减恰为 1 即同段）。因此 {@code segments(c)} 的四项聚合投影与 {@code scan(c)} 逐项相等，
 * 由 Property 2 锁住：
 * <ul>
 *   <li>{@code Σ days} == {@code totalDays}；</li>
 *   <li>{@code max days}（空集时 0）== {@code maxStreak}；</li>
 *   <li>最后一段的 {@code days}（空集时 0）== {@code currentSegment}；</li>
 *   <li>最后一段的 {@code endDate}（空集时 {@code null}）== {@code lastDate}。</li>
 * </ul>
 * 一旦有人改了其中一个的判定规则，属性测试立刻变红。</p>
 */
@Component
public class GrowthCalendarService {

    /** 记账日历为空时的扫描结果（需求 4.10）。 */
    private static final CalendarScan EMPTY = new CalendarScan(0, 0, 0, null);

    /** 追补窗口的最大跨度（自然日）：起点日加 999 天即窗口末日，含两端恰好 1000 天（需求 4.6）。 */
    private static final int MAX_BACKFILL_SPAN_DAYS = 999;

    /** 单次追补写入的 {@code DAILY_RECORD} 事件上界（需求 4.6、3.10）。 */
    private static final int MAX_BACKFILL_DATES = 1000;

    /** 本次无可追补日期时的结果（需求 4.6）：查询 A 返回 {@code null}，或时钟回拨致起点晚于结算日。 */
    private static final BackfillResult NOTHING_TO_BACKFILL = new BackfillResult(null, null, List.of());

    private final TransactionRepository transactionRepository;

    public GrowthCalendarService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * 有界追补：至多两次查询定出「本次应补写的记账日集合」（需求 4.6、4.14、4.16）。
     *
     * <p><b>查询 A（定追补起点）</b>：一次 {@code MIN(created_at)} 聚合。{@code lastRecordDate}
     * 为 {@code null}（该用户尚无记账日历）时不加时间下界，取该用户全部有效记账交易中最早那一笔；
     * 否则以「{@code lastRecordDate} 的次日 00:00」为下界，只看比已有日历更晚的交易。
     * 查询 A 返回 {@code null} 说明无可追补的有效记账交易，直接返回空 {@code dates} 并由调用方继续
     * 后续结算步骤（需求 4.3、4.6）。</p>
     *
     * <p><b>查询 B（取窗口内记账日）</b>：一次 distinct 日期聚合。追补窗口末日取
     * {@code min(追补起点 + 999 天, 结算日)}，查询区间是半开区间
     * {@code [追补起点 00:00, 窗口末日次日 00:00)}——两端都有界，故返回行数 ≤1000（含两端恰好
     * 覆盖至多 1000 个自然日，每日至多贡献 1 个 distinct 记账日）。</p>
     *
     * <p><b>查询次数恒 ≤2</b>：查询 A 必做；查询 B 仅在起点有效（非 {@code null} 且不晚于结算日）时做。
     * 时钟回拨致追补起点晚于结算日时跳过查询 B、返回空 {@code dates}，调用方不写任何事件（对应
     * 任务 4.2 的「起点 &gt; 结算日」分支）。</p>
     *
     * <p><b>时区结论——{@code CAST(created_at AS DATE)} 零换算</b>：全库所有 {@code DATETIME} 列存的
     * 都是 {@code Asia/Shanghai} 的<b>挂钟时刻</b>——{@code application.yml} 刻意不设
     * {@code hibernate.jdbc.time_zone}（设了会让 Hibernate 按「JVM 默认时区 → 目标时区」再换一次挂钟值），
     * 且 {@code TimeConfig} 的 {@code Clock} 固定为 {@code Asia/Shanghai}，写入侧本就以东八区挂钟落库。
     * 因此把 {@code created_at} 直接 {@code CAST ... AS DATE} 取到的就是东八区自然日，<b>零时区换算</b>，
     * 也与 {@link #scan} 消费的 {@code DAILY_RECORD} 日期口径一致。反面两种写法都被<b>刻意排除</b>：
     * <ul>
     *   <li>{@code CONVERT_TZ(created_at, '+00:00', '+08:00')}——依赖 {@code mysql.time_zone*} 系统表，
     *       这些表默认为空，缺失时 {@code CONVERT_TZ} <b>静默返回 {@code NULL}</b> 而非报错，会让整批
     *       记账日凭空消失、追补永久停摆且毫无告警；</li>
     *   <li>{@code created_at + INTERVAL 8 HOUR}——只有在列存 UTC 时才成立，而本项目的列存的就是东八区
     *       挂钟时刻，加 8 小时会把日期整体推后，凭空造出错误的记账日。</li>
     * </ul>
     * </p>
     *
     * <p><b>日期算术只用 {@code LocalDate} 层</b>：窗口末日与下界都用 {@code LocalDate.plusDays} 推导、
     * 用 {@link #minDate} 比较，不经 {@code Instant} + {@code ZoneId} 往返（{@code Asia/Shanghai} 不实行
     * 夏令时，任一自然日恒为 24 小时，日期加减不会出现 23/25 小时偏移，需求 4.16）。</p>
     *
     * @param userId         结算用户 id
     * @param lastRecordDate 该用户成长档案的 {@code last_record_date}；{@code null} 表示尚无记账日历
     * @param settleDate     结算日（{@code Asia/Shanghai} 自然日）
     * @return 追补窗口与其内的记账日集合；无可追补日期时 {@code dates} 为空
     */
    public BackfillResult backfillDates(Long userId, LocalDate lastRecordDate, LocalDate settleDate) {
        // 查询 A：定追补起点。lastRecordDate 为空时不加下界；否则只看「已有日历之后」的交易。
        LocalDateTime lowerBound = (lastRecordDate == null) ? null : lastRecordDate.plusDays(1).atStartOfDay();
        LocalDateTime earliest = transactionRepository.findEarliestRecordCreatedAt(userId, lowerBound);
        if (earliest == null) {
            // 无可追补的有效记账交易（需求 4.3、4.6）：只做了查询 A，查询次数 = 1。
            return NOTHING_TO_BACKFILL;
        }

        LocalDate windowStart = earliest.toLocalDate();
        if (windowStart.isAfter(settleDate)) {
            // 时钟回拨：追补起点晚于结算日。跳过查询 B、不写任何事件（任务 4.2「起点 > 结算日」分支）。
            return NOTHING_TO_BACKFILL;
        }

        // 窗口末日 = min(起点 + 999 天, 结算日)，两端都有界故返回行数 ≤1000。
        LocalDate windowEnd = minDate(windowStart.plusDays(MAX_BACKFILL_SPAN_DAYS), settleDate);
        LocalDateTime windowStartAt = windowStart.atStartOfDay();
        LocalDateTime windowEndExclusive = windowEnd.plusDays(1).atStartOfDay();

        // 查询 B：取窗口内 distinct 记账日（已按日期升序返回）。查询次数 = 2。
        // 仓储直接以 LocalDate 逐字回读（getObject(LocalDate.class)，零时区换算，需求 4.16），
        // 无需再从 java.sql.Date 转换（那条路径会经默认时区换算致整日平移）。
        List<LocalDate> dates = new ArrayList<>(
                transactionRepository.findRecordDatesInWindow(userId, windowStartAt, windowEndExclusive));
        if (dates.size() > MAX_BACKFILL_DATES) {
            // 有界性被破坏说明窗口推导有缺陷（区间跨度超过 1000 天）；宁可炸响也不静默写入超量事件。
            throw new IllegalStateException("追补日期个数 " + dates.size() + " 超过上界 " + MAX_BACKFILL_DATES
                    + "，窗口 [" + windowStart + ", " + windowEnd + "] 推导有误");
        }
        return new BackfillResult(windowStart, windowEnd, dates);
    }

    /** 取两个日期中较早者（等值时取任一）。 */
    private static LocalDate minDate(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    /**
     * 纯函数：对记账日历做一次 O(n) 扫描，得出累计天数、连续段长度、历史最长连续与最近记账日
     * （需求 4.9、4.10、4.12）。
     *
     * <p><b>纯函数</b>：不读时钟、不查库、不碰任何可变共享状态，同一输入恒得同一输出。
     * 结算与全量重算<b>共用这一个方法</b>，因此「增量维护结果 == 全量重算结果」（需求 1.12、4.13）
     * 是构造性成立的——两条路径读的是同一份 {@code DAILY_RECORD} 日历、算的是同一段代码，
     * 属性测试只负责把这条事实锁住，而不是去比对两份各自实现的算法。</p>
     *
     * <p><b>为什么不用窗口函数 SQL 算连续段</b>：用
     * {@code LAG()} / {@code ROW_NUMBER()} 配合「日期减行号得分组键」的经典写法确实能在库里一次算完，
     * 但测试跑在 H2（{@code MODE=MySQL}）、生产跑在 MySQL，两者的窗口函数在排序稳定性、
     * 空集与单行分区、{@code DATE} 与整数的隐式换算上行为可能不同。一旦核心不变式
     * （{@code maxStreak >= currentSegment}、累计天数等于日历日期个数、增量等于全量）
     * 依赖两个引擎各自的窗口函数实现，它就<b>失去了自动化验证</b>：H2 上全绿并不能说明生产正确，
     * 而生产上的差异只能靠人工查数据发现。放在 Java 里则可以用属性测试对着朴素 O(n²)
     * 参考实现做等价性比对（Property 6），代价只是多传一次日期列表。</p>
     *
     * <p><b>输入容错</b>：形参名叫 {@code ascendingDates} 是对调用方的期望
     * （{@code findDailyRecordKeys} 已按 {@code event_key} 升序返回，字典序与日期序一致），
     * 但本方法仍会防御性地排序与去重：同一输入的任意排列、任意重复都必须得出同一结果，
     * 否则「同一输入恒得同一输出」这条纯函数性质就只在调用方守约时成立。已升序无重复时
     * 不做任何拷贝，只多一次 O(n) 校验。</p>
     *
     * @param ascendingDates 记账日历，期望按日期升序且无重复；{@code null} 与空集等价对待
     * @return 四项派生取值；空集返回 {@code (0, 0, 0, null)}
     * @throws IllegalArgumentException 列表内含 {@code null} 元素。畸形输入说明写入路径或解析
     *                                  路径有缺陷，静默跳过会让累计天数悄悄少算
     */
    public static CalendarScan scan(List<LocalDate> ascendingDates) {
        if (ascendingDates == null || ascendingDates.isEmpty()) {
            return EMPTY;
        }
        List<LocalDate> dates = normalize(ascendingDates);
        int size = dates.size();

        int currentSegment = 1;
        int maxStreak = 1;
        long previousEpochDay = dates.get(0).toEpochDay();
        for (int i = 1; i < size; i++) {
            long epochDay = dates.get(i).toEpochDay();
            // 相邻两日相差恰好 1 天即属同一连续区间；相差 ≥2 天则开启新区间（需求 4.12）。
            // 用 toEpochDay 相减而非 plusDays(1).equals(...)：跨月、跨年、闰日一律由 epochDay 处理，
            // 无需任何按月长度分支。
            currentSegment = (epochDay - previousEpochDay == 1L) ? currentSegment + 1 : 1;
            if (currentSegment > maxStreak) {
                maxStreak = currentSegment;
            }
            previousEpochDay = epochDay;
        }
        // currentSegment 此刻正是以最后一个日期为终点的那一段的长度；maxStreak 取过它，故 maxStreak >= currentSegment。
        return new CalendarScan(size, currentSegment, maxStreak, dates.get(size - 1));
    }

    /**
     * 纯函数：把记账日历切成极大连续自然日区间，按起始日升序返回（需求 4.1、4.2、4.3）。
     *
     * <p>与 {@link #scan} 共用同一条相邻判定规则（{@code toEpochDay} 相减恰为 1 即同段）与同一个
     * {@link #normalize}（升序去重、含 {@code null} 即抛）。两者不是两套算法：
     * {@code segments(dates)} 的聚合投影与 {@code scan(dates)} 逐项相等，由 Property 2 锁住——
     * <ul>
     *   <li>{@code totalDays} == {@code Σ days}；</li>
     *   <li>{@code maxStreak} == {@code max days}（空集时 0）；</li>
     *   <li>{@code currentSegment} == 最后一段的 {@code days}（空集时 0）；</li>
     *   <li>{@code lastDate} == 最后一段的 {@code endDate}（空集时 {@code null}）。</li>
     * </ul>
     * 一旦有人改了其中一个的判定规则，属性测试立刻变红。</p>
     *
     * @param ascendingDates 记账日历，期望按日期升序且无重复；{@code null} 与空集返回空列表
     * @return 段序列，按起始日升序；不可变列表
     * @throws IllegalArgumentException 列表内含 {@code null} 元素（与 {@link #scan} 同一禁令）
     */
    public static List<StreakSegmentView> segments(List<LocalDate> ascendingDates) {
        if (ascendingDates == null || ascendingDates.isEmpty()) {
            return List.of();
        }
        List<LocalDate> dates = normalize(ascendingDates);
        List<StreakSegmentView> out = new ArrayList<>();
        LocalDate segStart = dates.get(0);
        LocalDate prev = segStart;
        for (int i = 1; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            if (d.toEpochDay() - prev.toEpochDay() != 1L) {   // 断链：收口上一段，另起一段
                out.add(StreakSegmentView.of(segStart, prev));
                segStart = d;
            }
            prev = d;
        }
        out.add(StreakSegmentView.of(segStart, prev));         // 收口最后一段
        return List.copyOf(out);
    }

    /**
     * 归一化为升序去重列表。
     *
     * <p>先一次 O(n) 判定是否已严格升序：是则原样返回（正常调用路径走这条，零额外分配）；
     * 否则退到 {@code TreeSet} 排序去重（O(n log n)，仅在乱序或含重复时付出）。</p>
     */
    private static List<LocalDate> normalize(List<LocalDate> dates) {
        boolean strictlyAscending = true;
        LocalDate previous = null;
        for (LocalDate date : dates) {
            if (date == null) {
                throw new IllegalArgumentException("记账日历不允许包含空日期");
            }
            if (previous != null && !date.isAfter(previous)) {
                strictlyAscending = false;
            }
            previous = date;
        }
        if (strictlyAscending) {
            return dates;
        }
        return new ArrayList<>(new TreeSet<>(dates));
    }
}
