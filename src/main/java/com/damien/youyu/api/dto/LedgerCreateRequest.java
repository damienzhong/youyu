package com.damien.youyu.api.dto;

/**
 * 创建/重命名账本请求体。{@code type} 仅创建时有意义：INDEPENDENT（独立，默认）/ COLLABORATIVE（协作）。
 */
public record LedgerCreateRequest(String name, String type) {
}
