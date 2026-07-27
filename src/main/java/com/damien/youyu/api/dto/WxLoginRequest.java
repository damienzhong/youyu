package com.damien.youyu.api.dto;

/**
 * 微信小程序登录请求。
 *
 * <p>{@code code} 为小程序前端调用 {@code wx.login()} 得到的一次性凭证，服务端据此向微信
 * 换取 openid 并签发本系统 JWT。</p>
 */
public record WxLoginRequest(String code) {
}
