package com.damien.youyu.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.damien.youyu.domain.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 签发与解析服务。
 *
 * <p>令牌以用户主键作为 subject，附带 {@code role} 声明与过期时间，使用 HS256 对称密钥签名。
 * 密钥与有效期由 {@code app.jwt.*} 配置提供（生产环境务必用环境变量覆盖密钥）。</p>
 *
 * <p>本服务在鉴权任务(3.1)中用于登录签发令牌；解析能力供 GET /api/me 及后续
 * Spring Security 过滤链(任务 3.2)复用。</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /** 为指定用户签发令牌。 */
    public String generateToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("role", user.getRole().getCode())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** 解析并校验令牌，返回声明集合；令牌无效或过期时抛出异常。 */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从令牌中提取用户主键。 */
    public Long extractUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /** 从令牌中提取角色编码（user/admin）。 */
    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }
}
