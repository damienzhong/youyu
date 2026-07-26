package com.damien.youyu.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 全局异常处理器单元测试（任务 12.1）。
 *
 * <p>使用 MockMvc standalone 装配一个仅用于测试的控制器 + 真实的
 * {@link GlobalExceptionHandler}，逐一验证代表性异常都被映射为统一错误体
 * {@code {code, message, field}} 与设计错误码表中的 HTTP 状态码，且兜底异常不泄漏内部细节。</p>
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void apiException_mapsToItsCodeStatusAndField() throws Exception {
        // ApiException.amountInvalid() -> 400 AMOUNT_INVALID field=amount（需求 4.4）
        mockMvc.perform(post("/probe/api-exception"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AMOUNT_INVALID"))
                .andExpect(jsonPath("$.field").value("amount"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void apiException_conflictStatusIsPreserved() throws Exception {
        // ApiException.usernameTaken() -> 409 USERNAME_TAKEN（需求 1.2）
        mockMvc.perform(post("/probe/username-taken"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"))
                .andExpect(jsonPath("$.field").value("username"));
    }

    @Test
    void malformedJson_returns400UnifiedBody() throws Exception {
        mockMvc.perform(post("/probe/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not valid json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void bodyValidationFailure_returns400FieldRequiredWithField() throws Exception {
        // password 过短触发 @Size 校验失败
        mockMvc.perform(post("/probe/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIELD_REQUIRED"))
                .andExpect(jsonPath("$.field").value("password"));
    }

    @Test
    void missingRequiredParam_returns400FieldRequiredWithField() throws Exception {
        mockMvc.perform(get("/probe/required-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIELD_REQUIRED"))
                .andExpect(jsonPath("$.field").value("month"));
    }

    @Test
    void paramTypeMismatch_returns400ParamInvalidWithField() throws Exception {
        mockMvc.perform(get("/probe/type-mismatch").param("n", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.field").value("n"));
    }

    @Test
    void unauthenticated_apiException_returns401() throws Exception {
        // Controller 内抛出的 ApiException.unauthenticated() -> 401 UNAUTHENTICATED（需求 2.5）
        mockMvc.perform(get("/probe/unauthenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void authenticationException_returns401Unauthenticated() throws Exception {
        mockMvc.perform(get("/probe/auth-exception"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void accessDenied_returns403ForbiddenWithoutLeakingContent() throws Exception {
        mockMvc.perform(get("/probe/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unexpectedException_returns500GenericWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                // 不泄漏内部异常信息
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后再试"));
    }

    /** 仅用于测试的探针控制器，各端点触发一种代表性异常。 */
    @RestController
    static class ProbeController {

        @PostMapping("/probe/api-exception")
        public void apiException() {
            throw ApiException.amountInvalid();
        }

        @PostMapping("/probe/username-taken")
        public void usernameTaken() {
            throw ApiException.usernameTaken();
        }

        @PostMapping("/probe/valid")
        public void valid(@Valid @RequestBody Payload payload) {
            // 正常情况下不会执行到这里（测试仅提交非法/畸形请求体）
        }

        @GetMapping("/probe/required-param")
        public void requiredParam(@RequestParam("month") String month) {
        }

        @GetMapping("/probe/type-mismatch")
        public void typeMismatch(@RequestParam("n") int n) {
        }

        @GetMapping("/probe/unauthenticated")
        public void unauthenticated() {
            throw ApiException.unauthenticated();
        }

        @GetMapping("/probe/auth-exception")
        public void authException() {
            throw new BadCredentialsException("bad");
        }

        @GetMapping("/probe/access-denied")
        public void accessDenied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/probe/boom")
        public void boom() {
            throw new IllegalStateException("绝密内部细节不应泄漏");
        }
    }

    /** 测试用请求体，带 Bean Validation 约束。 */
    record Payload(@NotBlank String username, @Size(min = 8, max = 64) String password) {
    }
}
