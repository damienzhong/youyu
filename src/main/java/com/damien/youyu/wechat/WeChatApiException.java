package com.damien.youyu.wechat;

import org.springframework.http.HttpStatus;

import com.damien.youyu.error.ApiException;

/**
 * 微信接口返回非零 {@code errcode} 时抛出的异常：对客户端与 {@code INVITE_QRCODE_FAILED} 完全等价，
 * 对服务端内部则额外携带微信错误码，供调用方按错误码分流处置。
 *
 * <p><b>为什么需要一个子类</b>：需求 3.7 要求微信 {@code errcode} 只进服务端日志、不透传客户端，
 * 因此不能把错误码塞进 message 或错误体。但 {@code errcode=40001}（凭证无效）必须能被上层识别——
 * 它通常意味着手上的 {@code access_token} 被别处刷新踢掉，唯一正确的处置是强制刷新凭证并重试一次
 * （任务 4.3）。若只抛 {@link ApiException}，上层就只能去匹配 message 文本，那是一条一改文案就
 * 静默失效的脆弱判定。</p>
 *
 * <p>本类的 {@code code}/{@code status}/{@code message} 与
 * {@link ApiException#inviteQrCodeFailed(String)} 保持一致（{@code INVITE_QRCODE_FAILED} + 502），
 * {@code GlobalExceptionHandler} 按 {@link ApiException} 统一处理（含其子类），
 * 因此客户端看到的响应体与普通二维码失败没有任何差别。</p>
 */
public class WeChatApiException extends ApiException {

    /** 凭证无效或已过期：通常是同 appid 的 token 被别处刷新踢掉，可强制刷新后重试一次（任务 4.3）。 */
    public static final int ERRCODE_INVALID_CREDENTIAL = 40001;

    /** 小程序码生成量超出限额：多实例凭证/额度失控的监控信号（任务 4.3）。 */
    public static final int ERRCODE_QUOTA_EXCEEDED = 45009;

    /** 微信返回的原始错误码；仅供服务端内部分流与日志使用。 */
    private final int errcode;

    /** 微信返回的原始错误描述；仅供服务端日志使用。 */
    private final String errmsg;

    public WeChatApiException(int errcode, String errmsg, String clientMessage) {
        super("INVITE_QRCODE_FAILED", HttpStatus.BAD_GATEWAY,
                clientMessage == null ? "邀请二维码暂时不可用，请稍后重试" : clientMessage, null);
        this.errcode = errcode;
        this.errmsg = errmsg;
    }

    /** 微信错误码；无法从响应中解析出错误码时为 0。 */
    public int getErrcode() {
        return errcode;
    }

    /** 微信错误描述，可能为 {@code null}。 */
    public String getErrmsg() {
        return errmsg;
    }

    /** 是否为「凭证无效」：调用方据此强制刷新凭证并重试一次（任务 4.3）。 */
    public boolean isInvalidCredential() {
        return errcode == ERRCODE_INVALID_CREDENTIAL;
    }
}
