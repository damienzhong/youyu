package com.damien.youyu.service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 邀请域的两类进程内滑动窗口计数器（需求 3.9、8.6、8.8、8.11）。
 *
 * <table border="1">
 *   <caption>两个互不相干的窗口</caption>
 *   <tr><th>计数器</th><th>键</th><th>窗口</th><th>上限</th><th>计数对象</th></tr>
 *   <tr><td>邀请人展示信息查询</td><td>来源 IP</td><td>60 秒</td><td>30</td>
 *       <td>每个未被拒绝的请求（邀请码存在与不存在同等计入，需求 8.10）</td></tr>
 *   <tr><td>邀请二维码</td><td>{@code userId}</td><td>24 小时</td><td>20</td>
 *       <td>仅未命中缓存的请求（含微信调用失败，需求 3.7）</td></tr>
 * </table>
 *
 * <p>两个窗口共用同一套实现，但计数彼此完全独立：任一方的额度消耗不影响另一方。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li><b>精确滑动窗口而非固定窗口</b>：固定窗口在边界处可放行 2 倍额度。这里每个键存一条按时刻
 *       升序的时间戳队列，单键最多 30 / 20 个 {@code Long}，内存代价可忽略。</li>
 *   <li><b>达上限返回 {@code false} 且不写入队列</b>，因此被拒绝的请求不消耗额度（需求 8.8）。</li>
 *   <li><b>队列自身作为该键的互斥锁</b>：{@code ArrayDeque} 非线程安全，全部读写都在
 *       {@code synchronized (q)} 内完成。</li>
 *   <li><b>时钟统一</b>：一律用注入的 {@link Clock}（{@code TimeConfig} 提供）取服务端时刻，
 *       不得改用 {@code System.currentTimeMillis()}，否则测试无法用固定时钟精确驱动窗口边界。</li>
 *   <li><b>进程内、按实例独立累计</b>（需求 8.11）：进程启动时两类计数均为 0；多实例部署时同一 IP
 *       或同一用户在各实例上分别享有独立额度（当前部署为单实例）。</li>
 * </ul>
 */
@Component
public class InviteRateLimiter {

    /** 邀请人展示信息查询：60 秒窗口。 */
    static final long INVITER_LOOKUP_WINDOW_MILLIS = 60_000L;

    /** 邀请人展示信息查询：单窗口 30 次。 */
    static final int INVITER_LOOKUP_LIMIT = 30;

    /** 邀请二维码未命中缓存：24 小时窗口。 */
    static final long QRCODE_MISS_WINDOW_MILLIS = 24L * 60 * 60 * 1000;

    /** 邀请二维码未命中缓存：单窗口 20 次。 */
    static final int QRCODE_MISS_LIMIT = 20;

    /**
     * 单个窗口的键数上限：达到该数量时先清理一遍「时刻已全部滑出窗口」的空队列，
     * 防御伪造 IP 导致的内存膨胀（30 个 {@code Long} × 10000 键量级仍在数 MB 内）。
     */
    static final int MAX_KEYS = 10_000;

    /** IP 取不到时的兜底键（正常部署下 nginx 必然带来源地址，此分支仅为防御 NPE）。 */
    private static final String UNKNOWN_IP_KEY = "";

    /** userId 取不到时的兜底键（受保护接口必有令牌用户，此分支仅为防御 NPE）。 */
    private static final long UNKNOWN_USER_KEY = -1L;

    private final Clock clock;

    /** 邀请人展示信息查询的窗口：key = 来源 IP。 */
    private final ConcurrentHashMap<String, ArrayDeque<Long>> inviterLookupWindows =
            new ConcurrentHashMap<>();

    /** 邀请二维码未命中缓存的窗口：key = userId。 */
    private final ConcurrentHashMap<Long, ArrayDeque<Long>> qrCodeMissWindows =
            new ConcurrentHashMap<>();

