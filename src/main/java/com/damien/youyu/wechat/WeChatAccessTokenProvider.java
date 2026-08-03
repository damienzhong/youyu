package com.damien.youyu.wechat;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.damien.youyu.error.ApiException;

/**
 * 微信接口调用凭证（{@code access_token}）的提供者与进程内缓存（需求 3.5、3.14）。
 *
 * <p><b>本类是全项目唯一允许调用 {@code cgi-bin/token} 的地方。</b>
 * 后续任何需要 {@code access_token} 的功能（模板消息、内容安全、订阅消息、其它小程序码……）
 * <b>必须注入本类取凭证</b>，不得自行调用 {@code cgi-bin/token}，也不得自建凭证缓存。</p>
 *
 * <p>原因：同一 appid 的 {@code access_token} 在微信侧<b>全局唯一</b>。
 * 每次调用 {@code cgi-bin/token} 都会让此前下发的凭证失效，多处各自获取会互相踢掉，
 * 表现为毫无规律的 {@code errcode=40001}（凭证无效）——请求量越大越频繁，且必然出现在
 * 「刚刚还好好的」那条链路上，排查成本极高。把获取点收敛到唯一入口是唯一可靠的防线。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li><b>单值 {@code volatile} 缓存 + {@link ReentrantLock} 双重检查</b>：读路径无锁（绝大多数请求
 *       直接命中缓存），只有需要刷新时才竞争锁；拿到锁后再复查一次缓存，避免并发刷新风暴——
 *       风暴本身就会让这些凭证互相踢掉。</li>
 *   <li><b>剩余有效期 ≥ 300 秒直接返回缓存</b>，否则刷新（需求 3.5）。留 300 秒余量是为了避开
 *       「本地判定未过期、请求到微信时已过期」的临界窗口。</li>
 *   <li><b>刷新失败保留原缓存值与到期时刻不变</b>（需求 3.14）：新值只在校验通过后才整体替换，
 *       失败路径一律不触碰 {@code cached}。这样一次网络抖动不会把手上还能用的凭证弄丢。</li>
 *   <li><b>时钟统一</b>：一律用注入的 {@link Clock}（{@code TimeConfig} 提供）取服务端时刻，
 *       不得改用 {@code System.currentTimeMillis()}，否则测试无法用固定时钟精确驱动 300 秒边界。</li>
 *   <li><b>进程内、按实例独立</b>：重启即冷启动。多实例部署时各实例各自持有凭证，会互相踢掉——
 *       这是本设计已知的代价，监控信号是日志中的 {@code errcode=40001}（见任务 4.3）。</li>
 * </ul>
 */
