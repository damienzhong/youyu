package com.damien.youyu.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.damien.youyu.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 未认证业务请求的入口点：返回 401 与统一错误体 {@code {code: "UNAUTHENTICATED"}}（需求 2.5）。
 *
 * <p>当受保护端点缺少有效令牌时，Spring Security 授权层会触发本入口点，而非进入 Controller，
 * 从而保证「未认证请求不执行任何读取或写入」。错误体格式与 {@link com.damien.youyu.error.GlobalExceptionHandler}
 * 保持一致，便于前端统一处理。</p>
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ErrorResponse body = new ErrorResponse("UNAUTHENTICATED", "未认证", null);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
