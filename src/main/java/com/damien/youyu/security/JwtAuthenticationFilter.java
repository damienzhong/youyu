package com.damien.youyu.security;

import java.io.IOException;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT 鉴权过滤器：解析 {@code Authorization: Bearer <token>} 并把身份写入 {@code SecurityContext}。
 *
 * <p>无状态鉴权流程：</p>
 * <ol>
 *   <li>请求无 Bearer 令牌或令牌无效/过期时，不设置任何认证信息，直接放行；后续授权规则会拒绝
 *       受保护端点并交由 {@link RestAuthenticationEntryPoint} 返回 401（需求 2.5）。</li>
 *   <li>令牌校验通过时，以 {@link CurrentUserPrincipal}（含 userId/role）构造认证对象并写入上下文，
 *       供 {@link CurrentUser} 及各业务服务读取当前用户（需求 2.2、2.3、2.4）。</li>
 * </ol>
 *
 * <p>本过滤器每请求执行一次，且不做数据库查询：会话身份完全来自签名令牌，保持无状态。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token, request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parseClaims(token);
            Long userId = Long.valueOf(claims.getSubject());
            String role = claims.get("role", String.class);

            CurrentUserPrincipal principal = new CurrentUserPrincipal(userId, role);
            var authorities = role == null
                    ? List.<SimpleGrantedAuthority>of()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            // 令牌无效/过期/格式错误：保持未认证状态，交由授权规则与入口点处理（401）。
            SecurityContextHolder.clearContext();
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
