package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.damien.youyu.domain.StreakSegment;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.StreakSegmentRepository;
import com.damien.youyu.repository.UserGrowthRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link StreakQueryService} 的示例/边界单元测试（关联需求 1.4、2.5、2.6、2.7、6.12、6.17、4.17）。
 *
 * <p>服务本身无状态，全部行为由五个依赖决定：结算服务、成长档案仓储、段仓储、里程碑组件与注入的
 * {@link Clock}。故不起 Spring 上下文，四个依赖用 Mockito 桩、{@code Clock} 用固定时钟
 * （判定日恒为 {@code 2025-06-15}，Asia/Shanghai）。锁住五组行为：</p>
 *
 * <ul>
 *   <li><b>无档案降级</b>（需求 1.4）：{@code profile} 为空时字段集与正常路径相同、全部为空/零、不报错；</li>
 *   <li><b>{@code broken} 三态下 {@code lastStreakDays} 的空/非空</b>（需求 2.5、2.6、2.7）：
 *       中断态取当前段投影为非空，未中断（今日/昨日）两态一律为空；</li>
 *   <li><b>时钟偏移记 WARN</b>（需求 1.12）：最近记账日晚于判定日记 {@code [STREAK_CLOCK_SKEW]}；</li>
 *   <li><b>分页参数解析的 12 条边界</b>（需求 6.12、6.17）：越界抛
 *       {@code STREAK_PAGE_PARAM_INVALID} 并置正确 {@code field}，合法值不抛且越界页码返回空列表 + 真实总条数；</li>
 *   <li><b>不变式在线校验不一致时只记 WARN、不抛出</b>（需求 4.17）。</li>
 * </ul>
 */
class StreakQueryServiceTest {

    private static final long USER = 42L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    private final Clock clock =
            Clock.fixed(TODAY.atStartOfDay(ZONE).plusHours(12).toInstant(), ZONE);

    private GrowthSettlementService settlementService;
    private UserGrowthRepository userGrowthRepository;
    private StreakSegmentRepository repository;
    private StreakMilestones milestones;
    private StreakQueryService service;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        settlementService = mock(GrowthSettlementService.class);
        userGrowthRepository = mock(UserGrowthRepository.class);
        repository = mock(StreakSegmentRepository.class);
        milestones = mock(StreakMilestones.class);
        service = new StreakQueryService(settlementService, userGrowthRepository, repository,
                milestones, clock);

        // 默认桩：里程碑不影响本测重点，一律返回「已达成全部」。各用例只覆盖自己关心的那几条。
        when(milestones.nextAfter(anyInt())).thenReturn(null);

