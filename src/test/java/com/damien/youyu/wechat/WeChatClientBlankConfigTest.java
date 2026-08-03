package com.damien.youyu.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.damien.youyu.error.ApiException;

/**
 * 微信 mock 三层中的<b>第三层</b>：以空 appid/secret 直接构造 {@link WeChatClient}，断言零网络调用
 * （需求 3.6）。
 *
 * <p>另两层分别是 Mockito mock {@code WeChatClient}（服务层分支）与 {@link MockRestServiceServer}
 * 绑 {@code RestClient}（协议层解析，见 {@link WeChatClientResponseTest}）。本层要锁死的是一条
 * 更靠前的不变式：<b>配置缺失时连一次 TCP 连接都不该发起</b>——`requireMiniappConfigured()`
 * 必须先于任何 {@code restClient} 调用执行。若有人把这个前置校验挪到网络调用之后（或干脆删掉），
 * 生产上就会拿着空 appid 反复外呼微信，既拿不到结果又白耗连接与日志。</p>
 *
 * <p>「零网络调用」用两道互补的证据同时判定：</p>
 * <ol>
 *   <li>{@link MockRestServiceServer} <b>不注册任何期望</b>：一旦真的发出请求，mock 会因
 *       「No further requests expected」直接失败；{@code server.verify()} 再确认请求数为 0。</li>
 *   <li>builder 上装一个计数拦截器：拦截器位于请求工厂之前，因此即使将来 mock 的行为变了，
 *       计数仍能如实反映「有没有走到发请求这一步」。</li>
 * </ol>
 */
class WeChatClientBlankConfigTest {

    private static final String TOKEN = "tk-123";
    private static final String SCENE = "ABCD2345";
    private static final String PAGE = "pages/invitelanding/invitelanding";

    /** 走到发请求这一步的次数；配置缺失时必须恒为 0。 */
    private final AtomicInteger httpCalls = new AtomicInteger();

    private MockRestServiceServer server;

    /**
     * 用给定的（可能为空白的）appid/secret 构造客户端，其 {@link RestClient} 全部绑到一台
     * 无任何期望的 mock 上，并叠一层计数拦截器。
     */
    private WeChatClient clientWith(String appId, String appSecret) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://wechat.invalid")
                .requestInterceptor((request, body, execution) -> {
                    httpCalls.incrementAndGet();
                    return execution.execute(request, body);
                });
        // 刻意不注册任何 expect(...)：任何一次真实请求都会让 mock 立即失败。
        server = MockRestServiceServer.bindTo(builder).build();
        return new WeChatClient(appId, appSecret, builder);
    }

    /** 断言零网络调用：计数为 0，且 mock 确认没有收到过请求。 */
    private void assertNoHttpCall() {
        assertThat(httpCalls.get()).as("配置缺失时不得发起任何网络调用").isZero();
        server.verify();
    }

    /**
     * appid / secret 的各种「去空白后为空」组合下，凭证接口先于任何网络调用抛
     * {@code INVITE_QRCODE_FAILED}（需求 3.6）。
     *
     * <p>null 无法直接写进 {@code @CsvSource}，用 {@code nullValues} 把字面量 {@code NULL} 映射为 null。</p>
     */
    @ParameterizedTest(name = "appid=[{0}], secret=[{1}]")
    @CsvSource(nullValues = "NULL", value = {
            "NULL, NULL",
            "NULL, wxsecret",
            "wxappid, NULL",
            "'', ''",
            "'', wxsecret",
            "wxappid, ''",
            "'   ', wxsecret",
            "wxappid, '   '",
            "'\t', '\t'",
    })
    void fetchAccessTokenFailsBeforeAnyHttpCall(String appId, String appSecret) {
        WeChatClient client = clientWith(appId, appSecret);

        assertThatThrownBy(client::fetchAccessToken)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVITE_QRCODE_FAILED"))
                // 内部配置细节不该暴露微信错误码语义，但要能让运维一眼看出是配置问题
                .hasMessageContaining("appid");

        assertNoHttpCall();
    }

    /** 小程序码接口同样在配置缺失时零网络调用，且不返回任何图片数据（需求 3.6）。 */
    @ParameterizedTest(name = "appid=[{0}], secret=[{1}]")
    @CsvSource(nullValues = "NULL", value = {
            "NULL, NULL",
            "'', ''",
            "'   ', '   '",
            "wxappid, ''",
            "'', wxsecret",
    })
    void fetchUnlimitedQrCodeFailsBeforeAnyHttpCall(String appId, String appSecret) {
        WeChatClient client = clientWith(appId, appSecret);

        assertThatThrownBy(() -> client.fetchUnlimitedQrCode(TOKEN, SCENE, PAGE, 430))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVITE_QRCODE_FAILED"));

        assertNoHttpCall();
    }

    /**
     * 配置校验必须<b>先于</b>入参校验：即使 token 也是空的，错误码仍是配置缺失那条，
     * 且依然零网络调用。锁死这个顺序，是因为一旦 token 校验被提到配置校验之前，
     * 「配置缺失」这条更根本的失败就会被掩盖成「凭证为空」，排障时容易查错方向。
     */
    @Test
    void configCheckPrecedesTokenCheckAndStillMakesNoHttpCall() {
        WeChatClient client = clientWith("", "");

        assertThatThrownBy(() -> client.fetchUnlimitedQrCode("  ", SCENE, PAGE, 430))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("appid");

        assertNoHttpCall();
    }

    /**
     * 登录接口（{@code sns/jscode2session}）的同构行为：配置缺失时抛 {@code WX_LOGIN_FAILED}
     * 且零网络调用。错误码与二维码链路不同（登录失败有自己的语义），但「不外呼」这条一致。
     */
    @Test
    void jscode2sessionFailsBeforeAnyHttpCall() {
        WeChatClient client = clientWith(" ", null);

        assertThatThrownBy(() -> client.jscode2session("any-code"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("WX_LOGIN_FAILED"));

        assertNoHttpCall();
    }

    /**
     * 反向对照：配置齐全时确实会走到发请求这一步。没有这条，上面所有「零调用」断言都可能因为
     * 客户端根本发不出请求（拦截器没装上、mock 绑错 builder 等）而假通过。
     * mock 无期望，请求必然失败，这里只关心计数被加过。
     */
    @Test
    void configuredClientDoesReachHttpLayer() {
        WeChatClient client = clientWith("wxappid", "wxsecret");

        assertThatThrownBy(client::fetchAccessToken).isInstanceOf(Throwable.class);

        assertThat(httpCalls.get()).as("配置齐全时应当真的发起请求").isPositive();
    }
}
