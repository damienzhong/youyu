package com.damien.youyu.api.dto;

import java.math.BigDecimal;

import com.damien.youyu.service.AccountService.RepayReminderView;

/**
 * 信用卡还款提醒响应：下一个还款日（yyyy-MM-dd）、剩余天数、待还金额（当前欠款）。
 *
 * <p>{@code GET /api/accounts/repay-reminders}，按剩余天数升序。仅含已开启还款提醒的信用卡。</p>
 */
public record RepayReminderResponse(
        Long accountId,
        String name,
        int repayDay,
        String nextRepayDate,
        int daysUntil,
        BigDecimal owed,
        int remindDays) {

    public static RepayReminderResponse from(RepayReminderView v) {
        return new RepayReminderResponse(
                v.accountId(), v.name(), v.repayDay(),
                v.nextRepayDate().toString(), v.daysUntil(), v.owed(), v.remindDays());
    }
}
