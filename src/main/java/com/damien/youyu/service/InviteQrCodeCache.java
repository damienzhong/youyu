package com.damien.youyu.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 邀请二维码（小程序码）的进程内图片缓存（需求 3.4、3.13）。
 *
 * <p>键 = 邀请码，值 = PNG 字节 + 写入时刻。容量上限可配，缺省 1000 项；TTL 7 天；<b>不落盘</b>。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li><b>FIFO 淘汰而非 LRU</b>：{@link LinkedHashMap} 以 {@code accessOrder = false}（插入顺序）
 *       配合 {@code removeEldestEntry} 构造，淘汰的恰是<b>写入时刻最早</b>的项，正是需求 3.13 的语义。
 *       不要顺手改成 {@code accessOrder = true}：那样读取命中会把冷门邀请码顶到队尾，
 *       淘汰顺序就不再是「写入时刻最早」。</li>
 *   <li><b>值存 {@code byte[]} 而非 base64 字符串</b>：base64 会带来约 4/3 的膨胀
 *       （430px 小程序码 20–40KB/项，编码后 27–54KB/项），1000 项即 27–54MB 堆占用。
 *       改存原始字节后降到 20–40MB，base64 编码移到响应组装时现场做（见
 *       {@code InviteQrCodeService.getQrCodeBase64}）。</li>
 *   <li><b>TTL 由读取时判定</b>：距写入时刻已满 7 天的项按未命中处理（半开区间，第 7 天整即过期）。
 *       过期项在读取时就地移除，随后的 {@link #put} 会把新值排到队尾。</li>
 *   <li><b>{@link #put} 一律先 {@code remove} 再 {@code put}</b>：{@code LinkedHashMap} 对已存在的键
 *       只更新值、保留原插入位置，若不先移除，刷新过的项仍会以旧的写入次序被优先淘汰。</li>
 *   <li><b>时钟统一</b>：一律用注入的 {@link Clock}（{@code TimeConfig} 提供）取服务端时刻，
 *       不得改用 {@code System.currentTimeMillis()}，否则测试无法用固定时钟精确驱动 TTL 边界。</li>
 *   <li><b>进程内、按实例独立</b>：重启即冷启动，多实例各自缓存（当前部署为单实例）。</li>
 * </ul>
 *
 * <p>线程安全：全部读写在 {@code synchronized (cache)} 内完成（{@code LinkedHashMap} 非线程安全，
 * 且「判定过期 → 移除 → 写入」是复合操作，必须整体互斥）。</p>
 */
@Component
public class InviteQrCodeCache {

    private static final Logger log = LoggerFactory.getLogger(InviteQrCodeCache.class);

    /** 缓存项存活时长：7 天（需求 3.4、3.13）。 */
    static final long TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /** 容量上限缺省值：需求 3.13 要求「不超过 1000 项」。 */
    static final int DEFAULT_MAX_ENTRIES = 1000;

    /** 缓存值：PNG 原始字节 + 写入时刻（毫秒）。 */
    private record CachedImage(byte[] png, long writtenAtMillis) { }

    private final Clock clock;

    private final int maxEntries;

    private final LinkedHashMap<String, CachedImage> cache;

    /**
     * @param clock      服务端时钟（{@code TimeConfig} 提供）
     * @param maxEntries 容量上限，缺省 1000。配置更小的值仍满足需求 3.13 的「不超过 1000」；
     *                   非正取值按 1 处理（若真按 {@code size() > 0} 淘汰，每次写入都会被立刻清空，
     *                   缓存静默失效、每个请求都打到微信）
     */
    public InviteQrCodeCache(
            Clock clock,
            @Value("${app.invite.qrcode.cache-max-entries:1000}") int maxEntries) {

        this.clock = clock;
        if (maxEntries < 1) {
            log.warn("app.invite.qrcode.cache-max-entries={} 非法，按 1 处理", maxEntries);
            this.maxEntries = 1;
        } else {
            this.maxEntries = maxEntries;
        }
        int initialCapacity = Math.min(this.maxEntries, 128);
        this.cache = new LinkedHashMap<>(initialCapacity, 0.75f, /* accessOrder */ false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedImage> eldest) {
                return size() > InviteQrCodeCache.this.maxEntries;
            }
        };
    }

    /**
     * 读取某邀请码的小程序码字节。
     *
     * <p>未写入过、或写入时刻距今已满 7 天，一律按未命中返回空（过期项就地移除）。</p>
     *
     * <p>返回的数组是缓存内部持有的同一个实例（避免每次命中都复制 20–40KB），
     * <b>调用方不得修改其内容</b>，只读取用于 base64 编码。</p>
     *
     * @param inviteCode 邀请码，可空
     * @return 命中且未过期时为 PNG 字节，否则 {@link Optional#empty()}
     */
    public Optional<byte[]> get(String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            return Optional.empty();
        }
        long now = clock.millis();
        synchronized (cache) {
            CachedImage cached = cache.get(inviteCode);
            if (cached == null) {
                return Optional.empty();
            }
            if (isExpired(cached, now)) {
                cache.remove(inviteCode);
                return Optional.empty();
            }
            return Optional.of(cached.png());
        }
    }

    /**
     * 写入某邀请码的小程序码字节，并记录服务端当前时刻为写入时刻。
     *
     * <p>写入使总数超过上限时，淘汰写入时刻最早的项（需求 3.13）。同键重复写入会先移除再写入，
     * 使刷新后的项重新排到队尾。</p>
     *
     * @param inviteCode 邀请码，空白时不写入
     * @param png        PNG 原始字节，空时不写入
     */
    public void put(String inviteCode, byte[] png) {
        if (inviteCode == null || inviteCode.isBlank() || png == null || png.length == 0) {
            return;
        }
        long now = clock.millis();
        synchronized (cache) {
            // 先移除：LinkedHashMap 对已存在的键保留原插入位置，不移除的话刷新过的项仍按旧次序被淘汰。
            cache.remove(inviteCode);
            cache.put(inviteCode, new CachedImage(png, now));
        }
    }

    /** 当前缓存项数（含尚未被读取判定的过期项）。 */
    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** 生效的容量上限（配置项 {@code app.invite.qrcode.cache-max-entries}）。 */
    int maxEntries() {
        return maxEntries;
    }

    /** 半开区间：距写入时刻恰好满 7 天即视为过期。 */
    private boolean isExpired(CachedImage cached, long now) {
        return now - cached.writtenAtMillis() >= TTL_MILLIS;
    }
}
