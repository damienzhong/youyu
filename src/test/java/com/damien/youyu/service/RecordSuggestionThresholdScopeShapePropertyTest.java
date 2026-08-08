package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.SuggestionRow;

/**
 * 记账推荐排序器 {@link RecordSuggestionRanker} 的属性测试（record-suggestion 设计 Property 4/5/6）。
 *
 * <p>{@code rank} 是不查库、不读时钟、无静态可变状态的纯函数，故本类走纯 jqwik、不起 Spring 上下文、
 * 不落库、不 mock，只用一个轻量 {@link SuggestionRow} 实现（{@link Row}）驱动。类名与任务 5.1
 * （Property 1/2/3）刻意区分，避免编译期类名冲突。</p>
 *
 * <ul>
 *   <li><b>Property 4（展示门槛）</b>：合格候选去重后 &lt; 2 组时服务层返回空列表；≥ 2 时返回 2 或 3 条。
 *       服务层门槛（{@code MIN_SUGGESTIONS=2}）在此以 {@code rank} 输出规模 + 文档化门槛规则建模验证：
 *       {@code rank} 恒返回 {@code min(去重形态数, 3)} 条；据此建模服务层「&lt;2 → 空，否则原样」的取值。</li>
 *   <li><b>Property 5（只含支出/收入且窗口内）</b>：对只喂 {@code type ∈ {expense, income}} 且
 *       {@code occurredAt} 落在窗口区间内的行，{@code rank} 输出的每条候选类型仍 ∈ {expense, income}、
 *       每条代表行 {@code recency} 仍落在窗口区间内（仓库查询负责过滤 transfer 与软删，本层验证排序器
 *       不臆造窗口外/类型外的候选）。</li>
 *   <li><b>Property 6（备注规整 + 金额标度并额）</b>：同一形态的多笔历史，仅备注首尾空白不同、金额标度不同
 *       （如 {@code 35} 与 {@code 35.00}）时合并为同一条候选（frequency 累计），不因空白/标度差异被拆分。</li>
 * </ul>
 */
class RecordSuggestionThresholdScopeShapePropertyTest {

    /** 服务层展示门槛：合格候选去重后 &lt; 2 条返回空（design.md {@code MIN_SUGGESTIONS=2}，需求 7.1）。 */
    private static final int MIN_SUGGESTIONS = 2;

    /** 排序器截断上限（需求 3.4）。 */
    private static final int MAX_SUGGESTIONS = 3;

    private static final LocalDateTime T0 = LocalDateTime.of(2024, 6, 1, 12, 0);

    // 窗口区间（Asia/Shanghai 口径的示例窗口，仅供 Property 5 生成/断言 recency 范围）：
    // [当日−29 日 00:00:00.000, 当日 23:59:59.999999999]（需求 2.4）。
    private static final LocalDate TODAY = LocalDate.of(2024, 6, 30);
    private static final LocalDateTime FROM = TODAY.minusDays(29).atStartOfDay();
    private static final LocalDateTime TO = TODAY.atTime(LocalTime.MAX);
    private static final long WINDOW_SECONDS = Duration.between(FROM, TO).getSeconds();

    /** 备注取值池：均为无首尾空白的干净词元（含空串），Property 6 在其外围随机加首尾空白扰动。 */
    private static final String[] NOTE_POOL = {"", "午餐", "coffee", "地铁"};

    // ============================ Property 4：展示门槛 ============================

    /**
     * Property 4：展示门槛。
     *
     * <p>对任意历史行集合：{@code rank} 恒返回 {@code min(去重形态数, 3)} 条（≤ 3）；据此建模服务层门槛
     * ——去重形态数 &lt; 2 时服务返回空列表，≥ 2 时返回 2 或 3 条。断言服务层输出规模恒 ∈ {0, 2, 3}
     * （绝不为 1），且与独立算得的去重形态数一致。</p>
     *
     * <p>Validates: Requirements 1.1, 6.6, 7.1</p>
     */
    @Property(tries = 25)
    void property4_thresholdYieldsEmptyBelowTwoOtherwiseTwoOrThree(
            @ForAll("shapeIndexLists") List<Integer> shapeIndices) {
        List<SuggestionRow> rows = rowsFromShapeIndices(shapeIndices);

        // 独立算得的去重形态数（不复用 rank）：每个池索引 = 一个独立形态（categoryId 唯一）。
        int distinctShapes = new HashSet<>(shapeIndices).size();

        List<RankedShape> ranked = RecordSuggestionRanker.rank(rows);

        // rank 恒返回 min(去重形态数, 3) 条。
        assertThat(ranked).hasSize(Math.min(distinctShapes, MAX_SUGGESTIONS));
        assertThat(ranked.size()).isLessThanOrEqualTo(MAX_SUGGESTIONS);

        // 服务层门槛建模：<2 组返回空，否则原样返回（design.md MIN_SUGGESTIONS=2）。
        List<RankedShape> serviceOutput = ranked.size() < MIN_SUGGESTIONS ? List.of() : ranked;

        // 服务层输出规模恒 ∈ {0, 2, 3}，绝不为 1（需求 1.1/6.6/7.1）。
        assertThat(serviceOutput.size()).isIn(0, 2, 3);

        if (distinctShapes < MIN_SUGGESTIONS) {
            assertThat(serviceOutput).isEmpty();
        } else {
            assertThat(serviceOutput.size()).isIn(2, 3);
            assertThat(serviceOutput.size()).isEqualTo(Math.min(distinctShapes, MAX_SUGGESTIONS));
        }
    }

