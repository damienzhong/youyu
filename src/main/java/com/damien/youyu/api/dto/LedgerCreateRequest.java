package com.damien.youyu.api.dto;

import java.util.List;

/**
 * 创建/重命名账本请求体。
 *
 * <p>{@code type} 仅创建时有意义：PERSONAL（个人，默认）/ COLLABORATIVE（协作）。
 * {@code accountIds} 仅创建时有意义：纳入该账本的账户 id 列表（需为本人账户）；为空表示默认全选当前用户的全部账户。</p>
 */
public record LedgerCreateRequest(String name, String type, List<Long> accountIds) {
}
