package com.damien.youyu.api.dto;

/**
 * 微信小程序登录请求。
 *
 * <p>{@code code} 为小程序前端调用 {@code wx.login()} 得到的一次性凭证，服务端据此向微信
 * 换取 openid 并签发本系统 JWT。</p>
 *
 * <p>{@code inviteCode} 为可选邀请码，取值长度上限 64（需求 5.1）。与
 * {@link EmailLoginRequest#inviteCode()} 同理，这里<b>不加</b> {@code @Size(max = 64)}
 * 之类的校验：超长取值应当以未绑定原因 {@code CODE_NOT_FOUND} 完成登录而非让登录失败
 * （需求 5.6），规整与判定统一交给服务层。</p>
 */
public record WxLoginRequest(String code, String inviteCode) {
}
