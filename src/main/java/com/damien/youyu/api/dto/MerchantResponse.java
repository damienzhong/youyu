package com.damien.youyu.api.dto;

import com.damien.youyu.domain.Merchant;

/** 商家响应体。 */
public record MerchantResponse(Long id, String name) {

    public static MerchantResponse from(Merchant m) {
        return new MerchantResponse(m.getId(), m.getName());
    }
}
