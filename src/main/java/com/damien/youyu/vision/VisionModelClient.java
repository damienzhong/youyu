package com.damien.youyu.vision;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.damien.youyu.config.VisionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 视觉大模型客户端：OpenAI 兼容 chat/completions，一张截图 + 一段提示词，要求 JSON 输出。
 *
 * <p>零额外 SDK，纯 {@link HttpClient}。隐私约束：截图只在内存里转发给模型，不落盘、不进库、不打日志；
 * 模型返回的内容含用户流水，同样不打日志，失败时只记录状态码与错误体前 200 字。</p>
 */
@Component
public class VisionModelClient {

    private static final Logger log = LoggerFactory.getLogger(VisionModelClient.class);

    private final VisionProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public VisionModelClient(VisionProperties props) {
        this.props = props;
    }

    /** 模型调用失败（网络 / 超时 / 非 2xx / 空返回）。 */
    public static class VisionCallException extends RuntimeException {
        public VisionCallException(String message) {
            super(message);
        }
    }

    /**
     * 识别一张截图。
     *
     * @return 模型返回的文本（应为 JSON）
     * @throws VisionCallException 调用失败
     */
    public String recognize(String prompt, byte[] image, String mime) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("temperature", 0.1);
        body.put("stream", false);
        // 百炼混合思考模型（qwen3.x）默认开启思考：照抄截图文字用不上，关掉以省时间和费用
        body.put("enable_thinking", false);
        body.putObject("response_format").put("type", "json_object");
        ObjectNode user = body.putArray("messages").addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject().put("type", "image_url").putObject("image_url")
                .put("url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image));
        content.addObject().put("type", "text").put("text", prompt);

        String base = props.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey().trim())
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                String b = resp.body() == null ? "" : resp.body();
                log.warn("[VISION_CALL_FAILED] status={} body={}", resp.statusCode(),
                        b.length() > 200 ? b.substring(0, 200) : b);
                throw new VisionCallException("HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) {
                throw new VisionCallException("模型返回为空");
            }
            JsonNode usage = root.path("usage");
            log.info("[VISION_CALL_OK] model={} promptTokens={} completionTokens={}", props.getModel(),
                    usage.path("prompt_tokens").asInt(-1), usage.path("completion_tokens").asInt(-1));
            return text;
        } catch (VisionCallException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VisionCallException("调用被中断");
        } catch (Exception e) {
            log.warn("[VISION_CALL_FAILED] {}", e.getClass().getSimpleName());
            throw new VisionCallException(e.getClass().getSimpleName());
        }
    }
}
