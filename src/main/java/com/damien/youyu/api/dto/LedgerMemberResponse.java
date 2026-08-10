package com.damien.youyu.api.dto;

/**
 * 账本成员响应体（需求 2.5）。
 *
 * <ul>
 *   <li>{@code displayName}：成员昵称（微信用户可能未设昵称，回退「用户{id}」；前端可再兜底）。</li>
 *   <li>{@code avatarSeed}：文字头像种子（昵称首个 Unicode 码点）。项目未存储头像图片，头像统一由
 *       昵称首字生成，口径与分享卡片一致，避免引入头像上传 / 外链。</li>
 *   <li>{@code role}：成员角色（OWNER / EDITOR）。</li>
 *   <li>{@code owner}：创建者标识，等价于 {@code role == OWNER}，供前端直接展示「创建者」徽标。</li>
 * </ul>
 */
public record LedgerMemberResponse(
        Long userId, String displayName, String avatarSeed, String role, boolean owner) {
}
