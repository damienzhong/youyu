package com.damien.youyu.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.api.dto.TrendReportResponse;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.ReportService;

/**
 * 报表接口（关联需求 4.12、7.1-7.7）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权，本控制器从 {@link CurrentUser} 读取当前会话用户主键，
 * 所有报表按该 userId 隔离（需求 2.3）。所有报表统计排除转账（需求 4.12、7.5）。</p>
 *
 * <ul>
 *   <li>GET {@code /api/reports/monthly?month=YYYY-MM} 本自然月收入/支出/结余（month 缺省取当前月）。</li>
 *   <li>GET {@code /api/reports/category?from=YYYY-MM-DD&to=YYYY-MM-DD} 分类占比（含起止边界）。</li>
 *   <li>GET {@code /api/reports/trend?fromMonth=YYYY-MM&toMonth=YYYY-MM} 月度趋势（区间 &gt;24 月或倒序则拒绝）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;
    private final CurrentUser currentUser;
    private final Clock clock;

    public ReportController(ReportService reportService, CurrentUser currentUser, Clock clock) {
        this.reportService = reportService;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** 本月报表：month 为 {@code YYYY-MM}，缺省取 {@code Asia/Shanghai} 当前自然月。 */
    @GetMapping("/monthly")
    public ResponseEntity<MonthlyReportResponse> monthly(
            @RequestParam(name = "month", required = false) String month) {
        Long userId = currentUser.requireUserId();
        YearMonth ym = (month == null || month.isBlank())
                ? YearMonth.now(clock)
                : parseMonth(month, "month");
        return ResponseEntity.ok(reportService.monthlyReport(userId, ym));
    }

    /** 分类占比报表：from/to 为 {@code YYYY-MM-DD}，含起止边界。 */
    @GetMapping("/category")
    public ResponseEntity<CategoryReportResponse> category(
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to) {
        Long userId = currentUser.requireUserId();
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        return ResponseEntity.ok(reportService.categoryReport(userId, fromDate, toDate));
    }

    /** 月度趋势报表：fromMonth/toMonth 为 {@code YYYY-MM}。 */
    @GetMapping("/trend")
    public ResponseEntity<TrendReportResponse> trend(
            @RequestParam(name = "fromMonth") String fromMonth,
            @RequestParam(name = "toMonth") String toMonth) {
        Long userId = currentUser.requireUserId();
        YearMonth from = parseMonth(fromMonth, "fromMonth");
        YearMonth to = parseMonth(toMonth, "toMonth");
        return ResponseEntity.ok(reportService.trendReport(userId, from, to));
    }

    private YearMonth parseMonth(String raw, String field) {
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.reportParamInvalid(field, "月份格式应为 YYYY-MM");
        }
    }

    private LocalDate parseDate(String raw, String field) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.reportParamInvalid(field, "日期格式应为 YYYY-MM-DD");
        }
    }
}
