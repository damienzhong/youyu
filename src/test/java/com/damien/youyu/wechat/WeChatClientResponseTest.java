package com.damien.youyu.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.damien.youyu.error.ApiException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link WeChatClient} 的协议层响应解析测试（关联需求 3.2、3.7）。
 *
 * <p>用 {@code MockRestServiceServer.bindTo(RestClient.builder())} 绑定 {@link WeChatClient}
 * 内部的 {@link RestClient}（包内可见的测试构造器让三个客户端共用同一个 builder，因此一台 mock
 * 就能拦住全部请求），构造<b>真实的 HTTP 响应</b>，覆盖只有在协议层才能复现的情形：</p>
 * <ul>
 *   <li>{@code image/png} 字节流 → 原样返回字节；</li>
 *   <li>{@code application/json} + 非零 {@code errcode}（HTTP 仍为 200）→ {@code INVITE_QRCODE_FAILED}，
 *       且告警日志中含微信错误码；</li>
 *   <li>声明 {@code image/png} 但首字节为 <code>'{'</code> 的畸形响应 → 同样按错误处理；</li>
 *   <li>超时分支：mock 抛 {@link IOException}（测试构造器刻意不装超时配置，用 IO 异常模拟）。</li>
 * </ul>
 *
 * <p>两个方法（{@code fetchAccessToken} 与 {@code fetchUnlimitedQrCode}）均覆盖。
 * baseUrl 指向一个纯虚构域名，且全部请求都被 mock 拦下，绝不外呼真实 {@code api.weixin.qq.com}。</p>
 */
class WeChatClientResponseTest {

    private static final String BASE_URL = "https://wechat.invalid";
    private static final String APP_ID = "wxappid";
    private static final String APP_SECRET = "wxsecret";
    private static final String TOKEN_URL =
            BASE_URL + "/cgi-bin/token?grant_type=client_credential&appid=" + APP_ID + "&secret=" + APP_SECRET;
    private static final String QRCODE_URL = BASE_URL + "/wxa/getwxacodeunlimit?access_token=tk-123";

    private MockRestServiceServer server;
    private WeChatClient client;

    private Logger clientLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WeChatClient(APP_ID, APP_SECRET, builder);

        clientLogger = (Logger) LoggerFactory.getLogger(WeChatClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
        clientLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    /** 全部 WARN 日志拼成一段文本，便于断言「日志含微信错误码」。 */
    private String warnLogText() {
        List<ILoggingEvent> events = logAppender.list;
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : events) {
            if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                sb.append(event.getFormattedMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    // ---- cgi-bin/token（需求 3.5、3.14 的协议层部分）----

    /** 正常 JSON 响应解析出凭证与剩余有效期。 */
    @Test
    void fetchAccessTokenParsesJsonBody() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"access_token\":\"tk-123\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));

        WxAccessToken token = client.fetchAccessToken();

        assertThat(token.accessToken()).isEqualTo("tk-123");
        assertThat(token.expiresInSeconds()).isEqualTo(7200L);
        server.verify();
    }

    /** HTTP 200 + JSON 错误体：抛携带 errcode 的异常，客户端错误码为 INVITE_QRCODE_FAILED，日志含错误码。 */
    @Test
    void fetchAccessTokenMapsNonZeroErrcodeToInviteQrCodeFailed() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchAccessToken())
                .isInstanceOf(WeChatApiException.class)
                .satisfies(ex -> {
                    WeChatApiException wx = (WeChatApiException) ex;
                    assertThat(wx.getErrcode()).isEqualTo(40001);
                    assertThat(wx.getCode()).isEqualTo("INVITE_QRCODE_FAILED");
                    assertThat(wx.isInvalidCredential()).isTrue();
                    // 错误码只进日志，不透传客户端
                    assertThat(wx.getMessage()).doesNotContain("40001");
                });

        assertThat(warnLogText()).contains("40001");
        server.verify();
    }

