package com.damien.youyu.api.dto;

/**
 * 解绑登录身份请求（需求 7）。
 *
 * <p>{@code POST /api/me/unbind} 为令牌保护端点。服务端在保底「至少一种登录方式」的前提下
 * 清除指定身份类型。</p>
 *
 * <ul>
 *   <li>{@code type}：待解绑身份类型，{@code "email"} 或 {@code "wechat"}（大小写不敏感）。</li>
 * </ul>
 */
public record UnbindRequest(String type) {
}
