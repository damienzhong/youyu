package com.damien.youyu.api.dto;

/**
 * 登录请求体：账号标识与口令。
 */
public record LoginRequest(String username, String password) {
}
