package com.damien.youyu.service;

import java.util.Base64;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.damien.youyu.error.ApiException;
import com.damien.youyu.wechat.WeChatQrCodeGateway;

/**
 * 邀请二维码（微信小程序码）服务（需求 3.1、3.2、3.4、3.7、3.9、3.12、3.13、8.8）。
 *
 * <h2>处理顺序是本类的核心约束，不可调整</h2>
 * <ol>
 *   <li><b>惰性补齐邀请码</b>（需求 3.12）：{@code invite_code} 为 NULL / 空白时先生成并持久化，
 *       小程序码的 {@code scene} 一律取补齐<b>之后</b>的取值。</li>
 *   <li><b>缓存命中判定</b>（需求 3.4）：命中且写入时刻距今不足 7 天 → 直接返回，
 *       <b>不调微信凭证接口、不调小程序码接口、不计数、不被限流</b>。</li>
 *   <li><b>限流判定</b>（需求 3.9、8.8）：仅未命中缓存时才消耗额度（同 userId / 24 小时 / 20 次）。</li>
 *   <li><b>取凭证 + 调小程序码接口</b>：整套动作（含 {@code errcode=40001} 的强制刷新与单次重试）
 *       由 {@link WeChatQrCodeGateway#fetchQrCode} 封成<b>一次</b>调用。</li>
 *   <li><b>写缓存</b>（需求 3.13）：仅微信调用成功时写入；失败不写。</li>
 * </ol>
 *
 * <h2>为什么限流必须在缓存命中判定之后</h2>
 * <p>这个额度计的是<b>打到微信的次数</b>，不是「用户看二维码的次数」。若把限流前置，一个只是反复打开
 * 邀请好友页的用户会被<b>自己的缓存命中请求</b>耗尽额度——那些请求根本没碰微信，拦下来纯属误伤，
 * 而真正需要保护的外部接口调用量一点没减少。反过来，微信调用失败也照样计入额度（需求 3.7）：
 * 额度是对外部接口的保护，失败的调用同样消耗了对方的配额。</p>
 *
 * <h2>两条实现约定</h2>
 * <ul>
 *   <li><b>一次未命中只调 {@code fetchQrCode} 一次、只扣 1 次额度</b>：网关内部为 {@code 40001}
 *       重试时会打两次微信，但那是同一次业务请求，<b>不得</b>因此额外扣额度（见网关的调用契约）。
 *       也不要绕过网关自己注入 {@code WeChatAccessTokenProvider} + {@code WeChatClient}，
 *       那等于把 40001 的重试逻辑复制一份到业务层。</li>
 *   <li><b>base64 现场编码</b>：缓存里存的是 PNG 原始字节（省掉 4/3 的膨胀，见
 *       {@link InviteQrCodeCache}），编码在本类响应组装时做。返回值<b>不含</b>
 *       {@code data:image/png;base64,} 前缀（需求 3.1），前缀由前端按需自行拼接。</li>
 * </ul>
 */
@Service
public class InviteQrCodeService {

    private static final Logger log = LoggerFactory.getLogger(InviteQrCodeService.class);

    /** 小程序码扫码后进入的页面路径（需求 3.2）。微信要求不以 {@code /} 开头。 */
    static final String QRCODE_PAGE = "pages/invitelanding/invitelanding";

    /** 小程序码边长（像素，需求 3.2）。 */
    static final int QRCODE_WIDTH = 430;

    private final InviteService inviteService;

    private final InviteQrCodeCache qrCodeCache;

    private final InviteRateLimiter rateLimiter;

    private final WeChatQrCodeGateway qrCodeGateway;

    public InviteQrCodeService(InviteService inviteService,
                              InviteQrCodeCache qrCodeCache,
                              InviteRateLimiter rateLimiter,
                              WeChatQrCodeGateway qrCodeGateway) {
        this.inviteService = inviteService;
        this.qrCodeCache = qrCodeCache;
        this.rateLimiter = rateLimiter;
        this.qrCodeGateway = qrCodeGateway;
    }

    /**
     * 取当前用户邀请二维码的 base64（不含 data URI 前缀，需求 3.1）。
     *
     * <p>刻意<b>不加</b> {@code @Transactional}：本方法要在一个事务里做几秒钟的外部 HTTP 调用是不可接受的
     * （连接会被占着直到微信返回）。需要事务的只有邀请码惰性补齐那一步，它由
     * {@link InviteService#requireInviteCode} 自己的 {@code @Transactional} 界定，
     * 在网络调用<b>开始之前</b>就已提交。</p>
     *
     * @param userId 令牌用户主键
     * @return PNG 图片的 base64 字符串，非空
     * @throws ApiException {@code UNAUTHENTICATED}（令牌用户已不存在）/
     *                      {@code INVITE_CODE_GEN_FAILED}（邀请码补齐失败）/
     *                      {@code INVITE_RATE_LIMITED}（24 小时内未命中缓存已达 20 次）/
     *                      {@code INVITE_QRCODE_FAILED}（配置缺失、凭证或小程序码接口失败）
     */
    public String getQrCodeBase64(Long userId) {
        // 1. 惰性补齐：scene 必须用补齐后的邀请码（需求 3.12）。
        String inviteCode = inviteService.requireInviteCode(userId);

        // 2. 缓存命中判定先于限流判定（见类级说明，需求 3.4、3.9、8.8）。
        Optional<byte[]> cached = qrCodeCache.get(inviteCode);
        if (cached.isPresent()) {
            return encodeBase64(cached.get());
        }

        // 3. 未命中才消耗额度；达上限时拒绝且不计数、不碰微信（需求 3.9）。
        if (!rateLimiter.tryAcquireQrCodeMiss(userId)) {
            log.warn("邀请二维码未命中缓存的请求已达 24 小时额度上限，拒绝本次请求：userId={}", userId);
            throw ApiException.inviteRateLimited();
        }

        // 4. 一次未命中只调网关一次；凭证与 40001 重试都在网关内部完成。
        //    失败时网关抛 INVITE_QRCODE_FAILED（已记含微信错误码的日志），额度已扣、缓存不写（需求 3.7）。
        byte[] png = qrCodeGateway.fetchQrCode(inviteCode, QRCODE_PAGE, QRCODE_WIDTH);

        // 5. 仅成功时写缓存（需求 3.13）。
        qrCodeCache.put(inviteCode, png);
        return encodeBase64(png);
    }

    /** PNG 字节 → 标准 base64（无换行、无 data URI 前缀，需求 3.1）。 */
    private String encodeBase64(byte[] png) {
        return Base64.getEncoder().encodeToString(png);
    }
}
