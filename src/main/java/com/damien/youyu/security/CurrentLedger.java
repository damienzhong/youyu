package com.damien.youyu.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.damien.youyu.service.LedgerService;

/**
 * 当前会话账本上下文：把「当前账本」解析收敛到单一入口，与 {@link CurrentUser}（当前用户）并列。
 *
 * <p>解析规则：读取请求头 {@code X-Ledger-Id}；若提供则校验其归属当前用户（越权/不存在抛 NOT_FOUND），
 * 否则回退到用户的默认账本（不存在则惰性创建）。业务服务以此得到 {@code ledgerId} 实现按账本隔离——
 * 写入强制覆盖 {@code ledger_id}、读取按 {@code ledger_id} 过滤。</p>
 */
@Component
public class CurrentLedger {

    public static final String HEADER = "X-Ledger-Id";

    private final CurrentUser currentUser;
    private final LedgerService ledgerService;

    public CurrentLedger(CurrentUser currentUser, LedgerService ledgerService) {
        this.currentUser = currentUser;
        this.ledgerService = ledgerService;
    }

    /** 返回当前请求应作用的账本 id；无有效会话时抛未认证。 */
    public Long requireLedgerId() {
        return requireLedger().getId();
    }

    /** 返回当前请求应作用的账本实体（校验成员可访问）；无有效会话时抛未认证。 */
    public com.damien.youyu.domain.Ledger requireLedger() {
        Long userId = currentUser.requireUserId();
        Long headerLedgerId = readHeaderLedgerId();
        if (headerLedgerId != null) {
            return ledgerService.requireAccessible(userId, headerLedgerId);
        }
        return ledgerService.ensureDefaultLedger(userId);
    }

    private Long readHeaderLedgerId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        String raw = attrs.getRequest().getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
