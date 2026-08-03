package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link ClientIpResolver#resolveClientIp} 的单元测试（关联需求 8.6）。
 *
 * <p>来源 IP 的定义：反向代理追加在 {@code X-Forwarded-For} 末位的地址；该头缺失、为空白或末位去空白后为空时，
 * 回退 TCP 连接远端地址；客户端自带的前序取值不得影响结果。</p>
 */
class ClientIpResolverTest {

    private MockHttpServletRequest requestWithRemoteAddr(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void 缺失XFF时回退远端地址() {
        MockHttpServletRequest request = requestWithRemoteAddr("10.0.0.7");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("10.0.0.7");
    }

    @Test
    void XFF为空白时回退远端地址() {
        MockHttpServletRequest request = requestWithRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "   ");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("10.0.0.7");
    }

    @Test
    void XFF单值时取该值() {
        MockHttpServletRequest request = requestWithRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "203.0.113.5");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void XFF单值两侧空白被去除() {
        MockHttpServletRequest request = requestWithRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "  203.0.113.5  ");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void XFF多值时取末位而非首位() {
        MockHttpServletRequest request = requestWithRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 198.51.100.2, 192.0.2.9");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("192.0.2.9");
    }

    @Test
    void XFF末位为空白时回退远端地址() {
        MockHttpServletRequest request = requestWithRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "203.0.113.5,   ");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("10.0.0.7");
    }

    @Test
    void 客户端伪造的前序取值不影响结果() {
        MockHttpServletRequest forged = requestWithRemoteAddr("10.0.0.7");
        forged.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 192.0.2.9");
        MockHttpServletRequest otherForged = requestWithRemoteAddr("10.0.0.7");
        otherForged.addHeader("X-Forwarded-For", "9.9.9.9, 192.0.2.9");

        String first = ClientIpResolver.resolveClientIp(forged);
        String second = ClientIpResolver.resolveClientIp(otherForged);

        assertThat(first).isEqualTo("192.0.2.9");
        assertThat(second).isEqualTo(first);
    }
}
