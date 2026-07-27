package com.damien.youyu.wechat;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.damien.youyu.error.ApiException;

/**
 * 微信小程序服务端接口客户端。
 *
 * <p>目前仅封装 {@code sns/jscode2session}：用小程序前端 {@code wx.login()} 拿到的
 * 一次性 {@code code} 换取 {@code openid}（及可选 {@code unionid}）。session_key 属于
 * 敏感信息，本期不落库、不返回给前端，仅在换取时由微信下发后即丢弃。</p>
 *
 * <p>appid/secret 通过 {@code app.wechat.miniapp.*} 配置提供，生产环境务必用环境变量覆盖。</p>
 */
@Component
public class WeChatClient {

    private static final String JSCODE2SESSION_PATH =
            "/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code";

    private final RestClient restClient;
    private final String appId;
    private final String appSecret;

    public WeChatClient(
            @Value("${app.wechat.miniapp.appid:}") String appId,
            @Value("${app.wechat.miniapp.secret:}") String appSecret,
            @Value("${app.wechat.api-base-url:https://api.weixin.qq.com}") String apiBaseUrl) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.restClient = RestClient.builder().baseUrl(apiBaseUrl).build();
    }

    /**
     * 用一次性 code 换取会话标识。
     *
     * @param code 小程序 {@code wx.login()} 返回的一次性凭证
     * @return 包含 openid（必有）与 unionid（可选）的会话
     * @throws ApiException WX_LOGIN_FAILED 当配置缺失、网络异常或微信返回错误码时
     */
    @SuppressWarnings("unchecked")
    public WxSession jscode2session(String code) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw ApiException.wxLoginFailed("服务端未配置微信小程序 appid/secret");
        }

        Map<String, Object> body;
        try {
            body = restClient.get()
                    .uri(JSCODE2SESSION_PATH, appId, appSecret, code)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException ex) {
            throw ApiException.wxLoginFailed("请求微信服务失败，请稍后重试");
        }

        if (body == null) {
            throw ApiException.wxLoginFailed("微信服务无响应，请稍后重试");
        }

        // 微信在出错时返回非零 errcode；成功时通常不含 errcode 或为 0。
        Object errcode = body.get("errcode");
        if (errcode != null && !"0".equals(String.valueOf(errcode))) {
            String errmsg = String.valueOf(body.getOrDefault("errmsg", "微信登录失败"));
            throw ApiException.wxLoginFailed("微信登录失败(" + errcode + "): " + errmsg);
        }

        Object openid = body.get("openid");
        if (openid == null || String.valueOf(openid).isBlank()) {
            throw ApiException.wxLoginFailed("微信未返回 openid，请重试");
        }

        Object unionid = body.get("unionid");
        return new WxSession(
                String.valueOf(openid),
                unionid == null ? null : String.valueOf(unionid));
    }
}