    /** 池索引列表（0–5，可空）：每个索引对应一个独立形态，重复索引触发去重合并。 */
    @Provide
    Arbitrary<List<Integer>> shapeIndexLists() {
        return Arbitraries.integers().between(0, 5).list().ofMaxSize(30);
    }

    /** 把池索引列表物化为历史行：索引 v → 形态 (type=v偶EXPENSE/奇INCOME, categoryId=v, accountId=1)。 */
    private static List<SuggestionRow> rowsFromShapeIndices(List<Integer> indices) {
        List<SuggestionRow> rows = new ArrayList<>(indices.size());
        long id = 1;
        for (int i = 0; i < indices.size(); i++) {
            int v = indices.get(i);
            TransactionType type = (v % 2 == 0) ? TransactionType.EXPENSE : TransactionType.INCOME;
            rows.add(new Row(type, new BigDecimal(10 + v), (long) v, 1L, "n" + v,
                    T0.plusMinutes(i), id++));
        }
        return rows;
    }

    // ==================== Property 5：只含支出/收入且窗口内 ====================

    /**
     * Property 5：只含支出/收入且窗口内。
     *
     * <p>对只喂 {@code type ∈ {EXPENSE, INCOME}} 且 {@code occurredAt ∈ [FROM, TO]} 的行，
     * {@code rank} 输出的每条候选类型仍 ∈ {EXPENSE, INCOME}，每条代表行 {@code recency} 仍落在窗口
     * 闭区间内——排序器不臆造类型外/窗口外的候选，代表行必取自输入行（需求 2.1、2.4）。</p>
     *
     * <p>Validates: Requirements 2.1, 2.4</p>
     */
    @Property(tries = 25)
    void property5_outputTypesInScopeAndRecencyWithinWindow(
            @ForAll("windowRowSpecs") List<RowSpec> specs) {
        List<SuggestionRow> rows = new ArrayList<>(specs.size());
        long id = 1;
        for (RowSpec s : specs) {
            LocalDateTime occurredAt = FROM.plusSeconds(s.secs());
            rows.add(new Row(s.type(), new BigDecimal(s.units()), (long) s.cat(), 1L,
                    "n" + s.cat(), occurredAt, id++));
        }

        List<RankedShape> ranked = RecordSuggestionRanker.rank(rows);

        for (RankedShape shape : ranked) {
            assertThat(shape.type()).isIn(TransactionType.EXPENSE, TransactionType.INCOME);
            assertThat(shape.recency()).isNotNull();
            assertThat(shape.recency()).isAfterOrEqualTo(FROM);
            assertThat(shape.recency()).isBeforeOrEqualTo(TO);
        }
    }

    /** 窗口内支出/收入行规格：类型 ∈ {expense, income}，occurredAt 由 secs 偏移落在 [FROM, TO]。 */
    record RowSpec(TransactionType type, int cat, int units, long secs) {
    }

    @Provide
    Arbitrary<List<RowSpec>> windowRowSpecs() {
        Arbitrary<RowSpec> spec = Combinators.combine(
                        Arbitraries.of(TransactionType.EXPENSE, TransactionType.INCOME),
                        Arbitraries.integers().between(0, 5),
                        Arbitraries.integers().between(1, 100_000),
                        Arbitraries.longs().between(0, WINDOW_SECONDS))
                .as(RowSpec::new);
        return spec.list().ofMaxSize(30);
    }

    // ==================== Property 6：备注规整 + 金额标度并额 ====================

