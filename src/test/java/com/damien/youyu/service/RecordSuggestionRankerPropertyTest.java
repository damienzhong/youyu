package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.SuggestionRow;

/**
 * {@link RecordSuggestionRanker#rank} 的属性测试（record-suggestion 设计文档 Property 1/2/3）。
 *
 * <h2>测试层级选择</h2>
 * <p>{@code rank} 是不查库、不读时钟、无静态可变状态的纯函数：窗口/账本/类型/软删过滤由仓库查询在入口
 * 完成，本类只对已限定的行做内存分组、排序、截断，故可直接以随机历史行投影驱动纯函数、走纯 jqwik，
 * 不引入 Spring 上下文、不落库、不 mock（对齐 {@link StreakSegmentInvariantsPropertyTest} 风格）。</p>
 *
 * <h2>生成维度</h2>
 * <p>形态由五元组 {@code (type, categoryId, accountId, amount规整, note规整)} 决定。为让「碰撞」高频发生
 * （否则每条行各成一形态，去重/并列决胜永不触发），各维度都取<strong>小基数值池</strong>：类型 2、分类 3、
 * 账户 2、金额基值 4（配 0/1/2 三种标度制造标度并额）、备注含 null/空串/含首尾空白变体、时间取少数几个
 * 时刻（制造 recency 并列与代表选取并列）。每行 {@code id} 唯一，随列表位置分配，打乱输入时 id 随行绑定
 * 不变——正是全序末级决胜键所依赖的。</p>
 *
 * <h2>期望值的独立计算</h2>
 * <p>三条属性的期望值均<strong>不复用</strong>被测的分组/排序逻辑，而是另写朴素参考：形态键用
 * {@link BigDecimal#stripTrailingZeros} + {@link String#strip} 直接算；代表行用 {@code (occurredAt, id)}
 * 字典序取最大值另行求；排序断言逐对比较三级键。两侧算法不同、互为参照。</p>
 *
 * <p>Feature: record-suggestion, Property 1/2/3</p>
 *
 * <p>Validates: Requirements 2.3, 3.1, 3.2, 3.3, 3.4, 3.5</p>
 */
class RecordSuggestionRankerPropertyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2024, 6, 1, 8, 0);

    /** 时间值池：少数几个时刻，制造 recency 并列与代表选取的 occurredAt 并列。 */
    private static final List<LocalDateTime> INSTANTS = List.of(
            BASE,
            BASE.plusHours(5),
            BASE.plusDays(1),
            BASE.plusDays(2),
            BASE.plusDays(2).plusHours(3),
            BASE.plusDays(7));

    /** 备注值池：含 null、空串、含首尾空白变体，验证规整后并组。 */
    private static final List<String> NOTES = new ArrayList<>();

    static {
        NOTES.add(null);
        NOTES.add("");
        NOTES.add("   ");
        NOTES.add("午餐");
        NOTES.add("  午餐  ");
        NOTES.add("\t奶茶\n");
        NOTES.add("地铁");
    }

    // ---------------- 轻量 SuggestionRow 测试替身 ----------------

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

    // ---------------- 生成器 ----------------

    /** 单行（不含 id），维度取小基数池以制造高频形态碰撞。 */
    private Arbitrary<Row> rowWithoutId() {
        Arbitrary<TransactionType> type = Arbitraries.of(TransactionType.EXPENSE, TransactionType.INCOME);
        Arbitrary<Long> categoryId = Arbitraries.of(1L, 2L, 3L);
        Arbitrary<Long> accountId = Arbitraries.of(1L, 2L);
        Arbitrary<Integer> amountBase = Arbitraries.of(6, 16, 35, 100);
        Arbitrary<Integer> amountScale = Arbitraries.of(0, 1, 2);
        Arbitrary<String> note = Arbitraries.of(NOTES);
        Arbitrary<LocalDateTime> occurredAt = Arbitraries.of(INSTANTS);

        return Combinators.combine(type, categoryId, accountId, amountBase, amountScale, note, occurredAt)
                .as((t, cat, acc, base, scale, n, at) ->
                        new Row(t, new BigDecimal(base).setScale(scale), cat, acc, n, at, null));
    }

    /** 行列表：分配唯一 id（随列表位置），规模封顶 40。 */
    @Provide
    Arbitrary<List<Row>> rowLists() {
        return rowWithoutId().list().ofMaxSize(40).map(RecordSuggestionRankerPropertyTest::assignUniqueIds);
    }

    private static List<Row> assignUniqueIds(List<Row> rows) {
        List<Row> withIds = new ArrayList<>(rows.size());
        long id = 1L;
        for (Row r : rows) {
            withIds.add(new Row(r.type, r.amount, r.categoryId, r.accountId, r.note, r.occurredAt, id++));
        }
        return withIds;
    }

    // ---------------- Property 1: 去重 ----------------

    /**
     * Feature: record-suggestion, Property 1: 去重
     *
     * <p>任意历史行集合经 {@code rank} 后，结果中不存在两条形态相同的候选。形态键由独立朴素参考
     * （{@code stripTrailingZeros} + {@code strip}）计算，不复用被测分组逻辑。</p>
     *
     * <p>Validates: Requirements 3.1</p>
     */
    @Property(tries = 25)
    void property1_noTwoResultsShareTheSameShape(@ForAll("rowLists") List<Row> rows) {
        List<RankedShape> result = RecordSuggestionRanker.rank(toRows(rows));

        Set<ShapeKeyT> seen = new HashSet<>();
        for (RankedShape r : result) {
            ShapeKeyT key = shapeOf(r.type(), r.amount(), r.categoryId(), r.accountId(), r.note());
            assertThat(seen.add(key))
                    .as("结果中不应出现两条形态相同的候选（形态=%s）", key)
                    .isTrue();
        }
    }

    // ---------------- Property 2: 全序 + 传入顺序无关确定性 ----------------

    /**
     * Feature: record-suggestion, Property 2: 排序全序且确定
     *
     * <p>结果按 {@code (frequency desc, recency desc, repId desc)} 全序排列（逐对断言三级键），
     * 且对同一输入（含多种打乱顺序）多次调用返回相同集合与相同次序。</p>
     *
     * <p>Validates: Requirements 3.2, 3.3, 3.5</p>
     */
    @Property(tries = 25)
    void property2_fullOrderAndOrderIndependentDeterminism(
            @ForAll("rowLists") List<Row> rows,
            @ForAll long shuffleSeed) {

        List<RankedShape> result = RecordSuggestionRanker.rank(toRows(rows));

        // 全序：任意相邻两条满足 (freq desc, recency desc, repId desc)，且严格可比（末级 repId 两两不同）。
        for (int i = 0; i + 1 < result.size(); i++) {
            RankedShape a = result.get(i);
            RankedShape b = result.get(i + 1);
            assertThat(precedes(a, b))
                    .as("第 %d 条应严格排在第 %d 条之前：a=%s, b=%s", i, i + 1, a, b)
                    .isTrue();
        }

        // 传入顺序无关确定性：多种打乱顺序（含反转）得到与原始完全相同的集合与次序。
        List<SuggestionRow> reversed = new ArrayList<>(toRows(rows));
        Collections.reverse(reversed);
        assertThat(RecordSuggestionRanker.rank(reversed))
                .as("反转输入不应改变结果集合与次序（纯函数、全序确定）")
                .isEqualTo(result);

        List<SuggestionRow> shuffled = new ArrayList<>(toRows(rows));
        Collections.shuffle(shuffled, new Random(shuffleSeed));
        assertThat(RecordSuggestionRanker.rank(shuffled))
                .as("随机打乱输入不应改变结果集合与次序（纯函数、全序确定）")
                .isEqualTo(result);
    }

    // ---------------- Property 3: 截断 ≤3 + 代表选取确定 ----------------

    /**
     * Feature: record-suggestion, Property 3: 截断至多 3、代表选取确定
     *
     * <p>结果条数 ≤ 3；每条候选的代表字段（type/amount/categoryId/accountId/note/recency/repId）与
     * frequency，取自该形态在输入中 {@code occurredAt} 最大（并列取 {@code id} 最大）的那一行——期望值由
     * 独立朴素参考按 {@code (occurredAt, id)} 字典序求最大值算出。</p>
     *
     * <p>Validates: Requirements 2.3, 3.4</p>
     */
    @Property(tries = 25)
    void property3_truncationAndDeterministicRepresentative(@ForAll("rowLists") List<Row> rows) {
        List<SuggestionRow> input = toRows(rows);
        List<RankedShape> result = RecordSuggestionRanker.rank(input);

        // 截断至多 3（需求 3.4）。
        assertThat(result).hasSizeLessThanOrEqualTo(RecordSuggestionRanker.MAX_SUGGESTIONS);

        // 按形态分组的独立参考。
        Map<ShapeKeyT, List<SuggestionRow>> groups = new HashMap<>();
        for (SuggestionRow r : input) {
            ShapeKeyT key = shapeOf(r.getType(), r.getAmount(), r.getCategoryId(), r.getAccountId(),
                    normalizeNote(r.getNote()));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        for (RankedShape rankedShape : result) {
            ShapeKeyT key = shapeOf(rankedShape.type(), rankedShape.amount(), rankedShape.categoryId(),
                    rankedShape.accountId(), rankedShape.note());
            List<SuggestionRow> group = groups.get(key);
            assertThat(group)
                    .as("每条候选的形态都应对应输入中的一个真实形态组：%s", key)
                    .isNotNull();

            // 独立参考：代表行 = (occurredAt, id) 字典序最大者。
            SuggestionRow expectedRep = group.stream()
                    .max(Comparator.comparing(SuggestionRow::getOccurredAt)
                            .thenComparing(SuggestionRow::getId))
                    .orElseThrow();

            assertThat(rankedShape.frequency())
                    .as("frequency 应等于该形态组的行数")
                    .isEqualTo(group.size());
            assertThat(rankedShape.repId())
                    .as("代表 id 应为组内 occurredAt 最大（并列 id 最大）者")
                    .isEqualTo(expectedRep.getId());
            assertThat(rankedShape.recency())
                    .as("recency 应为代表行的 occurredAt")
                    .isEqualTo(expectedRep.getOccurredAt());
            assertThat(rankedShape.type())
                    .as("代表 type 取自代表行")
                    .isEqualTo(expectedRep.getType());
            assertThat(rankedShape.amount())
                    .as("代表 amount 取自代表行的原始金额")
                    .isEqualTo(expectedRep.getAmount());
            assertThat(rankedShape.categoryId())
                    .as("代表 categoryId 取自代表行")
                    .isEqualTo(expectedRep.getCategoryId());
            assertThat(rankedShape.accountId())
                    .as("代表 accountId 取自代表行")
                    .isEqualTo(expectedRep.getAccountId());
            assertThat(rankedShape.note())
                    .as("代表 note 为代表行 note 的规整结果")
                    .isEqualTo(normalizeNote(expectedRep.getNote()));
        }
    }

    /** 空输入：结果为空（截断上界的平凡下界，需求 3.4）。 */
    @Example
    void property3_emptyInputYieldsEmptyResult() {
        assertThat(RecordSuggestionRanker.rank(List.of())).isEmpty();
        assertThat(RecordSuggestionRanker.rank(null)).isEmpty();
    }

    // ---------------- 独立朴素参考实现 ----------------

    /** 形态键：五元组，金额去尾随零、备注去首尾空白后比较（独立于被测分组逻辑）。 */
    private record ShapeKeyT(TransactionType type, Long categoryId, Long accountId, BigDecimal amount, String note) {
    }

    private static ShapeKeyT shapeOf(TransactionType type, BigDecimal amount, Long categoryId, Long accountId,
            String note) {
        return new ShapeKeyT(type, categoryId, accountId,
                amount == null ? null : amount.stripTrailingZeros(), normalizeNote(note));
    }

    private static String normalizeNote(String note) {
        return note == null ? "" : note.strip();
    }

    /** {@code a} 是否应严格排在 {@code b} 之前：freq desc → recency desc → repId desc。 */
    private static boolean precedes(RankedShape a, RankedShape b) {
        if (a.frequency() != b.frequency()) {
            return a.frequency() > b.frequency();
        }
        int byRecency = a.recency().compareTo(b.recency());
        if (byRecency != 0) {
            return byRecency > 0;
        }
        return Objects.compare(a.repId(), b.repId(), Comparator.naturalOrder()) > 0;
    }

    private static List<SuggestionRow> toRows(List<Row> rows) {
        return new ArrayList<>(rows);
    }
}
