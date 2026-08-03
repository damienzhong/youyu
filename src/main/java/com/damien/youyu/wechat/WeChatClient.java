package com.damien.youyu.wechat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.damien.youyu.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 微信小程序服务端接口客户端。
 *
 * <p>封装三个接口：</p>
 * <ul>
 *   <li>{@code sns/jscode2session}：用小程序前端 {@code wx.login()} 拿到的一次性 {@code code}
 *       换取 {@code openid}（及可选 {@code unionid}）。session_key 属于敏感信息，本期不落库、
 *       不返回给前端，仅在换取时由微信下发后即丢弃。</li>
 *   <li>{@code cgi-bin/token}：接口调用凭证，超时上限 2000ms（需求 3.5）。
 *       <b>只允许 {@link WeChatAccessTokenProvider} 调用</b>。</li>
 *   <li>{@code wxa/getwxacodeunlimit}：无限量小程序码，超时上限 3000ms（需求 3.7）。</li>
 * </ul>
 *
 * <p>appid/secret 通过 {@code app.wechat.miniapp.*} 配置提供，生产环境务必用环境变量覆盖；
 * 域名通过 {@code app.wechat.api-base-url} 配置（缺省为微信正式域名），测试 profile 下务必改指
 * mock 或不可路由地址，避免 CI 外呼真实微信并消耗凭证额度。</p>
 *
 * <p><b>超时按接口分别设定</b>，因此内部持有三个 {@link RestClient}：它们共用同一 baseUrl，
 * 只在请求工厂的超时配置上不同。二维码请求 3000ms + 凭证请求 2000ms 正好用满需求 3.10 给
 * 二维码接口的 5000ms 处理预算。</p>
 */
