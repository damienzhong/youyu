package com.damien.youyu.api.dto;

/** 新建/重命名标签请求体。{@code name} 标签名（1-30）。 */
public record TagSaveRequest(String name) {
}
