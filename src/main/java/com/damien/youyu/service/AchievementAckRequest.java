package com.damien.youyu.service;

/**
 * 游标推进请求：唯一入参是本次已播报到的最大成就事件 id（需求 5.6）。
 *
 * <p><b>{@code lastEventId} 刻意声明为 {@link String} 而不是 {@link Long}</b>，
 * 与 {@code GrowthController} 把 {@code page} / {@code size} 声明为 {@code String} 完全同一个理由：
 * <strong>不能让框架替我们做类型转换</strong>。若声明为 {@code Long}，客户端传 {@code "abc"} 时
 * Jackson 会在<strong>进入方法体之前</strong>抛 {@code HttpMessageNotReadableException}，
 * 被全局异常处理器映射成 {@code REQUEST_BODY_INVALID}（另一个错误码、另一套字段集）。
 * 那条路径有两个问题：一是它绕过了控制器第一步的「令牌用户在 {@code users} 表中仍存在」校验
 * （需求 6.9 要求该校验先于入参校验，已注销用户带非法取值请求应得 {@code UNAUTHENTICATED}
 * 而非 400）；二是它违背需求 5.12「缺失 / 为空 / 不可解析 / 小于 0 / 越界一律返回
 * {@code ACHIEVEMENT_ACK_PARAM_INVALID} 且 {@code field} 取 {@code lastEventId}」。
 * 故以原文接收，解析与取值校验全部落在服务层。</p>
 *
 * <p>Jackson 会把 JSON 数字 {@code 12} 也收成字符串 {@code "12"}，因此客户端传数字
 * （{@code {"lastEventId": 12}}）或字符串（{@code {"lastEventId": "12"}}）都能工作，
 * 不需要为此约定额外的协议细节。</p>
 *
 * @param lastEventId 本次已播报到的最大成就事件 id 的<strong>原文</strong>；
 *                    允许取值范围为 {@code [0, 该用户当前最大 BADGE 事件 id]}
 *                    （无 {@code BADGE} 行时上界按 0 计），越界与不可解析均由服务层拒绝
 */
public record AchievementAckRequest(String lastEventId) {
}
