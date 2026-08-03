package com.damien.youyu.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 回归守卫：<b>测试 profile 下微信接口域名不得指向真实 {@code api.weixin.qq.com}</b>（需求 3.6、任务 4.6）。
 *
 * <p>为什么需要一条测试来看配置文件：{@code src/test/resources/application.yml} 会整体<b>遮蔽</b>
 * 主配置（两者都是 {@code classpath:/application.yml}，测试类目录在类路径上更靠前），所以主配置里
 * 那句 {@code api-base-url: ${YOUYU_WX_API_BASE:https://api.weixin.qq.com}} 在测试中根本不生效；
 * 一旦测试配置里漏掉这一项，{@link WeChatClient} 就会退回 {@code @Value} 的缺省值，也就是真实微信域名。
 * 那时任何忘记装 mock 的测试都会真的外呼微信：CI 成败被外部服务绑定、凭证额度被白耗，
 * 而且失败现象（超时）与代码缺陷难以区分。</p>
 *
 * <p>这条断言刻意不启动 Spring 上下文——它要检查的正是「上下文启动时会读到什么」，
 * 直接解析类路径上的 {@code application.yml} 更直接、也更快。</p>
 */
class WeChatTestProfileIsolationTest {

    /** 真实微信域名，测试环境的禁字。 */
    private static final String REAL_WECHAT_HOST = "api.weixin.qq.com";

    private static final String API_BASE_URL_KEY = "app.wechat.api-base-url";
    private static final String APPID_KEY = "app.wechat.miniapp.appid";
    private static final String SECRET_KEY = "app.wechat.miniapp.secret";

    /** 解析测试类路径上生效的那份 {@code application.yml}。 */
    private static PropertySource<?> testYaml() throws IOException {
        ClassPathResource resource = new ClassPathResource("application.yml");
        assertThat(resource.exists()).as("测试类路径上应有 application.yml").isTrue();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("test-application", resource);
        assertThat(sources).as("application.yml 应能解析出属性源").isNotEmpty();
        return sources.get(0);
    }

    /** {@code app.wechat.api-base-url} 必须显式配置，且不得指向真实微信域名。 */
    @Test
    void testYamlPinsWeChatApiBaseUrlAwayFromRealWeChat() throws IOException {
        Object value = testYaml().getProperty(API_BASE_URL_KEY);

        assertThat(value)
                .as("测试配置必须显式给出 %s，否则会退回 @Value 缺省值（真实微信域名）", API_BASE_URL_KEY)
                .isNotNull();

        String baseUrl = String.valueOf(value).trim();
        assertThat(baseUrl).isNotEmpty();
        assertThat(baseUrl).doesNotContain(REAL_WECHAT_HOST);

        // 逐字符比对之外再按 URI 的 host 判一次：防「api.weixin.qq.com.example.com」这类绕过写法。
        String host = URI.create(baseUrl).getHost();
        assertThat(host).as("解析不出 host 的 baseUrl 无法判定是否安全").isNotNull();
        assertThat(host).isNotEqualTo(REAL_WECHAT_HOST);
        assertThat(host)
                .as("测试域名应不可路由（.invalid 保留域名或本机地址），确保漏掉 mock 时立刻失败而非外呼")
                .satisfiesAnyOf(
                        h -> assertThat(h).endsWith(".invalid"),
                        h -> assertThat(h).isEqualTo("localhost"),
                        h -> assertThat(h).isEqualTo("127.0.0.1"));
    }

    /**
     * 第二重保险：测试配置里的 appid/secret 必须是空值。
     * 这样即使有人把域名改回真实微信，{@link WeChatClient} 也会在网络调用之前因配置缺失失败
     * （见 {@link WeChatClientBlankConfigTest}），仍然不会有请求打到微信。
     */
    @Test
    void testYamlLeavesMiniappCredentialsBlank() throws IOException {
        PropertySource<?> yaml = testYaml();

        for (String key : List.of(APPID_KEY, SECRET_KEY)) {
            Object value = yaml.getProperty(key);
            String text = value == null ? "" : String.valueOf(value).trim();
            assertThat(text)
                    .as("测试配置不得携带可用的 %s（真凭证会让漏掉 mock 的测试真正调通微信）", key)
                    .isEmpty();
        }
    }
}
