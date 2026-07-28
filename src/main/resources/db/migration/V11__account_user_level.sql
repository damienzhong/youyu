-- ============================================================================
-- 有余(youyu) 账户升级为「用户级」：独立账本共享同一批账户
--
-- 背景：账本类型分独立/协作。独立账本共用用户的真实账户（用户级），协作账本将来用账本级账户。
-- 现阶段把全部账户回填为用户级：user_id 从其所属账本回填并置非空；ledger_id 放宽为可空
-- （用户级账户 ledger_id 为空；未来协作账本的账本级账户才填 ledger_id）。
-- 账户余额跨账本按账户汇总，交易仍归属各自账本(ledger_id)不变。
-- ============================================================================

-- 从账户当前所属账本回填 user_id。
UPDATE accounts a JOIN ledgers l ON l.id = a.ledger_id SET a.user_id = l.user_id;

-- user_id 置非空（用户级账户的归属键）。
ALTER TABLE accounts MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '归属用户(账户为用户级)';

-- ledger_id 放宽为可空，并清空为「用户级」（独立账本共享）。
ALTER TABLE accounts MODIFY COLUMN ledger_id BIGINT NULL COMMENT '归属账本(仅协作账本的账本级账户使用;用户级账户为空)';
UPDATE accounts SET ledger_id = NULL;
