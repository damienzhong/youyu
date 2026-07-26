package com.damien.youyu.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理器（任务 12.1）。
 *
 * <p>集中把领域异常（{@link ApiException}）与常见框架异常映射为统一错误体
 * {@code {code, message, field}}（见 {@link ErrorResponse}）与设计文档「错误分类与处理」错误码表中的
 * HTTP 状态码。目标是：任何从 Controller 抛出的异常都返回一致的 JSON 结构，而非 Spring 默认错误页/错误 JSON，
 * 且不向客户端泄漏堆栈或内部实现细节。</p>
 *
 * <p>说明：未认证业务请求在进入 Controller 之前即被 Spring Security 过滤链拦截，由
 * {@link com.damien.youyu.security.RestAuthenticationEntryPoint} 直接返回 401 统一错误体；本处理器中的
 * {@link #handleAuthentication} 仅覆盖极少数在 Controller 之内抛出的认证异常，二者错误体保持一致。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 领域异常：携带自身的错误码、HTTP 状态与出错字段，直接透传（需求覆盖见错误码表）。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        ErrorResponse body = new ErrorResponse(ex.getCode(), ex.getMessage(), ex.getField());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * 请求体字段校验失败（{@code @Valid} 触发）。映射为 400 {@code FIELD_REQUIRED}，
     * 并带上第一个出错字段（需求 1.4、4.8 等「必填项缺失/字段非法」）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        String field = fieldErrors.isEmpty() ? null : fieldErrors.get(0).getField();
        String message = fieldErrors.isEmpty()
                ? "请求字段校验失败"
                : defaultIfBlank(fieldErrors.get(0).getDefaultMessage(), "字段校验失败");
        return badRequest("FIELD_REQUIRED", message, field);
    }

    /**
     * 方法参数/路径参数上的约束校验失败（{@code @Validated} + 参数级约束）。
     * 映射为 400 {@code FIELD_REQUIRED}。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String field = null;
        String message = "请求参数校验失败";
        var violations = ex.getConstraintViolations();
        if (violations != null && !violations.isEmpty()) {
            ConstraintViolation<?> first = violations.iterator().next();
            field = lastPathNode(first.getPropertyPath() == null ? null : first.getPropertyPath().toString());
            message = defaultIfBlank(first.getMessage(), message);
        }
        return badRequest("FIELD_REQUIRED", message, field);
    }

    /** 请求体无法解析（如 JSON 语法错误、类型不匹配）。映射为 400，不回显原始报文内容。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return badRequest("REQUEST_BODY_INVALID", "请求体格式不正确或无法解析", null);
    }

    /** 缺少必填的查询/表单参数（如报表缺少 month）。映射为 400 {@code FIELD_REQUIRED}，带字段名。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("FIELD_REQUIRED", "缺少必填参数", ex.getParameterName());
    }

    /** 查询/路径参数类型不匹配（如 month 传入非法格式）。映射为 400 {@code PARAM_INVALID}，带字段名。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("PARAM_INVALID", "参数格式不正确", ex.getName());
    }

    /** Controller 内部抛出的认证异常：映射为 401 {@code UNAUTHENTICATED}（需求 2.5）。 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        ErrorResponse body = new ErrorResponse("UNAUTHENTICATED", "未认证", null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /** 越权访问：映射为 403 {@code FORBIDDEN}，不泄漏目标资源内容（需求 2.4）。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse body = new ErrorResponse("FORBIDDEN", "无权访问", null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * 兜底：其它未预期异常统一映射为 500 {@code INTERNAL_ERROR}，仅返回通用提示，
     * 不向客户端泄漏堆栈或内部细节；完整堆栈仅记录到服务端日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未预期的服务端异常", ex);
        ErrorResponse body = new ErrorResponse("INTERNAL_ERROR", "服务器内部错误，请稍后再试", null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ---- 辅助方法 ----

    private static ResponseEntity<ErrorResponse> badRequest(String code, String message, String field) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(code, message, field));
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** 从形如 {@code method.argName} 的属性路径中取最后一段作为字段名。 */
    private static String lastPathNode(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int idx = path.lastIndexOf('.');
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : path;
    }
}
