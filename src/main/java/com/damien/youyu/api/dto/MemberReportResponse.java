package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成员消费占比报表（协作账本用）：选定日期范围（含起止边界）内，各成员在指定类别（支出/收入）的
 * 金额、占该类别总额百分比与笔数。转账一律排除。各成员占比之和恒为 100.00（末项余数校正）。
 * {@code total} 为该类别（支出或收入）的总额。
 */
public record MemberReportResponse(
        String from, String to, BigDecimal total, List<MemberShare> members) {

    /** {@code displayName} 为成员账号标识（微信用户可能为空，前端回退展示）。 */
    public record MemberShare(
            Long userId, String displayName, BigDecimal amount, BigDecimal percentage, long count) {
    }
}
