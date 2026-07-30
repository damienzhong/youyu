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

import java.util.List;

import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.DimensionReportResponse;
import com.damien.youyu.api.dto.MemberReportResponse;
import com.damien.youyu.api.dto.MemberReportResponse.MemberShare;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.api.dto.RangeReportResponse;
import com.damien.youyu.api.dto.TrendReportResponse;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.service.ReportService;

/**
 * 报表接口（关联需求 4.12、7.1-7.7）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权，本控制器从 {@link CurrentLedger} 读取当前会话用户主键，
 * 所有报表按该 ledgerId 隔离（需求 2.3）。所有报表统计排除转账（需求 4.12、7.5）。</p>
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
    private final CurrentLedger currentLedger;
    private final UserRepository userRepository;
    private final Clock clock;

    public ReportController(ReportService reportService, CurrentLedger currentLedger,
            UserRepository userRepository, Clock clock) {
        this.reportService = reportService;
        this.currentLedger = currentLedger;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** 本月报表：month 为 {@code YYYY-MM}，缺省取 {@code Asia/Shanghai} 当前自然月。 */
    @GetMapping("/monthly")
    public ResponseEntity<MonthlyReportResponse> monthly(
            @RequestParam(name = "month", required = false) String month) {
        Long ledgerId = currentLedger.requireLedgerId();
        YearMonth ym = (month == null || month.isBlank())
                ? YearMonth.now(clock)
                : parseMonth(month, "month");
        return ResponseEntity.ok(reportService.monthlyReport(ledgerId, ym));
    }

    /** 分类占比报表：from/to 为 {@code YYYY-MM-DD}，含起止边界；kind 为 expense/income（缺省 expense）。 */
    @GetMapping("/category")
    public ResponseEntity<CategoryReportResponse> category(
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "kind", required = false) String kind) {
        Long ledgerId = currentLedger.requireLedgerId();
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        TransactionType type = parseKind(kind);
        return ResponseEntity.ok(reportService.categoryReport(ledgerId, fromDate, toDate, type));
    }

    /** 区间收支报表：from/to 为 {@code YYYY-MM-DD}，含起止边界；返回总收支 + 按日明细。 */
    @GetMapping("/range")
    public ResponseEntity<RangeReportResponse> range(
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to) {
        Long ledgerId = currentLedger.requireLedgerId();
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        return ResponseEntity.ok(reportService.rangeReport(ledgerId, fromDate, toDate));
    }

    /**
     * 成员占比报表（协作账本）：from/to 为 {@code YYYY-MM-DD}，含起止边界；kind 为 expense/income（缺省 expense）；
     * 返回各成员在该类别的占比。独立账本亦可调用（结果为单一成员=自己）。
     */
    @GetMapping("/members")
    public ResponseEntity<MemberReportResponse> members(
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "kind", required = false) String kind) {
        Long ledgerId = currentLedger.requireLedgerId();
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        TransactionType type = parseKind(kind);
        MemberReportResponse report = reportService.memberReport(ledgerId, fromDate, toDate, type);
        // 补齐成员显示名（记账人账号标识）。
        List<MemberShare> named = report.members().stream()
                .map(m -> new MemberShare(m.userId(), displayName(m.userId()),
                        m.amount(), m.percentage(), m.count()))
                .toList();
        return ResponseEntity.ok(new MemberReportResponse(
                report.from(), report.to(), report.total(), named));
    }

    /**
     * 维度占比报表（项目/商家/标签）：from/to 为 {@code YYYY-MM-DD}，含起止边界；
     * kind 为 expense/income（缺省 expense）；dim 为 project/merchant/tag。
     */
    @GetMapping("/dimension")
    public ResponseEntity<DimensionReportResponse> dimension(
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "dim") String dim,
            @RequestParam(name = "kind", required = false) String kind) {
        Long ledgerId = currentLedger.requireLedgerId();
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        TransactionType type = parseKind(kind);
        return ResponseEntity.ok(reportService.dimensionReport(ledgerId, fromDate, toDate, type, dim));
    }

    private String displayName(Long userId) {
        if (userId == null || userId == 0L) {
            return "未知";
        }
        return userRepository.findById(userId)
                .map(u -> u.getNickname() != null ? u.getNickname() : "用户" + userId)
                .orElse("用户" + userId);
    }

    /** 月度趋势报表：fromMonth/toMonth 为 {@code YYYY-MM}。 */
    @GetMapping("/trend")
    public ResponseEntity<TrendReportResponse> trend(
            @RequestParam(name = "fromMonth") String fromMonth,
            @RequestParam(name = "toMonth") String toMonth) {
        Long ledgerId = currentLedger.requireLedgerId();
        YearMonth from = parseMonth(fromMonth, "fromMonth");
        YearMonth to = parseMonth(toMonth, "toMonth");
        return ResponseEntity.ok(reportService.trendReport(ledgerId, from, to));
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

    /** 解析分类统计类别；缺省为支出，仅接受 expense/income（大小写不敏感）。 */
    private TransactionType parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return TransactionType.EXPENSE;
        }
        String v = raw.trim().toUpperCase();
        if ("INCOME".equals(v)) {
            return TransactionType.INCOME;
        }
        if ("EXPENSE".equals(v)) {
            return TransactionType.EXPENSE;
        }
        throw ApiException.reportParamInvalid("kind", "统计类别仅支持 expense 或 income");
    }
}
