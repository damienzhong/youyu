package com.damien.youyu.api.dto;

import com.damien.youyu.domain.AccountLedger;

/**
 * 账户与账本的参与关联（批量），用于资产页展示每个账户"参与了哪些账本"，
 * 以及账户详情里管理参与状态。账本名称/类型由前端按 ledgerId 从账本列表解析，
 * 避免服务层为聚合再引入账本仓储依赖。
 */
public record AccountLedgerLinkResponse(
        Long accountId,
        Long ledgerId,
        boolean visibleToOthers,
        boolean showBalance) {

    public static AccountLedgerLinkResponse from(AccountLedger al) {
        return new AccountLedgerLinkResponse(
                al.getAccountId(), al.getLedgerId(), al.isVisibleToOthers(), al.isShowBalance());
    }
}
