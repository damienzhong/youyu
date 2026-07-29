package com.damien.youyu.api.dto;

/**
 * 新建/修改项目请求体。{@code name} 项目名（1-50）；{@code archived} 归档状态（修改时可选）。
 */
public record ProjectSaveRequest(String name, Boolean archived) {
}
