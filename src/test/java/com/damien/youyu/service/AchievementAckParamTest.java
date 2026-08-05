package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.damien.youyu.domain.AchievementNotice;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AchievementNoticeRepository;
import com.damien.youyu.repository.GrowthEventRepository;

/**
 * {@link AchievementQueryService#ack(Long, String)} 的入参校验单元测试（关联需求 5.12、5.13）。
 *
 * <p>入参校验是纯逻辑加一条上界查询，故不起 Spring 上下文：仓储与 {@link JdbcTemplate} 用 Mockito 桩，
 * 时钟固定。被拒的六种取值（{@code null} / {@code ""} / {@code "  "} / {@code "abc"} / {@code "1.5"} /
 * {@code "-1"}）加上界越界一种，逐个断言错误码为 {@code ACHIEVEMENT_ACK_PARAM_INVALID} 且
 * {@code field} 为 {@code lastEventId}（需求 5.12）；<b>并且此时一条写语句都不发</b>——
 * 需求 5.12 要求被拒时 {@code achievement_notices} 的行数与全部列取值不变，
 * 「先写后校验」不会有任何症状，只能靠 {@code verifyNoInteractions(jdbcTemplate)} 拦住。</p>
 *
 * <p>两条边界必须通过：无 {@code BADGE} 行的用户以 {@code "0"} 推进（上界按 0 计，需求 5.6、5.13），
 * 以及取值恰好等于上界 {@code maxId}（取值范围是<b>闭</b>区间）。此外 {@code " 12 "} 按 12 解析
 * ——客户端多带的空白不该变成一个 400。</p>
 */
class AchievementAckParamTest {

    private static final long USER = 42L;

    private GrowthSettlementService settlementService;
    private AchievementSnapshotService snapshotService;
    private GrowthEventRepository growthEventRepository;
    private AchievementNoticeRepository noticeRepository;
    private JdbcTemplate jdbcTemplate;
    private AchievementQueryService service;

