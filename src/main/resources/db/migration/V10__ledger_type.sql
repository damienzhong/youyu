-- ============================================================================
-- 有余(youyu) 账本类型：独立(INDEPENDENT) / 协作(COLLABORATIVE)
--
-- 独立账本：使用用户级"我的账户"(account.ledger_id 为空)，同一用户的多个独立账本共享其个人账户。
-- 协作账本：使用账本级账户(account.ledger_id 指向该账本)，供未来多人协作时账本内成员共享。
--
-- 本迁移仅新增类型列（存量账本全部视为独立），账户归属与余额逻辑的落地在后续迁移/代码中进行。
-- ============================================================================
ALTER TABLE ledgers
    ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'INDEPENDENT' COMMENT '账本类型: INDEPENDENT/COLLABORATIVE'
    AFTER name;
