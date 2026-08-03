package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.damien.youyu.error.ApiException;
import com.damien.youyu.wechat.WeChatQrCodeGateway;

/**
 * {@link InviteQrCodeService} 的分支单元测试（关联需求 3.4、3.7、3.9、3.12）。
 *
 * <p>协作方取舍：{@link InviteQrCodeCache} 与 {@link InviteRateLimiter} 用<b>真实实例</b>
 * （二者都是无外部依赖的纯组件，由同一个可推进的固定时钟驱动，比 mock 更能反映真实的缓存/额度语义）；
 * {@link InviteService} 与 {@link WeChatQrCodeGateway} 用 Mockito mock。</p>
 *
 * <p>微信调用的 mock 打在 {@link WeChatQrCodeGateway} 而非 {@code WeChatClient} 上：网关是
 * 「取凭证 → 调小程序码接口 → 40001 重试一次」的整体封装，也是本服务唯一依赖的接缝。
 * 在 {@code WeChatClient} 上打桩会绕过网关、把 40001 重试的归属搅进业务层断言。</p>
 *
 * <p>覆盖分支：命中缓存零微信调用且不消耗额度、未命中第 21 次返回 429 且不碰微信、
 * 微信抛错时额度已扣但不写缓存、{@code invite_code} 为空时先补齐并以补齐后的码作 {@code scene}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InviteQrCodeServiceTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final long USER_ID = 77L;
    private static final String CODE = "K7M2Q9XT";
    private static final byte[] PNG = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock
    private InviteService inviteService;

    @Mock
    private WeChatQrCodeGateway qrCodeGateway;

    /** 递增序号：用于每次请求生成不同的邀请码，保证必然未命中缓存。 */
    private int missSeq = 0;

    private MutableClock clock;
    private InviteQrCodeCache cache;
    private InviteRateLimiter rateLimiter;
    private InviteQrCodeService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"), ZONE);
        cache = new InviteQrCodeCache(clock, InviteQrCodeCache.DEFAULT_MAX_ENTRIES);
        rateLimiter = new InviteRateLimiter(clock);
        service = new InviteQrCodeService(inviteService, cache, rateLimiter, qrCodeGateway);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    // ---- 缓存命中：零微信调用、不计数（需求 3.4、3.9）----

    /**
     * 首次未命中调一次网关并写缓存；随后 50 次全部命中，网关调用次数仍为 1，
     * 且额度只被首次那一次消耗（命中缓存的请求不计数，需求 3.4、8.8）。
     */
    @Test
    void cacheHitSkipsWeChatCallAndQuota() {
        when(inviteService.requireInviteCode(USER_ID)).thenReturn(CODE);
        when(qrCodeGateway.fetchQrCode(CODE, InviteQrCodeService.QRCODE_PAGE,
                InviteQrCodeService.QRCODE_WIDTH)).thenReturn(PNG);

        assertThat(service.getQrCodeBase64(USER_ID)).isEqualTo(base64(PNG));
        for (int i = 0; i < 50; i++) {
            assertThat(service.getQrCodeBase64(USER_ID))
                    .as("第 %d 次命中缓存应返回同一张图", i + 2)
                    .isEqualTo(base64(PNG));
        }

        verify(qrCodeGateway, times(1)).fetchQrCode(anyString(), anyString(), anyInt());

        // 只消耗了 1 次额度：还剩 19 次可放行，第 20 次之后才拒绝。
        for (int i = 1; i <= InviteRateLimiter.QRCODE_MISS_LIMIT - 1; i++) {
            assertThat(rateLimiter.tryAcquireQrCodeMiss(USER_ID))
                    .as("命中缓存未消耗额度，剩余第 %d 次应放行", i)
                    .isTrue();
        }
        assertThat(rateLimiter.tryAcquireQrCodeMiss(USER_ID)).isFalse();
    }

    /** 返回值是不带 {@code data:image/png;base64,} 前缀的裸 base64（需求 3.1）。 */
    @Test
    void returnsBareBase64WithoutDataUriPrefix() {
        when(inviteService.requireInviteCode(USER_ID)).thenReturn(CODE);
        when(qrCodeGateway.fetchQrCode(anyString(), anyString(), anyInt())).thenReturn(PNG);

        String actual = service.getQrCodeBase64(USER_ID);

        assertThat(actual).doesNotStartWith("data:").isEqualTo(base64(PNG));
        assertThat(Base64.getDecoder().decode(actual)).isEqualTo(PNG);
    }

    // ---- 限流：未命中第 21 次拒绝（需求 3.9）----

    /**
     * 每次请求都用不同邀请码以保证必然未命中缓存：前 20 次放行并各调一次网关，
     * 第 21 次抛 {@code INVITE_RATE_LIMITED}（429）且完全不碰微信。
     */
    @Test
    void twentyFirstCacheMissIsRateLimited() {
        when(inviteService.requireInviteCode(USER_ID))
                .thenAnswer(invocation -> "CODE" + (missSeq++));
        when(qrCodeGateway.fetchQrCode(anyString(), anyString(), anyInt())).thenReturn(PNG);

        for (int i = 1; i <= InviteRateLimiter.QRCODE_MISS_LIMIT; i++) {
            assertThat(service.getQrCodeBase64(USER_ID))
                    .as("第 %d 次未命中应放行", i)
                    .isEqualTo(base64(PNG));
        }
        verify(qrCodeGateway, times(InviteRateLimiter.QRCODE_MISS_LIMIT))
                .fetchQrCode(anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> service.getQrCodeBase64(USER_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVITE_RATE_LIMITED");
                    assertThat(ex.getStatus().value()).isEqualTo(429);
                });
        verify(qrCodeGateway, times(InviteRateLimiter.QRCODE_MISS_LIMIT))
                .fetchQrCode(anyString(), anyString(), anyInt());
    }

    /** 被限流的用户在 24 小时窗口滑出后恢复；且限流只按 userId 计，不影响其他用户。 */
    @Test
    void rateLimitIsPerUserAndRecoversAfterWindow() {
        when(inviteService.requireInviteCode(anyLong()))
                .thenAnswer(invocation -> "CODE" + (missSeq++));
        when(qrCodeGateway.fetchQrCode(anyString(), anyString(), anyInt())).thenReturn(PNG);

        for (int i = 0; i < InviteRateLimiter.QRCODE_MISS_LIMIT; i++) {
            service.getQrCodeBase64(USER_ID);
        }
        assertThatThrownBy(() -> service.getQrCodeBase64(USER_ID))
                .isInstanceOf(ApiException.class);

        assertThat(service.getQrCodeBase64(USER_ID + 1))
                .as("另一个用户有独立额度")
                .isEqualTo(base64(PNG));

        clock.advance(Duration.ofMillis(InviteRateLimiter.QRCODE_MISS_WINDOW_MILLIS));
        assertThat(service.getQrCodeBase64(USER_ID))
                .as("24 小时窗口滑出后恢复")
                .isEqualTo(base64(PNG));
    }

    // ---- 微信失败：计数但不写缓存（需求 3.7）----

    /**
     * 网关抛 {@code INVITE_QRCODE_FAILED} 时异常原样透出、缓存不写；
     * 失败那次照样计入未命中额度（额度保护的是外部接口调用量，失败调用同样消耗了对方配额）。
     */
    @Test
    void weChatFailureConsumesQuotaButDoesNotCache() {
        when(inviteService.requireInviteCode(USER_ID)).thenReturn(CODE);
        when(qrCodeGateway.fetchQrCode(CODE, InviteQrCodeService.QRCODE_PAGE,
                InviteQrCodeService.QRCODE_WIDTH))
                .thenThrow(ApiException.inviteQrCodeFailed(null));

        assertThatThrownBy(() -> service.getQrCodeBase64(USER_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVITE_QRCODE_FAILED");
                    assertThat(ex.getStatus().value()).isEqualTo(502);
                });

        assertThat(cache.get(CODE))
                .as("失败不写缓存")
                .isEmpty();
        assertThat(cache.size()).isZero();

        // 失败已扣 1 次额度：只剩 19 次。
        for (int i = 1; i <= InviteRateLimiter.QRCODE_MISS_LIMIT - 1; i++) {
            assertThat(rateLimiter.tryAcquireQrCodeMiss(USER_ID))
                    .as("失败已扣 1 次，剩余第 %d 次应放行", i)
                    .isTrue();
        }
        assertThat(rateLimiter.tryAcquireQrCodeMiss(USER_ID)).isFalse();
    }

    /** 连续 20 次微信失败即耗尽额度，第 21 次直接 429 且不再调微信。 */
    @Test
    void repeatedWeChatFailuresExhaustQuota() {
        when(inviteService.requireInviteCode(USER_ID)).thenReturn(CODE);
        when(qrCodeGateway.fetchQrCode(anyString(), anyString(), anyInt()))
                .thenThrow(ApiException.inviteQrCodeFailed(null));

        for (int i = 0; i < InviteRateLimiter.QRCODE_MISS_LIMIT; i++) {
            assertThatThrownBy(() -> service.getQrCodeBase64(USER_ID))
                    .isInstanceOf(ApiException.class);
        }
        verify(qrCodeGateway, times(InviteRateLimiter.QRCODE_MISS_LIMIT))
                .fetchQrCode(anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> service.getQrCodeBase64(USER_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITE_RATE_LIMITED"));
        verify(qrCodeGateway, times(InviteRateLimiter.QRCODE_MISS_LIMIT))
                .fetchQrCode(anyString(), anyString(), anyInt());
    }

    // ---- 惰性补齐：scene 取补齐后的码（需求 3.12）----

    /**
     * {@code invite_code} 原为空时，服务先经 {@link InviteService#requireInviteCode} 补齐，
     * 再以<b>补齐后</b>的码作 {@code scene}、以固定的页面路径与边长调网关，缓存也按该码写入。
     */
    @Test
    void lazilyFilledCodeIsUsedAsScene() {
        String filled = "PQ34RS56";
        when(inviteService.requireInviteCode(USER_ID)).thenReturn(filled);
        when(qrCodeGateway.fetchQrCode(anyString(), anyString(), anyInt())).thenReturn(PNG);

        assertThat(service.getQrCodeBase64(USER_ID)).isEqualTo(base64(PNG));

        verify(inviteService).requireInviteCode(USER_ID);
        verify(qrCodeGateway).fetchQrCode(
                eq(filled),
                eq("pages/invitelanding/invitelanding"),
                eq(430));
        assertThat(cache.get(filled))
                .as("缓存键为补齐后的邀请码")
                .isPresent();
    }

    /** 补齐失败（10 次候选码全被占用）时直接透出，不碰限流与微信。 */
    @Test
    void codeGenerationFailurePropagatesWithoutTouchingWeChat() {
        when(inviteService.requireInviteCode(USER_ID)).thenThrow(ApiException.inviteCodeGenFailed());

        assertThatThrownBy(() -> service.getQrCodeBase64(USER_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITE_CODE_GEN_FAILED"));

        verifyNoInteractions(qrCodeGateway);
        assertThat(cache.size()).isZero();
        for (int i = 0; i < InviteRateLimiter.QRCODE_MISS_LIMIT; i++) {
            assertThat(rateLimiter.tryAcquireQrCodeMiss(USER_ID))
                    .as("补齐失败不消耗额度")
                    .isTrue();
        }
    }

    /** 常量即契约：页面路径不以 {@code /} 开头（微信要求），边长 430（需求 3.2）。 */
    @Test
    void qrCodeConstantsMatchSpec() {
        assertThat(InviteQrCodeService.QRCODE_PAGE)
                .isEqualTo("pages/invitelanding/invitelanding")
                .doesNotStartWith("/");
        assertThat(InviteQrCodeService.QRCODE_WIDTH).isEqualTo(430);
    }

    /** 一次未命中只调网关一次：40001 的重试在网关内部，不由业务层重复发起（需求 3.7）。 */
    @Test
    void singleMissCallsGatewayExactlyOnce() {
        when(inviteService.requireInviteCode(USER_ID)).thenReturn(CODE);
        when(qrCodeGateway.fetchQrCode(anyString(), anyString(), anyInt())).thenReturn(PNG);

        service.getQrCodeBase64(USER_ID);

        verify(qrCodeGateway, times(1)).fetchQrCode(any(), any(), anyInt());
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
