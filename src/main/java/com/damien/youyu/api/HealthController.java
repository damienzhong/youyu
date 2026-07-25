package com.damien.youyu.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轻量健康检查端点，用于部署探活与启动冒烟测试。
 *
 * <p>返回服务状态与当前 {@code Asia/Shanghai} 时间，便于确认时区配置生效。</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "app", "youyu",
                "time", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString()
        );
    }
}
