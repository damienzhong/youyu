package com.damien.youyu.api.dto;

import java.util.List;

/**
 * 借贷详情响应体：借贷本身 + 收款/还款子台账明细（发生时间倒序）。
 */
public record LoanDetailResponse(
        LoanResponse loan,
        List<LoanRepaymentResponse> repayments) {
}
