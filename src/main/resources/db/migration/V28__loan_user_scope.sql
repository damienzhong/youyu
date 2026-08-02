-- ============================================================================
-- 有余(youyu) 借贷回归「用户级」隔离
--
-- 账户是独立于账本的用户级实体，借贷影响账户余额与净资产，因此借贷也应按用户隔离
-- （而非 V8/V9 引入的账本级）。本迁移：
--   1) 由账本回填 loans/loan_repayments 的 user_id；
--   2) 解除 loans → ledgers 外键、放开 ledger_id 可空（借贷不再随账本删除）；
--   3) user_id 收紧为非空（隔离键）。
-- ============================================================================

-- 1) 回填 user_id。
UPDATE loans l JOIN ledgers g ON l.ledger_id = g.id
    SET l.user_id = g.user_id
    WHERE l.user_id IS NULL;

UPDATE loan_repayments r JOIN loans l ON r.loan_id = l.id
    SET r.user_id = l.user_id;

-- 2) 解除账本外键并放开 ledger_id 可空（新借贷不再绑定账本）。
ALTER TABLE loans DROP FOREIGN KEY fk_loans_ledger;
ALTER TABLE loans MODIFY COLUMN ledger_id BIGINT NULL COMMENT '归属账本(历史列,借贷已回归用户级)';
ALTER TABLE loan_repayments MODIFY COLUMN ledger_id BIGINT NULL COMMENT '归属账本(历史列)';

-- 3) user_id 作为隔离键收紧为非空。
ALTER TABLE loans MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '归属用户(隔离键)';
ALTER TABLE loan_repayments MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '归属用户(隔离键)';

ALTER TABLE loans ADD KEY idx_loans_user_settled2 (user_id, settled);
