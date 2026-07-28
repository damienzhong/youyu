package com.damien.youyu.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.BillImportRequest;
import com.damien.youyu.api.dto.BillImportResponse;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.BillImportService;

/**
 * 账单导入接口。
 *
 * <p>支付宝/微信 CSV 由前端解析归一化后提交，后端批量落库并去重。身份由 Spring Security 统一鉴权，
 * 所有写入按会话 ledgerId 隔离。</p>
 *
 * <ul>
 *   <li>POST {@code /api/imports/bills} 批量导入账单，返回 导入/去重/非法 计数。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final BillImportService billImportService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public ImportController(BillImportService billImportService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.billImportService = billImportService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 批量导入账单流水。 */
    @PostMapping("/bills")
    public ResponseEntity<BillImportResponse> importBills(@RequestBody BillImportRequest req) {
        com.damien.youyu.domain.Ledger ledger = currentLedger.requireLedger();
        com.damien.youyu.service.AccountScope scope =
                com.damien.youyu.service.AccountScope.forLedger(currentUser.requireUserId(), ledger);
        return ResponseEntity.ok(billImportService.importBills(scope, ledger.getId(), req));
    }
}
