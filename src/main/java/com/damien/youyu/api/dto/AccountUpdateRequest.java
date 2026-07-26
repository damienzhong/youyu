package com.damien.youyu.api.dto;

/**
 * 修改账户请求体：可改名称、类型及扩展字段（计入总资产/隐藏/备注），余额（初始/当前）保持不变（需求 3.6）。
 *
 * <p>{@code type} 以字符串接收，由服务层按受支持枚举校验；名称按 1-50 校验。
 * 扩展字段缺省：includeInTotal=true、hidden=false、note=null。</p>
 */
public record AccountUpdateRequest(
        String name,
        String type,
        Boolean includeInTotal,
        Boolean hidden,
        String note) {
}
