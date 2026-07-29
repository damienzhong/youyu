package com.damien.youyu.api.dto;

/** 新建/重命名商家请求体。{@code name} 商家名（1-50）。 */
public record MerchantSaveRequest(String name) {
}
