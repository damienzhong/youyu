package com.damien.youyu.security;

/**
 * 已认证会话的用户主体，作为 Spring Security {@code Authentication} 的 principal 存放。
 *
 * <p>由 {@link JwtAuthenticationFilter} 从校验通过的 JWT 声明构造并写入 {@code SecurityContext}，
 * 服务层通过 {@link CurrentUser} 读取其中的 {@code userId} 以实现多租户隔离（写入强制覆盖
 * user_id、读取按 user_id 过滤）。</p>
 *
 * @param userId 当前会话用户主键
 * @param role   角色编码（user/admin），本期仅存储不做门控
 */
public record CurrentUserPrincipal(Long userId, String role) {
}