        serviceLogger = (Logger) LoggerFactory.getLogger(StreakQueryService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
        serviceLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // ---- 无档案降级（需求 1.4）----

    /** {@code profile} 为空且日历为空：14 项字段全为空/零、{@code broken=false}、不报错、不写表。 */
    @Test
    void overviewWithoutProfileDegradesToEmptyFields() {
        when(userGrowthRepository.findById(USER)).thenReturn(Optional.empty());
        when(repository.aggregateRaw(USER)).thenReturn(new Object[]{0L, 0L, 0});
        when(repository.endpointsRaw(USER)).thenReturn(List.of());

        StreakOverviewResponse response = service.getOverview(USER);

        assertThat(response.todayDone()).isFalse();
        assertThat(response.currentStreakDays()).isZero();
        assertThat(response.broken()).isFalse();
        assertThat(response.currentSegmentStart()).isNull();
        assertThat(response.currentSegmentEnd()).isNull();
        assertThat(response.lastStreakDays()).isNull();
        assertThat(response.lastStreakEnd()).isNull();
        assertThat(response.maxStreakDays()).isZero();
        assertThat(response.longestSegmentStart()).isNull();
        assertThat(response.longestSegmentEnd()).isNull();
        assertThat(response.totalRecordDays()).isZero();
        assertThat(response.segmentCount()).isZero();
        assertThat(response.nextMilestone()).isNull();
        assertThat(response.daysToNextMilestone()).isNull();
    }

    // ---- broken 三态下 lastStreakDays 的空/非空（需求 2.5、2.6、2.7）----

    /**
     * 中断态（最近记账日在判定日前两天）：{@code lastStreakDays} / {@code lastStreakEnd}
     * 取当前段的 {@code days} / {@code end_date}（需求 2.5、2.6），不发第 4 条查询找倒数第二段。
     */
    @Test
    void brokenStateProjectsCurrentSegmentAsLastStreak() {
        LocalDate segStart = TODAY.minusDays(6);
        LocalDate segEnd = TODAY.minusDays(2);
        when(userGrowthRepository.findById(USER))
                .thenReturn(Optional.of(profile(segEnd, 0, 5, 5)));
        when(repository.aggregateRaw(USER)).thenReturn(new Object[]{1L, 5L, 5});
        when(repository.endpointsRaw(USER)).thenReturn(List.of(
                endpoint(0, segStart, segEnd, 5),
                endpoint(1, segStart, segEnd, 5)));

        StreakOverviewResponse response = service.getOverview(USER);

        assertThat(response.broken()).isTrue();
        assertThat(response.lastStreakDays()).isEqualTo(5);
        assertThat(response.lastStreakEnd()).isEqualTo(segEnd);
        assertThat(response.currentSegmentStart()).isEqualTo(segStart);
        assertThat(response.currentSegmentEnd()).isEqualTo(segEnd);
    }

    /** 未中断（今日已记账）：{@code broken=false}，{@code lastStreakDays} / {@code lastStreakEnd} 均为空（需求 2.7）。 */
    @Test
    void continuousTodayStateHasNullLastStreak() {
        LocalDate segStart = TODAY.minusDays(2);
        when(userGrowthRepository.findById(USER))
                .thenReturn(Optional.of(profile(TODAY, 3, 3, 3)));
        when(repository.aggregateRaw(USER)).thenReturn(new Object[]{1L, 3L, 3});
        when(repository.endpointsRaw(USER)).thenReturn(List.of(
                endpoint(0, segStart, TODAY, 3),
                endpoint(1, segStart, TODAY, 3)));

        StreakOverviewResponse response = service.getOverview(USER);

        assertThat(response.broken()).isFalse();
        assertThat(response.todayDone()).isTrue();
        assertThat(response.currentStreakDays()).isEqualTo(3);
        assertThat(response.lastStreakDays()).isNull();
        assertThat(response.lastStreakEnd()).isNull();
    }

    /** 未中断（昨日记账、今日未记）：仍 {@code broken=false}，{@code lastStreakDays} 为空（需求 2.7）。 */
    @Test
    void continuousYesterdayStateHasNullLastStreak() {
        LocalDate lastRecord = TODAY.minusDays(1);
        LocalDate segStart = TODAY.minusDays(3);
        when(userGrowthRepository.findById(USER))
                .thenReturn(Optional.of(profile(lastRecord, 3, 3, 3)));
        when(repository.aggregateRaw(USER)).thenReturn(new Object[]{1L, 3L, 3});
        when(repository.endpointsRaw(USER)).thenReturn(List.of(
                endpoint(0, segStart, lastRecord, 3),
                endpoint(1, segStart, lastRecord, 3)));

        StreakOverviewResponse response = service.getOverview(USER);

        assertThat(response.broken()).isFalse();
        assertThat(response.todayDone()).isFalse();
        assertThat(response.currentStreakDays()).isEqualTo(3);
        assertThat(response.lastStreakDays()).isNull();
        assertThat(response.lastStreakEnd()).isNull();
    }

    // ---- 时钟偏移记 WARN（需求 1.12）----

    /** 最近记账日晚于判定日：记一条 {@code [STREAK_CLOCK_SKEW]} WARN，且请求照常返回不报错。 */
    @Test
    void clockSkewLogsWarnAndStillReturns() {
        LocalDate future = TODAY.plusDays(1);
        // 聚合与档案物化列一致，隔离掉不变式校验的 WARN，只留时钟偏移这一条。
        when(userGrowthRepository.findById(USER))
                .thenReturn(Optional.of(profile(future, 1, 1, 1)));
        when(repository.aggregateRaw(USER)).thenReturn(new Object[]{1L, 1L, 1});
        when(repository.endpointsRaw(USER)).thenReturn(List.of(
                endpoint(0, future, future, 1),
                endpoint(1, future, future, 1)));

        StreakOverviewResponse response = service.getOverview(USER);

        assertThat(response).isNotNull();
        assertThat(warnMessages()).anyMatch(m -> m.contains("[STREAK_CLOCK_SKEW]"));
    }

    // ---- 不变式在线校验不一致时只记 WARN、不抛出（需求 4.17）----

    /** {@code sumDays != totalRecordDays}：记 {@code [STREAK_INVARIANT_VIOLATED]} WARN（不变式 3），但不抛出。 */
    @Test
    void invariantMismatchLogsWarnWithoutThrowing() {
        when(userGrowthRepository.findById(USER))
                .thenReturn(Optional.of(profile(TODAY, 3, 45, 200)));
        // 段聚合与档案物化列不一致：sumDays=199 != totalRecordDays=200。
        when(repository.aggregateRaw(USER)).thenReturn(new Object[]{3L, 199L, 45});
        when(repository.endpointsRaw(USER)).thenReturn(List.of(
                endpoint(0, TODAY.minusDays(2), TODAY, 3),
                endpoint(1, TODAY.minusDays(2), TODAY, 3)));

        assertThatCode(() -> service.getOverview(USER)).doesNotThrowAnyException();

        assertThat(warnMessages())
                .anyMatch(m -> m.contains("[STREAK_INVARIANT_VIOLATED]") && m.contains("不变式=3"));
    }

    // ---- 分页参数解析的 12 条边界（需求 6.12）----

    /** {@code page} 合法取值（含 null/空白/空白包裹/最小/最大）不抛异常。 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "100000", " 5 ", "\t7\n"})
    void validPageParamsDoNotThrow(String rawPage) {
        stubEmptyPage();
        assertThatCode(() -> service.listSegments(USER, rawPage, null)).doesNotThrowAnyException();
    }

    /** {@code page} 为 null / 空白时取缺省 0（合法，不抛）。 */
    @Test
    void nullOrBlankPageDefaultsToZero() {
        stubEmptyPage();
        assertThatCode(() -> service.listSegments(USER, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> service.listSegments(USER, "   ", null)).doesNotThrowAnyException();
    }

    /** {@code page} 越界/非数字：抛 {@code STREAK_PAGE_PARAM_INVALID} 且 {@code field=page}。 */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "100001"})
    void invalidPageParamsThrowWithPageField(String rawPage) {
        assertThatThrownBy(() -> service.listSegments(USER, rawPage, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("STREAK_PAGE_PARAM_INVALID");
                    assertThat(ex.getField()).isEqualTo("page");
                });
    }

