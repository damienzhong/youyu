package com.damien.youyu.api.dto;

import com.damien.youyu.domain.Category;

/**
 * 单个分类响应体（扁平表示）。
 *
 * <p>{@code kind} 以枚举名（EXPENSE/INCOME）返回；{@code parentId} 为 null 表示父分类。
 * 用于创建/重命名接口返回单个分类；列表接口使用 {@link CategoryListResponse} 的层级结构。</p>
 */
public record CategoryResponse(Long id, String kind, String name, Long parentId, String icon, String iconColor) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getKind().name(),
                category.getName(),
                category.getParentId(),
                category.getIcon(),
                category.getIconColor());
    }
}