    @BeforeEach
    void setUp() {
        settlementService = mock(GrowthSettlementService.class);
        snapshotService = mock(AchievementSnapshotService.class);
        growthEventRepository = mock(GrowthEventRepository.class);
        noticeRepository = mock(AchievementNoticeRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2025-06-15T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

        service = new AchievementQueryService(settlementService, snapshotService,
                new GrowthBadgeCatalog(), growthEventRepository, noticeRepository, jdbcTemplate, clock);

        // 默认：该用户最大 BADGE 事件 id 为 100，游标行尚不存在。
        when(growthEventRepository.maxBadgeEventId(USER)).thenReturn(100L);
        when(noticeRepository.findById(USER)).thenReturn(Optional.empty());
    }

    // ---- 被拒的取值（需求 5.12）----

    /** {@code null}（入参缺失）。 */
    @Test
    void nullIsRejected() {
        assertRejected(() -> service.ack(USER, null));
    }

    /** 空字符串。 */
    @Test
    void emptyStringIsRejected() {
        assertRejected(() -> service.ack(USER, ""));
    }

    /** 纯空白：{@code trim} 后为空，同样属于「为空值」。 */
    @Test
    void blankStringIsRejected() {
        assertRejected(() -> service.ack(USER, "  "));
    }

    /** 非数字：无法解析为整数。 */
    @Test
    void nonNumericStringIsRejected() {
        assertRejected(() -> service.ack(USER, "abc"));
    }

    /**
     * 小数写法：{@code "1.5"} 无法解析为整数。
     *
     * <p>刻意断言的是「被拒」而非「截断成 1」：小数入参说明客户端算错了游标，静默取整会让
     * 播报游标停在一个客户端并未播报到的位置，而漏播是不可接受的（需求 5.18 的播报语义）。</p>
     */
    @Test
    void decimalStringIsRejected() {
        assertRejected(() -> service.ack(USER, "1.5"));
    }

    /** 负数：小于 0。 */
    @Test
    void negativeValueIsRejected() {
        assertRejected(() -> service.ack(USER, "-1"));
    }

    /** 超出上界：{@code maxId + 1}（上界取该用户当前最大 {@code BADGE} 事件 id，需求 5.6）。 */
    @Test
    void valueAboveUpperBoundIsRejected() {
        assertRejected(() -> service.ack(USER, "101"));
    }

    /** 无 {@code BADGE} 行时上界按 0 计，故任何正数都越界（需求 5.13）。 */
    @Test
    void valueAboveZeroIsRejectedWhenUserHasNoBadgeEvents() {
        when(growthEventRepository.maxBadgeEventId(USER)).thenReturn(0L);

        assertRejected(() -> service.ack(USER, "1"));
    }

    // ---- 通过的取值（需求 5.13、5.6）----

    /**
     * 无 {@code BADGE} 行的用户以 {@code "0"} 推进：接受并返回游标取值 0（需求 5.13）。
     *
     * <p>上界与入参同为 0，闭区间取等号成立。这是新用户打开小程序、客户端拿着「没播报过任何东西」
     * 的初始游标来确认时的正常路径，把它判成 400 会让新用户第一次进成就页就看到报错。</p>
     */
    @Test
    void zeroIsAcceptedWhenUserHasNoBadgeEvents() {
        when(growthEventRepository.maxBadgeEventId(USER)).thenReturn(0L);
        when(noticeRepository.findById(USER)).thenReturn(Optional.of(notice(0L)));

        AchievementAckResponse response = service.ack(USER, "0");

        assertThat(response.lastNotifiedEventId()).isZero();
        verify(jdbcTemplate, times(1)).update(anyString(),
                eq(USER), eq(0L), any(), any(), eq(0L), any(), eq(0L));
    }

    /** 取值恰好等于上界：允许取值范围是闭区间，等号成立（需求 5.6）。 */
    @Test
    void valueExactlyAtUpperBoundIsAccepted() {
        when(noticeRepository.findById(USER)).thenReturn(Optional.of(notice(100L)));

        AchievementAckResponse response = service.ack(USER, "100");

        assertThat(response.lastNotifiedEventId()).isEqualTo(100L);
        verify(jdbcTemplate, times(1)).update(anyString(),
                eq(USER), eq(100L), any(), any(), eq(100L), any(), eq(100L));
    }

    /**
     * 带首尾空白的取值按数值解析：{@code " 12 "} 就是 12。
     *
     * <p>断言的不只是「不报错」，而是<b>落到写语句里的取值确实是 12</b>：三个位置的
     * {@code lastEventId} 参数（{@code VALUES}、{@code CASE WHEN}、{@code GREATEST}）逐个比对，
     * 任何一处传错都会让游标推到错误的位置。</p>
     */
    @Test
    void surroundingWhitespaceIsTrimmedAndParsedAsTheNumber() {
        when(noticeRepository.findById(USER)).thenReturn(Optional.of(notice(12L)));

        AchievementAckResponse response = service.ack(USER, " 12 ");

        assertThat(response.lastNotifiedEventId()).isEqualTo(12L);
        verify(jdbcTemplate, times(1)).update(anyString(),
                eq(USER), eq(12L), any(), any(), eq(12L), any(), eq(12L));
    }

    /** 重复确认（传入 ≤ 当前游标）返回的是<b>库里</b>的当前取值，不是入参（需求 5.8）。 */
    @Test
    void responseReflectsPersistedCursorRatherThanTheRequestParameter() {
        when(noticeRepository.findById(USER)).thenReturn(Optional.of(notice(80L)));

        AchievementAckResponse response = service.ack(USER, "12");

        assertThat(response.lastNotifiedEventId()).isEqualTo(80L);
    }

    // ---- 辅助 ----

    /**
     * 断言该次调用被拒：错误码 {@code ACHIEVEMENT_ACK_PARAM_INVALID}、{@code field} 为
     * {@code lastEventId}、HTTP 400，且<b>一条写语句都没发</b>（需求 5.12 的表快照不变）。
     */
    private void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException e = (ApiException) thrown;
                    assertThat(e.getCode()).isEqualTo("ACHIEVEMENT_ACK_PARAM_INVALID");
                    assertThat(e.getField()).isEqualTo("lastEventId");
                    assertThat(e.getStatus().value()).isEqualTo(400);
                });

        verifyNoInteractions(jdbcTemplate);
        // 入参非法时也不该触发结算——ack 是纯游标路径（需求 5.14）。
        verify(settlementService, never()).settle(any(), any());
    }

    private static AchievementNotice notice(long lastNotifiedEventId) {
        AchievementNotice notice = new AchievementNotice();
        notice.setUserId(USER);
        notice.setLastNotifiedEventId(lastNotifiedEventId);
        return notice;
    }
}
