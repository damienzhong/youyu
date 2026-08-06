package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.SuggestionRow;

/**
 * {@link RecordSuggestionRanker#rank} 的单元测试（record-suggestion 需求 2.2、2.3、
 * 3.1、3.2、3.3、3.4、3.5）。
 *
 * <p>{@code rank} 是不查库、不读时钟、无静态可变状态的纯函数，故不起 Spring 上下文，只用一个
 * 轻量 {@link SuggestionRow} 实现（{@link Row}）构造输入行。覆盖：去重、三级键排序、并列决胜、
 * 截断 3、空输入、note 首尾空白并组、amount 标度并额（{@code 35} vs {@code 35.00}）、传入顺序无关。</p>
 */
class RecordSuggestionRankerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2024, 6, 1, 12, 0);

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

    /** 便捷构造：默认 expense、分类 1、账户 1、无备注。 */
    private static Row row(long id, LocalDateTime at) {
        return new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "午餐", at, id);
    }

    // ---- 空输入 ----

    @Test
    void nullInputReturnsEmpty() {
        assertThat(RecordSuggestionRanker.rank(null)).isEmpty();
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(RecordSuggestionRanker.rank(List.of())).isEmpty();
    }

    // ---- 去重（需求 3.1）：同一形态合并为一条，frequency 累计 ----

    @Test
    void identicalShapesAreDedupedIntoOne() {
        List<SuggestionRow> rows = List.of(
                row(1L, T0),
                row(2L, T0.plusDays(1)),
                row(3L, T0.plusDays(2)));

        List<RankedShape> result = RecordSuggestionRanker.rank(rows);

        assertThat(result).hasSize(1);
        RankedShape only = result.get(0);
        assertThat(only.frequency()).isEqualTo(3);
        // 代表行：occurredAt 最大者（id=3）
        assertThat(only.repId()).isEqualTo(3L);
        assertThat(only.recency()).isEqualTo(T0.plusDays(2));
    }

    // ---- 三级键排序：主键 frequency 降序（需求 3.2）----

    @Test
    void sortsByFrequencyDescending() {
        // 形态 A（分类 1）出现 3 次；形态 B（分类 2）出现 1 次
        List<SuggestionRow> rows = List.of(
                new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", T0, 1L),
                new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", T0.plusDays(1), 2L),
                new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", T0.plusDays(2), 3L),
                new Row(TransactionType.EXPENSE, new BigDecimal("6"), 2L, 1L, "b", T0.plusDays(5), 4L));

        List<RankedShape> result = RecordSuggestionRanker.rank(rows);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).frequency()).isEqualTo(3);
        assertThat(result.get(0).categoryId()).isEqualTo(1L);
        assertThat(result.get(1).frequency()).isEqualTo(1);
        assertThat(result.get(1).categoryId()).isEqualTo(2L);
    }

    // ---- 三级键排序：frequency 相等时按 recency 降序（需求 3.2）----

    @Test
    void sortsByRecencyDescendingWhenFrequencyEqual() {
        // 两个形态各出现 1 次；B 的 occurredAt 更晚 → B 靠前
        Row a = new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", T0, 1L);
        Row b = new Row(TransactionType.EXPENSE, new BigDecimal("6"), 2L, 1L, "b", T0.plusDays(3), 2L);

        List<RankedShape> result = RecordSuggestionRanker.rank(List.of(a, b));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).categoryId()).isEqualTo(2L);
        assertThat(result.get(1).categoryId()).isEqualTo(1L);
    }

    // ---- 并列决胜：frequency 与 recency 均相等时按代表 id 降序（需求 3.3、3.5）----

    @Test
    void breaksTieByRepresentativeIdDescending() {
        // 两个不同形态，出现次数与 occurredAt 完全相同，仅 id 不同
        Row lowId = new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", T0, 10L);
        Row highId = new Row(TransactionType.INCOME, new BigDecimal("35"), 1L, 1L, "a", T0, 20L);

        List<RankedShape> result = RecordSuggestionRanker.rank(List.of(lowId, highId));

        assertThat(result).hasSize(2);
        // id 更大者排前
        assertThat(result.get(0).repId()).isEqualTo(20L);
        assertThat(result.get(1).repId()).isEqualTo(10L);
    }

    // ---- 代表行选取：occurredAt 并列取 id 最大（需求 2.3）----

    @Test
    void representativePicksMaxOccurredAtThenMaxId() {
        // 同一形态三笔，最大 occurredAt 有两笔并列（id 5 与 id 9），应取 id=9
        List<SuggestionRow> rows = List.of(
                row(3L, T0),
                row(5L, T0.plusDays(2)),
                row(9L, T0.plusDays(2)));

        List<RankedShape> result = RecordSuggestionRanker.rank(rows);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).repId()).isEqualTo(9L);
        assertThat(result.get(0).recency()).isEqualTo(T0.plusDays(2));
        assertThat(result.get(0).frequency()).isEqualTo(3);
    }

    // ---- 截断至多 3（需求 3.4）----

    @Test
    void truncatesToAtMostThree() {
        // 5 个不同形态（不同分类），各出现 1 次，频次相同 → 按 recency 降序取前 3
        List<SuggestionRow> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(new Row(TransactionType.EXPENSE, new BigDecimal("10"),
                    (long) (i + 1), 1L, "n" + i, T0.plusDays(i), (long) (i + 1)));
        }

        List<RankedShape> result = RecordSuggestionRanker.rank(rows);

        assertThat(result).hasSize(3);
        // recency 最晚的三个：day4(cat5), day3(cat4), day2(cat3)
        assertThat(result).extracting(RankedShape::categoryId)
                .containsExactly(5L, 4L, 3L);
    }

    // ---- note 首尾空白并组（需求 2.2）----

    @Test
    void notesDifferingOnlyByLeadingTrailingWhitespaceAreSameShape() {
        Row plain = new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "午餐", T0, 1L);
        Row padded = new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "  午餐  ", T0.plusDays(1), 2L);

        List<RankedShape> result = RecordSuggestionRanker.rank(List.of(plain, padded));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).frequency()).isEqualTo(2);
        // 规整后的 note 去首尾空白
        assertThat(result.get(0).note()).isEqualTo("午餐");
    }

    // ---- note null 与空白归一为同一形态（需求 2.2）----

    @Test
    void nullBlankAndWhitespaceNotesAreSameShape() {
        Row nullNote = new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, null, T0, 1L);
        Row blankNote = new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "   ", T0.plusDays(1), 2L);
        Row emptyNote = new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "", T0.plusDays(2), 3L);

        List<RankedShape> result = RecordSuggestionRanker.rank(List.of(nullNote, blankNote, emptyNote));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).frequency()).isEqualTo(3);
        assertThat(result.get(0).note()).isEmpty();
    }

    // ---- amount 标度并额：35 与 35.00 视为同一形态（需求 2.2）----

    @Test
    void amountsEqualIgnoringScaleAreSameShape() {
        Row scaleZero = new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "午餐", T0, 1L);
        Row scaleTwo = new Row(TransactionType.EXPENSE, new BigDecimal("35.00"), 1L, 1L, "午餐", T0.plusDays(1), 2L);

        List<RankedShape> result = RecordSuggestionRanker.rank(List.of(scaleZero, scaleTwo));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).frequency()).isEqualTo(2);
    }

    @Test
    void amountsWithDifferentValuesAreDifferentShapes() {
        Row a = new Row(TransactionType.EXPENSE, new BigDecimal("35.00"), 1L, 1L, "午餐", T0, 1L);
        Row b = new Row(TransactionType.EXPENSE, new BigDecimal("35.50"), 1L, 1L, "午餐", T0.plusDays(1), 2L);

        List<RankedShape> result = RecordSuggestionRanker.rank(List.of(a, b));

        assertThat(result).hasSize(2);
    }

    // ---- 「当天已记」排除：近因 >= 边界的形态被剔除（issue #3）----

    @Test
    void excludesShapesWithRecencyOnOrAfterBoundary() {
        // 形态 A（分类 1）近因落在边界当天 → 排除；形态 B（分类 2）近因在边界之前 → 保留。
        LocalDateTime boundary = T0.plusDays(3); // 今天 00:00 的等价边界
        List<SuggestionRow> rows = List.of(
                new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", boundary.plusHours(2), 1L),
                new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", T0, 2L),
                new Row(TransactionType.EXPENSE, new BigDecimal("6"), 2L, 1L, "b", T0.plusDays(1), 3L));

        List<RankedShape> result = RecordSuggestionRanker.rank(rows, boundary);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryId()).isEqualTo(2L);
    }

    @Test
    void boundaryIsInclusive_recencyExactlyAtBoundaryIsExcluded() {
        LocalDateTime boundary = T0.plusDays(3);
        // 形态 A 近因恰好等于边界 → 排除（当天 00:00 也算当天已记）。
        List<SuggestionRow> rows = List.of(
                new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", boundary, 1L),
                new Row(TransactionType.EXPENSE, new BigDecimal("6"), 2L, 1L, "b", T0, 2L));

        List<RankedShape> result = RecordSuggestionRanker.rank(rows, boundary);

        assertThat(result).extracting(RankedShape::categoryId).containsExactly(2L);
    }

    @Test
    void nullBoundaryDisablesExclusion() {
        List<SuggestionRow> rows = List.of(
                new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", T0.plusDays(9), 1L),
                new Row(TransactionType.EXPENSE, new BigDecimal("6"), 2L, 1L, "b", T0, 2L));

        // 传 null 边界：等价于不排除的两参调用 == 无参调用。
        assertThat(RecordSuggestionRanker.rank(rows, null)).isEqualTo(RecordSuggestionRanker.rank(rows));
    }

    // ---- 传入顺序无关（需求 3.5）----

    @Test
    void resultIsIndependentOfInputOrder() {
        List<SuggestionRow> rows = new ArrayList<>(List.of(
                new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", T0, 1L),
                new Row(TransactionType.EXPENSE, new BigDecimal("35"), 1L, 1L, "a", T0.plusDays(1), 2L),
                new Row(TransactionType.EXPENSE, new BigDecimal("6"), 2L, 1L, "b", T0.plusDays(5), 3L),
                new Row(TransactionType.INCOME, new BigDecimal("100"), 3L, 2L, "c", T0.plusDays(2), 4L),
                new Row(TransactionType.INCOME, new BigDecimal("100"), 3L, 2L, "c", T0.plusDays(3), 5L)));

        List<RankedShape> forwardResult = RecordSuggestionRanker.rank(rows);

        List<SuggestionRow> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        List<RankedShape> reversedResult = RecordSuggestionRanker.rank(reversed);

        List<SuggestionRow> shuffled = new ArrayList<>(rows);
        Collections.shuffle(shuffled, new java.util.Random(42));
        List<RankedShape> shuffledResult = RecordSuggestionRanker.rank(shuffled);

        // 三种传入顺序得到完全相同的集合与次序（record 值相等）
        assertThat(reversedResult).isEqualTo(forwardResult);
        assertThat(shuffledResult).isEqualTo(forwardResult);

        // 明确断言排序次序：形态 A(freq2) → 形态 C(freq2,recency 更晚) 需比较；此处校验主次键
        // A: freq=2, recency=day1; C: freq=2, recency=day3 → C 在前；B: freq=1 最后
        assertThat(forwardResult).extracting(RankedShape::categoryId)
                .containsExactly(3L, 1L, 2L);
    }
}
