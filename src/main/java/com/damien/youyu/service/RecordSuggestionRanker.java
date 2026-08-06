package com.damien.youyu.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.SuggestionRow;

/**
 * 记账推荐的分组去重 + 排序 + 截断的<strong>唯一实现</strong>（record-suggestion 需求 2.2、2.3、
 * 3.1、3.2、3.3、3.4、3.5）。
 *
 * <p>对齐 {@link StreakJudgment} 的静态工具风格：{@link #rank} 是不读时钟、不查库、无静态可变状态的
 * 纯函数——输入一组窗口内历史行投影，输出去重且全序排列、至多 3 条的候选。窗口/账本/类型/软删过滤
 * 由仓库查询（{@code findSuggestionWindowRows}）在入口处完成，本类只对已限定的行做内存聚合，
 * 因此可被属性测试穷举（需求 3 全部）。</p>
 *
 * <p><strong>形态标识</strong>为五元组
 * {@code (type, categoryId, accountId, amount规整, normalizeNote(note))}：其中 {@code amount} 以
 * {@link BigDecimal#stripTrailingZeros()} 归一后参与 equals/hashCode，故 {@code 35} 与 {@code 35.00}
 * 视为同额、同一形态（需求 2.2）；{@code note} 以 {@link #normalizeNote} 去首尾空白、null/空白归一为
 * 空串后比较。</p>
 *
 * <p><strong>排序全序</strong>：{@code frequency} 降序 → {@code recency}（代表流水 {@code occurredAt}）
 * 降序 → 代表流水 {@code id} 降序。三级键构成全序，故结果集合与其精确次序对同一输入历史唯一确定，
 * 与输入行的传入顺序无关（需求 3.2、3.3、3.5）。</p>
 */
public final class RecordSuggestionRanker {

    /** 推荐卡至多展示的候选数（需求 3.4）。 */
    static final int MAX_SUGGESTIONS = 3;

    /**
     * 候选全序比较器：{@code frequency} 降序 → {@code recency} 降序 → 代表 {@code id} 降序。
     *
     * <p>三级键各自取降序；因每条候选的代表 {@code id} 两两不同（同一代表流水只属一个形态），
     * 末级键保证任意两条候选严格可比，排序结果唯一（需求 3.3、3.5）。</p>
     */
    private static final Comparator<RankedShape> BY_FREQ_DESC =
            Comparator.comparingInt(RankedShape::frequency).reversed();
    private static final Comparator<RankedShape> BY_RECENCY_DESC =
            Comparator.comparing(RankedShape::recency, Comparator.reverseOrder());
    private static final Comparator<RankedShape> BY_REP_ID_DESC =
            Comparator.comparing(RankedShape::repId, Comparator.reverseOrder());
    private static final Comparator<RankedShape> ORDER =
            BY_FREQ_DESC.thenComparing(BY_RECENCY_DESC).thenComparing(BY_REP_ID_DESC);

    private RecordSuggestionRanker() {
        // 纯函数工具类，不允许实例化。
    }

    /**
     * 分组去重 + 排序 + 截断到前 {@value #MAX_SUGGESTIONS}（不做「当天已记」排除）。
     *
     * <p>等价于 {@link #rank(List, LocalDateTime)} 传入 {@code null} 边界。输入行假定已由仓库限定
     * 窗口/账本/类型/未删除；本方法只按形态分组、聚合、全序排序、取前 3。传入 {@code null} 或空列表
     * 返回空列表。</p>
     *
     * @param rows 窗口内历史行投影（顺序不影响结果）
     * @return 去重且按 {@link #ORDER} 全序排列的候选，至多 3 条
     */
    public static List<RankedShape> rank(List<SuggestionRow> rows) {
        return rank(rows, null);
    }

