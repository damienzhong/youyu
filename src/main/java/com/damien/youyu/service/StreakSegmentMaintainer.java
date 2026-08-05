package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.damien.youyu.domain.StreakSegment;
import com.damien.youyu.repository.StreakSegmentRepository;

/**
 * 段维护：一次结算内「以记账日历全量重算段序列、与已持久化的段比对、只写差异行」的过程
 * （需求 4.4、4.6、4.7、4.8、4.10～4.16；5.1、5.2、5.9；7.7、7.13）。
 *
 * <p><b>段维护只有一条代码路径，没有「增量路径」与「修复路径」之分：</b></p>
 * <pre>
 *   读全量已持久化段  →  用本次已加载的日历重算应有段序列  →  逐项 diff  →  只写差异行
 * </pre>
 * <p>这样做的收益是三条不变式<b>构造性成立</b>，而不是靠测试凑巧对上：增量结果 == 全量结果
 * （需求 4.9）；无变化即无写入（需求 4.8、4.11）；存量用户惰性建立 + 脏数据自愈（需求 8.10、4.17）都退化为
 * 「diff 非空 ⇒ 补齐」。稳态下 diff 是 0～2 行（尾段延长 / 另起新段），首次结算时 diff 等于该用户的全部段。</p>
 *
 * <h2>两条不可动的禁令</h2>
 *
 * <ol>
 *   <li><b>本方法刻意不 catch 任何异常。</b>它运行在 {@link GrowthSettlementService} 的
 *       {@code @Transactional(REQUIRES_NEW)} 事务内，异常必须<b>穿出</b>才能让该独立事务回滚
 *       （需求 7.3、4.16）——只有异常穿出被通知方法时，Spring 事务切面才会回滚。若在此处 {@code catch}
 *       掉数据库异常并正常返回，Spring 会照常提交，可能产生部分写入，破坏「段维护失败不产生部分写入」。
 *       因此「吞异常只记 WARN」只能发生在事务边界<b>之外</b>（{@code GrowthSettlementTrigger} 与两个
 *       {@code QueryService}）。本方法内出现任何 {@code catch} 都是缺陷。</li>
 *   <li><b>upsert 绝不改成 {@code INSERT IGNORE}。</b>见 {@link #UPSERT_SQL} 的说明——{@code INSERT IGNORE}
 *       会把 CHECK 违例、非空违例一并静默降级为警告让脏数据落库（沿用 {@link GrowthSettlementService}
 *       批量插入的同一条立场）。</li>
 * </ol>
 *
 * <p><b>时钟统一</b>：一律用注入的 {@link Clock}（{@code TimeConfig} 提供，固定 {@code Asia/Shanghai}）读
 * 墙钟耗时，不用 {@code System.currentTimeMillis()}。事件的 {@code created_at} / {@code updated_at} 一律用
 * 调用方传入的、本次结算唯一的那一次时钟读数 {@code now}，不在此处二次读时钟。</p>
 *
 * <p>Feature: streak-system。覆盖需求 4.4、4.6、4.7、4.8、4.10、4.11、4.13、4.14、4.15、4.16、5.1、5.2、7.7、7.13。</p>
 */
@Component
public class StreakSegmentMaintainer {

    private static final Logger log = LoggerFactory.getLogger(StreakSegmentMaintainer.class);

    /** 段维护耗时告警阈值（需求 7.7）：超过只记 WARN，不使结算失败。 */
    static final long SLOW_MAINTAIN_MILLIS = 300L;

    /** 单次维护写入行数硬上界的下限（需求 4.11）：实际上界为 {@code max(1000, 累计记账天数) + 1}。 */
    static final int MIN_WRITE_CEILING = 1000;

    /**
     * 段的插入 / 更新一律走这一条 ODKU（需求 4.13、4.14）。
     *
     * <p>唯一键 {@code (user_id, start_date)} 冲突时只更新 {@code end_date} / {@code days} /
     * {@code updated_at} 三列，{@code user_id} / {@code start_date} / {@code created_at} 三列不动
     * （需求 4.14），且不抛异常。<b>绝不改成 {@code INSERT IGNORE}</b>：那会把 CHECK 违例、非空违例
     * 一并静默降级为警告（见类级 Javadoc 第二条禁令）。</p>
     *
     * <p>占位符顺序：{@code user_id, start_date, end_date, days, created_at, updated_at}。</p>
     */
    private static final String UPSERT_SQL =
            "INSERT INTO streak_segments (user_id, start_date, end_date, days, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE end_date = VALUES(end_date), days = VALUES(days), "
                    + "updated_at = VALUES(updated_at)";

