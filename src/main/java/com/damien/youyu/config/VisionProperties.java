package com.damien.youyu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 截图识别（视觉大模型）配置，前缀 {@code youyu.vision}。
 *
 * <p>走 OpenAI 兼容的 chat/completions 接口（默认阿里云百炼 DashScope 兼容模式），与 lodestar 共用同一个
 * 百炼账号的 API Key。{@code apiKey} 只能经环境变量 {@code YOUYU_VISION_API_KEY} 注入、切勿写进仓库；
 * 未配置时 {@link #enabled()} 为 false，识别接口返回「功能未开启」，不影响其它任何功能。</p>
 */
@Component
@ConfigurationProperties(prefix = "youyu.vision")
public class VisionProperties {

    /** OpenAI 兼容接口根地址（不含 /chat/completions）。 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** API Key；为空即关闭截图识别。 */
    private String apiKey = "";

    /** 视觉模型名。 */
    private String model = "qwen3.8-flash";

    /** 每人每天可识别的截图张数（识别失败 / 不是账单的图不计）。 */
    private int dailyLimit = 10;

    /** 单次最多上传的截图张数。 */
    private int maxImages = 9;

    /** 单张截图解码后的最大字节数（前端已压缩，正常在 1MB 内）。 */
    private int maxImageBytes = 4 * 1024 * 1024;

    /** 单次模型调用超时（秒）。须小于 nginx 默认的 60 秒代理读超时，前端一次只传一张。 */
    private int timeoutSeconds = 50;

    /** 是否已开启：配了 API Key 即开启。 */
    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public int getMaxImages() {
        return maxImages;
    }

    public void setMaxImages(int maxImages) {
        this.maxImages = maxImages;
    }

    public int getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(int maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
