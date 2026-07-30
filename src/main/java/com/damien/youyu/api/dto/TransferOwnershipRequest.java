package com.damien.youyu.api.dto;

/**
 * 账户转交请求体：把账户 owner 变更为 {@code newOwnerUserId}（需求 9）。
 */
public record TransferOwnershipRequest(Long newOwnerUserId) {
}
