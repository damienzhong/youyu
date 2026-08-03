package com.damien.youyu.api.dto;

/**
 * 邀请二维码响应体（GET /api/invite/qrcode）。
 *
 * <p>字段<strong>是且仅是</strong>一个 {@code imageBase64}（需求 3.1）：不含邀请码、不含链接、
 * 不含任何用户标识字段。</p>
 *
 * <p>取值为 PNG 的 base64 文本，<strong>不含</strong> {@code data:image/png;base64,} 前缀——
 * 前缀由前端按用途自行拼接（需求 3.1）。</p>
 *
 * @param imageBase64 小程序码 PNG 的 base64 文本，无 data URI 前缀
 */
public record InviteQrCodeResponse(String imageBase64) {

    public static InviteQrCodeResponse of(String imageBase64) {
        return new InviteQrCodeResponse(imageBase64);
    }
}
