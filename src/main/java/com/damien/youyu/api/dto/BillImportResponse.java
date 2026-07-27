package com.damien.youyu.api.dto;

/**
 * 账单批量导入结果。
 *
 * @param imported          成功导入笔数
 * @param skippedDuplicate  因重复（同 external_id 已存在）跳过的笔数
 * @param skippedInvalid    因数据非法（金额/类型/分类等）跳过的笔数
 */
public record BillImportResponse(
        int imported,
        int skippedDuplicate,
        int skippedInvalid) {
}
