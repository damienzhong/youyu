-- ============================================================================
-- 有余(youyu) 交易软删除（回收站）
-- transactions.deleted_at：非空表示已移入回收站（可恢复/彻底删除）。
-- 删除时反向回滚账户余额并置 deleted_at；恢复时重新应用余额并清空 deleted_at；
-- 彻底删除为物理删行（余额已在软删时回滚，无需再动）。
-- 实体以 @SQLRestriction("deleted_at is null") 全局过滤，常规查询/统计自动排除回收站记录；
-- 回收站的列出/恢复/彻底删除走原生 SQL 绕过该过滤。
-- ============================================================================
ALTER TABLE transactions
    ADD COLUMN deleted_at DATETIME NULL COMMENT '软删除时间(回收站),NULL=有效' AFTER updated_at;
CREATE INDEX idx_tx_ledger_deleted ON transactions (ledger_id, deleted_at);
