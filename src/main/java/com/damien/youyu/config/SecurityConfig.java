package com.damien.youyu.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
 *   <li>放行 {@code GET /api/invite/inviter}（落地页在登录前展示邀请人昵称，邀请系统需求 8.4）；
 *       其余 {@code /api/invite/**} 需有效令牌（需求 8.1）。<strong>两条规则的先后顺序不可调换</strong>，
 *       详见方法内注释。</li>
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
                        // 邀请人展示信息查询为公开端点：落地页在用户登录前就要展示邀请人昵称
                        // （邀请系统需求 8.4）。携带无效或过期令牌时也必须忽略该令牌按匿名请求返回 200，
                        // 不得返回 UNAUTHENTICATED —— 匿名放行由本规则保证，
                        // JwtAuthenticationFilter 对无效令牌不写 SecurityContext 也不中断链路。
                        //
                        // 【顺序是有语义的，不要重排】authorizeHttpRequests 按声明顺序取首个匹配的规则，
                        // 后续规则不再参与判定。因此本条 permitAll 必须写在下面 /api/invite/** 的
                        // authenticated 之前；一旦调换，GET /api/invite/inviter 会先命中
                        // authenticated 而变成需令牌，需求 8.4 被静默破坏（编译与单测都不会报错，
                        // 只有落地页在未登录时拿到 401）。
                        //
                        // 显式写 HttpMethod.GET：只放行读取，其它方法仍走后面的 authenticated 规则。
                        .requestMatchers(HttpMethod.GET, "/api/invite/inviter").permitAll()
                        // 邀请信息 / 二维码 / 被邀请人列表必须携带有效令牌（需求 8.1）。
                        // 这条规则的效果与末尾 anyRequest().authenticated() 相同，刻意显式写出，
                        // 是为了让上面那条 permitAll 的「必须在前」这一约束在代码里看得见。
                        .requestMatchers("/api/invite/**").authenticated()
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