    public InviteRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 邀请人展示信息查询限流：同一来源 IP，60 秒滑动窗口内至多 30 次（需求 8.6）。
     *
     * <p>调用方须在邀请码的格式校验与存在性查询<b>之前</b>调用本方法。</p>
     *
     * @param ip 来源 IP（{@code X-Forwarded-For} 末位或 TCP 远端地址），可空
     * @return 放行返回 {@code true}；达上限返回 {@code false} 且不消耗额度
     */
    public boolean tryAcquireInviterLookup(String ip) {
        String key = (ip == null || ip.isBlank()) ? UNKNOWN_IP_KEY : ip.trim();
        return tryAcquire(inviterLookupWindows, key, INVITER_LOOKUP_WINDOW_MILLIS, INVITER_LOOKUP_LIMIT);
    }

    /**
     * 邀请二维码未命中缓存限流：同一 userId，24 小时滑动窗口内至多 20 次（需求 3.9、8.8）。
     *
     * <p>调用方须在<b>缓存命中判定之后</b>调用本方法：额度计的是真正打到微信的次数，
     * 命中缓存的请求不计入、也不被拒绝。</p>
     *
     * @param userId 令牌所标识的用户 id，可空
     * @return 放行返回 {@code true}；达上限返回 {@code false} 且不消耗额度
     */
    public boolean tryAcquireQrCodeMiss(Long userId) {
        Long key = userId == null ? UNKNOWN_USER_KEY : userId;
        return tryAcquire(qrCodeMissWindows, key, QRCODE_MISS_WINDOW_MILLIS, QRCODE_MISS_LIMIT);
    }

    /**
     * 滑动窗口判定：踢出滑出窗口的时刻 → 未达上限则记一次并放行，已达上限直接拒绝且不记数。
     *
     * <p>窗口边界取半开区间：距今恰好 {@code windowMillis} 的时刻视为已滑出。</p>
     */
    private <K> boolean tryAcquire(ConcurrentHashMap<K, ArrayDeque<Long>> windows, K key,
                                   long windowMillis, int limit) {
        long now = clock.millis();
        if (windows.size() >= MAX_KEYS) {
            purgeExpiredWindows(windows, now, windowMillis);
        }
        while (true) {
            ArrayDeque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (q) {
                if (windows.get(key) != q) {
                    // 该队列刚被回收（见下方 remove(key, q)），取当前生效的队列重试，避免写进孤儿队列丢计数。
                    continue;
                }
                evictExpired(q, now, windowMillis);
                if (q.size() >= limit) {
                    if (q.isEmpty()) {
                        // 仅在 limit <= 0 的退化配置下成立：拒绝且队列为空，顺手原子回收该键。
                        windows.remove(key, q);
                    }
                    return false;
                }
                q.addLast(now);
                return true;
            }
        }
    }

    /**
     * 清理时刻已全部滑出窗口的键。
     *
     * <p>正常放行路径总会往队列里写入一个时刻，因此不会留下空队列；长期运行积累的空条目
     * （某个 IP 只来过一阵子）由本方法在键数达上限时统一回收。回收一律用
     * {@code remove(key, value)} 的原子两参数形式，避免误删刚被别的线程换上的新队列。</p>
     */
    private <K> void purgeExpiredWindows(ConcurrentHashMap<K, ArrayDeque<Long>> windows,
                                         long now, long windowMillis) {
        for (Map.Entry<K, ArrayDeque<Long>> entry : windows.entrySet()) {
            ArrayDeque<Long> q = entry.getValue();
            synchronized (q) {
                evictExpired(q, now, windowMillis);
                if (q.isEmpty()) {
                    windows.remove(entry.getKey(), q);
                }
            }
        }
    }

    /** 队首为最早时刻；距今已满一个窗口的时刻一律踢出。调用方须持有 {@code q} 的锁。 */
    private void evictExpired(ArrayDeque<Long> q, long now, long windowMillis) {
        while (!q.isEmpty() && now - q.peekFirst() >= windowMillis) {
            q.pollFirst();
        }
    }

    /** 仅供测试观察键的回收情况。 */
    int inviterLookupKeyCount() {
        return inviterLookupWindows.size();
    }

    /** 仅供测试观察键的回收情况。 */
    int qrCodeMissKeyCount() {
        return qrCodeMissWindows.size();
    }
}
