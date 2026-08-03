package com.damien.youyu.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 邀请系统公开接口的来源 IP 解析：取 {@code X-Forwarded-For} 的<strong>末位</strong>。
 *
 * <p>关联需求 8.6。用于 {@code GET /api/invite/inviter} 的 IP 限流键（见 {@code InviteRateLimiter}）。</p>
 *
 * <p><strong>为什么取末位，而既有 {@code AuthController.resolveClientIp}（发码限流）取首位？</strong>
 * 这是刻意的区别，不是疏漏：</p>
 * <ul>
 *   <li>{@code X-Forwarded-For} 的<strong>首位由客户端自己填写</strong>，可以每次请求换一个伪造值。
 *       用首位当限流键，攻击者只要轮换该头就能把额度刷成无限，等于没有限流。</li>
 *   <li><strong>末位是 nginx 追加的</strong>：{@code deploy/nginx-youyu.conf} 配置了
 *       {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;}，该指令把真实对端
 *       {@code $remote_addr} 追加在客户端原有取值之后，因此末位是客户端无法控制的地址。</li>
 * </ul>
 *
 * <p><strong>不要顺手把这里"统一"回首位。</strong>发码限流沿用首位是既有行为，本 spec 不改动它；
 * 但新代码不复制这个模式。若哪天觉得"两处取法不一致、统一一下更整齐"，请记住统一的方向只能是
 * 让发码限流也取末位，反向统一会静默废掉邀请码枚举防护的第一道防线。</p>
 *
 * <p>当前部署只有一层 nginx，所以"末位"就是可信对端。若将来引入多层代理，这里要改成
 * "从右往左跳过 N 个可信代理"，而不是继续取末位。</p>
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    /**
     * 解析来源 IP：{@code X-Forwarded-For} 末位去空白后非空则取末位；
     * 该头缺失、为空白或末位去空白后为空时，回退 {@link HttpServletRequest#getRemoteAddr()}。
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }
}
