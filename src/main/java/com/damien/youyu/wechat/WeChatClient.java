package com.damien.youyu.wechat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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

    /** 一次性订阅消息发送接口（custom-reminder 需求 6.1）。 */
    private static final String SUBSCRIBE_SEND_PATH = "/cgi-bin/message/subscribe/send?access_token={token}";

    /** 凭证接口超时上限（需求 3.5）。 */
    private static final int TOKEN_TIMEOUT_MILLIS = 2000;

    /** 小程序码接口超时上限（需求 3.7）。 */
    private static final int QRCODE_TIMEOUT_MILLIS = 3000;

    /** 订阅消息接口超时上限（custom-reminder 需求 6.1）。 */
    private static final int SUBSCRIBE_TIMEOUT_MILLIS = 3000;

    /**
     * 订阅消息本地失败的哨兵 errcode（非微信下发）：模板未配置、凭证为空、网络异常或响应无法解析时返回。
     * 取负值以与任何真实微信 errcode（≥0）区分；调用方（ReminderDispatchService，任务 6.1）按
     * 「非零即失败」记 {@code FAILED}，安全降级、不消耗额度、不影响任何主路径（需求 6.4）。
     */
    static final int ERRCODE_LOCAL_FAILURE = -1;

    /** 解析微信「200 但返回 JSON 错误体」的响应；无状态，可静态复用。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final RestClient tokenRestClient;
    private final RestClient qrCodeRestClient;
    private final RestClient subscribeRestClient;
    private final String appId;
    private final String appSecret;

    /** 订阅消息模板 id（{@code app.wechat.subscribe.reminder-template-id}）；未配置时发送安全降级为失败。 */
    private final String subscribeTemplateId;

    /**
     * 预算提醒订阅消息模板 id（{@code app.wechat.subscribe.budget-template-id}）；<b>独立于</b>记账提醒模板。
     * 未配置时 {@link #sendBudgetSubscribeMessage} 安全降级为失败（返回哨兵），既不外呼微信、也不影响主路径
     * （subscribe-message-reminders 需求 4.8）。
     */
    private final String budgetTemplateId;

    /** 订阅消息模板中承载提醒文案的字段名（{@code app.wechat.subscribe.message-field}，如 {@code thing1}）。 */
    private final String subscribeMessageField;

    /**
     * 凭证提供者：识别 {@code errcode=40001}（凭证无效）后强制刷新并重试一次（需求 11.3）。
     * <b>复用全项目唯一的凭证网关，不新建第二套凭证获取</b>。以 {@link Lazy} 注入打破与
     * {@link WeChatAccessTokenProvider} 的构造期循环依赖（后者构造需要本类）。测试构造器下可为 {@code null}。
     */
    private final WeChatAccessTokenProvider accessTokenProvider;

    // 类中有两个构造器（另一个是测试用的注入 RestClient.Builder 版本），必须显式标注注入哪一个。
    @Autowired
    public WeChatClient(
            @Value("${app.wechat.miniapp.appid:}") String appId,
            @Value("${app.wechat.miniapp.secret:}") String appSecret,
            @Value("${app.wechat.api-base-url:https://api.weixin.qq.com}") String apiBaseUrl,
            @Value("${app.wechat.subscribe.reminder-template-id:}") String subscribeTemplateId,
            @Value("${app.wechat.subscribe.budget-template-id:}") String budgetTemplateId,
            @Value("${app.wechat.subscribe.message-field:thing1}") String subscribeMessageField,
            @Lazy WeChatAccessTokenProvider accessTokenProvider) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.subscribeTemplateId = subscribeTemplateId;
        this.budgetTemplateId = budgetTemplateId;
        this.subscribeMessageField = subscribeMessageField;
        this.accessTokenProvider = accessTokenProvider;
        this.restClient = RestClient.builder().baseUrl(apiBaseUrl).build();
        this.tokenRestClient = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .requestFactory(requestFactory(TOKEN_TIMEOUT_MILLIS))
                .build();
        this.qrCodeRestClient = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .requestFactory(requestFactory(QRCODE_TIMEOUT_MILLIS))
                .build();
        this.subscribeRestClient = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .requestFactory(requestFactory(SUBSCRIBE_TIMEOUT_MILLIS))
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
        this(appId, appSecret, builder, null, "thing1", null);
    }

    /**
     * 仅供测试：在共享 {@link RestClient.Builder} 的基础上另行注入订阅消息模板配置与凭证提供者，
     * 使 {@code sendSubscribeMessage} 的协议层与 40001 重试路径可被 {@code MockRestServiceServer} 覆盖
     * （任务 5.2）。同样不覆盖请求工厂（保留 mock 的那个），因此不带超时配置。
     */
    WeChatClient(String appId, String appSecret, RestClient.Builder builder,
            String subscribeTemplateId, String subscribeMessageField,
            WeChatAccessTokenProvider accessTokenProvider) {
        this(appId, appSecret, builder, subscribeTemplateId, null, subscribeMessageField, accessTokenProvider);
    }

    /**
     * 仅供测试：在共享 {@link RestClient.Builder} 的基础上另行注入记账提醒与<b>预算提醒</b>两个订阅模板配置
     * 与凭证提供者，使 {@code sendBudgetSubscribeMessage} 的协议层与 40001 重试路径可被
     * {@code MockRestServiceServer} 覆盖。同样不覆盖请求工厂（保留 mock 的那个），因此不带超时配置。
     */
    WeChatClient(String appId, String appSecret, RestClient.Builder builder,
            String subscribeTemplateId, String budgetTemplateId, String subscribeMessageField,
            WeChatAccessTokenProvider accessTokenProvider) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.subscribeTemplateId = subscribeTemplateId;
        this.budgetTemplateId = budgetTemplateId;
        this.subscribeMessageField = subscribeMessageField;
        this.accessTokenProvider = accessTokenProvider;
        RestClient shared = builder.build();
        this.restClient = shared;
        this.tokenRestClient = shared;
        this.qrCodeRestClient = shared;
        this.subscribeRestClient = shared;
    }

    /**
     * 构造带连接 / 读超时的请求工厂。
     *
     * <p><b>刻意用 {@link JdkClientHttpRequestFactory}（{@code java.net.http.HttpClient}）而非
     * {@code SimpleClientHttpRequestFactory}（JDK {@code HttpURLConnection}）</b>：后者对
     * {@code POST} 请求会带上一组微信新版网关不接受的默认请求头（如异常的 {@code Accept} /
     * {@code User-Agent}），导致 {@code wxa/getwxacodeunlimit}、{@code message/subscribe/send} 等
     * <b>POST 接口</b>在业务层之前就被网关以 {@code HTTP 412 Precondition Failed}（空响应体）挡回——
     * 表现为「同机 curl 正常 200、Java 端 412」。GET 接口（{@code jscode2session} / {@code cgi-bin/token}）
     * 不受影响，故此前登录正常、只有小程序码 / 订阅消息失败。{@code java.net.http.HttpClient} 发送干净的
     * 最小请求头，与 curl 一致，可正常拿到响应。强制 {@code HTTP/1.1} 与 curl 对齐、避免协议协商差异。</p>
     */
    private static ClientHttpRequestFactory requestFactory(int timeoutMillis) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(timeoutMillis));
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

        // 微信 sns/jscode2session 的响应 Content-Type 为 text/plain（并非 application/json），
        // 用 .body(Map.class) 会因找不到匹配 text/plain 的 JSON 转换器而抛异常。故与小程序码路径
        // 同策略：先取原始字符串，再用 OBJECT_MAPPER 解析，绕开按 content-type 选转换器的坑。
        Map<String, Object> body;
        try {
            String raw = restClient.get()
                    .uri(JSCODE2SESSION_PATH, appId, appSecret, code)
                    .retrieve()
                    .body(String.class);
            body = (raw == null || raw.isBlank()) ? null : OBJECT_MAPPER.readValue(raw, Map.class);
        } catch (Exception ex) {
            log.warn("请求微信登录接口失败：{}", ex.toString());
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

        // cgi-bin/token 同样返回 text/plain，故与登录路径同策略：取原始字符串后用 OBJECT_MAPPER 解析，
        // 避免 .body(Map.class) 因 content-type 不是 application/json 而找不到转换器抛异常。
        Map<String, Object> body;
        try {
            String raw = tokenRestClient.get()
                    .uri(TOKEN_PATH, appId, appSecret)
                    .retrieve()
                    .body(String.class);
            body = (raw == null || raw.isBlank()) ? null : OBJECT_MAPPER.readValue(raw, Map.class);
        } catch (Exception ex) {
            // 超时、连接失败、非 2xx 状态、解析失败一律归一为 INVITE_QRCODE_FAILED（需求 3.14）。
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

    /**
     * 下发一次性订阅消息（{@code cgi-bin/message/subscribe/send}），单次调用超时上限 3000 毫秒。
     * 返回微信 {@code errcode}（{@code 0} 表示成功）；本地失败（模板未配置 / 凭证为空 / 网络异常 /
     * 响应无法解析）返回哨兵 {@link #ERRCODE_LOCAL_FAILURE}，由调用方（任务 6.1）统一按「非零即失败」
     * 记 {@code FAILED}。本方法<b>不抛异常</b>，任何故障都就地吞掉并记告警日志，绝不冒泡到调度或主路径。
     *
     * <p>识别 {@code errcode=40001}（凭证无效，通常是同 appid 的 token 被别处刷新踢掉）时，经
     * {@link WeChatAccessTokenProvider#forceRefresh(String)} 强制刷新凭证后重试一次并返回重试结果——
     * <b>复用全项目唯一的凭证网关，不新建第二套凭证获取</b>（需求 11.3）。</p>
     *
     * <p>模板 id（{@code app.wechat.subscribe.reminder-template-id}）由运营在微信后台申请提醒模板后填入；
     * 未配置时安全降级为失败（返回哨兵），既不外呼微信、也不影响记账等任何主路径。请求体为
     * {@code {touser, template_id, data:{<message-field>:{value:<文案>}}}}，文案字段名由
     * {@code app.wechat.subscribe.message-field} 配置（缺省 {@code thing1}）。</p>
     *
     * @param accessToken 由 {@link WeChatAccessTokenProvider} 提供的接口调用凭证
     * @param openid      收件用户的 {@code wx_openid}
     * @param message     提醒文案（两条之一，均在模板字段长度限制内）
     * @return 微信 {@code errcode}（{@code 0} 成功），或本地失败时的 {@link #ERRCODE_LOCAL_FAILURE}
     */
    public int sendSubscribeMessage(String accessToken, String openid, String message) {
        return sendWithTemplate(subscribeTemplateId, "app.wechat.subscribe.reminder-template-id",
                accessToken, openid, message);
    }

    /**
     * 下发一次性<b>预算提醒</b>订阅消息，使用<b>独立于记账提醒</b>的预算提醒模板
     * （{@code app.wechat.subscribe.budget-template-id}），语义与 {@link #sendSubscribeMessage} 完全一致：
     * 返回微信 {@code errcode}（{@code 0} 成功），本地失败（模板未配置 / 凭证为空 / 网络异常 / 响应无法解析）
     * 返回哨兵 {@link #ERRCODE_LOCAL_FAILURE}；识别 {@code errcode=40001} 后经
     * {@link WeChatAccessTokenProvider#forceRefresh(String)} 强制刷新凭证并重试一次；<b>不抛异常</b>。
     *
     * <p>复用全项目唯一的凭证网关（{@code WeChatAccessTokenProvider}），不新建第二套凭证获取
     * （subscribe-message-reminders 需求 4.7）。模板 id 未配置时安全降级为失败，既不外呼微信、也不影响
     * 记账等任何主路径（需求 4.8）。请求体结构与文案字段名与记账提醒一致，仅模板 id 不同。</p>
     *
     * @param accessToken 由 {@link WeChatAccessTokenProvider} 提供的接口调用凭证
     * @param openid      收件用户的 {@code wx_openid}
     * @param message     预算提醒文案（在模板字段长度限制内）
     * @return 微信 {@code errcode}（{@code 0} 成功），或本地失败时的 {@link #ERRCODE_LOCAL_FAILURE}
     */
    public int sendBudgetSubscribeMessage(String accessToken, String openid, String message) {
        return sendWithTemplate(budgetTemplateId, "app.wechat.subscribe.budget-template-id",
                accessToken, openid, message);
    }

    /**
     * 用指定模板下发一次性订阅消息（记账提醒与预算提醒共用此实现，仅模板 id 不同）。
     * 模板未配置 / 凭证为空一律安全降级为 {@link #ERRCODE_LOCAL_FAILURE}；{@code errcode=40001} 强制刷新重试一次。
     */
    private int sendWithTemplate(String templateId, String templateConfigKey,
            String accessToken, String openid, String message) {
        if (templateId == null || templateId.isBlank()) {
            log.warn("未配置 {}，订阅消息发送安全降级为失败", templateConfigKey);
            return ERRCODE_LOCAL_FAILURE;
        }
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("订阅消息接口凭证为空，发送安全降级为失败");
            return ERRCODE_LOCAL_FAILURE;
        }

        int errcode = postSubscribeMessage(templateId, accessToken, openid, message);
        if (errcode == WeChatApiException.ERRCODE_INVALID_CREDENTIAL) {
            log.warn("微信订阅消息接口返回 errcode=40001 凭证无效，强制刷新凭证后重试一次");
            String freshToken;
            try {
                freshToken = accessTokenProvider.forceRefresh(accessToken);
            } catch (RuntimeException ex) {
                log.warn("强制刷新微信凭证失败，订阅消息发送安全降级为失败：{}", ex.toString());
                return ERRCODE_LOCAL_FAILURE;
            }
            errcode = postSubscribeMessage(templateId, freshToken, openid, message);
        }
        return errcode;
    }

    /**
     * 执行一次 {@code subscribeMessage.send} 调用并解析 {@code errcode}。
     * 网络异常、空响应体一律归一为 {@link #ERRCODE_LOCAL_FAILURE}（记告警日志，不抛异常）。
     */
    @SuppressWarnings("unchecked")
    private int postSubscribeMessage(String templateId, String accessToken, String openid, String message) {
        // LinkedHashMap 而非 Map.of：固定字段顺序让抓包与日志比对更直观。
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("value", message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(subscribeMessageField, field);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("touser", openid);
        payload.put("template_id", templateId);
        payload.put("data", data);

        Map<String, Object> body;
        try {
            body = subscribeRestClient.post()
                    .uri(SUBSCRIBE_SEND_PATH, accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException ex) {
            log.warn("请求微信订阅消息接口失败：{}", ex.toString());
            return ERRCODE_LOCAL_FAILURE;
        }

        if (body == null || body.isEmpty()) {
            log.warn("微信订阅消息接口返回空响应体");
            return ERRCODE_LOCAL_FAILURE;
        }

        int errcode = (int) parseLong(body.get("errcode"));
        if (errcode != 0) {
            Object errmsg = body.get("errmsg");
            log.warn("微信订阅消息接口返回错误：errcode={}, errmsg={}", errcode, errmsg);
        }
        return errcode;
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
