package com.damien.youyu.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 周期记账错误码与统一映射测试（任务 7.3）。
 *
 * <p>逐一断言 {@link ApiException} 周期记账域五个新增工厂方法产出的错误码、HTTP 状态与出错字段
 * 与设计文档「错误处理策略」一致，并验证 {@link GlobalExceptionHandler} 通过 {@link ApiException#getStatus()}
 * 将这些异常映射为统一错误体 {@code {code, message, field}} 且保留各自的 HTTP 状态（如 400 与 409）。</p>
 *
 * <p>金额越界复用 {@code AMOUNT_INVALID}、越权复用 {@code NOT_FOUND}、未认证复用 {@code UNAUTHENTICATED}
 * （需求 1.4、1.6、1.7、4.5、4.6、8.2、8.3）。</p>
 */
class RecurringApiExceptionTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void recurringRuleInvalid_is400WithGivenField() {
        ApiException ex = ApiException.recurringRuleInvalid("type", "类型非法");

        assertThat(ex.getCode()).isEqualTo("RECURRING_RULE_INVALID");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getField()).isEqualTo("type");

        assertMapped(ex, HttpStatus.BAD_REQUEST, "RECURRING_RULE_INVALID", "type");
    }

    @Test
    void recurringFrequencyInvalid_is400WithFrequencyField() {
        ApiException ex = ApiException.recurringFrequencyInvalid();

        assertThat(ex.getCode()).isEqualTo("RECURRING_FREQUENCY_INVALID");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getField()).isEqualTo("frequency");

        assertMapped(ex, HttpStatus.BAD_REQUEST, "RECURRING_FREQUENCY_INVALID", "frequency");
    }

    @Test
    void recurringEndConditionInvalid_is400WithEndConditionField() {
        ApiException ex = ApiException.recurringEndConditionInvalid();

        assertThat(ex.getCode()).isEqualTo("RECURRING_END_CONDITION_INVALID");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getField()).isEqualTo("endCondition");

        assertMapped(ex, HttpStatus.BAD_REQUEST, "RECURRING_END_CONDITION_INVALID", "endCondition");
    }

    @Test
    void recurringItemAlreadyProcessed_is409() {
        ApiException ex = ApiException.recurringItemAlreadyProcessed();

        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_ALREADY_PROCESSED");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getField()).isNull();

        assertMapped(ex, HttpStatus.CONFLICT, "RECURRING_ITEM_ALREADY_PROCESSED", null);
    }

    @Test
    void recurringItemTargetMissing_is409WithGivenField() {
        ApiException ex = ApiException.recurringItemTargetMissing("accountId");

        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_TARGET_MISSING");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getField()).isEqualTo("accountId");

        assertMapped(ex, HttpStatus.CONFLICT, "RECURRING_ITEM_TARGET_MISSING", "accountId");
    }

    @Test
    void reusedCodes_keepTheirExistingCodeAndStatus() {
        // 金额越界复用 AMOUNT_INVALID（需求 1.4、4.8）
        assertThat(ApiException.amountInvalid().getCode()).isEqualTo("AMOUNT_INVALID");
        assertThat(ApiException.amountInvalid().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 越权复用 NOT_FOUND（需求 8.3）
        assertThat(ApiException.notFound("x").getCode()).isEqualTo("NOT_FOUND");
        assertThat(ApiException.notFound("x").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        // 未认证复用 UNAUTHENTICATED（需求 8.2）
        assertThat(ApiException.unauthenticated().getCode()).isEqualTo("UNAUTHENTICATED");
        assertThat(ApiException.unauthenticated().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 断言 GlobalExceptionHandler 将该 ApiException 映射为统一错误体，且状态/码/字段保持一致。 */
    private void assertMapped(ApiException ex, HttpStatus expectedStatus, String expectedCode, String expectedField) {
        ResponseEntity<ErrorResponse> resp = handler.handleApiException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(expectedStatus);
        ErrorResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(expectedCode);
        assertThat(body.field()).isEqualTo(expectedField);
        assertThat(body.message()).isNotBlank();
    }
}
