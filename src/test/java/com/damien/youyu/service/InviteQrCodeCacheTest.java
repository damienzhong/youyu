package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link InviteQrCodeCache} 的示例/边界单元测试（关联需求 3.4、3.13）。
 *
 * <p>全部用例由可推进的固定时钟驱动，不依赖真实时间，TTL 边界可精确到毫秒断言。
 * 覆盖：写满上限后淘汰写入时刻最早的项、同键重写（含过期后重写）排到队尾、
 * 7 天 TTL 的半开区间边界、容量上限配置项生效。</p>
 */
class InviteQrCodeCacheTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"), ZONE);
    }

    private static byte[] png(String tag) {
        return tag.getBytes(StandardCharsets.UTF_8);
    }

    /** 命中断言：byte[] 需按内容比较，不能依赖数组的 equals（引用相等）。 */
    private static void assertHit(Optional<byte[]> actual, byte[] expected) {
        assertThat(actual).isPresent();
        assertThat(actual.get()).isEqualTo(expected);
    }

    // ---- 容量上限与 FIFO 淘汰（需求 3.13）----

    /** 写入 1001 项后只留 1000 项，被淘汰的恰是首个写入者。 */
    @Test
    void evictsFirstWrittenEntryWhenExceedingDefaultCapacity() {
        InviteQrCodeCache cache = new InviteQrCodeCache(clock, InviteQrCodeCache.DEFAULT_MAX_ENTRIES);

        for (int i = 0; i <= InviteQrCodeCache.DEFAULT_MAX_ENTRIES; i++) {
            cache.put("CODE" + i, png("png" + i));
        }

        assertThat(cache.size())
                .as("总数不超过上限")
                .isEqualTo(InviteQrCodeCache.DEFAULT_MAX_ENTRIES);
        assertThat(cache.get("CODE0"))
                .as("首个写入者被淘汰")
                .isEmpty();
        // 第二个写入者与最后写入者都仍在
        int last = InviteQrCodeCache.DEFAULT_MAX_ENTRIES;
        assertHit(cache.get("CODE1"), png("png1"));
        assertHit(cache.get("CODE" + last), png("png" + last));
    }

    /** 同键重写按新的写入时刻排到队尾，不再以旧次序被优先淘汰。 */
    @Test
    void rewritingExistingKeyMovesItToEvictionTail() {
        InviteQrCodeCache cache = new InviteQrCodeCache(clock, 3);
        cache.put("A", png("a1"));
        cache.put("B", png("b"));
        cache.put("C", png("c"));

        clock.advance(Duration.ofHours(1));
        cache.put("A", png("a2"));

        cache.put("D", png("d"));

        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.get("B"))
                .as("刷新后 B 成为写入时刻最早者，被淘汰")
                .isEmpty();
        assertHit(cache.get("A"), png("a2"));
        assertHit(cache.get("C"), png("c"));
        assertHit(cache.get("D"), png("d"));
    }

    /** 过期项在读取时就地移除，随后的写入排到队尾。 */
    @Test
    void expiredEntryIsRemovedOnReadAndRewriteGoesToTail() {
        InviteQrCodeCache cache = new InviteQrCodeCache(clock, 3);
        cache.put("A", png("a1"));
        cache.put("B", png("b"));
        cache.put("C", png("c"));

        clock.advance(Duration.ofMillis(InviteQrCodeCache.TTL_MILLIS));
        assertThat(cache.get("A"))
                .as("已满 7 天，按未命中处理")
                .isEmpty();
        assertThat(cache.size())
                .as("过期项被就地移除")
                .isEqualTo(2);

        cache.put("A", png("a2"));
        cache.put("D", png("d"));
        assertThat(cache.size()).isEqualTo(3);
        assertHit(cache.get("A"), png("a2"));

        // 再写一项时被淘汰的是 C（A 已排到 C 之后）
        cache.put("E", png("e"));
        assertThat(cache.size()).isEqualTo(3);
        assertHit(cache.get("A"), png("a2"));
        assertHit(cache.get("D"), png("d"));
        assertHit(cache.get("E"), png("e"));
    }

    // ---- TTL 边界：半开区间，第 7 天整即过期（需求 3.4）----

    @Test
    void hitsUntilTtlEdgeAndMissesExactlyAtSevenDays() {
        InviteQrCodeCache cache = new InviteQrCodeCache(clock, InviteQrCodeCache.DEFAULT_MAX_ENTRIES);
        cache.put("CODE", png("png"));

        assertThat(InviteQrCodeCache.TTL_MILLIS)
                .as("TTL 为 7 天")
                .isEqualTo(Duration.ofDays(7).toMillis());

        clock.advance(Duration.ofMillis(InviteQrCodeCache.TTL_MILLIS - 1));
        assertHit(cache.get("CODE"), png("png"));

        clock.advance(Duration.ofMillis(1));
        assertThat(cache.get("CODE"))
                .as("第 7 天整已过期")
                .isEmpty();
        assertThat(cache.size()).isZero();
    }

    // ---- 容量上限配置项生效 ----

    @Test
    void configuredMaxEntriesTakesEffect() {
        InviteQrCodeCache cache = new InviteQrCodeCache(clock, 3);
        assertThat(cache.maxEntries()).isEqualTo(3);

        cache.put("A", png("a"));
        cache.put("B", png("b"));
        cache.put("C", png("c"));
        assertThat(cache.size()).isEqualTo(3);

        cache.put("D", png("d"));
        assertThat(cache.size())
                .as("容量按配置的 3 生效")
                .isEqualTo(3);
        assertThat(cache.get("A")).isEmpty();
        assertHit(cache.get("D"), png("d"));
    }

    /** 非正的配置取值按 1 处理，避免每次写入都被立刻清空导致缓存静默失效。 */
    @Test
    void nonPositiveMaxEntriesFallsBackToOne() {
        InviteQrCodeCache cache = new InviteQrCodeCache(clock, 0);
        assertThat(cache.maxEntries()).isEqualTo(1);

        cache.put("A", png("a"));
        cache.put("B", png("b"));
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("A")).isEmpty();
        assertHit(cache.get("B"), png("b"));
    }

    @Test
    void ignoresBlankKeyAndEmptyImage() {
        InviteQrCodeCache cache = new InviteQrCodeCache(clock, InviteQrCodeCache.DEFAULT_MAX_ENTRIES);
        cache.put(null, png("a"));
        cache.put("   ", png("a"));
        cache.put("CODE", null);
        cache.put("CODE", new byte[0]);

        assertThat(cache.size()).isZero();
        assertThat(cache.get(null)).isEmpty();
        assertThat(cache.get("  ")).isEmpty();
        assertThat(cache.get("CODE")).isEmpty();
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
