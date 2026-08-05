package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;

import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.UserGrowthRepository;

/**
 * {@link GrowthSettlementService#lockProfileWithBudget} 的示例/边界单元测试（关联需求 1.9、9.16）。
 *
 * <p>纯粹验证「应用层墙钟预算 + 有限次退避重试」这段逻辑，故不起 Spring 上下文：仓储用 Mockito 桩、
 * 墙钟用可推进的 {@link MutableClock}。真实 MySQL 上「500ms 耗尽 → 放弃」这条分支的最终确认属于
 * 任务 1.5 的手工清单（H2 复现不出真实的 InnoDB 行锁争抢，见被测方法 Javadoc）；这里覆盖的是
 * 由注入时钟与桩仓储可确定性驱动的四条分支：</p>
 *
 * <ul>
 *   <li>首次即取到锁 → 直接返回档案行，不重试不睡眠；</li>
 *   <li>先失败后成功 → 在预算内退避重试后返回；</li>
 *   <li>退避次数用尽（{@link GrowthSettlementService#MAX_LOCK_RETRIES} 次后仍失败）→ 抛
 *       {@link GrowthLockAbandonedException}；</li>
 *   <li>墙钟预算耗尽（剩余 ≤ 0）→ 立即抛 {@link GrowthLockAbandonedException}，不再重试；</li>
 *   <li>档案行缺失（未先建档）→ 抛 {@link IllegalStateException}，与「取不到锁」区分。</li>
 * </ul>
 */
class GrowthSettlementServiceLockTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long USER = 42L;

    private UserGrowthRepository repository;
    private MutableClock clock;
    private GrowthSettlementService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserGrowthRepository.class);
        clock = new MutableClock(Instant.parse("2025-06-01T04:00:00Z"), ZONE);
        // 本测试只驱动 lockProfileWithBudget，它仅依赖 userGrowthRepository 与 clock；
        // 其余协作者与 settle 主路径无关，传 null 即可（不会被本方法触及）。
        service = new GrowthSettlementService(
                repository, null, null, null, null, null, null, null, null, null, null, null, null, clock);
    }

    /** 首次尝试即取到锁：直接返回档案行，仓储只被调用一次。 */
    @Test
    void returnsProfileOnFirstSuccessfulLock() {
        UserGrowth growth = new UserGrowth();
        when(repository.findForUpdateById(USER)).thenReturn(Optional.of(growth));

        UserGrowth result = service.lockProfileWithBudget(USER, GrowthSettlementService.LOCK_BUDGET_MILLIS);

        assertThat(result).isSameAs(growth);
        verify(repository, times(1)).findForUpdateById(USER);
        verifyNoMoreInteractions(repository);
    }

    /** 先抛两次锁失败、第三次成功：在预算内退避重试后返回，共尝试 3 次。 */
    @Test
    void retriesWithinBudgetThenSucceeds() {
        UserGrowth growth = new UserGrowth();
        when(repository.findForUpdateById(USER))
                .thenThrow(new PessimisticLockingFailureException("锁被占用"))
                .thenThrow(new PessimisticLockingFailureException("锁被占用"))
                .thenReturn(Optional.of(growth));

        UserGrowth result = service.lockProfileWithBudget(USER, GrowthSettlementService.LOCK_BUDGET_MILLIS);

        assertThat(result).isSameAs(growth);
        // 初次尝试 + 两次退避重试 = 3 次。
        verify(repository, times(3)).findForUpdateById(USER);
    }

    /**
     * 预算充裕但一直取不到锁：退避次数用尽（{@code MAX_LOCK_RETRIES} 后）抛
     * {@link GrowthLockAbandonedException}，异常带上 userId 与底层锁异常作为 cause。
     */
    @Test
    void abandonsAfterMaxRetriesWhenLockNeverAcquired() {
        PessimisticLockingFailureException lockError = new PessimisticLockingFailureException("锁一直被占用");
        when(repository.findForUpdateById(USER)).thenThrow(lockError);

        assertThatThrownBy(() ->
                service.lockProfileWithBudget(USER, GrowthSettlementService.LOCK_BUDGET_MILLIS))
                .isInstanceOf(GrowthLockAbandonedException.class)
                .hasCause(lockError)
                .satisfies(e -> assertThat(((GrowthLockAbandonedException) e).getUserId()).isEqualTo(USER));

        // 初次尝试 + MAX_LOCK_RETRIES 次退避重试。
        verify(repository, times(1 + GrowthSettlementService.MAX_LOCK_RETRIES)).findForUpdateById(USER);
    }

    /**
     * 墙钟预算耗尽：首次锁失败时时钟已越过 deadline，剩余 ≤ 0，立即放弃，不再退避重试。
     *
     * <p>用桩仓储在每次调用时把时钟推进 600ms（> 500ms 预算）来模拟「拿锁本身就耗光了预算」。</p>
     */
    @Test
    void abandonsImmediatelyWhenWallClockBudgetExhausted() {
        when(repository.findForUpdateById(USER)).thenAnswer(invocation -> {
            clock.advance(Duration.ofMillis(600));
            throw new PessimisticLockingFailureException("锁被占用且已超预算");
        });

        assertThatThrownBy(() ->
                service.lockProfileWithBudget(USER, GrowthSettlementService.LOCK_BUDGET_MILLIS))
                .isInstanceOf(GrowthLockAbandonedException.class);

        // 预算已耗尽 → 不再重试，仓储只被调用一次。
        verify(repository, times(1)).findForUpdateById(USER);
    }

    /** 档案行缺失（调用方未先 ODKU 建档）：抛 IllegalStateException，而非「放弃锁」的降级异常。 */
    @Test
    void throwsIllegalStateWhenProfileRowMissing() {
        when(repository.findForUpdateById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.lockProfileWithBudget(USER, GrowthSettlementService.LOCK_BUDGET_MILLIS))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(GrowthLockAbandonedException.class);

        verify(repository, times(1)).findForUpdateById(USER);
    }

    // ---- 可推进的时钟 ----

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
