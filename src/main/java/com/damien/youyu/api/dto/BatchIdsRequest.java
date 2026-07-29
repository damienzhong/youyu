package com.damien.youyu.api.dto;

import java.util.List;

/** 批量操作请求体：交易 id 列表。 */
public record BatchIdsRequest(List<Long> ids) {
}
