package com.damien.youyu.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.ScreenshotRecognizeRequest;
import com.damien.youyu.api.dto.ScreenshotRecognizeResponse;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.vision.ScreenshotRecognizeService;

/**
 * 截图记账接口。身份由 Spring Security 统一鉴权，按会话 ledgerId 隔离。
 *
 * <ul>
 *   <li>GET {@code /api/imports/screenshot/status}：是否开启 + 今日剩余额度。</li>
 *   <li>POST {@code /api/imports/screenshot/recognize}：识别截图，只返回预览，不写账本。</li>
 *   <li>POST {@code /api/imports/screenshot/undo}：24 小时内整批撤销已入账的截图流水，返回 {@code undone} 笔数。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/imports/screenshot")
public class ScreenshotController {

    private final ScreenshotRecognizeService service;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public ScreenshotController(ScreenshotRecognizeService service, CurrentLedger currentLedger,
            CurrentUser currentUser) {
        this.service = service;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    public ResponseEntity<ScreenshotRecognizeService.Status> status() {
        return ResponseEntity.ok(service.status(currentUser.requireUserId()));
    }

    @PostMapping("/recognize")
    public ResponseEntity<ScreenshotRecognizeResponse> recognize(@RequestBody ScreenshotRecognizeRequest req) {
        Long userId = currentUser.requireUserId();
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(service.recognize(userId, ledgerId, req));
    }

    /** 请求体 {@code {"batch":"32位十六进制"}}。 */
    @PostMapping("/undo")
    public ResponseEntity<Map<String, Integer>> undo(@RequestBody Map<String, String> body) {
        Long userId = currentUser.requireUserId();
        Long ledgerId = currentLedger.requireLedgerId();
        return ResponseEntity.ok(Map.of("undone", service.undo(userId, ledgerId, body == null ? null : body.get("batch"))));
    }
}
