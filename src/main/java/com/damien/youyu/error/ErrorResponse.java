package com.damien.youyu.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体：{@code {code, message, field}}。
 *
 * <p>{@code field} 为可选字段，仅在错误可归因到具体请求字段时出现。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String field) {
}
