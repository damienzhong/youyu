package com.damien.youyu.api.dto;

/**
 * 发送邮箱验证码请求。
 *
 * <p>关联需求 1、9.2（{@code POST /api/auth/send-code} 为公开端点）。</p>
 *
 * <ul>
 *   <li>{@code email}：目标邮箱，格式校验在 {@code VerificationCodeService} 内完成
 *       （非法即 {@code EMAIL_INVALID}），发送/校验不因邮箱是否已注册而区分结果（需求 1.7）。</li>
 *   <li>{@code purpose}：验证码用途字符串，取值 {@code LOGIN}/{@code BIND}/{@code DELETE}
 *       （大小写不敏感），由控制器映射为
 *       {@link com.damien.youyu.domain.EmailCodePurpose}；为空或非法取值将以
 *       {@code FIELD_REQUIRED(purpose)} 拒绝。在未登录的鉴权流程（本控制器）中
 *       通常传 {@code LOGIN}；{@code BIND}/{@code DELETE} 由 /api/me 相关端点使用
 *       （见任务 7.2）。</li>
 * </ul>
 */
public record SendCodeRequest(String email, String purpose) {
}
