-- ============================================================================
-- 有余(youyu) 多账本 —— 阶段二：强制 ledger_id 非空 + 外键；user_id 放宽为可空（下线）
--
-- 阶段一(V8)已给六张业务表回填 ledger_id。此处置为 NOT NULL 并加外键，正式以账本为隔离边界；
-- user_id 不再作为业务隔离键，放宽为可空（保留列以便回溯，不再由服务层写入）。
-- ============================================================================

-- 先加外键（此时数据已回填，非空约束随后置上）。
ALTER TABLE accounts         ADD CONSTRAINT fk_accounts_ledger    FOREIGN KEY (ledger_id) REFERENCES ledgers (id);
ALTER TABLE categories       ADD CONSTRAINT fk_categories_ledger  FOREIGN KEY (ledger_id) REFERENCES ledgers (id);
ALTER TABLE transactions     ADD CONSTRAINT fk_tx_ledger          FOREIGN KEY (ledger_id) REFERENCES ledgers (id);
ALTER TABLE budgets          ADD CONSTRAINT fk_budgets_ledger     FOREIGN KEY (ledger_id) REFERENCES ledgers (id);
ALTER TABLE category_budgets ADD CONSTRAINT fk_cat_budgets_ledger FOREIGN KEY (ledger_id) REFERENCES ledgers (id);
ALTER TABLE loans            ADD CONSTRAINT fk_loans_ledger       FOREIGN KEY (ledger_id) REFERENCES ledgers (id);

-- 置 ledger_id 为非空。
ALTER TABLE accounts         MODIFY COLUMN ledger_id BIGINT NOT NULL COMMENT '归属账本';
ALTER TABLE categories       MODIFY COLUMN ledger_id BIGINT NOT NULL COMMENT '归属账本';
ALTER TABLE transactions     MODIFY COLUMN ledger_id BIGINT NOT NULL COMMENT '归属账本';
ALTER TABLE budgets          MODIFY COLUMN ledger_id BIGINT NOT NULL COMMENT '归属账本';
ALTER TABLE category_budgets MODIFY COLUMN ledger_id BIGINT NOT NULL COMMENT '归属账本';
ALTER TABLE loans            MODIFY COLUMN ledger_id BIGINT NOT NULL COMMENT '归属账本';

-- user_id 放宽为可空（不再由服务层写入；隔离改由 ledger_id 承载）。
ALTER TABLE accounts         MODIFY COLUMN user_id BIGINT NULL COMMENT '归属用户(历史列,已由 ledger_id 取代)';
ALTER TABLE categories       MODIFY COLUMN user_id BIGINT NULL COMMENT '归属用户(历史列,已由 ledger_id 取代)';
ALTER TABLE transactions     MODIFY COLUMN user_id BIGINT NULL COMMENT '归属用户(历史列,已由 ledger_id 取代)';
ALTER TABLE budgets          MODIFY COLUMN user_id BIGINT NULL COMMENT '归属用户(历史列,已由 ledger_id 取代)';
ALTER TABLE category_budgets MODIFY COLUMN user_id BIGINT NULL COMMENT '归属用户(历史列,已由 ledger_id 取代)';
ALTER TABLE loans            MODIFY COLUMN user_id BIGINT NULL COMMENT '归属用户(历史列,已由 ledger_id 取代)';
