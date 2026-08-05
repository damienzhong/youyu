package com.damien.youyu.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link WeChatClient#sendSubscribeMessage(String, String, String)} 的协议层测试（任务 5.2，需求 6.1、6.4）。
 *
 * <p>沿用 {@link WeChatClientResponseTest} 的 {@code MockRestServiceServer.bindTo(RestClient.builder())}
 * 手法，另经包内测试构造器注入订阅模板配置与一个 Mockito 版 {@link WeChatAccessTokenProvider}，
 * 以覆盖订阅消息发送的四条分支：</p>
 * <ul>
 *   <li>{@code errcode=0} → 返回 0（发送成功），且请求体形如
 *       {@code {touser, template_id, data:{<field>:{value:<文案>}}}}；</li>
 *   <li>非零 {@code errcode}（如 {@code 43101}）→ 原样透传该错误码，不重试；</li>
 *   <li>{@code errcode=40001}（凭证无效）→ 触发一次 {@code forceRefresh} 后以新凭证重试，返回重试结果；</li>
 *   <li>网络超时/异常 → 归一为哨兵 {@link WeChatClient#ERRCODE_LOCAL_FAILURE}（{@code -1}），不抛异常；</li>
 *   <li>模板 id 未配置 → 直接安全降级为 {@link WeChatClient#ERRCODE_LOCAL_FAILURE}，零网络调用。</li>
 * </ul>
 *
 * <p>baseUrl 指向纯虚构域名，且全部请求均被 mock 拦下，绝不外呼真实 {@code api.weixin.qq.com}。</p>
 */
class WeChatClientSubscribeMessageTest {

    private static final String BASE_URL = "https://wechat.invalid";
    private static final String APP_ID = "wxappid";
    private static final String APP_SECRET = "wxsecret";
    private static final String TEMPLATE_ID = "tmpl-abc-123";
    private static final String MESSAGE_FIELD = "thing1";

    private static final String OPENID = "o-user-1";
    private static final String MESSAGE = "今天还没记账哦~";

    private static final String SEND_URL_PREFIX =
            BASE_URL + "/cgi-bin/message/subscribe/send?access_token=";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private WeChatAccessTokenProvider accessTokenProvider;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        accessTokenProvider = mock(WeChatAccessTokenProvider.class);
    }

    /** 用给定模板 id 构造被测客户端（其余配置固定）。 */
    private WeChatClient clientWithTemplate(String templateId) {
        return new WeChatClient(APP_ID, APP_SECRET, builder, templateId, MESSAGE_FIELD, accessTokenProvider);
    }

    /** errcode=0：返回 0，并按约定发出 touser/template_id/data 三段请求体。 */
    @Test
    void sendSubscribeMessageReturnsZeroOnSuccess() {
        server.expect(requestTo(SEND_URL_PREFIX + "tk-123"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.touser").value(OPENID))
                .andExpect(jsonPath("$.template_id").value(TEMPLATE_ID))
                .andExpect(jsonPath("$.data." + MESSAGE_FIELD + ".value").value(MESSAGE))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        int errcode = clientWithTemplate(TEMPLATE_ID).sendSubscribeMessage("tk-123", OPENID, MESSAGE);

        assertThat(errcode).isZero();
        verifyNoInteractions(accessTokenProvider);
        server.verify();
    }

    /** 非零 errcode（如 43101 用户拒收/无额度）：原样透传，不刷新凭证、不重试。 */
    @Test
    void sendSubscribeMessagePassesThroughNonZeroErrcode() {
        server.expect(requestTo(SEND_URL_PREFIX + "tk-123"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"errcode\":43101,\"errmsg\":\"user refuse\"}",
                        MediaType.APPLICATION_JSON));

        int errcode = clientWithTemplate(TEMPLATE_ID).sendSubscribeMessage("tk-123", OPENID, MESSAGE);

        assertThat(errcode).isEqualTo(43101);
        verifyNoInteractions(accessTokenProvider);
        server.verify();
    }

    /** errcode=40001：强制刷新凭证一次后用新凭证重试，返回重试结果（此处 0）。 */
    @Test
    void sendSubscribeMessageRefreshesAndRetriesOnceOn40001() {
        when(accessTokenProvider.forceRefresh("stale-token")).thenReturn("fresh-token");

        // 第一次用旧凭证 → 40001；第二次用刷新后的新凭证 → 成功。
        server.expect(requestTo(SEND_URL_PREFIX + "stale-token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEND_URL_PREFIX + "fresh-token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.touser").value(OPENID))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        int errcode = clientWithTemplate(TEMPLATE_ID).sendSubscribeMessage("stale-token", OPENID, MESSAGE);

        assertThat(errcode).isZero();
        verify(accessTokenProvider, times(1)).forceRefresh("stale-token");
        server.verify();
    }

    /** errcode=40001 且重试后仍非零：只刷新一次，返回重试的错误码。 */
    @Test
    void sendSubscribeMessageRetriesOnlyOnceThenReturnsRetryErrcode() {
        when(accessTokenProvider.forceRefresh("stale-token")).thenReturn("fresh-token");

        server.expect(requestTo(SEND_URL_PREFIX + "stale-token"))
                .andRespond(withSuccess("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEND_URL_PREFIX + "fresh-token"))
                .andRespond(withSuccess("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}",
                        MediaType.APPLICATION_JSON));

        int errcode = clientWithTemplate(TEMPLATE_ID).sendSubscribeMessage("stale-token", OPENID, MESSAGE);

        assertThat(errcode).isEqualTo(40001);
        verify(accessTokenProvider, times(1)).forceRefresh("stale-token");
        server.verify();
    }

    /** 网络异常（超时用 IOException 模拟）→ 归一为 ERRCODE_LOCAL_FAILURE，不抛异常。 */
    @Test
    void sendSubscribeMessageMapsIoFailureToLocalFailure() {
        server.expect(requestTo(SEND_URL_PREFIX + "tk-123"))
                .andRespond(request -> {
                    throw new IOException("Read timed out");
                });

        int errcode = clientWithTemplate(TEMPLATE_ID).sendSubscribeMessage("tk-123", OPENID, MESSAGE);

        assertThat(errcode).isEqualTo(WeChatClient.ERRCODE_LOCAL_FAILURE);
        verifyNoInteractions(accessTokenProvider);
        server.verify();
    }

    /** 空响应体 → 归一为 ERRCODE_LOCAL_FAILURE。 */
    @Test
    void sendSubscribeMessageMapsEmptyBodyToLocalFailure() {
        server.expect(requestTo(SEND_URL_PREFIX + "tk-123"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        int errcode = clientWithTemplate(TEMPLATE_ID).sendSubscribeMessage("tk-123", OPENID, MESSAGE);

        assertThat(errcode).isEqualTo(WeChatClient.ERRCODE_LOCAL_FAILURE);
        server.verify();
    }

    /** 模板 id 未配置（null）→ 直接安全降级为 ERRCODE_LOCAL_FAILURE，零网络调用、不取凭证。 */
    @Test
    void sendSubscribeMessageFailsWhenTemplateIdMissing() {
        // 不注册任何 expect：一旦发出请求 mock 会立即失败。
        int errcode = clientWithTemplate(null).sendSubscribeMessage("tk-123", OPENID, MESSAGE);

        assertThat(errcode).isEqualTo(WeChatClient.ERRCODE_LOCAL_FAILURE);
        verifyNoInteractions(accessTokenProvider);
        server.verify();
    }

    /** 模板 id 为空白字符串 → 同样安全降级，零网络调用。 */
    @Test
    void sendSubscribeMessageFailsWhenTemplateIdBlank() {
        int errcode = clientWithTemplate("   ").sendSubscribeMessage("tk-123", OPENID, MESSAGE);

        assertThat(errcode).isEqualTo(WeChatClient.ERRCODE_LOCAL_FAILURE);
        verifyNoInteractions(accessTokenProvider);
        server.verify();
    }

    /** 凭证为空 → 安全降级为 ERRCODE_LOCAL_FAILURE，零网络调用。 */
    @Test
    void sendSubscribeMessageFailsWhenAccessTokenBlank() {
        int errcode = clientWithTemplate(TEMPLATE_ID).sendSubscribeMessage("  ", OPENID, MESSAGE);

        assertThat(errcode).isEqualTo(WeChatClient.ERRCODE_LOCAL_FAILURE);
        verifyNoInteractions(accessTokenProvider);
        server.verify();
    }

    /** 40001 后强制刷新凭证抛异常 → 安全降级为 ERRCODE_LOCAL_FAILURE，只发一次请求。 */
    @Test
    void sendSubscribeMessageMapsRefreshFailureToLocalFailure() {
        when(accessTokenProvider.forceRefresh(anyString()))
                .thenThrow(new RuntimeException("refresh failed"));

        server.expect(requestTo(SEND_URL_PREFIX + "stale-token"))
                .andRespond(withSuccess("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}",
                        MediaType.APPLICATION_JSON));

        int errcode = clientWithTemplate(TEMPLATE_ID).sendSubscribeMessage("stale-token", OPENID, MESSAGE);

        assertThat(errcode).isEqualTo(WeChatClient.ERRCODE_LOCAL_FAILURE);
        verify(accessTokenProvider, times(1)).forceRefresh("stale-token");
        verify(accessTokenProvider, never()).getToken();
        server.verify();
    }
}
