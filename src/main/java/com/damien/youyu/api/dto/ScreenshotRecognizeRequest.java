package com.damien.youyu.api.dto;

import java.util.List;

/**
 * 截图识别请求。前端已把每张截图压缩到长边约 1600px 的 JPEG，再以 base64 提交（不含 data: 前缀亦可）。
 *
 * @param images 截图（1..9 张；前端通常一次只传 1 张，逐张展示进度）
 */
public record ScreenshotRecognizeRequest(List<Image> images) {

    /**
     * @param data base64 图片数据（允许带 {@code data:image/...;base64,} 前缀）
     * @param mime image/jpeg 或 image/png
     */
    public record Image(String data, String mime) {
    }
}
