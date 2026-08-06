package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.RecordSuggestionItem;
import com.damien.youyu.api.dto.RecordSuggestionResponse;
import com.damien.youyu.domain.Category;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.SuggestionRow;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 记账推荐的只读编排服务（record-suggestion 需求 1.1、2.5、6.1、6.6、7.1）。
 *
 * <p>纯只读、纯派生：按当前账本近 {@value #WINDOW_DAYS} 天（含当日）窗口拉取未删除的
 * {@code expense}/{@code income} 历史行，交由 {@link RecordSuggestionRanker#rank} 分组去重、
 * 「当天已记」排除、全序排序、截断至前 {@value #MAX_SUGGESTIONS} 条；候选不足 {@value #MIN_SUGGESTIONS}
 * 条时返回空列表（历史不足不硬猜，需求 7.1）。窗口起点/终点按 {@code Asia/Shanghai}
 * （由 {@link Clock} 注入，不依赖 JVM 默认时区）计算。</p>
 *
 * <p><strong>「当天已记」排除</strong>：某形态若在今天（{@code Asia/Shanghai}）已有一笔记录，则当天不再
 * 推荐它（用户「已经记过就别再推」的预期），次日自然恢复。排除以「今天 00:00」为边界传给排序器，在截断
 * 前生效，被排除的形态不占名额。</p>
 *
 * <p>候选达标时，批量按代表流水的 {@code categoryId} 取分类 name/icon 供展示；分类已删则
 * name/icon 留 {@code null}（前端回退）。分类/账户是否仍存在<strong>不影响</strong>候选生成——
 * 代表流水的 id 照带（需求 4.5）。两次查询皆为 {@code SELECT}，事务只读，绝不写任何表。</p>
 */
@Service
public class RecordSuggestionService {

    /** 窗口天数：含当日共 30 个自然日（需求 2.4）。 */
    static final int WINDOW_DAYS = 30;

    /** 推荐卡至多展示的候选数（需求 3.4）。 */
    static final int MAX_SUGGESTIONS = 3;

    /** 展示门槛：算得的候选不足 2 条则返回空列表（需求 7.1）。 */
    static final int MIN_SUGGESTIONS = 2;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final Clock clock;

    public RecordSuggestionService(TransactionRepository transactionRepository,
                                   CategoryRepository categoryRepository,
                                   Clock clock) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.clock = clock;
    }

    /**
     * 列出当前账本的记账推荐候选（0 至 {@value #MAX_SUGGESTIONS} 条）。
     *
     * <p>算得的候选不足 {@value #MIN_SUGGESTIONS} 条时返回空列表（不抛错，需求 2.5、6.6、7.1）。</p>
     *
     * @param ledgerId 当前账本 id（由控制器经 {@code CurrentLedger} 解析并校验归属）
     * @return 按融合分降序及其决胜次序排列的候选列表，条数为 0 或 [2, 3]
     */
    @Transactional(readOnly = true)
    public RecordSuggestionResponse list(Long ledgerId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = today.minusDays(WINDOW_DAYS - 1L).atStartOfDay();
        LocalDateTime to = today.atTime(LocalTime.MAX);
        // 「当天已记」排除边界：今天 00:00（Asia/Shanghai）。某形态当天已记过一笔即不再推荐，次日恢复。
        LocalDateTime todayStart = today.atStartOfDay();

        List<SuggestionRow> rows = transactionRepository.findSuggestionWindowRows(ledgerId, from, to);
        List<RankedShape> ranked = RecordSuggestionRanker.rank(rows, todayStart);
        if (ranked.size() < MIN_SUGGESTIONS) {
            return new RecordSuggestionResponse(List.of());
        }

        List<Long> categoryIds = ranked.stream()
                .map(RankedShape::categoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Category> categoriesById = categoryIds.isEmpty()
                ? Map.of()
                : categoryRepository.findByIdIn(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity()));

        List<RecordSuggestionItem> items = ranked.stream()
                .map(shape -> toItem(shape, categoriesById.get(shape.categoryId())))
                .toList();
        return new RecordSuggestionResponse(items);
    }

    /**
     * 把一条排序后的候选形态组装为对外候选项。分类已删（{@code category == null}）时
     * name/icon 留 {@code null}，由前端回退（需求 4.5、1.2）。{@code type} 以
     * {@link com.damien.youyu.domain.TransactionType#getCode()} 输出小写编码（expense/income）。
     */
    private RecordSuggestionItem toItem(RankedShape shape, Category category) {
        return new RecordSuggestionItem(
                shape.type().getCode(),
                shape.amount(),
                shape.categoryId(),
                shape.accountId(),
                shape.note(),
                category == null ? null : category.getName(),
                category == null ? null : category.getIcon());
    }
}
