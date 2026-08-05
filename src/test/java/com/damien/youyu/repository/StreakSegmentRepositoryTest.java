package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.damien.youyu.domain.StreakSegment;

/**
 * 历史连续区间数据层的映射与查询验证（H2，表由 Hibernate 依实体生成，Flyway 关闭）。
 *
 * <p>沿用 {@link GrowthRepositoryMappingTest} / {@link AchievementRepositoryMappingTest} 的范式
 * （{@code @DataJpaTest} + 真实 H2 + 真实仓储，无 mock）。覆盖任务 1.7 的四组口径
 * （需求 6.3、6.4、6.5、6.17、7.10、7.11）：</p>
 * <ul>
 *   <li>{@code streak_segments} 恰好 7 列、实体↔表结构一致；以自增主键保存后可读回；
 *       {@code deleteByUserId} / {@code deleteByIdIn} 在无行 / 空列表时影响行数 0 且不抛错；</li>
 *   <li>{@code aggregateRaw} 空表返回 {@code (0, 0, 0)}；造 3 段断言 {@code COUNT=3}、
 *       {@code SUM(days)}、{@code MAX(days)} 正确；</li>
 *   <li>{@code endpointsRaw}：造「当前段与最长段是同一段」「当前段短于最长段」「两段并列最长」
 *       三种数据，断言当前段取 {@code start_date} 最大者、最长段取 {@code days} 最大并列时
 *       {@code start_date} 最晚者；</li>
 *   <li>{@code findByUserIdOrderByStartDateDesc} 分页越界返回空列表、{@code getTotalElements}
 *       仍为真实总条数。</li>
 * </ul>
 *
 * <p>段行写入刻意走 {@link TestEntityManager#persist}，不用继承来的 {@code save}：该仓储的契约是
 * 「不提供任何单行写入方法」，段的插入 / 更新只能走 {@code StreakSegmentMaintainer} 的 ODKU 批量
 * 语句。测试里用 {@code save} 会给后来者一个「这条路是通的」的错误示范。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StreakSegmentRepositoryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private StreakSegmentRepository repository;

    /** 让后续读取一定回库，避免持久化上下文里的旧实体掩盖实际的映射与写入效果。 */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @SuppressWarnings("unchecked")
    private List<String> columnNamesOf(String table) {
        return em.getEntityManager()
                .createNativeQuery("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = ?1 ORDER BY COLUMN_NAME")
                .setParameter(1, table)
                .getResultList();
    }

    /** 以 {@link TestEntityManager#persist} 落一行段（自增主键由库分配），返回其 id。 */
    private Long persistSegment(long userId, LocalDate start, LocalDate end) {
        StreakSegment s = new StreakSegment();
        s.setUserId(userId);
        s.setStartDate(start);
        s.setEndDate(end);
        s.setDays((int) (end.toEpochDay() - start.toEpochDay() + 1L));
        s.setCreatedAt(BASE);
        s.setUpdatedAt(BASE);
        em.persist(s);
        return s.getId();
    }

    /** endpointsRaw / native 查询里日期列的稳健回读（H2 native 通常回 {@code java.sql.Date}）。 */
    private static LocalDate toLocalDate(Object raw) {
        if (raw instanceof LocalDate ld) {
            return ld;
        }
        if (raw instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (raw instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        return LocalDate.parse(raw.toString());
    }

    // ---- 实体↔表结构一致、自增主键读回、无行删除（需求 6.3、6.5） ----

    @Test
    void streakSegmentsTableHasExactlySevenMappedColumns() {
        assertThat(columnNamesOf("STREAK_SEGMENTS")).containsExactlyInAnyOrder(
                "ID", "USER_ID", "START_DATE", "END_DATE", "DAYS", "CREATED_AT", "UPDATED_AT");
    }

    @Test
    void streakSegmentPersistsWithGeneratedPkAndRoundTripsAllColumns() {
        StreakSegment s = new StreakSegment();
        s.setUserId(7001L);
        s.setStartDate(LocalDate.of(2025, 6, 1));
        s.setEndDate(LocalDate.of(2025, 6, 10));
        s.setDays(10);
        s.setCreatedAt(BASE);
        s.setUpdatedAt(BASE.plusMinutes(5));
        em.persist(s);
        flushAndClear();

        Long generatedId = s.getId();
        assertThat(generatedId).isNotNull();

        StreakSegment reloaded = repository.findById(generatedId).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(generatedId);
        assertThat(reloaded.getUserId()).isEqualTo(7001L);
        assertThat(reloaded.getStartDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(reloaded.getEndDate()).isEqualTo(LocalDate.of(2025, 6, 10));
        assertThat(reloaded.getDays()).isEqualTo(10);
        assertThat(reloaded.getCreatedAt()).isEqualTo(BASE);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(BASE.plusMinutes(5));

        // 主键是数据库分配的自增代理键（AUTO_INCREMENT），非应用赋值
        Object rawId = em.getEntityManager()
                .createNativeQuery("SELECT id FROM streak_segments WHERE user_id = ?1")
                .setParameter(1, 7001L)
                .getSingleResult();
        assertThat(((Number) rawId).longValue()).isEqualTo(generatedId);
    }

    @Test
    void deleteByUserIdAffectsZeroRowsWhenNoRowsAndDoesNotThrow() {
        assertThatCode(() -> assertThat(repository.deleteByUserId(123456L)).isZero())
                .doesNotThrowAnyException();
    }

    @Test
    void deleteByIdInAffectsZeroRowsWhenEmptyListAndDoesNotThrow() {
        assertThatCode(() -> assertThat(repository.deleteByIdIn(List.of())).isZero())
                .doesNotThrowAnyException();
    }

    @Test
    void deleteByUserIdRemovesOnlyTargetUsersRows() {
        persistSegment(7101L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 5));
        persistSegment(7101L, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 3));
        persistSegment(7102L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));
        flushAndClear();

        assertThat(repository.deleteByUserId(7101L)).isEqualTo(2);
        flushAndClear();

        assertThat(repository.findByUserIdOrderByStartDateAsc(7101L)).isEmpty();
        assertThat(repository.findByUserIdOrderByStartDateAsc(7102L)).hasSize(1);
    }

    @Test
    void deleteByIdInRemovesOnlyGivenIds() {
        Long a = persistSegment(7201L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 5));
        Long b = persistSegment(7201L, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 3));
        Long c = persistSegment(7201L, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 2));
        flushAndClear();

        assertThat(repository.deleteByIdIn(List.of(a, c))).isEqualTo(2);
        flushAndClear();

        assertThat(repository.findByUserIdOrderByStartDateAsc(7201L))
                .extracting(StreakSegment::getId)
                .containsExactly(b);
    }

    // ---- aggregateRaw：空表 (0,0,0)；三段的 COUNT / SUM / MAX（需求 7.10、7.11） ----

    @Test
    void aggregateRawReturnsZeroTripleWhenNoRows() {
        Object[] agg = aggregateColumns(repository.aggregateRaw(8001L));
        assertThat(((Number) agg[0]).longValue()).isZero();   // COUNT
        assertThat(((Number) agg[1]).longValue()).isZero();   // COALESCE(SUM(days), 0)
        assertThat(((Number) agg[2]).intValue()).isZero();    // COALESCE(MAX(days), 0)
    }

    @Test
    void aggregateRawCountsSumsAndMaxesAcrossThreeSegments() {
        // 三段：5 天、3 天、10 天 ⇒ COUNT=3、SUM=18、MAX=10
        persistSegment(8002L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 5));   // 5
        persistSegment(8002L, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 3));   // 3
        persistSegment(8002L, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 10));  // 10
        // 另一用户的段不进入聚合
        persistSegment(8003L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 20));  // 20
        flushAndClear();

        Object[] agg = aggregateColumns(repository.aggregateRaw(8002L));
        assertThat(((Number) agg[0]).longValue()).isEqualTo(3L);
        assertThat(((Number) agg[1]).longValue()).isEqualTo(18L);
        assertThat(((Number) agg[2]).intValue()).isEqualTo(10);
    }

    // ---- endpointsRaw：当前段（start_date 最大者）与最长段（days 最大、并列取 start_date 最晚） ----

    @Test
    void endpointsRawReturnsEmptyWhenNoRows() {
        assertThat(repository.endpointsRaw(9000L)).isEmpty();
    }

    @Test
    void endpointsRawCurrentSegmentIsAlsoTheLongest() {
        // 最后一段同时是最长段：kind=0 与 kind=1 指向同一段
        persistSegment(9001L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 3));    // 3 天
        persistSegment(9001L, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 10));   // 10 天，最新且最长
        flushAndClear();

        List<Object[]> rows = repository.endpointsRaw(9001L);
        Object[] current = rowOfKind(rows, 0);
        Object[] longest = rowOfKind(rows, 1);

        // 当前段 = start_date 最大者
        assertThat(toLocalDate(current[1])).isEqualTo(LocalDate.of(2025, 5, 1));
        assertThat(toLocalDate(current[2])).isEqualTo(LocalDate.of(2025, 5, 10));
        assertThat(((Number) current[3]).intValue()).isEqualTo(10);
        // 最长段 = 同一段
        assertThat(toLocalDate(longest[1])).isEqualTo(LocalDate.of(2025, 5, 1));
        assertThat(toLocalDate(longest[2])).isEqualTo(LocalDate.of(2025, 5, 10));
        assertThat(((Number) longest[3]).intValue()).isEqualTo(10);
    }

    @Test
    void endpointsRawCurrentSegmentShorterThanLongest() {
        // 最长段在中间，当前段（最新起始日）更短
        persistSegment(9002L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 30));   // 30 天，最长
        persistSegment(9002L, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 3));    // 3 天，最新
        flushAndClear();

        List<Object[]> rows = repository.endpointsRaw(9002L);
        Object[] current = rowOfKind(rows, 0);
        Object[] longest = rowOfKind(rows, 1);

        // 当前段 = start_date 最大者（较短的那段）
        assertThat(toLocalDate(current[1])).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(((Number) current[3]).intValue()).isEqualTo(3);
        // 最长段 = days 最大者（较早的那段）
        assertThat(toLocalDate(longest[1])).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(toLocalDate(longest[2])).isEqualTo(LocalDate.of(2025, 1, 30));
        assertThat(((Number) longest[3]).intValue()).isEqualTo(30);
    }

    @Test
    void endpointsRawTwoTiedLongestBreaksTieByLatestStartDate() {
        // 两段并列最长（都是 7 天）：最长段应取 start_date 更晚的那一段
        persistSegment(9003L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 7));    // 7 天，较早
        persistSegment(9003L, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 7));    // 7 天，较晚 ⇒ 最长段
        persistSegment(9003L, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 2));    // 2 天，最新 ⇒ 当前段
        flushAndClear();

        List<Object[]> rows = repository.endpointsRaw(9003L);
        Object[] current = rowOfKind(rows, 0);
        Object[] longest = rowOfKind(rows, 1);

        // 当前段 = start_date 最大者
        assertThat(toLocalDate(current[1])).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(((Number) current[3]).intValue()).isEqualTo(2);
        // 最长段并列取起始日最晚者（2025-03-01 而非 2025-01-01）
        assertThat(toLocalDate(longest[1])).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(toLocalDate(longest[2])).isEqualTo(LocalDate.of(2025, 3, 7));
        assertThat(((Number) longest[3]).intValue()).isEqualTo(7);
    }

    /**
     * {@code aggregateRaw} 声明返回 {@code Object[]}，而 Spring Data 对「单行多列」聚合查询会把那一行
     * 的列数组再包一层（即返回值 {@code agg[0]} 本身是 {@code [COUNT, SUM, MAX]} 列数组）。这里把它
     * 拆平成列数组，服务层的 {@code StreakAggregate} 包装同样要处理这层嵌套。
     */
    private static Object[] aggregateColumns(Object[] agg) {
        if (agg.length == 1 && agg[0] instanceof Object[] inner) {
            return inner;
        }
        return agg;
    }

    private static Object[] rowOfKind(List<Object[]> rows, int kind) {
        return rows.stream()
                .filter(r -> ((Number) r[0]).intValue() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("endpointsRaw 缺少 kind=" + kind + " 的行"));
    }

    // ---- findByUserIdOrderByStartDateDesc：越界空列表 + 真实总条数（需求 6.17） ----

    @Test
    void findByUserIdOrderByStartDateDescReturnsPageSortedDescending() {
        persistSegment(9101L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 3));
        persistSegment(9101L, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 5));
        persistSegment(9101L, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 2));
        flushAndClear();

        Page<StreakSegment> page = repository.findByUserIdOrderByStartDateDesc(9101L, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(3L);
        assertThat(page.getContent())
                .extracting(StreakSegment::getStartDate)
                .containsExactly(
                        LocalDate.of(2025, 3, 1),
                        LocalDate.of(2025, 2, 1),
                        LocalDate.of(2025, 1, 1));
    }

    @Test
    void findByUserIdOrderByStartDateDescOutOfRangePageReturnsEmptyContentButRealTotal() {
        persistSegment(9102L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 3));
        persistSegment(9102L, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 5));
        flushAndClear();

        // 第 5 页（size=2）远超已有 2 条数据：content 为空，但 total 仍为真实条数 2
        Page<StreakSegment> page = repository.findByUserIdOrderByStartDateDesc(9102L, PageRequest.of(5, 2));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }
}