    private final StreakSegmentRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public StreakSegmentMaintainer(StreakSegmentRepository repository,
                                   JdbcTemplate jdbcTemplate,
                                   Clock clock) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * 一次结算内的段维护：全量对账（需求 4.4）。
     *
     * <p>流程：① {@link GrowthCalendarService#segments(List)} 得应有段序列（纯函数，与 {@code scan}
     * 共用同一判定规则，需求 4.5）→ ② {@link StreakSegmentRepository#findByUserIdOrderByStartDateAsc}
     * 读已持久化段（这是「已知偏差①」的那 1 条读查询）→ ③ 以 {@code start_date} 为键逐项 diff，
     * {@code have == null || end_date 不同 || days 不同} 则加入 upsert 批 → ④ 已持久化中起始日不在重算结果里
     * 的段收进 {@code orphanIds}（数据修复路径删除，需求 4.15）→ ⑤ 有界性断言（写入行数越界抛
     * {@link IllegalStateException}，需求 4.11）→ ⑥ 空 diff 即零 SQL（需求 4.8、4.10、5.2）；
     * {@code orphanIds} 非空先记 {@code [STREAK_SEGMENT_REPAIRED]} WARN 再删；upsert 批走
     * {@link JdbcTemplate#batchUpdate}。</p>
     *
     * <p><b>diff 的键为什么是 {@code start_date} 而不是 {@code id}</b>：段是派生数据，{@code id} 不承载
     * 任何业务语义，而 {@code start_date} 在唯一约束 {@code uk_streak_segments_user_start} 的保护下是该用户
     * 段序列的天然主键。以 {@code start_date} 为键，「延长尾段」表现为一次 UPDATE（{@code start_date} 不变、
     * {@code end_date} 与 {@code days} 变），「另起一段」表现为一次 INSERT，两者都是 ODKU 的一次调用，
     * 不需要区分。</p>
     *
     * <p><b>为什么删除分支几乎永不触发</b>：记账日历只追加、且 {@code GrowthCalendarService.backfillDates}
     * 的追补起点恒为「{@code last_record_date} 的次日」之后，新日期只会落在尾段之后——既不会在两段之间
     * 架桥把两段合成一段，也不会让某个已存在的段起始日消失。删除分支只在数据被外部改动后的修复路径上生效
     * （需求 4.15），保留它是为了让「段序列与日历互为充要」在任何脏数据下都能自愈。</p>
     *
     * <p><b>幂等性论证（需求 4.8、4.9、4.10）</b>：{@code segments(calendar)} 是纯函数，同一日历恒得同一
     * 段序列；diff 比较的是「应有值」与「已持久化值」的逐项相等，因此第二次维护在日历未变时必然得到空 diff。
     * 幂等不是靠「先查是否存在再决定写不写」这种时序判断，而是<b>值幂等</b>——即便重复执行 ODKU，写入的也是
     * 同一组值。</p>
     *
     * @param userId   段维护用户 id（等于令牌用户 id / {@code users.id}）
     * @param calendar 本次结算已加载的完整记账日历（复用结算第 ⑥ 步的入参，零额外日历查询，需求 7.2）
     * @param now      本次结算唯一的那一次时钟读数，用作新段 / 延长段的 {@code created_at} / {@code updated_at}
     */
    void maintain(Long userId, List<LocalDate> calendar, LocalDateTime now) {
        long startedAt = clock.millis();

        // ① 应有的段序列：纯函数，与 scan 共用同一判定规则（需求 4.5）。
        List<StreakSegmentView> desired = GrowthCalendarService.segments(calendar);

        // ② 已持久化的段：1 条读查询，走 uk_streak_segments_user_start（已知偏差①的那 1 条读查询）。
        List<StreakSegment> persisted = repository.findByUserIdOrderByStartDateAsc(userId);

        // ③ 逐项 diff。键是 start_date：唯一约束保证同一用户同一起始日至多一段，故它是段序列的天然主键。
        //    从 byStart 里 remove 命中项后，剩余项即「起始日不在重算结果中的段行」。
        Map<LocalDate, StreakSegment> byStart = index(persisted);
        List<Object[]> upserts = new ArrayList<>();
        for (StreakSegmentView want : desired) {
            StreakSegment have = byStart.remove(want.startDate());
            if (have == null
                    || !have.getEndDate().equals(want.endDate())
                    || have.getDays() != want.days()) {
                upserts.add(new Object[] {userId, want.startDate(), want.endDate(), want.days(), now, now});
            }
        }
        // byStart 里的剩余项 = 起始日不在重算结果中的段行 ⇒ 数据修复路径的删除（需求 4.15）。
        List<Long> orphanIds = byStart.values().stream().map(StreakSegment::getId).toList();

        // ④ 有界性断言（需求 4.11）：越界说明重算或 diff 有缺陷，宁可炸响也不静默写超量。
        int writes = upserts.size() + orphanIds.size();
        int ceiling = Math.max(MIN_WRITE_CEILING, calendar.size()) + 1;
        if (writes > ceiling) {
            throw new IllegalStateException("单次段维护写入 " + writes + " 行超过上界 " + ceiling
                    + "，userId=" + userId);
        }

        // ⑤ diff 为空即零 SQL（需求 4.8、4.10、5.2）。orphanIds 非空先记 WARN 再删。
        if (!orphanIds.isEmpty()) {
            log.warn("[STREAK_SEGMENT_REPAIRED] userId={} 删除 {} 条起始日不在重算结果中的段行",
                    userId, orphanIds.size());
            repository.deleteByIdIn(orphanIds);
        }
        if (!upserts.isEmpty()) {
            jdbcTemplate.batchUpdate(UPSERT_SQL, upserts);
        }

        long cost = clock.millis() - startedAt;
        if (cost > SLOW_MAINTAIN_MILLIS) {
            log.warn("[STREAK_MAINTAIN_SLOW] userId={} cost={}ms 超出 {}ms 预算",
                    userId, cost, SLOW_MAINTAIN_MILLIS);
        }
    }

    /**
     * 以 {@code start_date} 为键把已持久化段建成索引。
     *
     * <p>{@code (user_id, start_date)} 唯一约束保证同一用户同一起始日至多一段，因此以 {@code start_date}
     * 为键不会覆盖丢失任何行；用可变 {@link HashMap} 以便 diff 阶段边命中边 {@code remove}，
     * 剩余项即孤儿段。</p>
     */
    private static Map<LocalDate, StreakSegment> index(List<StreakSegment> persisted) {
        Map<LocalDate, StreakSegment> byStart = new HashMap<>();
        for (StreakSegment s : persisted) {
            byStart.put(s.getStartDate(), s);
        }
        return byStart;
    }
}