    /**
     * 分组去重 + 「当天已记」排除 + 排序 + 截断到前 {@value #MAX_SUGGESTIONS}。
     *
     * <p>在分组聚合之后、排序截断之前，剔除<strong>代表流水近因（组内最晚 {@code occurredAt}）落在
     * {@code excludeIfRecordedOnOrAfter}（含）之后</strong>的形态：某形态只要在该边界当日已有一笔记录，
     * 整条候选当天即不再出现（用户「已经记过就别再推」的预期）；次日边界推进后该形态自然恢复。因近因取
     * 组内最晚 {@code occurredAt}，「近因 ≥ 边界」与「该形态在边界当日已有一笔」等价。</p>
     *
     * <p>排除发生在 {@code limit(3)} 之前，故被排除的形态不会白占名额——若还有其它合格形态，它们会补位。
     * 边界传 {@code null} 时不排除，退化为纯分组排序（保持既有纯函数语义）。</p>
     *
     * @param rows                       窗口内历史行投影（顺序不影响结果）
     * @param excludeIfRecordedOnOrAfter 排除边界（通常为「今天 00:00」，Asia/Shanghai）；{@code null} 表示不排除
     * @return 去重、排除当天已记、并按 {@link #ORDER} 全序排列的候选，至多 3 条
     */
    public static List<RankedShape> rank(List<SuggestionRow> rows, LocalDateTime excludeIfRecordedOnOrAfter) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<ShapeKey, Agg> byShape = new LinkedHashMap<>();
        for (SuggestionRow r : rows) {
            ShapeKey key = new ShapeKey(r.getType(), r.getCategoryId(), r.getAccountId(),
                    normalizeAmount(r.getAmount()), normalizeNote(r.getNote()));
            byShape.computeIfAbsent(key, k -> new Agg()).accept(r);
        }
        Stream<RankedShape> shapes = byShape.values().stream().map(Agg::toRanked);
        if (excludeIfRecordedOnOrAfter != null) {
            shapes = shapes.filter(s -> s.recency().isBefore(excludeIfRecordedOnOrAfter));
        }
        return shapes.sorted(ORDER)
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    /**
     * 备注规整：{@code null}/纯空白 → 空串；否则去首尾空白（需求 2.2 术语「note规整」）。
     * 形态标识的一部分：仅首尾空白差异的两条备注视为同一形态。
     */
    static String normalizeNote(String note) {
        return note == null ? "" : note.strip();
    }

    /**
     * 金额规整：以 {@link BigDecimal#stripTrailingZeros()} 去除尾随零，使 {@code 35} 与 {@code 35.00}
     * 归一为同一值参与形态 equals/hashCode（需求 2.2）。{@code null} 原样返回。
     */
    private static BigDecimal normalizeAmount(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros();
    }

    /**
     * 形态标识：五元组 {@code (type, categoryId, accountId, amount规整, note规整)}。
     *
     * <p>record 自动生成的 equals/hashCode 对 {@code amount} 走 {@link BigDecimal#equals(Object)}——
     * 因 {@code amount} 已 {@code stripTrailingZeros} 归一，标度不同的同额（{@code 35} vs {@code 35.00}）
     * 归一后 unscaled/scale 一致，故判等成立（需求 2.2）。</p>
     */
    private record ShapeKey(TransactionType type, Long categoryId, Long accountId, BigDecimal amount, String note) {
    }

    /**
     * 单个形态的聚合器：累加出现次数并维护代表流水。
     *
     * <p>代表流水取组内 {@code occurredAt} 最大者，并列时取 {@code id} 最大者（确定性，需求 2.3）。
     * 与全排序的次级/末级键口径一致，保证「代表选取」与「候选排序」用的是同一近因与决胜键。</p>
     */
    private static final class Agg {

        private int frequency;
        private SuggestionRow rep;

        void accept(SuggestionRow r) {
            frequency++;
            if (rep == null || isBetterRep(r, rep)) {
                rep = r;
            }
        }

        RankedShape toRanked() {
            return new RankedShape(
                    rep.getType(),
                    rep.getAmount(),
                    rep.getCategoryId(),
                    rep.getAccountId(),
                    normalizeNote(rep.getNote()),
                    frequency,
                    rep.getOccurredAt(),
                    rep.getId());
        }

        /** 候选行 {@code candidate} 是否比现任代表 {@code current} 更该当代表：occurredAt 更晚，或并列时 id 更大。 */
        private static boolean isBetterRep(SuggestionRow candidate, SuggestionRow current) {
            LocalDateTime candAt = candidate.getOccurredAt();
            LocalDateTime curAt = current.getOccurredAt();
            int byTime = candAt.compareTo(curAt);
            if (byTime != 0) {
                return byTime > 0;
            }
            return Objects.compare(candidate.getId(), current.getId(), Comparator.naturalOrder()) > 0;
        }
    }
}
