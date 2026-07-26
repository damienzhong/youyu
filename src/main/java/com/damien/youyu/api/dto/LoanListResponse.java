package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 借贷列表 + 汇总响应体（GET /api/loans）。
 *
 * <p>{@code borrowOutstanding} 借入/待还合计（未结清 BORROW 之和）；
 * {@code lendOutstanding} 借出/待收合计（未结清 LEND 之和）；{@code loans} 全部借贷明细。</p>
 */
public record LoanListResponse(
        BigDecimal borrowOutstanding,
        BigDecimal lendOutstanding,
        List<LoanResponse> loans) {
}
