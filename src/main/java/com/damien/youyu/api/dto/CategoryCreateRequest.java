package com.damien.youyu.api.dto;

/**
 * 创建分类请求体（关联需求 5.1-5.3、5.6-5.8）。
 *
 * <p>{@code kind} 以字符串接收（EXPENSE/INCOME），由服务层按枚举校验；创建父分类时
 * {@code parentId} 为 null，创建子分类时 {@code parentId} 指向已存在的父分类。当提供
 * {@code parentId} 时，子分类的种类以父分类为准（保证父子 kind 一致）。名称由服务层按
 * 去空白后 1-50 校验（非法返回 {@code CATEGORY_NAME_INVALID}）。</p>
 */
public record CategoryCreateRequest(String kind, String name, Long parentId, String icon) {
}
