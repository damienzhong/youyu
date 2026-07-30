package com.damien.youyu.api.dto;

/**
 * 绑定微信请求（需求 6）。
 *
 * <p>{@code POST /api/me/bind-wechat} 为令牌保护端点。服务端用一次性 {@code code}
 * 换取 openid，经冲突检查后将该微信身份写入当前账号。</p>
 *
 * <ul>
 *   <li>{@code code}：微信小程序 {@code wx.login} 返回的一次性授权码。</li>
 * </ul>
 */
public record BindWechatRequest(String code) {
}
