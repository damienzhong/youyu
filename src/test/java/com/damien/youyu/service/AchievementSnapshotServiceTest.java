package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.service.AchievementSnapshotService.AchievementSnapshot;

/**
 * {@link AchievementSnapshotService#snapshot(Long)} 的示例/边界单元测试（关联需求 3.14、3.16、1.12）。
 *
 * <p>快照服务本身没有状态，全部行为由四个仓储的返回值决定，故不起 Spring 上下文：四个仓储用 Mockito
 * 桩、{@link GrowthBadgeCatalog} 用真实实例（清单是常量，替换成假清单只会让「未知编码」这类断言
 * 失去意义）。三组断言对应任务 5.5 的三条要求：</p>
 *
 * <ul>
 *   <li><b>八个口径各自取值正确</b>（需求 3.1、3.3、3.6~3.10、3.13）：其中三个基于事件键的口径
 *       必须只数经验事件行、把 {@code BADGE:} 行排除在外；无 {@code user_growth} 行时两个天数口径按 0 计；</li>
 *   <li><b>每个口径只求值一次</b>（需求 3.16）：五条读查询各 {@code times(1)}，并以
 *       {@link org.mockito.Mockito#verifyNoMoreInteractions} 锁死「没有第六条查询」——
 *       口径在调用点各自查一次是这条不变式最常见的破法，而它不会有任何运行期症状；</li>
 *   <li><b>单个聚合抛异常时该口径取 0、其余照常、不抛出</b>（需求 3.14）；</li>
 *   <li><b>未知 {@code BADGE} 行被忽略且列表仍 16 项</b>（需求 1.12）：清单是权威，库里的意外行
 *       既不能多出一项、也不能让请求失败。</li>
 * </ul>
 */
class AchievementSnapshotServiceTest {

    private static final long USER = 42L;

    private GrowthEventRepository growthEventRepository;
    private UserGrowthRepository userGrowthRepository;
    private TransactionRepository transactionRepository;
    private LedgerMemberRepository ledgerMemberRepository;
    private GrowthBadgeCatalog catalog;
    private AchievementSnapshotService service;

