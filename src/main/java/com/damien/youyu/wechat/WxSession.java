package com.damien.youyu.wechat;

/**
 * 微信 {@code jscode2session} 换取结果中我们需要持有的部分。
 *
 * <p>{@code openid} 为同一小程序内的稳定唯一标识；{@code unionid} 仅在小程序已绑定微信开放平台
 * 时下发，可能为空。session_key 属敏感数据，此处刻意不持有。</p>
 */
public record WxSession(String openid, String unionid) {
}
