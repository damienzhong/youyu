package com.damien.youyu.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.damien.youyu.error.ApiException;

/**
 * 小程序码调用网关：把「取凭证 → 调小程序码接口 → 撞上 {@code errcode=40001} 时强制刷新凭证并重试一次」
 * 这一整套动作封成<b>一次调用</b>（需求 3.5、3.7、3.14，任务 4.3）。
 *
 * <h2>调用契约（业务层务必遵守）</h2>
 * <ul>
 *   <li>业务层（{@code InviteQrCodeService}，任务 5.9）在<b>一次未命中缓存的请求里只调用本方法一次</b>，
 *       并按「未命中缓存计 1 次额度」自行扣 1 次限流额度。本方法内部可能打两次微信小程序码接口
 *       （首发 + 40001 后的一次重试），但那是<b>同一次业务请求</b>，<b>不得</b>因此额外扣额度。</li>
 *   <li>业务层<b>不要</b>自己注入 {@link WeChatAccessTokenProvider} 再调
 *       {@link WeChatClient#fetchUnlimitedQrCode}：那样就得在业务层重写一遍 40001 的重试，
 *       而重试逻辑一旦散落两处必然走偏（要么忘了重试，要么重试时顺手多扣一次额度）。</li>
 *   <li>本方法要么返回<b>非空 PNG 字节</b>，要么抛 {@code INVITE_QRCODE_FAILED}；不返回 null。
 *       业务层照需求 3.7 处置：计入未命中额度、不写缓存、记日志。</li>
 * </ul>
 *
 * <h2>为什么重试放在这一层</h2>
 * <p>重试要同时用到「凭证提供者」和「小程序码接口」两个协作方，放进其中任何一方都会造成反向依赖：
 * 放 {@link WeChatAccessTokenProvider} 里它就得知道小程序码接口（凭证提供者本该与用途无关，
 * 将来订阅消息、内容安全同样要用它）；放 {@link WeChatClient} 里它就得持有凭证缓存，
 * 而客户端是无状态协议层。放业务层则会把「限流额度」和「微信重试」两件事搅在一起。
 * 因此单独一个薄网关，是唯一不制造耦合的位置。</p>
 *
 * <h2>为什么只重试一次、且只对 40001 重试</h2>
 * <p>{@code 40001} 的典型成因是手上的凭证被别处（另一个实例、另一段自行取 token 的代码）刷新踢掉，
 * 强制刷新一次即可自愈，属于确定性可恢复故障。其余错误码（如 {@code 41030} 页面不存在、
 * {@code 45009} 超出限额）重试只会白打一次微信、白耗一次接口额度，因此一律直接失败。
 * 重试次数硬编码为 1：{@code 40001} 若刷新后仍出现，说明有别的进程在持续踢掉凭证，
 * 继续重试是把偶发故障放大成刷新风暴，并撞穿需求 3.10 给的 5000ms 处理预算
 * （2000ms 凭证 + 3000ms 小程序码已经占满，重试路径靠命中凭证缓存才不超时）。</p>
 *
 * <h2>监控信号</h2>
 * <p>{@code 40001} 与 {@code 45009} 各记一条带固定前缀 {@value #SIGNAL_PREFIX} 的日志，供日志告警
 * 按前缀抓取（任务 4.3）。这两个码是本设计两处已知风险的唯一外部可观测信号：</p>
 * <ul>
 *   <li>{@code 40001} 频发 → 多实例各自持有凭证缓存互相踢掉，或有人绕过
 *       {@link WeChatAccessTokenProvider} 自行调用了 {@code cgi-bin/token}。</li>
 *   <li>{@code 45009} 出现 → 小程序码日调用量已触及微信侧限额，说明进程内图片缓存或
 *       用户级 24 小时限流没起到预期作用（多实例下缓存不共享、额度按 N 倍消耗）。</li>
 * </ul>
 */
@Component
public class WeChatQrCodeGateway {

    private static final Logger log = LoggerFactory.getLogger(WeChatQrCodeGateway.class);

    /** 监控信号日志前缀：告警规则按这个前缀抓取，改动前先改告警规则。 */
    static final String SIGNAL_PREFIX = "[WECHAT_ERRCODE_SIGNAL]";

    private final WeChatClient weChatClient;

    private final WeChatAccessTokenProvider tokenProvider;

    public WeChatQrCodeGateway(WeChatClient weChatClient, WeChatAccessTokenProvider tokenProvider) {
        this.weChatClient = weChatClient;
        this.tokenProvider = tokenProvider;
    }

    /**
     * 取一张小程序码的 PNG 字节。凭证获取、40001 的强制刷新与单次重试都在方法内完成。
     *
     * @param scene 场景值（本 spec 传邀请码）
     * @param page  扫码后进入的页面路径（不以 {@code /} 开头）
     * @param width 图片边长（像素）
     * @return 非空 PNG 字节
     * @throws ApiException INVITE_QRCODE_FAILED 配置缺失、凭证获取失败、小程序码接口非零 errcode /
     *                      超时 / 抛异常，或 {@code 40001} 重试后仍失败
     */
    public byte[] fetchQrCode(String scene, String page, int width) {
        String token = tokenProvider.getToken();
        try {
            return weChatClient.fetchUnlimitedQrCode(token, scene, page, width);
        } catch (WeChatApiException ex) {
            logSignal(ex, false);
            if (!ex.isInvalidCredential()) {
                throw ex;
            }
            return retryOnceWithFreshToken(token, scene, page, width, ex);
        }
    }

    /**
     * {@code 40001} 后的唯一一次重试：强制刷新凭证再打一次。
     *
     * <p>刷新本身失败时抛出刷新的异常（同为 {@code INVITE_QRCODE_FAILED}，且凭证提供者已按需求 3.14
     * 保留原缓存）；重试调用失败时抛出重试那次的异常，不再有第三次尝试。</p>
     */
    private byte[] retryOnceWithFreshToken(String staleToken, String scene, String page, int width,
            WeChatApiException firstFailure) {
        log.warn("{} errcode=40001 凭证无效，强制刷新凭证后重试一次：scene={}, errmsg={}",
                SIGNAL_PREFIX, scene, firstFailure.getErrmsg());
        String freshToken = tokenProvider.forceRefresh(staleToken);
        try {
            return weChatClient.fetchUnlimitedQrCode(freshToken, scene, page, width);
        } catch (WeChatApiException retryEx) {
            logSignal(retryEx, true);
            throw retryEx;
        }
    }

    /**
     * 记录 {@code 40001} / {@code 45009} 的监控信号。
     *
     * <p>{@code 40001} 在首发失败时不在此处记（由重试路径记一条含「将重试」语义的日志，避免同一次
     * 请求出现两条含义相同的告警）；重试后仍 {@code 40001} 才是真正需要告警的持续性故障。</p>
     */
    private void logSignal(WeChatApiException ex, boolean afterRetry) {
        int errcode = ex.getErrcode();
        if (errcode == WeChatApiException.ERRCODE_QUOTA_EXCEEDED) {
            // 45009：微信侧日限额已触顶，重试无用，直接告警。
            log.error("{} errcode=45009 小程序码调用超出微信限额，errmsg={}", SIGNAL_PREFIX, ex.getErrmsg());
            return;
        }
        if (errcode == WeChatApiException.ERRCODE_INVALID_CREDENTIAL && afterRetry) {
            log.error("{} errcode=40001 强制刷新凭证后重试仍失败，疑有其它进程在持续刷新同 appid 凭证，errmsg={}",
                    SIGNAL_PREFIX, ex.getErrmsg());
        }
    }
}
