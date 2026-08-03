package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link InviteRateLimiter} 的示例/边界单元测试（关联需求 3.9、8.6、8.8、8.11）。
 *
 * <p>全部用例由可推进的固定时钟驱动，不依赖真实时间，因此窗口边界可精确到毫秒断言。
 * 覆盖：60 秒 / 30 次与 24 小时 / 20 次两个窗口的放行—拒绝边界、被拒绝的请求不消耗额度、
 * 时刻滑出窗口后额度恢复、键之间互不影响、两个窗口彼此独立、键数达上限时空队列被回收。</p>
 */
class InviteRateLimiterTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final String IP = "203.0.113.9";
    private static final long USER = 42L;

    private MutableClock clock;
    private InviteRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"), ZONE);
        limiter = new InviteRateLimiter(clock);
    }

    // ---- 邀请人展示信息查询：60 秒 / 30 次（需求 8.6）----

    @Test
    void inviterLookupAllowsExactlyLimitWithinWindow() {
        for (int i = 1; i <= InviteRateLimiter.INVITER_LOOKUP_LIMIT; i++) {
            assertThat(limiter.tryAcquireInviterLookup(IP))
                    .as("第 %d 次应放行", i)
                    .isTrue();
        }
        assertThat(limiter.tryAcquireInviterLookup(IP))
                .as("第 %d 次应被拒绝", InviteRateLimiter.INVITER_LOOKUP_LIMIT + 1)
                .isFalse();
    }

    /**
     * 被拒绝的请求不写入队列，因此不消耗额度（需求 8.8）。
     *
     * <p>构造：t0 放行 29 次、t0+10s 放行第 30 次，随后在 t0+10s 连续被拒 5 次。
     * 推进到 t0+60s 时只有 t0 的 29 个时刻滑出，若被拒的 5 次消耗了额度，
     * 恢复的额度就会少于 29。</p>
     */
    @Test
    void rejectedInviterLookupDoesNotConsumeQuota() {
        for (int i = 0; i < InviteRateLimiter.INVITER_LOOKUP_LIMIT - 1; i++) {
            assertThat(limiter.tryAcquireInviterLookup(IP)).isTrue();
        }
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquireInviterLookup(IP)).isTrue();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquireInviterLookup(IP)).isFalse();
        }

        clock.advance(Duration.ofSeconds(50));
        for (int i = 1; i <= InviteRateLimiter.INVITER_LOOKUP_LIMIT - 1; i++) {
            assertThat(limiter.tryAcquireInviterLookup(IP))
                    .as("滑出后恢复的第 %d 次应放行", i)
                    .isTrue();
        }
        assertThat(limiter.tryAcquireInviterLookup(IP))
                .as("t0+10s 的那次仍在窗口内，额度应恰好用尽")
                .isFalse();
    }

    /** 窗口边界取半开区间：距今恰好 60 秒的时刻已滑出，恢复满额。 */
    @Test
    void inviterLookupQuotaRestoredExactlyAtWindowEdge() {
        for (int i = 0; i < InviteRateLimiter.INVITER_LOOKUP_LIMIT; i++) {
            assertThat(limiter.tryAcquireInviterLookup(IP)).isTrue();
        }

        clock.advance(Duration.ofMillis(InviteRateLimiter.INVITER_LOOKUP_WINDOW_MILLIS - 1));
        assertThat(limiter.tryAcquireInviterLookup(IP))
                .as("距今 59999ms 仍在窗口内")
                .isFalse();

        clock.advance(Duration.ofMillis(1));
        for (int i = 1; i <= InviteRateLimiter.INVITER_LOOKUP_LIMIT; i++) {
            assertThat(limiter.tryAcquireInviterLookup(IP))
                    .as("窗口整点滑出后第 %d 次应放行", i)
                    .isTrue();
        }
        assertThat(limiter.tryAcquireInviterLookup(IP)).isFalse();
    }

    @Test
    void inviterLookupCountsPerIpKey() {
        for (int i = 0; i < InviteRateLimiter.INVITER_LOOKUP_LIMIT; i++) {
            assertThat(limiter.tryAcquireInviterLookup(IP)).isTrue();
        }
        assertThat(limiter.tryAcquireInviterLookup(IP)).isFalse();

        assertThat(limiter.tryAcquireInviterLookup("198.51.100.7"))
                .as("另一个 IP 有独立额度")
                .isTrue();
    }

    @Test
    void blankIpFallsBackToSingleSharedKey() {
        assertThat(limiter.tryAcquireInviterLookup(null)).isTrue();
        assertThat(limiter.tryAcquireInviterLookup("   ")).isTrue();
        assertThat(limiter.inviterLookupKeyCount())
                .as("null 与空白 IP 共用同一个兜底键")
                .isEqualTo(1);
    }

    // ---- 邀请二维码未命中缓存：24 小时 / 20 次（需求 3.9、8.8）----

    @Test
    void qrCodeMissWindowIsIsomorphic() {
        for (int i = 1; i <= InviteRateLimiter.QRCODE_MISS_LIMIT; i++) {
            assertThat(limiter.tryAcquireQrCodeMiss(USER))
                    .as("第 %d 次应放行", i)
                    .isTrue();
        }
        assertThat(limiter.tryAcquireQrCodeMiss(USER))
                .as("第 %d 次应被拒绝", InviteRateLimiter.QRCODE_MISS_LIMIT + 1)
                .isFalse();

        clock.advance(Duration.ofMillis(InviteRateLimiter.QRCODE_MISS_WINDOW_MILLIS - 1));
        assertThat(limiter.tryAcquireQrCodeMiss(USER))
                .as("距今 24h-1ms 仍在窗口内")
                .isFalse();

        clock.advance(Duration.ofMillis(1));
        for (int i = 1; i <= InviteRateLimiter.QRCODE_MISS_LIMIT; i++) {
            assertThat(limiter.tryAcquireQrCodeMiss(USER))
                    .as("24h 整点滑出后第 %d 次应放行", i)
                    .isTrue();
        }
        assertThat(limiter.tryAcquireQrCodeMiss(USER)).isFalse();
    }

    @Test
    void qrCodeMissCountsPerUserKey() {
        for (int i = 0; i < InviteRateLimiter.QRCODE_MISS_LIMIT; i++) {
            assertThat(limiter.tryAcquireQrCodeMiss(USER)).isTrue();
        }
        assertThat(limiter.tryAcquireQrCodeMiss(USER)).isFalse();
        assertThat(limiter.tryAcquireQrCodeMiss(USER + 1))
                .as("另一个用户有独立额度")
                .isTrue();
    }

    /** 两类计数彼此完全独立：任一方耗尽额度不影响另一方（需求 8.11）。 */
    @Test
    void twoWindowsAreIndependent() {
        for (int i = 0; i < InviteRateLimiter.INVITER_LOOKUP_LIMIT; i++) {
            assertThat(limiter.tryAcquireInviterLookup(IP)).isTrue();
        }
        assertThat(limiter.tryAcquireInviterLookup(IP)).isFalse();

        for (int i = 0; i < InviteRateLimiter.QRCODE_MISS_LIMIT; i++) {
            assertThat(limiter.tryAcquireQrCodeMiss(USER))
                    .as("二维码额度不受查询窗口影响")
                    .isTrue();
        }
        assertThat(limiter.tryAcquireQrCodeMiss(USER)).isFalse();
    }

    @Test
    void countsStartFromZeroOnFreshInstance() {
        assertThat(limiter.inviterLookupKeyCount()).isZero();
        assertThat(limiter.qrCodeMissKeyCount()).isZero();
    }

    // ---- 键数达上限时回收空队列 ----

    @Test
    void purgesExpiredEmptyWindowsWhenKeyCountReachesMax() {
        for (int i = 0; i < InviteRateLimiter.MAX_KEYS; i++) {
            assertThat(limiter.tryAcquireInviterLookup("198.51.100." + i)).isTrue();
        }
        assertThat(limiter.inviterLookupKeyCount()).isEqualTo(InviteRateLimiter.MAX_KEYS);

        // 全部时刻滑出窗口后，下一次请求会先回收空队列，再为新键建队列。
        clock.advance(Duration.ofMillis(InviteRateLimiter.INVITER_LOOKUP_WINDOW_MILLIS));
        assertThat(limiter.tryAcquireInviterLookup(IP)).isTrue();
        assertThat(limiter.inviterLookupKeyCount())
                .as("10000 个空队列被回收，只剩新键")
                .isEqualTo(1);
    }

    /** 窗口内仍有时刻的键不会被回收（清理只针对已全部滑出的键）。 */
    @Test
    void purgeKeepsWindowsWithLiveTimestamps() {
        for (int i = 0; i < InviteRateLimiter.MAX_KEYS - 1; i++) {
            assertThat(limiter.tryAcquireInviterLookup("198.51.100." + i)).isTrue();
        }
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquireInviterLookup(IP)).isTrue();
        assertThat(limiter.inviterLookupKeyCount()).isEqualTo(InviteRateLimiter.MAX_KEYS);

        // 早先那批已滑出，IP 的时刻（t0+30s）仍在窗口内，触发清理后应保留 IP 与新键。
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquireInviterLookup("192.0.2.1")).isTrue();
        assertThat(limiter.inviterLookupKeyCount()).isEqualTo(2);
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
