package com.damien.youyu.api.dto;

/**
 * 重命名分类请求体（关联需求 5.4、5.7、5.8）。
 *
 * <p>仅允许修改名称；kind、parentId 与其下所有 Transaction 关联保持不变。名称由服务层按
 * 去空白后 1-50 校验，并在同一 kind、同一父级范围内做重名校验。</p>
 */
public record CategoryUpdateRequest(String name) {
}
