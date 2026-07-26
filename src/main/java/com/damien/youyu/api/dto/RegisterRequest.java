package com.damien.youyu.api.dto;

/**
 * 注册请求体：账号标识与口令。
 *
 * <p>长度与必填校验在 {@link com.damien.youyu.service.AuthService} 中依据需求 1.1-1.4 执行，
 * 以便返回带 {@code field} 的统一错误体。</p>
 */
public record RegisterRequest(String username, String password) {
}
