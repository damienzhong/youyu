package com.damien.youyu.api.dto;

import java.util.List;

/**
 * 记账推荐查询响应体。{@code suggestions} 为当前账本的候选列表，
 * 条数为 0 至 3 条；不足 2 条时服务端返回空列表（历史不足不硬猜）。
 */
public record RecordSuggestionResponse(List<RecordSuggestionItem> suggestions) {
}
