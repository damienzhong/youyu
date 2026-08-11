package com.damien.youyu.api;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.CashflowResponse;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.AssetsCashflowService;
import com.damien.youyu.service.AssetsCashflowService.CashflowResult;

/**
 * 资产现金流（Assets_Cashflow_System）只读接口：按<b>账户维度</b>返回当前用户某自然月的
 * 实际流出、实际流入、净流入，以及「今日」实际流出/流入（需求 2.1）。
 *
 * <p>置于 {@code /api/all} 命名空间（与「全部账本」聚合只读接口一脉相承），但<b>不改动</b>
 * {@link AggregateController} / {@code AggregateService}——现金流口径含 AA 实付、排除转账，
 * 刻意独立于账本维度收支，故走独立的 {@link AssetsCashflowService}。</p>
 *
 * <p>数据归属只认令牌解析出的 userId：忽略任何用于指定目标用户身份的查询/路径/请求体/自定义头，
 * 且与会话账本无关、不要求 {@code X-Ledger-Id}（需求 2.8、3.3）。</p>
 *
 * <ul>
 *   <li>GET {@code /api/all/cashflow?month=YYYY-MM} 该自然月账户维度现金流 + 今日流出/流入。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/all")
public class AssetsCashflowController {

    private final AssetsCashflowService assetsCashflowService;
    private final CurrentUser currentUser;

    public AssetsCashflowController(AssetsCashflowService assetsCashflowService, CurrentUser currentUser) {
        this.assetsCashflowService = assetsCashflowService;
        this.currentUser = currentUser;
    }

    /**
     * 返回选定自然月的账户维度现金流。
     *
     * <p>处理顺序：先鉴权（{@link CurrentUser#requireUserId()}，无效令牌/用户不存在 →
     * {@code UNAUTHENTICATED}，先于任何参数校验、响应不含现金流数值，需求 3.1、3.2），
     * 再校验 {@code month}（非法 → 复用 {@link ApiException#reportParamInvalid} 的既有错误码，
     * 需求 2.5），最后调用只读聚合服务组装两位小数字符串响应（需求 2.6）。</p>
     */
    @GetMapping("/cashflow")
    public ResponseEntity<CashflowResponse> cashflow(@RequestParam(name = "month") String month) {
        Long userId = currentUser.requireUserId();
        YearMonth ym = parseMonth(month);
        CashflowResult r = assetsCashflowService.cashflow(userId, ym);
        CashflowResponse body = CashflowResponse.of(
                ym, r.outflow(), r.inflow(), r.netInflow(), r.todayOutflow(), r.todayInflow());
        return ResponseEntity.ok(body);
    }

    private YearMonth parseMonth(String raw) {
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException | NullPointerException ex) {
            throw ApiException.reportParamInvalid("month", "月份格式应为 YYYY-MM");
        }
    }
}
