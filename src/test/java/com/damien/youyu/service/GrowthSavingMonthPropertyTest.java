package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.statistics.Statistics;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * <b>Property 10：储蓄月判定的算术与边界</b>的属性测试（任务 8.5）。
 *
 * <p><i>对任意</i>月度收入 / 支出组合与任意结算日：{@link GrowthSavingMonthEvaluator#savingMonths}
 * 的返回值与一份<b>独立写成的朴素 {@link BigDecimal} 参考实现</b>逐例相同；取等号即成立；
 * 空收入按 {@code 0.00} 计且判为不是储蓄月；{@code event_key} 恒为 {@code SAVING_MONTH:YYYY-MM}
 * 且长度恒为 20（需求 4.1–4.5、4.8、4.10）。</p>
 *
 * <h2>另写一份朴素参考实现，而不是复算一遍被测的公式</h2>
 *
 * <p>沿用 {@code GrowthCalendarScanPropertyTest} 的做法：期望值由 {@link #naiveSavingMonths} 独立算出，
 * 它与被测在<b>两个</b>关键处走的是不同机制，因此互为参照而不是自我一致——</p>
 *
 * <ul>
 *   <li><b>月份归属</b>：被测把归属压进 SQL 的半开区间
 *       {@code occurred_at ∈ [窗口起点, 结算月 1 日)} 再按 {@code YEAR()} / {@code MONTH()} 分组；
 *       朴素实现<b>逐笔</b>取 {@code YearMonth.from(occurredAt)} 与目标月直接比对。于是「恰好落在次月
 *       1 日 00:00:00.000 的交易归次月」这条（需求 4.6）在两侧是两套完全不同的推理，
 *       任一侧把区间端点写成闭区间、或把 {@code YEAR()/MONTH()} 换成会漂移的
 *       {@code DATE_FORMAT}，等价性立刻断裂。</li>
 *   <li><b>判定与舍入</b>：朴素实现只按需求 4.3 / 4.8 的文字直译一遍——
 *       {@code 收入 ≥ 0.01 且 收入 − 支出 ≥ round(收入 × 0.2, 2, HALF_UP)}，
 *       不引入被测的「具名储蓄门槛值」中间量、不对结余做 {@code setScale}。
 *       把舍入挪走（先比较后舍入 / 改成 {@code DOWN} / 用 {@code double}）就会在边界上分叉：
 *       收入 {@code 333.33} 时门槛 {@code 66.67} 与 {@code 66.66} 只差一分，
 *       而 {@link #property10_equalityAtThresholdAndOneCentBelow} 恰好把探针钉在这一分上。</li>
 * </ul>
 *
 * <p>三条排除（{@code deleted_at} 非空 / {@code ledger_id} 为 NULL / {@code type = 'transfer'}）
 * 两侧也各自实现：被测写在 SQL 的 {@code WHERE} 里，朴素实现按生成的标记就地跳过。</p>
 *
 * <h2>已实测：两处改坏各自会让哪一条变红</h2>
 *
 * <p>写完后对 {@link GrowthSavingMonthEvaluator} 做了两次临时改动验证本类真的会咬人（改完即还原，
 * 生产代码逐字节不变）：</p>
 * <ol>
 *   <li>门槛的舍入 {@code HALF_UP} → {@code DOWN}（其余一字不改）：
 *       {@link #property10_equalityAtThresholdAndOneCentBelow} 变红，反例是收入 {@code 0.99}
 *       （门槛 {@code 0.198}，{@code HALF_UP} 是 {@code 0.20}、截断是 {@code 0.19}，
 *       探针的「少一分」样本结余恰好 {@code 0.19}）。<b>此时等价性属性仍是绿的</b>——随机金额几乎不会
 *       正好落在两个门槛之间那一分上，这正是「取等号」需要一条专用探针、
 *       而不能指望主属性顺带覆盖的原因。</li>
 *   <li>回看窗口多含结算日所属月（循环下界 {@code back >= 1} → {@code back >= 0}，
 *       右端点同步放宽一个月）：{@link #property10_savingMonthDecisionMatchesNaiveBigDecimalReference}
 *       变红，反例是结算日 {@code 2025-01-15} 加 1 笔交易——朴素实现只认三个回看月，
 *       被测多返回了 {@code 2025-01}（需求 4.1「不判定结算日所属自然月」）。</li>
 * </ol>
 *
 * <h2>走真实 H2 而不是 mock 仓储</h2>
 *
 * <p>{@code GrowthSavingMonthEvaluatorTest} 已用 Mockito 桩覆盖了算术与窗口的示例边界。本类刻意走
 * <b>真实数据库</b>：交易逐笔直插，金额由 {@code DECIMAL(18,2)} 列往返一趟，分组合计由 H2 的
 * {@code SUM} 算出、再经仓储的 {@code Object[]} 回读到 {@link BigDecimal}。这样「全程无浮点参与」
 * （需求 4.8）才真的被验到——JDBC 层若有人把 {@code SUM} 的结果按 {@code doubleValue()} 取回，
 * 桩测试是发现不了的，而随机 {@code DECIMAL(18,2)} 取样一压就会在某个取值上错开一分。</p>
 *
 * <h2>生成维度</h2>
 *
 * <ul>
 *   <li><b>金额</b>：设计文档点名的 {@code {0, 0.01, 0.99, 1.00, 100.00, 333.33, 999999.99}}
 *       ∪ 随机 {@code DECIMAL(18,2)}（以「分」为单位取整数再 {@code movePointLeft(2)}，
 *       故恒为 2 位小数、恒无浮点）。</li>
 *   <li><b>结算日</b>：跨年（{@code 2025-01-15} 回看上年 10/11/12 月、{@code 2024-01-01}、
 *       {@code 2024-12-31}）、<b>闰年 2 月</b>（{@code 2024-03-31} 与 {@code 2024-05-15} 的回看窗口含
 *       29 天的 {@code 2024-02}）、平年 2 月对照（{@code 2023-03-15}）∪ 随机日期。</li>
 *   <li><b>月份偏移</b>：{@code 0}（结算月，不该参与判定）、{@code 1/2/3}（回看窗口内）、
 *       {@code 4}（第 4 个更早的月，需求 4.10 要求不判）。</li>
 *   <li><b>月内时刻</b>：月首 {@code 00:00:00}、月中 {@code 12:00}、月末 {@code 23:59:59}、
 *       <b>次月 1 日 {@code 00:00:00}</b>（需求 4.6 的边界，应归次月）。</li>
 *   <li><b>已有事件</b>：三个回看月的任意子集已写过 {@code SAVING_MONTH}（需求 4.19 的幂等跳过），
 *       另掺入 {@code BADGE:} / {@code BUDGET_MET:} / {@code DAILY_RECORD:} 噪声键，它们一律不得影响判定。</li>
 * </ul>
 *
 * <h2>驱动方式与清理（不能依赖事务回滚）</h2>
 *
 * <p>沿用 {@code AchievementIdempotencyPropertyTest} 的范式：独立命名的内存库、<b>不用测试级事务包裹</b>、
 * 每次迭代前由 {@link #resetState()} 显式清表，并用全局自增序号 {@link #SEQ} 保证 {@code userId} 全局唯一
 * （双重隔离）。jqwik 属性方法不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 手工完成（上下文缓存复用）。</p>
 *
 * <p><b>本类不注入 {@code Clock}</b>：{@code savingMonths(userId, settleDate, existingKeys)} 把结算日
 * 作为<b>形参</b>接收，判定过程一次也不读时钟，故没有可被时钟影响的行为——结算日改由生成器驱动，
 * 覆盖面比任何一个固定时钟都宽。时区口径本身（默认时区变化下结果不变）是 Property 11 的职责，
 * 由 {@code SavingMonthTimezonePropertyTest}（任务 8.6）覆盖，本类不重复。</p>
 *
 * <p>Feature: achievement-system, Property 10: 储蓄月判定的算术与边界</p>
 *
 * <p>Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 4.8, 4.10</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-saving-month-pt;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
class GrowthSavingMonthPropertyTest {

    /** 回看窗口固定为 3 个已结束自然月（需求 4.1）；本测试自己持有一份，不引用被测的常量。 */
    private static final int LOOKBACK_MONTHS = 3;

    /** 收入下限（需求 4.4）。 */
    private static final BigDecimal MIN_INCOME = new BigDecimal("0.01");

    /** 储蓄率 20%（需求 4.8）。 */
    private static final BigDecimal SAVING_RATE = new BigDecimal("0.2");

    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private static final String PREFIX = "SAVING_MONTH:";
    private static final String TYPE_INCOME = "income";
    private static final String TYPE_EXPENSE = "expense";
    private static final String TYPE_TRANSFER = "transfer";

    /** 全局自增序号：保证跨迭代 userId / ledgerId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(770_000_000L);

    /** 交易直插语句：{@code ledger_id} 与 {@code deleted_at} 都可为 NULL（三条排除的两条靠它们构造）。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Autowired
    private GrowthSavingMonthEvaluator evaluator;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthSavingMonthPropertyTest.class).prepareTestInstance(this);
        jdbcTemplate.update("DELETE FROM transactions");
    }

    // ---------------- 朴素参考实现：独立于被测 ----------------

    /**
     * 朴素参考实现：按需求 4.1 / 4.3 / 4.4 / 4.6 / 4.7 / 4.8 的文字逐句直译，<b>逐笔</b>判月份归属。
     *
     * <p>与被测的两处机制差异见类级 Javadoc：这里用 {@code YearMonth.from(occurredAt)} 与目标月直接比对
     * （不经任何区间端点推导），判定式也不引入具名门槛中间量、不对结余做 {@code setScale}
     * （2 位小数相减本就精确，{@code compareTo} 忽略标度差异）。</p>
     */
    private static List<String> naiveSavingMonths(LocalDate settleDate, List<TxSpec> specs,
                                                  Set<String> existingKeys) {
        List<String> result = new ArrayList<>(LOOKBACK_MONTHS);
        YearMonth settleMonth = YearMonth.from(settleDate);
        // back 从 3 到 1：最早回看月 → 最近回看月，即升序（需求 4.1）。
        for (int back = LOOKBACK_MONTHS; back >= 1; back--) {
            YearMonth month = settleMonth.minusMonths(back);
            String monthKey = month.toString();
            if (existingKeys.contains(PREFIX + monthKey)) {
                continue; // 已发放的月份跳过（需求 4.19）
            }
            BigDecimal income = ZERO;
            BigDecimal expense = ZERO;
            for (TxSpec spec : specs) {
                if (spec.deleted() || spec.ledgerless()) {
                    continue; // 三条排除之二（需求 4.7）
                }
                if (!YearMonth.from(spec.occurredAt(settleDate)).equals(month)) {
                    continue; // 逐笔判月份归属，不经区间端点（需求 4.6）
                }
                BigDecimal amount = new BigDecimal(spec.amount());
                if (TYPE_INCOME.equals(spec.type())) {
                    income = income.add(amount);
                } else if (TYPE_EXPENSE.equals(spec.type())) {
                    expense = expense.add(amount);
                }
                // transfer 不进任何一项（三条排除之三，需求 4.7）
            }
            if (income.compareTo(MIN_INCOME) < 0) {
                continue; // 无收入不算存钱；查询无行时两项均为 0.00（需求 4.4）
            }
            BigDecimal threshold = income.multiply(SAVING_RATE).setScale(2, RoundingMode.HALF_UP);
            if (income.subtract(expense).compareTo(threshold) >= 0) {
                result.add(monthKey); // 取等号即成立（需求 4.3）
            }
        }
        return result;
    }

    // ---------------- 生成器 ----------------

    /**
     * 一笔待直插的交易。
     *
     * @param monthOffset 相对结算月往前的月数：0 = 结算月（不该参与判定）、1/2/3 = 回看窗口内、
     *                    4 = 第 4 个更早的月（需求 4.10 要求不判）
     * @param dayKind     月内时刻：0 = 月首 00:00:00、1 = 月中 12:00、2 = 月末 23:59:59、
     *                    3 = <b>次月 1 日 00:00:00</b>（需求 4.6 的边界，应归次月）
     * @param type        {@code income} / {@code expense} / {@code transfer}
     * @param amount      2 位小数的金额字面量
     * @param deleted     {@code deleted_at} 非空（软删，应被排除）
     * @param ledgerless  {@code ledger_id} 为 NULL（应被排除）
     */
    record TxSpec(int monthOffset, int dayKind, String type, String amount,
                  boolean deleted, boolean ledgerless) {

        /** 该笔交易的 {@code occurred_at}：由结算日、月份偏移与月内时刻共同决定。 */
        LocalDateTime occurredAt(LocalDate settleDate) {
            YearMonth month = YearMonth.from(settleDate).minusMonths(monthOffset);
            return switch (dayKind) {
                case 0 -> month.atDay(1).atStartOfDay();
                case 1 -> month.atDay(15).atTime(12, 0, 0);
                case 2 -> month.atEndOfMonth().atTime(23, 59, 59);
                // 恰好等于次月 1 日 00:00:00.000：半开区间的右端点，应归次月（需求 4.6）。
                case 3 -> month.plusMonths(1).atDay(1).atStartOfDay();
                default -> throw new IllegalStateException("未覆盖的月内时刻: " + dayKind);
            };
        }
    }

    /**
     * 金额：设计文档点名的七个取值 ∪ 随机 {@code DECIMAL(18,2)}。
     *
     * <p>随机取值以<b>「分」为单位的整数</b>生成再 {@code movePointLeft(2)}，因此恒为 2 位小数、
     * 全程无浮点。上界取 {@code 9999999.99}（十亿分）而不是 {@code DECIMAL(18,2)} 的理论上界：
     * 单月可累到十余笔，取满 16 位整数部分会让 {@code SUM} 越出列宽而在 H2 上直接报错——
     * 那考的是列宽而不是本属性。设计文档点名的最大取值 {@code 999999.99} 落在这个上界之内。</p>
     */
    @Provide
    Arbitrary<String> amounts() {
        Arbitrary<String> named = Arbitraries.of("0.00", "0.01", "0.99", "1.00", "100.00", "333.33",
                "999999.99");
        Arbitrary<String> randomDecimals = Arbitraries.longs().between(0L, 999_999_999L)
                .map(cents -> BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY)
                        .toPlainString());
        return Arbitraries.oneOf(named, randomDecimals);
    }

    /**
     * 结算日：跨年边界与闰年 2 月的关键取值 ∪ 随机日期。
     *
     * <p>关键取值逐条的用意：{@code 2025-01-15} / {@code 2024-01-01} 使回看窗口跨到上一年
     * （10/11/12 月，需求 4.1 末句）；{@code 2024-03-31} 与 {@code 2024-05-15} 使 29 天的
     * {@code 2024-02} 分别落在窗口的最近端与最远端；{@code 2023-03-15} 是 28 天平年 2 月的对照；
     * {@code 2024-12-31} 是年末；{@code 2025-06-15} 与本 spec 其它测试同基准，便于交叉对读。
     * 随机日期的日取 1–28，避免生成 {@code 2 月 30 日} 这类不存在的日期。</p>
     */
    @Provide
    Arbitrary<LocalDate> settleDates() {
        Arbitrary<LocalDate> critical = Arbitraries.of(
                LocalDate.of(2025, 1, 15),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 5, 15),
                LocalDate.of(2023, 3, 15),
                LocalDate.of(2024, 12, 31),
                LocalDate.of(2025, 6, 15));
        Arbitrary<LocalDate> random = Combinators.combine(
                Arbitraries.integers().between(2021, 2030),
                Arbitraries.integers().between(1, 12),
                Arbitraries.integers().between(1, 28)).as(LocalDate::of);
        return Arbitraries.oneOf(critical, random);
    }

    /** 交易列表：0–12 笔。月份偏移与类型都刻意向「窗口内的收支」倾斜，使判定不总是落在同一侧。 */
    @Provide
    Arbitrary<List<TxSpec>> txSpecLists() {
        Arbitrary<Integer> monthOffset = Arbitraries.frequency(
                Tuple.of(1, 0), Tuple.of(4, 1), Tuple.of(4, 2), Tuple.of(4, 3), Tuple.of(1, 4));
        Arbitrary<Integer> dayKind = Arbitraries.integers().between(0, 3);
        Arbitrary<String> type = Arbitraries.frequency(
                Tuple.of(5, TYPE_INCOME), Tuple.of(5, TYPE_EXPENSE), Tuple.of(1, TYPE_TRANSFER));
        Arbitrary<Boolean> deleted = Arbitraries.frequency(Tuple.of(1, true), Tuple.of(6, false));
        Arbitrary<Boolean> ledgerless = Arbitraries.frequency(Tuple.of(1, true), Tuple.of(6, false));
        Arbitrary<TxSpec> tx = Combinators.combine(monthOffset, dayKind, type, amounts(), deleted, ledgerless)
                .as(TxSpec::new);
        return tx.list().ofMinSize(0).ofMaxSize(12);
    }

    // ---------------- Property 10（主属性：与朴素参考实现等价） ----------------

    /**
     * Feature: achievement-system, Property 10: 储蓄月判定的算术与边界
     *
     * <p>对任意结算日 × 任意 0–12 笔交易 × 任意「已发放月份」子集：</p>
     * <ul>
     *   <li>被测返回值与 {@link #naiveSavingMonths} <b>逐例相同</b>（需求 4.1、4.3、4.4、4.5、4.8）；</li>
     *   <li>返回的月份严格升序、无重复，且<b>只能</b>是三个回看月之一
     *       ——结算日所属月与第 4 个更早的月一律不出现（需求 4.1、4.10）；</li>
     *   <li>每个返回月份的 {@code event_key} 恒为 {@code SAVING_MONTH:YYYY-MM}、长度恒 20（需求 4.2）；</li>
     *   <li>已发放的月份一律不再返回，噪声事件键不影响判定（需求 4.19）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 4.8, 4.10</p>
     */
    @Property(tries = 25)
    void property10_savingMonthDecisionMatchesNaiveBigDecimalReference(
            @ForAll("settleDates") LocalDate settleDate,
            @ForAll("txSpecLists") List<TxSpec> specs,
            @ForAll @IntRange(min = 0, max = 7) int alreadyGrantedMask) {

        long userId = SEQ.getAndIncrement();
        long ledgerId = SEQ.getAndIncrement();
        for (TxSpec spec : specs) {
            insert(userId, ledgerId, spec, settleDate);
        }

        List<String> lookback = lookbackMonths(settleDate);
        Set<String> existingKeys = existingKeys(lookback, alreadyGrantedMask);

        List<String> expected = naiveSavingMonths(settleDate, specs, existingKeys);
        List<String> actual = evaluator.savingMonths(userId, settleDate, existingKeys);

        assertThat(actual)
                .as("储蓄月判定应与朴素 BigDecimal 参考实现逐例相同（结算日 %s，%d 笔交易，已发放掩码 %d）",
                        settleDate, specs.size(), alreadyGrantedMask)
                .containsExactlyElementsOf(expected);

        // 非空洞守卫：等价性断言在「两侧都返回空列表」时恒真，因此必须确认取样确实压出过「是储蓄月」
        // 的样本。若哪天生成器漂移（例如金额上界改小、支出权重调高）致储蓄月再也出不来，本条会变红。
        // 门槛取 3 次（50 次迭代的 ~5%，与 tries 同步下调后按比例重算）：只用来挡住「一次都压不出储蓄月」
        // 这类生成器漂移，而不是把随机取样的分布本身钉死——定得太高会让属性随种子偶发变红。
        Statistics.label("判定结果")
                .collect(actual.isEmpty() ? "无储蓄月" : "有储蓄月")
                .coverage(coverage -> coverage.check("有储蓄月").count(count -> count >= 3));
        assertThat(actual).as("返回月份严格升序且无重复").isSorted().doesNotHaveDuplicates();
        assertThat(actual)
                .as("只能返回三个回看月之一：结算月与第 4 个更早的月都不参与判定（需求 4.1、4.10）")
                .isSubsetOf(lookback);
        for (String month : actual) {
            assertThat(month).as("月份键格式恒为 YYYY-MM").matches("\\d{4}-\\d{2}");
            assertThat(PREFIX + month).as("event_key 长度恒为 20（需求 4.2）").hasSize(20);
        }
        for (String granted : existingKeys) {
            assertThat(actual)
                    .as("已发放的月份不再返回（需求 4.19）：%s", granted)
                    .noneMatch(month -> (PREFIX + month).equals(granted));
        }
    }

    // ---------------- Property 10（边界探针：取等号即成立） ----------------

    /**
     * Feature: achievement-system, Property 10: 储蓄月判定的算术与边界
     *
     * <p><b>取等号即成立</b>这条（需求 4.3）的专用探针：对任意收入取值与任意回看月，
     * 构造两个只差一分的用户——</p>
     * <ul>
     *   <li>用户 A 的支出 = {@code 收入 − 门槛值}，即结余<b>恰好等于</b>门槛值 ⇒ 必须判为储蓄月；</li>
     *   <li>用户 B 的支出再多一分，即结余 = 门槛值 − {@code 0.01} ⇒ 必须判为不是储蓄月。</li>
     * </ul>
     *
     * <p>收入低于 {@code 0.01}（生成器会产出 {@code 0.00}）时两侧都必须判为不是储蓄月，
     * 无论结余多高（需求 4.4：无收入不算存钱）。</p>
     *
     * <p>两个方向合起来把门槛值<b>钉在一分的精度上</b>，因此舍入方式一旦改动就会变红：收入
     * {@code 333.33} 的门槛按 {@code HALF_UP} 是 {@code 66.67}，若改成截断（{@code DOWN}）就是
     * {@code 66.66}——用户 B 的结余恰好是 {@code 66.66}，截断实现会把它判为储蓄月，
     * 而本属性要求它不是。用 {@code double} 承载 {@code 收入 × 0.2} 同理会在某些取值上错开一分。</p>
     *
     * <p>两侧用<b>两个不同的 {@code userId}</b> 而不是清表重插：判定的归属只认 {@code created_by}，
     * 两个用户天然互不可见，一次迭代内就能比出「差一分」的两个结论。</p>
     *
     * <p>月内时刻取 {@code 0–2}（月首 / 月中 / 月末）而<b>不含</b> {@code 3}：{@code 3} 是「次月 1 日
     * 00:00:00.000」这个边界，按需求 4.6 该笔交易归<b>次月</b>，于是本探针的两笔收支会落到第
     * {@code back − 1} 个回看月上、{@code back = 1} 时更是落进不参与判定的结算月，
     * 「门槛差一分」的比较对象随之失效。该边界由上面的等价性属性以远高的密度覆盖
     * （{@link #txSpecLists()} 的 {@code dayKind} 取满 0–3），本探针不必也不该重复它。</p>
     *
     * <p>Validates: Requirements 4.3, 4.4, 4.5, 4.8</p>
     */
    @Property(tries = 25)
    void property10_equalityAtThresholdAndOneCentBelow(
            @ForAll("settleDates") LocalDate settleDate,
            @ForAll("amounts") String rawIncome,
            @ForAll @IntRange(min = 1, max = LOOKBACK_MONTHS) int back,
            @ForAll @IntRange(min = 0, max = 2) int dayKind) {

        BigDecimal income = new BigDecimal(rawIncome);
        String month = YearMonth.from(settleDate).minusMonths(back).toString();
        BigDecimal threshold = income.multiply(SAVING_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal atThreshold = income.subtract(threshold);            // 结余恰好等于门槛值
        BigDecimal oneCentBelow = atThreshold.add(ONE_CENT);            // 结余 = 门槛值 − 0.01

        long ledgerId = SEQ.getAndIncrement();
        long userAtThreshold = seedIncomeAndExpense(ledgerId, settleDate, back, dayKind, income, atThreshold);
        long userBelow = seedIncomeAndExpense(ledgerId, settleDate, back, dayKind, income, oneCentBelow);

        List<String> resultAtThreshold = evaluator.savingMonths(userAtThreshold, settleDate, Set.of());
        List<String> resultBelow = evaluator.savingMonths(userBelow, settleDate, Set.of());

        if (income.compareTo(MIN_INCOME) < 0) {
            // 无收入不算存钱：结余再高也不是储蓄月（需求 4.4）。
            assertThat(resultAtThreshold)
                    .as("收入 %s < 0.01：不是储蓄月（需求 4.4）", income).doesNotContain(month);
            assertThat(resultBelow)
                    .as("收入 %s < 0.01：不是储蓄月（需求 4.4）", income).doesNotContain(month);
            return;
        }
        assertThat(resultAtThreshold)
                .as("收入 %s、结余恰好等于门槛 %s：取等号即成立（需求 4.3）", income, threshold)
                .contains(month);
        assertThat(resultBelow)
                .as("收入 %s、结余比门槛 %s 少一分：不是储蓄月（需求 4.5；门槛舍入改成截断这条会红）",
                        income, threshold)
                .doesNotContain(month);
    }

    // ---------------- Property 10（空收入按 0.00） ----------------

    /**
     * 空收入按 {@code 0.00} 计且判为不是储蓄月（需求 4.4）：四种「一分钱收入也没有」的形态各造一个用户。
     *
     * <ul>
     *   <li>该月一行交易也没有（查询结果为空）；</li>
     *   <li>只有支出（收入行缺失）；</li>
     *   <li>收入被软删（{@code deleted_at} 非空）；</li>
     *   <li>收入的 {@code ledger_id} 为 NULL，或收入其实是 {@code transfer}。</li>
     * </ul>
     *
     * <p>四种形态都必须得出「三个回看月一个都不是储蓄月」。第一种是需求 4.4 后半句的字面情形
     * （查询结果为空按 {@code 0.00} 计），后三种保证「收入合计」不会把被排除的行悄悄算进去。</p>
     *
     * <p>Validates: Requirements 4.4, 4.7</p>
     */
    @Example
    void emptyIncomeCountsAsZeroAndIsNeverASavingMonth() {
        LocalDate settleDate = LocalDate.of(2025, 6, 15);
        long ledgerId = SEQ.getAndIncrement();

        long noRows = SEQ.getAndIncrement();
        assertThat(evaluator.savingMonths(noRows, settleDate, Set.of()))
                .as("该月一行交易也没有：收入按 0.00 计，不是储蓄月（需求 4.4）").isEmpty();

        long expenseOnly = SEQ.getAndIncrement();
        insert(expenseOnly, ledgerId, new TxSpec(1, 1, TYPE_EXPENSE, "100.00", false, false), settleDate);
        assertThat(evaluator.savingMonths(expenseOnly, settleDate, Set.of()))
                .as("只有支出、没有收入：不是储蓄月（需求 4.4）").isEmpty();

        long deletedIncome = SEQ.getAndIncrement();
        insert(deletedIncome, ledgerId, new TxSpec(1, 1, TYPE_INCOME, "5000.00", true, false), settleDate);
        assertThat(evaluator.savingMonths(deletedIncome, settleDate, Set.of()))
                .as("收入被软删：不计入收入合计，不是储蓄月（需求 4.7）").isEmpty();

        long excludedIncome = SEQ.getAndIncrement();
        insert(excludedIncome, ledgerId, new TxSpec(2, 1, TYPE_INCOME, "5000.00", false, true), settleDate);
        insert(excludedIncome, ledgerId, new TxSpec(3, 1, TYPE_TRANSFER, "5000.00", false, false), settleDate);
        assertThat(evaluator.savingMonths(excludedIncome, settleDate, Set.of()))
                .as("ledger_id 为 NULL 的收入与 transfer 都不计入收入合计（需求 4.7）").isEmpty();
    }

    // ---------------- 播种与工具 ----------------

    /**
     * 造一个用户：在第 {@code back} 个回看月写一笔收入与一笔支出，返回其 {@code userId}。
     *
     * <p>支出金额为 {@code 0.00} 时不插支出行（金额列语义上恒为正，且此时结余等于收入，
     * 与「插一行 0.00」判定等价）。</p>
     */
    private long seedIncomeAndExpense(long ledgerId, LocalDate settleDate, int back, int dayKind,
                                      BigDecimal income, BigDecimal expense) {
        long userId = SEQ.getAndIncrement();
        insert(userId, ledgerId, new TxSpec(back, dayKind, TYPE_INCOME, income.toPlainString(), false, false),
                settleDate);
        if (expense.compareTo(ZERO) > 0) {
            insert(userId, ledgerId,
                    new TxSpec(back, dayKind, TYPE_EXPENSE, expense.toPlainString(), false, false), settleDate);
        }
        return userId;
    }

    /** 直插一笔交易：归属只认 {@code created_by}，{@code ledger_id} / {@code deleted_at} 按标记置空或置值。 */
    private void insert(long userId, long ledgerId, TxSpec spec, LocalDate settleDate) {
        LocalDateTime occurredAt = spec.occurredAt(settleDate);
        Timestamp createdAt = Timestamp.valueOf(occurredAt);
        Object[] args = new Object[] {
                userId,
                spec.ledgerless() ? null : ledgerId,
                userId,
                spec.type(),
                new BigDecimal(spec.amount()),
                ledgerId,
                ledgerId,
                Timestamp.valueOf(occurredAt),
                createdAt,
                createdAt,
                spec.deleted() ? createdAt : null
        };
        int[] types = new int[] {
                Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.DECIMAL,
                Types.BIGINT, Types.BIGINT, Types.TIMESTAMP, Types.TIMESTAMP, Types.TIMESTAMP,
                Types.TIMESTAMP
        };
        jdbcTemplate.update(INSERT_TX_SQL, args, types);
    }

    /** 结算日所属月的前 3 / 2 / 1 个自然月，<b>升序</b>的 {@code YYYY-MM}（需求 4.1）。 */
    private static List<String> lookbackMonths(LocalDate settleDate) {
        YearMonth settleMonth = YearMonth.from(settleDate);
        List<String> months = new ArrayList<>(LOOKBACK_MONTHS);
        for (int back = LOOKBACK_MONTHS; back >= 1; back--) {
            months.add(settleMonth.minusMonths(back).toString());
        }
        return months;
    }

    /**
     * 按位掩码把三个回看月的任意子集标记为「已发放」，并掺入三类噪声事件键。
     *
     * <p>噪声键（{@code BADGE:} / {@code BUDGET_MET:} / {@code DAILY_RECORD:}）用于验证存在性判定只看
     * {@code SAVING_MONTH:} 前缀：若有人把判定写成「任意键里含该月份字符串」，
     * {@code BUDGET_MET:<同一个月>} 就会把该月错误地跳过。</p>
     */
    private static Set<String> existingKeys(List<String> lookback, int mask) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < lookback.size(); i++) {
            if ((mask & (1 << i)) != 0) {
                keys.add(PREFIX + lookback.get(i));
            }
        }
        // 噪声：同月份的其它前缀、以及与月份无关的键，一律不得影响判定。
        keys.add("BUDGET_MET:" + lookback.get(0));
        keys.add("DAILY_RECORD:" + lookback.get(1) + "-15");
        keys.add("BADGE:SAVING_MASTER");
        return keys;
    }
}