    /** 超时分支：底层 IO 异常归一为 INVITE_QRCODE_FAILED，且不是携带 errcode 的子类语义。 */
    @Test
    void fetchAccessTokenMapsIoFailureToInviteQrCodeFailed() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(request -> {
                    throw new IOException("Read timed out");
                });

        assertThatThrownBy(() -> client.fetchAccessToken())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVITE_QRCODE_FAILED"));

        assertThat(warnLogText()).contains("请求微信凭证接口失败");
        server.verify();
    }

    // ---- wxa/getwxacodeunlimit（需求 3.2、3.7）----

    /** Content-Type: image/png → 原样返回 PNG 字节，并按约定发出 scene/page/width。 */
    @Test
    void fetchUnlimitedQrCodeReturnsImageBytes() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        server.expect(requestTo(QRCODE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.scene").value("ABCD2345"))
                .andExpect(jsonPath("$.page").value("pages/invitelanding/invitelanding"))
                .andExpect(jsonPath("$.width").value(430))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));

        byte[] bytes = client.fetchUnlimitedQrCode(
                "tk-123", "ABCD2345", "pages/invitelanding/invitelanding", 430);

        assertThat(bytes).isEqualTo(png);
        assertThat(warnLogText()).isEmpty();
        server.verify();
    }

    /** application/json + errcode=41030（HTTP 仍 200）→ INVITE_QRCODE_FAILED 且日志含 41030。 */
    @Test
    void fetchUnlimitedQrCodeMapsJsonErrcodeToInviteQrCodeFailed() {
        server.expect(requestTo(QRCODE_URL))
                .andRespond(withSuccess("{\"errcode\":41030,\"errmsg\":\"invalid page\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchUnlimitedQrCode(
                "tk-123", "ABCD2345", "pages/nope/nope", 430))
                .isInstanceOf(WeChatApiException.class)
                .satisfies(ex -> {
                    WeChatApiException wx = (WeChatApiException) ex;
                    assertThat(wx.getErrcode()).isEqualTo(41030);
                    assertThat(wx.getErrmsg()).isEqualTo("invalid page");
                    assertThat(wx.getCode()).isEqualTo("INVITE_QRCODE_FAILED");
                    assertThat(wx.isInvalidCredential()).isFalse();
                    assertThat(wx.getMessage()).doesNotContain("41030");
                });

        assertThat(warnLogText()).contains("41030");
        server.verify();
    }

    /** 畸形响应：Content-Type 写着 image/png，实际首字节为 '{' 的 JSON 错误体 → 同样按错误处理。 */
    @Test
    void fetchUnlimitedQrCodeTreatsJsonBodyDeclaredAsImageAsError() {
        byte[] jsonBytes = "{\"errcode\":41030,\"errmsg\":\"invalid page\"}"
                .getBytes(StandardCharsets.UTF_8);
        server.expect(requestTo(QRCODE_URL))
                .andRespond(withSuccess(jsonBytes, MediaType.IMAGE_PNG));

        assertThatThrownBy(() -> client.fetchUnlimitedQrCode(
                "tk-123", "ABCD2345", "pages/nope/nope", 430))
                .isInstanceOf(WeChatApiException.class)
                .satisfies(ex -> assertThat(((WeChatApiException) ex).getErrcode()).isEqualTo(41030));

        assertThat(warnLogText()).contains("41030");
        server.verify();
    }

    /** 超时分支：底层 IO 异常归一为 INVITE_QRCODE_FAILED，且响应体从未被当作图片返回。 */
    @Test
    void fetchUnlimitedQrCodeMapsIoFailureToInviteQrCodeFailed() {
        server.expect(requestTo(QRCODE_URL))
                .andRespond(request -> {
                    throw new IOException("Read timed out");
                });

        assertThatThrownBy(() -> client.fetchUnlimitedQrCode(
                "tk-123", "ABCD2345", "pages/invitelanding/invitelanding", 430))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVITE_QRCODE_FAILED"));

        assertThat(warnLogText()).contains("请求微信小程序码接口失败");
        server.verify();
    }
}
