package com.damien.youyu.api.dto;

import com.damien.youyu.domain.Project;

/** 项目响应体。 */
public record ProjectResponse(Long id, String name, boolean archived) {

    public static ProjectResponse from(Project p) {
        return new ProjectResponse(p.getId(), p.getName(), p.isArchived());
    }
}
