package com.damien.youyu.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.damien.youyu.error.ApiException;

/**
 * {@link WeChatAccessTokenProvider} 与 {@link WeChatQrCodeGateway} 重试路径的示例/边界单元测试
 * （关联需求 3.5、3.14）。
 *
 * <p>覆盖：剩余 301s 复用缓存 / 299s 触发刷新（含 300s 阈值本身仍复用）、刷新失败保留旧凭证与到期时刻
 * 且不调用小程序码接口、并发只刷新一次、{@code errcode=40001} 后强制刷新且重试次数恰为 1。</p>
 *
 * <p>时刻一律由可推进的固定时钟驱动，因此 300 秒阈值可精确到毫秒断言。</p>
 */
class WeChatAccessTokenProviderTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final long TOKEN_TTL_SECONDS = 7200L;

    private MutableClock clock;
    private WeChatClient weChatClient;
    private WeChatAccessTokenProvider provider;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"), ZONE);
        weChatClient = mock(WeChatClient.class);
        provider = new WeChatAccessTokenProvider(weChatClient, clock);
    }

    // ---- 300 秒刷新阈值（需求 3.5）----

    /** 剩余 301 秒（以及恰好 300 秒）时复用缓存，不产生任何网络调用。 */
    @Test
    void reusesCachedTokenWhenRemainingAtOrAboveThreshold() {
        provider.seedCache("cached-token", clock.millis() + 301_000L);
        assertThat(provider.getToken()).isEqualTo("cached-token");

        // 推进 1 秒后剩余恰好等于 300 秒阈值，仍复用。
        clock.advance(Duration.ofSeconds(1));
        assertThat(provider.getToken()).isEqualTo("cached-token");

        verifyNoInteractions(weChatClient);
    }

    /** 剩余 299 秒时刷新，并按新凭证的有效期重算到期时刻。 */
    @Test
    void refreshesWhenRemainingBelowThreshold() {
        long seededExpiresAt = clock.millis() + 299_000L;
        provider.seedCache("stale-token", seededExpiresAt);
        when(weChatClient.fetchAccessToken()).thenReturn(new WxAccessToken("fresh-token", TOKEN_TTL_SECONDS));

        assertThat(provider.getToken()).isEqualTo("fresh-token");
        assertThat(provider.cachedToken()).isEqualTo("fresh-token");
        assertThat(provider.cachedExpiresAtMillis())
                .as("到期时刻按刷新时刻 + expires_in 重算")
                .isEqualTo(clock.millis() + TOKEN_TTL_SECONDS * 1000L);
        verify(weChatClient, times(1)).fetchAccessToken();
    }

    // ---- 刷新失败保留旧值（需求 3.14）----

    @Test
    void refreshFailureKeepsCachedTokenAndExpiry() {
        long seededExpiresAt = clock.millis() + 299_000L;
        provider.seedCache("stale-token", seededExpiresAt);
        when(weChatClient.fetchAccessToken())
                .thenThrow(ApiException.inviteQrCodeFailed("获取微信接口凭证失败，请稍后重试"));

        assertThatThrownBy(() -> provider.getToken())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVITE_QRCODE_FAILED");

        assertThat(provider.cachedToken()).isEqualTo("stale-token");
        assertThat(provider.cachedExpiresAtMillis()).isEqualTo(seededExpiresAt);
    }

    /** 未预期的运行时异常同样归一为 INVITE_QRCODE_FAILED，且旧缓存不受影响。 */
    @Test
    void unexpectedRuntimeExceptionKeepsCachedTokenAndExpiry() {
        long seededExpiresAt = clock.millis() + 1_000L;
        provider.seedCache("stale-token", seededExpiresAt);
        when(weChatClient.fetchAccessToken()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> provider.getToken())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVITE_QRCODE_FAILED");

        assertThat(provider.cachedToken()).isEqualTo("stale-token");
        assertThat(provider.cachedExpiresAtMillis()).isEqualTo(seededExpiresAt);
    }

    /** 返回空凭证或非正有效期按失败处理，旧缓存保持不变。 */
    @Test
    void blankTokenOrNonPositiveExpiryIsTreatedAsFailure() {
        long seededExpiresAt = clock.millis() + 1_000L;
        provider.seedCache("stale-token", seededExpiresAt);
        when(weChatClient.fetchAccessToken())
                .thenReturn(new WxAccessToken("  ", TOKEN_TTL_SECONDS))
                .thenReturn(new WxAccessToken("fresh-token", 0L));

        assertThatThrownBy(() -> provider.getToken()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> provider.getToken()).isInstanceOf(ApiException.class);

        assertThat(provider.cachedToken()).isEqualTo("stale-token");
        assertThat(provider.cachedExpiresAtMillis()).isEqualTo(seededExpiresAt);
    }

    /** 凭证刷新失败时不得调用小程序码接口（需求 3.14）。 */
    @Test
    void refreshFailureDoesNotCallQrCodeApi() {
        when(weChatClient.fetchAccessToken())
                .thenThrow(ApiException.inviteQrCodeFailed("获取微信接口凭证失败，请稍后重试"));
        WeChatQrCodeGateway gateway = new WeChatQrCodeGateway(weChatClient, provider);

        assertThatThrownBy(() -> gateway.fetchQrCode("ABC123", "pages/invitelanding/invitelanding", 430))
                .isInstanceOf(ApiException.class);

        verify(weChatClient, never()).fetchUnlimitedQrCode(anyString(), anyString(), anyString(), anyInt());
    }

    // ---- 并发只刷新一次 ----

    @Test
    void concurrentGetTokenRefreshesOnlyOnce() throws Exception {
        int threads = 16;
        AtomicInteger fetchCount = new AtomicInteger();
        when(weChatClient.fetchAccessToken()).thenAnswer(invocation -> {
            fetchCount.incrementAndGet();
            // 拉长刷新窗口，让其余线程一定会撞上刷新锁。
            Thread.sleep(50);
            return new WxAccessToken("fresh-token", TOKEN_TTL_SECONDS);
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return provider.getToken();
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("fresh-token");
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(fetchCount.get()).as("并发刷新只应打微信一次").isEqualTo(1);
        verify(weChatClient, times(1)).fetchAccessToken();
    }

    /** 缓存已被别的线程换成可用新值时，forceRefresh 直接复用，不再打微信。 */
    @Test
    void forceRefreshReusesRotatedCacheWithoutCallingWeChat() {
        provider.seedCache("rotated-token", clock.millis() + 3_600_000L);

        assertThat(provider.forceRefresh("stale-token")).isEqualTo("rotated-token");

        verify(weChatClient, never()).fetchAccessToken();
    }

    // ---- 40001 后强制刷新且总重试次数为 1（任务 4.3 / 需求 3.5、3.14）----

    @Test
    void invalidCredentialTriggersForcedRefreshAndExactlyOneRetry() {
        byte[] png = new byte[] { (byte) 0x89, 'P', 'N', 'G' };
        when(weChatClient.fetchAccessToken())
                .thenReturn(new WxAccessToken("token-1", TOKEN_TTL_SECONDS))
                .thenReturn(new WxAccessToken("token-2", TOKEN_TTL_SECONDS));
        when(weChatClient.fetchUnlimitedQrCode(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new WeChatApiException(WeChatApiException.ERRCODE_INVALID_CREDENTIAL,
                        "invalid credential", null))
                .thenReturn(png);
        WeChatQrCodeGateway gateway = new WeChatQrCodeGateway(weChatClient, provider);

        assertThat(gateway.fetchQrCode("ABC123", "pages/invitelanding/invitelanding", 430))
                .isEqualTo(png);

        verify(weChatClient, times(2)).fetchUnlimitedQrCode(anyString(), anyString(), anyString(), anyInt());
        verify(weChatClient, times(1)).fetchUnlimitedQrCode("token-1", "ABC123",
                "pages/invitelanding/invitelanding", 430);
        verify(weChatClient, times(1)).fetchUnlimitedQrCode("token-2", "ABC123",
                "pages/invitelanding/invitelanding", 430);
        verify(weChatClient, times(2)).fetchAccessToken();
        assertThat(provider.cachedToken()).isEqualTo("token-2");
    }

    /** 重试后仍 40001 时直接失败，不再有第三次尝试。 */
    @Test
    void invalidCredentialAfterRetryFailsWithoutThirdAttempt() {
        when(weChatClient.fetchAccessToken())
                .thenReturn(new WxAccessToken("token-1", TOKEN_TTL_SECONDS))
                .thenReturn(new WxAccessToken("token-2", TOKEN_TTL_SECONDS));
        when(weChatClient.fetchUnlimitedQrCode(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new WeChatApiException(WeChatApiException.ERRCODE_INVALID_CREDENTIAL,
                        "invalid credential", null));
        WeChatQrCodeGateway gateway = new WeChatQrCodeGateway(weChatClient, provider);

        assertThatThrownBy(() -> gateway.fetchQrCode("ABC123", "pages/invitelanding/invitelanding", 430))
                .isInstanceOf(WeChatApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVITE_QRCODE_FAILED");

        verify(weChatClient, times(2)).fetchUnlimitedQrCode(anyString(), anyString(), anyString(), anyInt());
    }

    /** 非 40001 的错误码不重试、不刷新凭证。 */
    @Test
    void otherErrCodeIsNotRetried() {
        when(weChatClient.fetchAccessToken()).thenReturn(new WxAccessToken("token-1", TOKEN_TTL_SECONDS));
        when(weChatClient.fetchUnlimitedQrCode(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new WeChatApiException(WeChatApiException.ERRCODE_QUOTA_EXCEEDED,
                        "quota exceeded", null));
        WeChatQrCodeGateway gateway = new WeChatQrCodeGateway(weChatClient, provider);

        assertThatThrownBy(() -> gateway.fetchQrCode("ABC123", "pages/invitelanding/invitelanding", 430))
                .isInstanceOf(WeChatApiException.class);

        verify(weChatClient, times(1)).fetchUnlimitedQrCode(anyString(), anyString(), anyString(), anyInt());
        verify(weChatClient, times(1)).fetchAccessToken();
    }

    // ---- 可推进的时钟 ----

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
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
