package com.damien.youyu.wechat;

/**
 * 微信 {@code cgi-bin/token} 接口返回的接口调用凭证。
 *
 * <p>刻意把 {@code expires_in} 一并带出来，而不是只返回 token 字符串：
 * {@link WeChatAccessTokenProvider} 需要用它算出到期时刻，才能实现「剩余有效期不足 300 秒才刷新」
 * 这条判定（需求 3.5）。若只拿到 token，提供者就只能硬编码假设 7200 秒，微信一旦调整有效期
 * 就会出现「本地以为还有效、微信侧已过期」的随机 {@code errcode=40001}。</p>
 *
 * @param accessToken      接口调用凭证
 * @param expiresInSeconds 凭证剩余有效期（秒），微信当前下发 7200
 */
public record WxAccessToken(String accessToken, long expiresInSeconds) {
}
