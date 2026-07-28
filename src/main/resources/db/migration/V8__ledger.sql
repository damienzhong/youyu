-- ============================================================================
-- 有余(youyu) 多账本 —— 阶段一：新增 ledgers 表，给现有六张业务表补 ledger_id 并回填
-- MySQL 8.x / utf8mb4
--
-- 设计：账本是完整独立的记账空间；账户/分类/交易/预算/分类预算/借贷归属到某账本。
-- 迁移零风险：为每个现有用户建一个「默认账本」，把其全部业务数据回填到该账本。
-- 本阶段 ledger_id 暂为可空（服务层尚未写入），阶段二(V9)再置为 NOT NULL 并下线 user_id。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- ledgers 账本表：归属用户；一个用户可有多个账本。
-- ----------------------------------------------------------------------------
CREATE TABLE ledgers (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL COMMENT '归属用户',
    name        VARCHAR(50) NOT NULL COMMENT '账本名称,去空白后 1-50',
    sort_order  INT         NOT NULL DEFAULT 0 COMMENT '列表排序',
    is_default  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否默认账本(每用户唯一)',
    created_at  DATETIME    NOT NULL COMMENT '创建时间',
    updated_at  DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_ledgers_user (user_id, sort_order, id),
    CONSTRAINT fk_ledgers_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '账本';

-- 为每个现有用户创建一个默认账本。
INSERT INTO ledgers (user_id, name, sort_order, is_default, created_at, updated_at)
SELECT id, '默认账本', 0, 1, NOW(), NOW() FROM users;

-- ----------------------------------------------------------------------------
-- 六张业务表补 ledger_id（可空），并从「用户的默认账本」回填。
-- ----------------------------------------------------------------------------
ALTER TABLE accounts        ADD COLUMN ledger_id BIGINT NULL COMMENT '归属账本' AFTER user_id;
ALTER TABLE categories      ADD COLUMN ledger_id BIGINT NULL COMMENT '归属账本' AFTER user_id;
ALTER TABLE transactions    ADD COLUMN ledger_id BIGINT NULL COMMENT '归属账本' AFTER user_id;
ALTER TABLE budgets         ADD COLUMN ledger_id BIGINT NULL COMMENT '归属账本' AFTER user_id;
ALTER TABLE category_budgets ADD COLUMN ledger_id BIGINT NULL COMMENT '归属账本' AFTER user_id;
ALTER TABLE loans           ADD COLUMN ledger_id BIGINT NULL COMMENT '归属账本' AFTER user_id;

UPDATE accounts a         JOIN ledgers l ON l.user_id = a.user_id SET a.ledger_id = l.id;
UPDATE categories c       JOIN ledgers l ON l.user_id = c.user_id SET c.ledger_id = l.id;
UPDATE transactions t     JOIN ledgers l ON l.user_id = t.user_id SET t.ledger_id = l.id;
UPDATE budgets b          JOIN ledgers l ON l.user_id = b.user_id SET b.ledger_id = l.id;
UPDATE category_budgets cb JOIN ledgers l ON l.user_id = cb.user_id SET cb.ledger_id = l.id;
UPDATE loans ln           JOIN ledgers l ON l.user_id = ln.user_id SET ln.ledger_id = l.id;

-- 索引（阶段二再加 NOT NULL 与外键约束）。
ALTER TABLE accounts         ADD KEY idx_accounts_ledger (ledger_id, sort_order, id);
ALTER TABLE categories       ADD KEY idx_categories_ledger (ledger_id, kind, parent_id);
ALTER TABLE transactions     ADD KEY idx_tx_ledger_time (ledger_id, occurred_at);
ALTER TABLE budgets          ADD KEY idx_budgets_ledger (ledger_id, budget_month);
ALTER TABLE category_budgets ADD KEY idx_cat_budgets_ledger (ledger_id, budget_month);
ALTER TABLE loans            ADD KEY idx_loans_ledger (ledger_id, settled);
