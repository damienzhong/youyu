-- ============================================================================
-- 有余(youyu) 协作账本：流水记账人归属
-- MySQL 8.x / utf8mb4（H2 dev 走 MySQL 兼容模式）
--
-- 为交易补 created_by（记账人），协作账本据此区分是哪位成员记的账。
-- 回填：优先取交易历史 user_id（重构前的归属），否则取所属账本的创建者。
-- ============================================================================

ALTER TABLE transactions
    ADD COLUMN created_by BIGINT NULL COMMENT '记账人(协作账本区分成员)' AFTER user_id;

UPDATE transactions t
    JOIN ledgers l ON l.id = t.ledger_id
    SET t.created_by = COALESCE(t.user_id, l.user_id);

ALTER TABLE transactions ADD KEY idx_tx_created_by (created_by);