    /** {@code size} 合法取值（最小 1、最大 50）不抛异常。 */
    @ParameterizedTest
    @ValueSource(strings = {"1", "50"})
    void validSizeParamsDoNotThrow(String rawSize) {
        stubEmptyPage();
        assertThatCode(() -> service.listSegments(USER, null, rawSize)).doesNotThrowAnyException();
    }

    /** {@code size} 越界（0 或 51）：抛 {@code STREAK_PAGE_PARAM_INVALID} 且 {@code field=size}。 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "51"})
    void invalidSizeParamsThrowWithSizeField(String rawSize) {
        assertThatThrownBy(() -> service.listSegments(USER, null, rawSize))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("STREAK_PAGE_PARAM_INVALID");
                    assertThat(ex.getField()).isEqualTo("size");
                });
    }

    // ---- 页码越界返回空列表 + 真实总条数（需求 6.17）----

    /** 页码越界：{@code items} 为空列表，{@code total} 仍为真实总条数，不报错。 */
    @Test
    void outOfRangePageReturnsEmptyItemsWithRealTotal() {
        Page<StreakSegment> emptyPageWithTotal =
                new PageImpl<>(List.of(), PageRequest.of(9999, 20), 7L);
        when(repository.findByUserIdOrderByStartDateDesc(eq(USER), any(PageRequest.class)))
                .thenReturn(emptyPageWithTotal);

        StreakSegmentPageResponse response = service.listSegments(USER, "9999", "20");

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isEqualTo(7L);
    }

    // ---- 辅助 ----

    private void stubEmptyPage() {
        when(repository.findByUserIdOrderByStartDateDesc(eq(USER), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static Object[] endpoint(int kind, LocalDate start, LocalDate end, int days) {
        return new Object[]{kind, start, end, days};
    }

    private static UserGrowth profile(LocalDate lastRecordDate, int currentStreakDays,
                                      int maxStreakDays, int totalRecordDays) {
        UserGrowth growth = new UserGrowth();
        growth.setUserId(USER);
        growth.setLastRecordDate(lastRecordDate);
        growth.setCurrentStreakDays(currentStreakDays);
        growth.setMaxStreakDays(maxStreakDays);
        growth.setTotalRecordDays(totalRecordDays);
        growth.setCreatedAt(LocalDateTime.now(ZONE));
        growth.setUpdatedAt(LocalDateTime.now(ZONE));
        return growth;
    }
}
