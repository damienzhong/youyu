package com.damien.youyu.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 自定义提醒域 5 个错误码单元测试（custom-reminder 任务 3.2）。
 *
 * <p>逐个断言 {@link ApiException} 上 5 个提醒工厂方法产出的错误码、HTTP 状态、出错字段与文案约束
 * （需求 8.6、11.5），并断言统一错误体 {@link ErrorResponse} 序列化后的字段集恰为
 * {@code {code, message, field}}、且 {@code field} 与具体字段无关时被省略（需求 8.7）。</p>
 */
class ReminderErrorCodeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 需求 8.6 / 11.5：5 个提醒错误码逐个断言——码取值、HTTP 状态 400、出错字段（field），
     * 以及 {@code message} 为长度 ≤100 的中文文案且不含用户 id / 邮箱 / 令牌三类内容。
     */
    @Test
    void reminderErrorCodes_haveCorrectCodeStatusFieldAndSafeMessage() {
        assertReminderCode(ApiException::reminderFrequencyInvalid,
                "REMINDER_FREQUENCY_INVALID", "frequency");
        assertReminderCode(ApiException::reminderTimeInvalid,
                "REMINDER_TIME_INVALID", "remindTime");
        assertReminderCode(ApiException::reminderDuplicate,
                "REMINDER_DUPLICATE", "frequency");
        assertReminderCode(ApiException::reminderLimitExceeded,
                "REMINDER_LIMIT_EXCEEDED", null);
        assertReminderCode(ApiException::reminderGrantInvalid,
                "REMINDER_GRANT_INVALID", "grantedCount");
    }

    /**
     * 需求 8.7：统一错误体序列化后字段集恰为 {@code {code, message, field}}；
     * 当错误与具体字段无关（{@code field} 为空）时省略 {@code field} 键，但 {@code code} 与
     * {@code message} 两键始终存在。
     */
    @Test
    void reminderErrorBody_hasExactlyCodeMessageField_andOmitsFieldWhenNull() throws Exception {
        // field 非空的 4 个码：字段集恰为 {code, message, field}
        for (Supplier<ApiException> factory : java.util.List.of(
                (Supplier<ApiException>) ApiException::reminderFrequencyInvalid,
                (Supplier<ApiException>) ApiException::reminderTimeInvalid,
                (Supplier<ApiException>) ApiException::reminderDuplicate,
                (Supplier<ApiException>) ApiException::reminderGrantInvalid)) {
            Map<String, Object> body = serialize(factory.get());
            assertThat(body.keySet()).containsExactlyInAnyOrder("code", "message", "field");
            assertThat(body.get("field")).isNotNull();
        }

        // field 无关的码（REMINDER_LIMIT_EXCEEDED）：省略 field，字段集恰为 {code, message}
        Map<String, Object> limitBody = serialize(ApiException.reminderLimitExceeded());
        assertThat(limitBody.keySet()).containsExactlyInAnyOrder("code", "message");
        assertThat(limitBody).doesNotContainKey("field");
        assertThat(limitBody.get("code")).isEqualTo("REMINDER_LIMIT_EXCEEDED");
        assertThat(limitBody.get("message")).isNotNull();
    }

    // ---- 辅助方法 ----

    private void assertReminderCode(Supplier<ApiException> factory, String expectedCode, String expectedField) {
        ApiException ex = factory.get();

        // 码取值
        assertThat(ex.getCode()).isEqualTo(expectedCode);
        // HTTP 状态 400
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 出错字段
        assertThat(ex.getField()).isEqualTo(expectedField);

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

    /** 把 ApiException 映射为统一错误体并序列化为 JSON，再解析回 Map 以断言实际输出的键集。 */
    private Map<String, Object> serialize(ApiException ex) throws Exception {
        ErrorResponse body = new ErrorResponse(ex.getCode(), ex.getMessage(), ex.getField());
        String json = objectMapper.writeValueAsString(body);
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }
}