    @BeforeEach
    void setUp() {
        growthEventRepository = mock(GrowthEventRepository.class);
        userGrowthRepository = mock(UserGrowthRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        ledgerMemberRepository = mock(LedgerMemberRepository.class);
        catalog = new GrowthBadgeCatalog();
        service = new AchievementSnapshotService(growthEventRepository, userGrowthRepository,
                transactionRepository, ledgerMemberRepository, catalog);

        // 默认桩：零数据用户。各用例只覆盖自己关心的那几条。
        when(growthEventRepository.findEventKeysByUserId(USER)).thenReturn(List.of());
        when(growthEventRepository.findBadgeEvents(USER)).thenReturn(List.of());
        when(userGrowthRepository.findById(USER)).thenReturn(Optional.empty());
        when(transactionRepository.countValidRecordsByCreatedBy(USER)).thenReturn(0L);
        when(ledgerMemberRepository.countEditorsOfOwnedLedgers(USER)).thenReturn(0L);
        when(transactionRepository.countTravelExpenses(USER)).thenReturn(0L);
    }

    // ---- 八个口径各自取值正确（需求 3.1、3.3、3.6~3.10、3.13）----

    /**
     * 八个口径逐项取值正确；三个基于事件键的口径只数经验事件行，{@code BADGE:} 行不参与。
     *
     * <p>桩数据里刻意混入 {@code BADGE:BUDGET_MET}、{@code BADGE:SAVING_MASTER} 与
     * {@code BADGE:INVITE_1} 三行——若前缀计数写成「包含」而不是「以…开头」，或漏了
     * {@code BUDGET_MET:} 尾冒号，这三行就会被误计（需求 3.6、3.7、3.8 的反向隔离）。</p>
     */
    @Test
    void allEightMetricsAreReadCorrectly() {
        when(growthEventRepository.findEventKeysByUserId(USER)).thenReturn(List.of(
                "DAILY_RECORD:2025-06-01", "FIRST_RECORD", "STREAK_7",
                "BUDGET_MET:2025-03", "BUDGET_MET:2025-04",           // 预算达成 2 个月
                "SAVING_MONTH:2025-03", "SAVING_MONTH:2025-04", "SAVING_MONTH:2025-05", // 储蓄 3 个月
                "FIRST_INVITE",                                        // 存在型
                "BADGE:BUDGET_MET", "BADGE:SAVING_MASTER", "BADGE:INVITE_1")); // 不得计入任何口径
        when(transactionRepository.countValidRecordsByCreatedBy(USER)).thenReturn(1234L);
        when(userGrowthRepository.findById(USER)).thenReturn(Optional.of(profile(45, 200)));
        when(ledgerMemberRepository.countEditorsOfOwnedLedgers(USER)).thenReturn(2L);
        when(transactionRepository.countTravelExpenses(USER)).thenReturn(7L);

        GrowthFacts facts = service.snapshot(USER).facts();

        assertThat(facts.recordCount()).isEqualTo(1234L);
        assertThat(facts.maxStreakDays()).isEqualTo(45);
        assertThat(facts.totalRecordDays()).isEqualTo(200);
        assertThat(facts.budgetMetCount()).as("BADGE:BUDGET_MET 不计入").isEqualTo(2L);
        assertThat(facts.savingMonthCount()).as("BADGE:SAVING_MASTER 不计入").isEqualTo(3L);
        assertThat(facts.firstInviteEvent()).isTrue();
        assertThat(facts.collabMemberCount()).isEqualTo(2L);
        assertThat(facts.travelRecordCount()).isEqualTo(7L);
    }

    /** 零数据用户：八个口径全为 0 / false，16 项全未解锁，且不报错（需求 3.13）。 */
    @Test
    void newUserWithoutAnyDataYieldsAllZeroFacts() {
        AchievementSnapshot snapshot = service.snapshot(USER);

        assertThat(snapshot.facts()).isEqualTo(GrowthFacts.EMPTY);
        assertThat(snapshot.unlockedByCode()).isEmpty();
        assertThat(projectedCodes(snapshot)).hasSize(16);
    }

    /**
     * 无 {@code user_growth} 行时两个天数口径按 0 计，其余口径照常（需求 3.13）。
     *
     * <p>「结算失败但成就清单照常返回」的间隙态就长这样：交易已经落库、档案还没建。</p>
     */
    @Test
    void missingGrowthProfileYieldsZeroDayMetricsWithoutAffectingOthers() {
        when(userGrowthRepository.findById(USER)).thenReturn(Optional.empty());
        when(transactionRepository.countValidRecordsByCreatedBy(USER)).thenReturn(11L);

        GrowthFacts facts = service.snapshot(USER).facts();

        assertThat(facts.maxStreakDays()).isZero();
        assertThat(facts.totalRecordDays()).isZero();
        assertThat(facts.recordCount()).isEqualTo(11L);
    }

    /** 已解锁映射：key 是去掉 {@code BADGE:} 前缀后的编码，value 承载解锁时刻与事件 id（需求 6.3、5.4）。 */
    @Test
    void unlockedMapKeysAreCodesWithoutPrefixAndCarryEventRow() {
        LocalDateTime unlockedAt = LocalDateTime.of(2025, 6, 1, 10, 30, 0);
        when(growthEventRepository.findBadgeEvents(USER)).thenReturn(List.of(
                badgeEvent(101L, "BADGE:FIRST_RECORD", unlockedAt),
                badgeEvent(102L, "BADGE:RECORD_10", unlockedAt.plusDays(1))));

        AchievementSnapshot snapshot = service.snapshot(USER);

        assertThat(snapshot.unlockedByCode()).containsOnlyKeys("FIRST_RECORD", "RECORD_10");
        assertThat(snapshot.unlocked("FIRST_RECORD")).isTrue();
        assertThat(snapshot.unlocked("STREAK_7")).isFalse();
        assertThat(snapshot.eventOf("FIRST_RECORD").getId()).isEqualTo(101L);
        assertThat(snapshot.eventOf("FIRST_RECORD").getCreatedAt()).isEqualTo(unlockedAt);
        assertThat(snapshot.eventOf("STREAK_7")).isNull();
    }

    // ---- 每个口径只求值一次（需求 3.16）----

    /**
     * 单次 {@code snapshot} 内五条读查询各恰好 1 次，且没有第六条查询。
     *
     * <p>{@code findEventKeysByUserId} 一条喂三个口径，因此「八个口径」并不等于「八条查询」；
     * 这里断言的是<b>每个口径只求值一次</b>（需求 3.16）——同一口径查两次会让同一次请求里两枚成就
     * 读到不同时刻的取值，而这种漂移只在解锁那一刻才暴露。{@code verifyNoMoreInteractions} 是这条
     * 断言的关键一半：只数 {@code times(1)} 拦不住「又多加了一条查询」。</p>
     */
    @Test
    void eachMetricIsEvaluatedExactlyOncePerSnapshot() {
        when(growthEventRepository.findEventKeysByUserId(USER)).thenReturn(List.of(
                "BUDGET_MET:2025-03", "SAVING_MONTH:2025-03", "FIRST_INVITE"));
        when(userGrowthRepository.findById(USER)).thenReturn(Optional.of(profile(9, 30)));

        service.snapshot(USER);

        // 三个基于事件键的口径共用这一条（需求 3.6、3.7、3.8）。
        verify(growthEventRepository, times(1)).findEventKeysByUserId(USER);
        verify(transactionRepository, times(1)).countValidRecordsByCreatedBy(USER);
        // MAX_STREAK 与 TOTAL_DAYS 共用 user_growth 单行，不读 transactions 重算（需求 3.2）。
        verify(userGrowthRepository, times(1)).findById(USER);
        verify(ledgerMemberRepository, times(1)).countEditorsOfOwnedLedgers(USER);
        verify(transactionRepository, times(1)).countTravelExpenses(USER);
        verify(growthEventRepository, times(1)).findBadgeEvents(USER);

        verifyNoMoreInteractions(growthEventRepository, userGrowthRepository,
                transactionRepository, ledgerMemberRepository);
    }

    /** 连续两次 {@code snapshot} 各自只求值一次：口径不在服务内被缓存，也不被重复查询。 */
    @Test
    void twoSnapshotsEvaluateEachMetricOncePerCall() {
        service.snapshot(USER);
        service.snapshot(USER);

        verify(growthEventRepository, times(2)).findEventKeysByUserId(USER);
        verify(transactionRepository, times(2)).countValidRecordsByCreatedBy(USER);
        verify(userGrowthRepository, times(2)).findById(USER);
        verify(ledgerMemberRepository, times(2)).countEditorsOfOwnedLedgers(USER);
        verify(transactionRepository, times(2)).countTravelExpenses(USER);
        verify(growthEventRepository, times(2)).findBadgeEvents(USER);
        verifyNoMoreInteractions(growthEventRepository, userGrowthRepository,
                transactionRepository, ledgerMemberRepository);
    }

    // ---- 单个聚合抛异常时该口径取 0、其余照常、不抛出（需求 3.14）----

    /** 旅行聚合抛异常：{@code TRAVEL_RECORD_COUNT} 取 0，其余七个口径照常返回，不向上抛。 */
    @Test
    void failingTravelAggregateYieldsZeroForThatMetricOnly() {
        givenAllMetricsNonZero();
        when(transactionRepository.countTravelExpenses(USER))
                .thenThrow(new RuntimeException("travel aggregate down"));

        GrowthFacts facts = service.snapshot(USER).facts();

        assertThat(facts.travelRecordCount()).isZero();
        assertThat(facts.recordCount()).isEqualTo(1234L);
        assertThat(facts.maxStreakDays()).isEqualTo(45);
        assertThat(facts.totalRecordDays()).isEqualTo(200);
        assertThat(facts.budgetMetCount()).isEqualTo(2L);
        assertThat(facts.savingMonthCount()).isEqualTo(3L);
        assertThat(facts.firstInviteEvent()).isTrue();
        assertThat(facts.collabMemberCount()).isEqualTo(2L);
    }

    /** 协作成员聚合抛异常：只有 {@code COLLAB_MEMBER_COUNT} 取 0，两条聚合各自独立降级、互不牵连。 */
    @Test
    void failingCollabAggregateYieldsZeroForThatMetricOnly() {
        givenAllMetricsNonZero();
        when(ledgerMemberRepository.countEditorsOfOwnedLedgers(USER))
                .thenThrow(new RuntimeException("member aggregate down"));

        GrowthFacts facts = service.snapshot(USER).facts();

        assertThat(facts.collabMemberCount()).isZero();
        assertThat(facts.travelRecordCount()).isEqualTo(7L);
        assertThat(facts.recordCount()).isEqualTo(1234L);
    }

    /** 记账笔数聚合抛异常：只有 {@code RECORD_COUNT} 取 0。 */
    @Test
    void failingRecordCountAggregateYieldsZeroForThatMetricOnly() {
        givenAllMetricsNonZero();
        when(transactionRepository.countValidRecordsByCreatedBy(USER))
                .thenThrow(new RuntimeException("count down"));

        GrowthFacts facts = service.snapshot(USER).facts();

        assertThat(facts.recordCount()).isZero();
        assertThat(facts.travelRecordCount()).isEqualTo(7L);
        assertThat(facts.maxStreakDays()).isEqualTo(45);
    }

    /** {@code user_growth} 查询抛异常：两个天数口径同时取 0（一条查询喂两个口径），其余照常。 */
    @Test
    void failingProfileReadYieldsZeroForBothDayMetrics() {
        givenAllMetricsNonZero();
        when(userGrowthRepository.findById(USER)).thenThrow(new RuntimeException("profile down"));

        GrowthFacts facts = service.snapshot(USER).facts();

        assertThat(facts.maxStreakDays()).isZero();
        assertThat(facts.totalRecordDays()).isZero();
        assertThat(facts.recordCount()).isEqualTo(1234L);
        assertThat(facts.collabMemberCount()).isEqualTo(2L);
    }

    /** 事件键查询抛异常：三个基于事件键的口径同时取 0，其余五个照常。 */
    @Test
    void failingEventKeyReadYieldsZeroForAllThreeKeyBasedMetrics() {
        givenAllMetricsNonZero();
        when(growthEventRepository.findEventKeysByUserId(USER))
                .thenThrow(new RuntimeException("event keys down"));

        GrowthFacts facts = service.snapshot(USER).facts();

        assertThat(facts.budgetMetCount()).isZero();
        assertThat(facts.savingMonthCount()).isZero();
        assertThat(facts.firstInviteEvent()).isFalse();
        assertThat(facts.recordCount()).isEqualTo(1234L);
        assertThat(facts.travelRecordCount()).isEqualTo(7L);
    }

    /** 全部五条查询同时抛异常：八个口径全取 0、16 项照常投影、依然不抛出（需求 3.14、6.7）。 */
    @Test
    void everyFailingQueryDegradesToZeroAndNothingIsRethrown() {
        when(growthEventRepository.findEventKeysByUserId(USER)).thenThrow(new RuntimeException("a"));
        when(growthEventRepository.findBadgeEvents(USER)).thenThrow(new RuntimeException("b"));
        when(userGrowthRepository.findById(USER)).thenThrow(new RuntimeException("c"));
        when(transactionRepository.countValidRecordsByCreatedBy(USER)).thenThrow(new RuntimeException("d"));
        when(transactionRepository.countTravelExpenses(USER)).thenThrow(new RuntimeException("e"));
        when(ledgerMemberRepository.countEditorsOfOwnedLedgers(USER)).thenThrow(new RuntimeException("f"));

        assertThatCode(() -> service.snapshot(USER)).doesNotThrowAnyException();

        AchievementSnapshot snapshot = service.snapshot(USER);
        assertThat(snapshot.facts()).isEqualTo(GrowthFacts.EMPTY);
        assertThat(snapshot.unlockedByCode()).isEmpty();
        assertThat(projectedCodes(snapshot)).hasSize(16);
    }

    // ---- 未知 BADGE 行被忽略且列表仍 16 项（需求 1.12）----

    /**
     * 库里的未知编码行被忽略：不进已解锁映射、不多出一项、不报错。
     *
     * <p>三类意外行各造一条：清单里没有的编码、缺少 {@code BADGE:} 前缀的畸形键、{@code null} 键。
     * 已解锁映射只保留清单内的 {@code FIRST_RECORD}，投影出的项数仍恒为清单项数 16
     * （成就墙不会因为库里的脏行而多出一格）。</p>
     */
    @Test
    void unknownBadgeRowsAreIgnoredAndProjectionStillHasSixteenItems() {
        when(growthEventRepository.findBadgeEvents(USER)).thenReturn(List.of(
                badgeEvent(1L, "BADGE:FIRST_RECORD", LocalDateTime.of(2025, 1, 1, 0, 0)),
                badgeEvent(2L, "BADGE:LEGACY_REMOVED", LocalDateTime.of(2025, 1, 2, 0, 0)),
                badgeEvent(3L, "FIRST_RECORD", LocalDateTime.of(2025, 1, 3, 0, 0)),
                badgeEvent(4L, null, LocalDateTime.of(2025, 1, 4, 0, 0))));

        AchievementSnapshot snapshot = service.snapshot(USER);

        assertThat(snapshot.unlockedByCode()).containsOnlyKeys("FIRST_RECORD");
        assertThat(snapshot.unlocked("LEGACY_REMOVED")).isFalse();
        assertThat(projectedCodes(snapshot)).hasSize(16);
        assertThat(projectedCodes(snapshot)).doesNotContain("LEGACY_REMOVED");
    }

    /** 全部 {@code BADGE} 行都是未知编码：已解锁映射为空，16 项全未解锁，不报错。 */
    @Test
    void catalogIsAuthoritativeWhenEveryBadgeRowIsUnknown() {
        when(growthEventRepository.findBadgeEvents(USER)).thenReturn(List.of(
                badgeEvent(1L, "BADGE:GONE_1", LocalDateTime.of(2025, 1, 1, 0, 0)),
                badgeEvent(2L, "BADGE:", LocalDateTime.of(2025, 1, 2, 0, 0))));

        AchievementSnapshot snapshot = service.snapshot(USER);

        assertThat(snapshot.unlockedByCode()).isEmpty();
        assertThat(projectedCodes(snapshot)).hasSize(16);
    }

    // ---- 辅助 ----

    /** 八个口径全取非零，供各降级用例只改动其中一条查询、断言其余口径不受牵连。 */
    private void givenAllMetricsNonZero() {
        when(growthEventRepository.findEventKeysByUserId(USER)).thenReturn(List.of(
                "BUDGET_MET:2025-03", "BUDGET_MET:2025-04",
                "SAVING_MONTH:2025-03", "SAVING_MONTH:2025-04", "SAVING_MONTH:2025-05",
                "FIRST_INVITE"));
        when(transactionRepository.countValidRecordsByCreatedBy(USER)).thenReturn(1234L);
        when(userGrowthRepository.findById(USER)).thenReturn(Optional.of(profile(45, 200)));
        when(ledgerMemberRepository.countEditorsOfOwnedLedgers(USER)).thenReturn(2L);
        when(transactionRepository.countTravelExpenses(USER)).thenReturn(7L);
    }

    /**
     * 按生产路径（{@code AchievementQueryService} 第 ③ 步）把快照投影到清单上，返回项的编码序列。
     *
     * <p>项数恒等于清单项数（16）这条不变式，只有把快照真的投影一遍才测得到——直接断言
     * {@code catalog.badges().size()} 只是在测清单常量。</p>
     */
    private List<String> projectedCodes(AchievementSnapshot snapshot) {
        List<String> codes = new ArrayList<>();
        for (BadgeDef def : catalog.badges()) {
            boolean unlocked = snapshot.unlocked(def.code());
            // 投影时同时走一遍当前值钳制，确保降级取值不会算出越界的 current。
            int current = catalog.currentOf(def, snapshot.facts(), unlocked);
            assertThat(current).isBetween(0, def.target());
            codes.add(def.code());
        }
        return codes;
    }

    private static UserGrowth profile(int maxStreakDays, int totalRecordDays) {
        UserGrowth growth = new UserGrowth();
        growth.setUserId(USER);
        growth.setMaxStreakDays(maxStreakDays);
        growth.setTotalRecordDays(totalRecordDays);
        return growth;
    }

    private static GrowthEvent badgeEvent(long id, String eventKey, LocalDateTime createdAt) {
        GrowthEvent event = new GrowthEvent();
        event.setId(id);
        event.setUserId(USER);
        event.setEventType(GrowthEventType.BADGE);
        event.setEventKey(eventKey);
        event.setExpAmount(0);
        event.setCreatedAt(createdAt);
        return event;
    }
}
