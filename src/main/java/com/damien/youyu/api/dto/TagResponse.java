package com.damien.youyu.api.dto;

import com.damien.youyu.domain.Tag;

/** 标签响应体。 */
public record TagResponse(Long id, String name) {

    public static TagResponse from(Tag t) {
        return new TagResponse(t.getId(), t.getName());
    }
}
