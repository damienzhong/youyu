package com.damien.youyu.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.damien.youyu.error.ApiException;

/**
 * 当前会话用户上下文抽象。
 *
 * <p>统一从 Spring Security 的 {@code SecurityContext} 读取由 {@link JwtAuthenticationFilter}
 * 注入的 {@link CurrentUserPrincipal}。业务服务通过本组件获取当前 {@code userId}，从而：</p>
 * <ul>
 *   <li>写入路径由服务端强制以会话 userId 覆盖实体 user_id（忽略请求体传入值，需求 2.2）；</li>
 *   <li>读取/修改路径固定携带 {@code user_id = 当前用户} 条件，越权访问返回 403/404 且不泄漏内容
 *       （需求 2.3、2.4）。</li>
 * </ul>
 *
 * <p>将「取当前用户」收敛到单一入口，降低各业务模块「忘记按 user_id 过滤」的风险。</p>
 */
@Component
public class CurrentUser {

    /**
     * 返回当前会话用户主键；若无有效会话则抛出未认证异常。
     *
     * <p>业务服务应优先使用本方法：正常情况下请求已由过滤链鉴权，缺失即代表编程错误或
     * 未受保护路径被误用。</p>
     */
    public Long requireUserId() {
        return principal().userId();
    }

    /** 返回当前会话用户主键（如存在）。 */
    public Optional<Long> userId() {
        return principalOpt().map(CurrentUserPrincipal::userId);
    }

    /** 返回当前会话角色编码（如存在）。 */
    public Optional<String> role() {
        return principalOpt().map(CurrentUserPrincipal::role);
    }

    /** 是否存在已认证会话。 */
    public boolean isAuthenticated() {
        return principalOpt().isPresent();
    }

    private CurrentUserPrincipal principal() {
        return principalOpt().orElseThrow(ApiException::unauthenticated);
    }

    private Optional<CurrentUserPrincipal> principalOpt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof CurrentUserPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }
}
