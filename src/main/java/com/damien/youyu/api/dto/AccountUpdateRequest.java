package com.damien.youyu.api.dto;

/**
 * 修改账户请求体：仅允许修改名称与类型，余额（初始/当前）保持不变（需求 3.6）。
 *
 * <p>{@code type} 以字符串接收，由服务层按受支持枚举校验；名称按 1-50 校验。</p>
 */
public record AccountUpdateRequest(String name, String type) {
}
