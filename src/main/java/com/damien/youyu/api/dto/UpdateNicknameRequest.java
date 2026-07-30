package com.damien.youyu.api.dto;

/**
 * 修改昵称请求（需求 4.4）。
 *
 * <p>{@code POST /api/me/nickname}（需令牌）。昵称去空白后长度需为 1-64，仅用于展示、可重复。</p>
 */
public record UpdateNicknameRequest(String nickname) {
}