@Component
public class WeChatAccessTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(WeChatAccessTokenProvider.class);

    /** 剩余有效期低于该阈值即刷新；恰好等于阈值仍复用缓存（需求 3.5）。 */
    static final long REFRESH_THRESHOLD_MILLIS = 300_000L;

    /** 单值缓存：凭证 + 到期时刻（毫秒）。整体替换，不做部分更新。 */
    private record Cached(String token, long expiresAtMillis) { }

    private final WeChatClient weChatClient;

    private final Clock clock;

    /** 刷新前的缓存快照；{@code volatile} 保证读路径无锁也能看到最新值。 */
    private volatile Cached cached;

    /** 刷新临界区：同一时刻至多一个线程打到 {@code cgi-bin/token}。 */
    private final ReentrantLock refreshLock = new ReentrantLock();

    public WeChatAccessTokenProvider(WeChatClient weChatClient, Clock clock) {
        this.weChatClient = weChatClient;
        this.clock = clock;
    }

    /**
     * 取一个可用的接口调用凭证。
     *
     * <p>缓存剩余有效期 ≥ 300 秒时直接返回缓存值（不产生任何网络调用）；否则加锁刷新，
     * 拿锁后复查缓存，因此并发场景下只有一个线程真正刷新，其余线程直接复用其结果。</p>
     *
     * @return 非空的 {@code access_token}
     * @throws ApiException INVITE_QRCODE_FAILED 刷新失败（非零 errcode / 超时 / 抛异常）；
     *                      此时已缓存的凭证取值与到期时刻保持不变（需求 3.14）
     */
    public String getToken() {
        Cached snapshot = cached;
        if (isUsable(snapshot, clock.millis())) {
            return snapshot.token();
        }
        refreshLock.lock();
        try {
            // 双重检查：等锁期间可能已有别的线程刷新成功。
            Cached rechecked = cached;
            if (isUsable(rechecked, clock.millis())) {
                return rechecked.token();
            }
            return refresh();
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 强制刷新凭证，供「微信返回 {@code errcode=40001}（凭证无效）后重试一次」的场景使用（任务 4.3）。
     *
     * <p>只有当前缓存仍是调用方手上那个已被判定失效的凭证时才真正刷新；若期间已有别的线程换来了新值，
     * 直接返回新值。这条判定不是优化而是必需：{@code 40001} 常常正是「凭证被别处刷新踢掉」的结果，
     * 若每个撞上 40001 的请求都无条件刷新一次，就会形成互相踢掉的刷新风暴，把偶发故障放大成持续故障。</p>
     *
     * @param staleToken 调用方本次使用并被微信判定为无效的凭证，可空（视作强制刷新）
     * @return 非空的 {@code access_token}
     * @throws ApiException INVITE_QRCODE_FAILED 刷新失败；已缓存的凭证与到期时刻保持不变
     */
    public String forceRefresh(String staleToken) {
        refreshLock.lock();
        try {
            Cached current = cached;
            if (staleToken != null && current != null && !staleToken.equals(current.token())
                    && isUsable(current, clock.millis())) {
                // 别的线程已经换过一次了，复用它的结果，不再打微信。
                return current.token();
            }
            return refresh();
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 真正打到微信换取新凭证。调用方须持有 {@link #refreshLock}。
     *
     * <p>失败路径一律不触碰 {@link #cached}：无论是 {@code WeChatClient} 抛出的
     * {@code INVITE_QRCODE_FAILED}（配置缺失 / 非零 errcode / 超时），还是任何未预期的运行时异常，
     * 原缓存值与到期时刻都保持不变（需求 3.14）。</p>
     */
    private String refresh() {
        WxAccessToken fetched;
        try {
            fetched = weChatClient.fetchAccessToken();
        } catch (ApiException ex) {
            // WeChatClient 已按需求 3.14 归一为 INVITE_QRCODE_FAILED，原样抛出（保留其 message）。
            log.warn("微信凭证刷新失败，保留原缓存：code={}, message={}", ex.getCode(), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("微信凭证刷新抛出未预期异常，保留原缓存", ex);
            throw ApiException.inviteQrCodeFailed("获取微信接口凭证失败，请稍后重试");
        }

        if (fetched == null || fetched.accessToken() == null || fetched.accessToken().isBlank()) {
            log.warn("微信凭证刷新返回空凭证，保留原缓存");
            throw ApiException.inviteQrCodeFailed("获取微信接口凭证失败，请稍后重试");
        }
        if (fetched.expiresInSeconds() <= 0) {
            // 到期时刻算出来只会立刻过期，等于每个请求都去刷一次，直接按失败处理。
            log.warn("微信凭证刷新返回非正有效期 expiresIn={}，保留原缓存", fetched.expiresInSeconds());
            throw ApiException.inviteQrCodeFailed("获取微信接口凭证失败，请稍后重试");
        }

        long expiresAtMillis = clock.millis() + fetched.expiresInSeconds() * 1000L;
        cached = new Cached(fetched.accessToken(), expiresAtMillis);
        log.info("微信凭证已刷新，有效期 {} 秒", fetched.expiresInSeconds());
        return fetched.accessToken();
    }

    /** 缓存可用：存在、凭证非空、且剩余有效期不低于 300 秒（恰好 300 秒仍复用）。 */
    private boolean isUsable(Cached snapshot, long now) {
        return snapshot != null
                && snapshot.token() != null
                && !snapshot.token().isBlank()
                && snapshot.expiresAtMillis() - now >= REFRESH_THRESHOLD_MILLIS;
    }

    /** 仅供测试：预置缓存的凭证与到期时刻，免去为「命中缓存」分支跑一次刷新。 */
    void seedCache(String token, long expiresAtMillis) {
        this.cached = new Cached(token, expiresAtMillis);
    }

    /** 仅供测试：当前缓存的凭证，未缓存时为 {@code null}。 */
    String cachedToken() {
        Cached snapshot = cached;
        return snapshot == null ? null : snapshot.token();
    }

    /** 仅供测试：当前缓存的到期时刻（毫秒），未缓存时为 {@code -1}。 */
    long cachedExpiresAtMillis() {
        Cached snapshot = cached;
        return snapshot == null ? -1L : snapshot.expiresAtMillis();
    }
}
