package com.damien.youyu.api;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.damien.youyu.error.ApiException;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.service.ExportService;
import com.damien.youyu.service.ImportService;

import java.io.ByteArrayInputStream;

/**
 * 数据导出接口（关联需求 8.1-8.4、8.6、8.7）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权；本控制器在请求线程内从 {@link CurrentLedger}
 * 读取会话用户主键并捕获，随后以 {@link StreamingResponseBody} 流式写出，避免全量数据载入内存
 * （需求 8.1、8.2）。导出对任何 plan 免费、无门控（需求 8.3）。</p>
 *
 * <ul>
 *   <li>GET {@code /api/export?format=csv} 导出 CSV（UTF-8 带 BOM）。</li>
 *   <li>GET {@code /api/export?format=json} 导出 JSON（业务引用键，缺省格式）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ExportController {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");
    private static final MediaType JSON = MediaType.parseMediaType("application/json; charset=UTF-8");

    private final ExportService exportService;
    private final ImportService importService;
    private final CurrentLedger currentLedger;
    private final Clock clock;

    public ExportController(ExportService exportService, ImportService importService,
            CurrentLedger currentLedger, Clock clock) {
        this.exportService = exportService;
        this.importService = importService;
        this.currentLedger = currentLedger;
        this.clock = clock;
    }

    /**
     * 导出当前用户全部数据。{@code format} 取 {@code csv} 或 {@code json}（缺省 json）。
     *
     * @throws ApiException EXPORT_FORMAT_UNSUPPORTED（format 非 csv/json）
     */
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(name = "format", defaultValue = "json") String format) {
        // 在请求线程内解析会话用户，供流式回调（可能在其他线程执行）使用（需求 8.4）。
        Long ledgerId = currentLedger.requireLedgerId();
        String fmt = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        String date = LocalDate.now(clock).format(FILE_DATE);

        return switch (fmt) {
            case "csv" -> build(CSV, "youyu-export-" + date + ".csv",
                    out -> exportService.writeCsv(ledgerId, out));
            case "json" -> build(JSON, "youyu-export-" + date + ".json",
                    out -> exportService.writeJson(ledgerId, out));
            default -> throw ApiException.exportFormatUnsupported();
        };
    }

    private ResponseEntity<StreamingResponseBody> build(
            MediaType contentType, String filename, StreamingResponseBody body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(contentType)
                .body(body);
    }

    /**
     * 从导出 JSON 还原数据到当前用户（往返一致性/迁移用途，需求 8.5）。
     *
     * <p>请求体为 {@link ExportService} 产出的 JSON 文档；整个还原在单个事务内完成，
     * 失败即整体回滚不产生部分数据。归属一律强制为会话用户（需求 2.2）。</p>
     *
     * @return 还原的账户/分类/交易记录数汇总
     * @throws ApiException IMPORT_INVALID / IMPORT_FAILED
     */
    @PostMapping("/import")
    public ImportService.ImportResult importJson(@RequestBody byte[] body) {
        Long ledgerId = currentLedger.requireLedgerId();
        try (InputStream in = new ByteArrayInputStream(body == null ? new byte[0] : body)) {
            return importService.importJson(ledgerId, in);
        } catch (IOException e) {
            throw ApiException.importFailed();
        }
    }
}