@Component
public class WeChatClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatClient.class);

    private static final String JSCODE2SESSION_PATH =
            "/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code";

    private static final String TOKEN_PATH =
            "/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}";

    private static final String QRCODE_UNLIMITED_PATH = "/wxa/getwxacodeunlimit?access_token={token}";

    /** 凭证接口超时上限（需求 3.5）。 */
    private static final int TOKEN_TIMEOUT_MILLIS = 2000;

    /** 小程序码接口超时上限（需求 3.7）。 */
    private static final int QRCODE_TIMEOUT_MILLIS = 3000;

    /** 解析微信「200 但返回 JSON 错误体」的响应；无状态，可静态复用。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final RestClient tokenRestClient;
    private final RestClient qrCodeRestClient;
    private final String appId;
    private final String appSecret;

    // 类中有两个构造器（另一个是测试用的注入 RestClient.Builder 版本），必须显式标注注入哪一个。
    @Autowired
    public WeChatClient(
            @Value("${app.wechat.miniapp.appid:}") String appId,
            @Value("${app.wechat.miniapp.secret:}") String appSecret,
            @Value("${app.wechat.api-base-url:https://api.weixin.qq.com}") String apiBaseUrl) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.restClient = RestClient.builder().baseUrl(apiBaseUrl).build();
        this.tokenRestClient = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .requestFactory(requestFactory(TOKEN_TIMEOUT_MILLIS))
                .build();
        this.qrCodeRestClient = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .requestFactory(requestFactory(QRCODE_TIMEOUT_MILLIS))
                .build();
    }

    /**
     * 仅供测试：用调用方给定的 {@link RestClient.Builder} 构造三个 {@link RestClient}，
     * 使 {@code MockRestServiceServer.bindTo(builder)} 能拦住全部请求（协议层解析测试）。
     *
     * <p>此路径不覆盖请求工厂（否则会顶掉 mock 装上去的那个），因此<b>不带超时配置</b>；
     * 超时属于生产构造器的职责，协议层测试用 mock 抛 IO 异常来模拟超时分支。</p>
     */
    WeChatClient(String appId, String appSecret, RestClient.Builder builder) {
        this.appId = appId;
        this.appSecret = appSecret;
        RestClient shared = builder.build();
        this.restClient = shared;
        this.tokenRestClient = shared;
        this.qrCodeRestClient = shared;
    }

    private static ClientHttpRequestFactory requestFactory(int timeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        return factory;
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

    /**
     * 获取微信接口调用凭证（{@code cgi-bin/token}），单次调用超时上限 2000 毫秒（需求 3.5）。
     *
     * <p><b>只允许 {@link WeChatAccessTokenProvider} 调用本方法</b>：同一 appid 的凭证全局唯一，
     * 多处各自获取会互相踢掉，表现为随机 {@code errcode=40001}。</p>
     *
     * @return 凭证与其剩余有效期
     * @throws ApiException INVITE_QRCODE_FAILED 配置缺失、非零 errcode、超时或调用抛异常（需求 3.14）；
     *                      微信返回非零 errcode 时为携带该错误码的 {@link WeChatApiException}
     */
    @SuppressWarnings("unchecked")
    public WxAccessToken fetchAccessToken() {
        requireMiniappConfigured();

        Map<String, Object> body;
        try {
            body = tokenRestClient.get()
                    .uri(TOKEN_PATH, appId, appSecret)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException ex) {
            // 超时、连接失败、非 2xx 状态一律归一为 INVITE_QRCODE_FAILED（需求 3.14）。
            log.warn("请求微信凭证接口失败：{}", ex.toString());
            throw ApiException.inviteQrCodeFailed("获取微信接口凭证失败，请稍后重试");
        }

        if (body == null || body.isEmpty()) {
            log.warn("微信凭证接口返回空响应体");
            throw ApiException.inviteQrCodeFailed("获取微信接口凭证失败，请稍后重试");
        }

        throwIfWeChatError(body, "微信凭证接口");

        Object accessToken = body.get("access_token");
        if (accessToken == null || String.valueOf(accessToken).isBlank()) {
            log.warn("微信凭证接口未返回 access_token");
            throw ApiException.inviteQrCodeFailed("获取微信接口凭证失败，请稍后重试");
        }
        long expiresIn = parseLong(body.get("expires_in"));
        if (expiresIn <= 0) {
            log.warn("微信凭证接口返回非正 expires_in={}", body.get("expires_in"));
            throw ApiException.inviteQrCodeFailed("获取微信接口凭证失败，请稍后重试");
        }
        return new WxAccessToken(String.valueOf(accessToken), expiresIn);
    }

    /**
     * 生成无限量小程序码（{@code wxa/getwxacodeunlimit}），单次调用超时上限 3000 毫秒（需求 3.7）。
     *
     * <p>该接口成功时返回图片字节流、失败时返回 JSON 且 HTTP 状态<b>仍为 200</b>，因此不能只看状态码。
     * 判定规则（需求 3.7）：响应 {@code Content-Type} 以 {@code image/} 开头且首字节不是 {@code '{'}
     * → 图片字节；否则按 JSON 解析 {@code errcode}/{@code errmsg}，记一条含微信错误码的 WARN 日志后抛
     * {@link WeChatApiException}（错误码只进日志，不透传客户端）。首字节判定是对「Content-Type 写着
     * image 但实际是 JSON 错误体」这类畸形响应的兜底。</p>
     *
     * @param token 由 {@link WeChatAccessTokenProvider} 提供的接口调用凭证
     * @param scene 小程序码携带的场景值（本 spec 传邀请码）
     * @param page  扫码后进入的页面路径（不以 {@code /} 开头）
     * @param width 图片边长（像素）
     * @return PNG 图片字节
     * @throws ApiException INVITE_QRCODE_FAILED 配置缺失、凭证为空、非零 errcode、超时或调用抛异常；
     *                      微信返回非零 errcode 时为携带该错误码的 {@link WeChatApiException}，
     *                      调用方可据 {@link WeChatApiException#isInvalidCredential()} 判定
     *                      {@code 40001} 并强制刷新凭证后重试一次（任务 4.3）
     */
    public byte[] fetchUnlimitedQrCode(String token, String scene, String page, int width) {
        requireMiniappConfigured();
        if (token == null || token.isBlank()) {
            throw ApiException.inviteQrCodeFailed("微信接口凭证为空");
        }

        // LinkedHashMap 而非 Map.of：微信接口对字段顺序不敏感，但固定顺序让抓包与日志比对更直观。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scene", scene);
        payload.put("page", page);
        payload.put("width", width);

        ResponseEntity<byte[]> response;
        try {
            response = qrCodeRestClient.post()
                    .uri(QRCODE_UNLIMITED_PATH, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(byte[].class);
        } catch (RuntimeException ex) {
            log.warn("请求微信小程序码接口失败：{}", ex.toString());
            throw ApiException.inviteQrCodeFailed("邀请二维码暂时不可用，请稍后重试");
        }

        byte[] bytes = response.getBody();
        MediaType contentType = response.getHeaders().getContentType();
        boolean declaredImage = contentType != null && "image".equalsIgnoreCase(contentType.getType());

        if (bytes == null || bytes.length == 0) {
            log.warn("微信小程序码接口返回空响应体，Content-Type={}", contentType);
            throw ApiException.inviteQrCodeFailed("邀请二维码暂时不可用，请稍后重试");
        }
        if (declaredImage && bytes[0] != '{') {
            return bytes;
        }

        // 走到这里：要么明确是 JSON 错误体，要么是「声明 image 但内容是 JSON」的畸形响应。
        throw parseWeChatError(bytes, contentType);
    }

    /** 把微信 200 + JSON 错误体解析为携带 errcode 的异常，并记一条含错误码的 WARN 日志（需求 3.7）。 */
    @SuppressWarnings("unchecked")
    private WeChatApiException parseWeChatError(byte[] bytes, MediaType contentType) {
        Map<String, Object> body;
        try {
            body = OBJECT_MAPPER.readValue(bytes, Map.class);
        } catch (Exception ex) {
            log.warn("微信小程序码接口返回无法解析的响应体，Content-Type={}, 长度={}",
                    contentType, bytes.length);
            return new WeChatApiException(0, null, "邀请二维码暂时不可用，请稍后重试");
        }
        int errcode = (int) parseLong(body.get("errcode"));
        String errmsg = body.get("errmsg") == null ? null : String.valueOf(body.get("errmsg"));
        log.warn("微信小程序码接口返回错误：errcode={}, errmsg={}", errcode, errmsg);
        return new WeChatApiException(errcode, errmsg, "邀请二维码暂时不可用，请稍后重试");
    }

    /** JSON 响应体含非零 {@code errcode} 时记日志并抛出携带该错误码的异常。 */
    private void throwIfWeChatError(Map<String, Object> body, String what) {
        Object errcode = body.get("errcode");
        if (errcode == null || parseLong(errcode) == 0L) {
            return;
        }
        String errmsg = body.get("errmsg") == null ? null : String.valueOf(body.get("errmsg"));
        log.warn("{}返回错误：errcode={}, errmsg={}", what, errcode, errmsg);
        throw new WeChatApiException((int) parseLong(errcode), errmsg, "获取微信接口凭证失败，请稍后重试");
    }

    /** 宽松解析微信返回的数值字段（可能是 Integer/Long/String）；无法解析时返回 0。 */
    private static long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /** appid/secret 去空白为空时先于任何网络调用失败（需求 3.6）。 */
    private void requireMiniappConfigured() {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw ApiException.inviteQrCodeFailed("服务端未配置微信小程序 appid/secret");
        }
    }
}
