package com.damien.youyu.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * {@link ApiException} 工厂方法单元测试。
 *
 * <p>逐个断言错误码工厂方法产出的码、HTTP 状态、出错字段与文案约束，
 * 与设计文档「错误处理策略」的错误码表对齐。</p>
 */
class ApiExceptionTest {

    /**
     * 连续记账域唯一错误码 {@code STREAK_PAGE_PARAM_INVALID}（streak-system 需求 6.13）：
     * 码正确、HTTP 状态 400、{@code field} 取入参、{@code message} 为 ≤100 字符的中文文案，
     * 且不含用户 id / 邮箱 / 令牌三类内容。
     */
    @Test
    void streakPageParamInvalid_hasCorrectCodeStatusFieldAndSafeMessage() {
        for (String field : new String[] {"page", "size"}) {
            ApiException ex = ApiException.streakPageParamInvalid(field);

            // 码正确
            assertThat(ex.getCode()).isEqualTo("STREAK_PAGE_PARAM_INVALID");
            // HTTP 状态 400
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            // field 取入参
            assertThat(ex.getField()).isEqualTo(field);

            String message = ex.getMessage();
            assertThat(message).isNotBlank();
            // message 长度 ≤100 字符
            assertThat(message.length()).isLessThanOrEqualTo(100);
            // message 不含用户 id / 邮箱 / 令牌三类内容
            assertThat(message)
                    .doesNotContainIgnoringCase("userId")
                    .doesNotContainIgnoringCase("user_id")
                    .doesNotContain("用户id")
                    .doesNotContain("用户 id")
                    .doesNotContain("@")
                    .doesNotContainIgnoringCase("email")
                    .doesNotContain("邮箱")
                    .doesNotContainIgnoringCase("token")
                    .doesNotContainIgnoringCase("bearer")
                    .doesNotContain("令牌");
        }
    }
}
