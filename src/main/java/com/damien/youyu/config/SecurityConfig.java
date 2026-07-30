package com.damien.youyu.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.damien.youyu.security.JwtAuthenticationFilter;
import com.damien.youyu.security.RestAuthenticationEntryPoint;

/**
 * Spring Security 无状态过滤链配置。
 *
 * <p>核心策略：</p>
 * <ul>
 *   <li>放行邮箱验证码发送/登录、微信登录、注销等 {@code /api/auth/**} 端点与健康检查、探活端点；
 *       其余请求（含 {@code /api/me/**} 绑定/解绑/注销）一律需要有效令牌（需求 9.2、9.3）。</li>
 *   <li>会话策略为 {@link SessionCreationPolicy#STATELESS}，身份完全由 JWT 承载。</li>
 *   <li>{@link JwtAuthenticationFilter} 在用户名密码过滤器之前解析令牌并注入 {@code SecurityContext}。</li>
 *   <li>未认证业务请求由 {@link RestAuthenticationEntryPoint} 返回 401 与统一错误体（UNAUTHENTICATED）。</li>
 * </ul>
 *
 * <p>系统为无密码（passwordless）设计，不存储或校验任何登录密码（需求 4.3），故不再提供
 * {@code PasswordEncoder} Bean。</p>
 *
 * <p>越权访问他人资源(403/404、不泄漏内容)在服务层按会话 user_id 校验并抛出领域异常，
 * 由全局异常处理器映射，不在此过滤链处理。</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/health",
                                "/actuator/health",
                                "/actuator/health/**").permitAll()
                        // 邮箱验证码发送/登录、微信登录、注销无需令牌
                        // （send-code / email-login / wx-login / logout；logout 无状态，客户端丢弃令牌即可）
                        .requestMatchers("/api/auth/**").permitAll()
                        // 绑定/解绑/注销等个人端点必须携带有效令牌（需求 9.3）
                        .requestMatchers("/api/me/**").authenticated()
                        // 其余业务请求必须携带有效令牌
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