    /**
     * Property 6：备注规整 + 金额标度并额。
     *
     * <p>构造若干（1–3 个，均以唯一 categoryId 彼此区分）逻辑形态，每个形态的多笔历史仅在
     * <b>备注首尾空白</b>与<b>金额标度</b>（如 {@code 35} vs {@code 35.00}）上随机不同、数值与词元相同。
     * 断言 {@code rank} 把每个逻辑形态恰好合并为一条候选（形态数不因空白/标度差异被拆分），
     * 每条候选的 {@code frequency} 等于该形态的笔数，且输出备注为规整后文本、输出金额与基准数值相等
     * （需求 2.2）。</p>
     *
     * <p>形态数封顶 3，确保不触发 {@code limit(3)} 截断，从而可逐一校验每个形态的合并结果。</p>
     *
     * <p>Validates: Requirements 2.2</p>
     */
    @Property(tries = 25)
    void property6_whitespaceAndScaleVariantsMergeIntoOneShape(
            @ForAll("shapeGroups") List<int[]> groups,
            @ForAll long seed) {
        Random rnd = new Random(seed);
        List<SuggestionRow> rows = new ArrayList<>();
        Map<Long, Integer> expectedFrequency = new java.util.HashMap<>();
        Map<Long, BigDecimal> expectedAmount = new java.util.HashMap<>();
        Map<Long, String> expectedNote = new java.util.HashMap<>();

        long id = 1;
        int minute = 0;
        for (int gi = 0; gi < groups.size(); gi++) {
            int[] g = groups.get(gi);
            int units = g[0];
            String baseNote = NOTE_POOL[g[1]];
            int count = g[2];
            long categoryId = gi;                 // 唯一 categoryId ⇒ 各逻辑形态天然可区分
            BigDecimal baseAmount = new BigDecimal(units);

            expectedFrequency.put(categoryId, count);
            expectedAmount.put(categoryId, baseAmount);
            expectedNote.put(categoryId, baseNote.strip());

            for (int j = 0; j < count; j++) {
                // 金额标度扰动：数值不变，仅尾随零位数不同（35 → 35.00 …）。
                BigDecimal amount = baseAmount.setScale(rnd.nextInt(5));
                // 备注首尾空白扰动：仅在两端加空白，规整后应与基准词元一致。
                String note = " ".repeat(rnd.nextInt(4)) + baseNote + "\t".repeat(rnd.nextInt(4));
                rows.add(new Row(TransactionType.EXPENSE, amount, categoryId, 1L, note,
                        T0.plusMinutes(minute++), id++));
            }
        }

        List<RankedShape> ranked = RecordSuggestionRanker.rank(rows);

        // 每个逻辑形态恰好合并为一条候选（未因空白/标度差异被拆分）。
        assertThat(ranked).hasSize(groups.size());

        Map<Long, RankedShape> byCategory = ranked.stream()
                .collect(Collectors.toMap(RankedShape::categoryId, Function.identity()));
        assertThat(byCategory.keySet()).isEqualTo(expectedFrequency.keySet());

        for (Long categoryId : expectedFrequency.keySet()) {
            RankedShape shape = byCategory.get(categoryId);
            assertThat(shape.frequency()).isEqualTo(expectedFrequency.get(categoryId));
            // 输出备注为规整后文本（首尾空白已去除，null/空白归一为空串）。
            assertThat(shape.note()).isEqualTo(expectedNote.get(categoryId));
            // 输出金额与基准数值相等（标度差异不影响）。
            assertThat(shape.amount()).usingComparator(BigDecimal::compareTo)
                    .isEqualTo(expectedAmount.get(categoryId));
        }
    }

    /** 形态分组：每个 int[]{units, noteIdx, count}，1–3 个（不触发 limit(3) 截断）。 */
    @Provide
    Arbitrary<List<int[]>> shapeGroups() {
        Arbitrary<int[]> group = Combinators.combine(
                        Arbitraries.integers().between(1, 100_000),
                        Arbitraries.integers().between(0, NOTE_POOL.length - 1),
                        Arbitraries.integers().between(1, 6))
                .as((u, n, c) -> new int[] {u, n, c});
        return group.list().ofMinSize(1).ofMaxSize(3);
    }

    // ============================ 测试替身 ============================

    /** 轻量 {@link SuggestionRow} 测试替身。 */
    private record Row(
            TransactionType type,
            BigDecimal amount,
            Long categoryId,
            Long accountId,
            String note,
            LocalDateTime occurredAt,
            Long id) implements SuggestionRow {

        @Override
        public TransactionType getType() {
            return type;
        }

        @Override
        public BigDecimal getAmount() {
            return amount;
        }

        @Override
        public Long getCategoryId() {
            return categoryId;
        }

        @Override
        public Long getAccountId() {
            return accountId;
        }

        @Override
        public String getNote() {
            return note;
        }

        @Override
        public LocalDateTime getOccurredAt() {
            return occurredAt;
        }

        @Override
        public Long getId() {
            return id;
        }
    }
}
