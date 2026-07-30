-- ============================================================================
-- 有余(youyu) 账户与账本解耦重构
-- MySQL 8.x / utf8mb4
--
-- 目标：
--   1. 账户升级为独立于账本的一等实体：始终归属用户(owner=user_id)，废弃"账本级账户"。
--   2. 新增 account_ledger 多对多可见性：账户参与哪些账本、是否对协作成员可见、是否显示余额。
--   3. 转账下沉为账户间动作，脱离账本：transactions.ledger_id 放宽为可空。
--   4. 账本类型枚举 INDEPENDENT -> PERSONAL（个人账本），COLLABORATIVE 不变。
--
-- 无存量数据迁移（当前仅测试数据）；本脚本以建立新结构为目标。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 账户去"账本级"概念：owner 即 user_id。
--    先删外键与索引，再删 ledger_id 列（V9 加的 fk_accounts_ledger / V8 加的 idx_accounts_ledger）。
-- ----------------------------------------------------------------------------
ALTER TABLE accounts DROP FOREIGN KEY fk_accounts_ledger;
ALTER TABLE accounts DROP INDEX idx_accounts_ledger;
ALTER TABLE accounts DROP COLUMN ledger_id;

-- ----------------------------------------------------------------------------
-- 2. 账户/账本多对多可见性关联表。
--    一行 = "账户参与该账本"；两个标志正交：
--      visible_to_others：协作账本内是否对其他成员可见/可选（个人账本单成员时无意义，取默认）；
--      show_balance：是否对其他成员显示真实余额（AA 场景可关闭）。
-- ----------------------------------------------------------------------------
CREATE TABLE account_ledger (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    account_id        BIGINT      NOT NULL COMMENT '账户',
    ledger_id         BIGINT      NOT NULL COMMENT '账本',
    visible_to_others TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '协作账本内是否对其他成员可见/可选',
    show_balance      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否对其他成员显示真实余额',
    created_at        DATETIME    NOT NULL COMMENT '关联建立时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_ledger (account_id, ledger_id),
    KEY idx_al_ledger (ledger_id),
    CONSTRAINT fk_al_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_al_ledger  FOREIGN KEY (ledger_id)  REFERENCES ledgers (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '账户与账本的多对多可见性关联';

-- ----------------------------------------------------------------------------
-- 3. 转账脱离账本：ledger_id 放宽为可空。
--    形态约束（expense/income 必填 ledger_id；transfer 为空）由服务层保证。
-- ----------------------------------------------------------------------------
ALTER TABLE transactions MODIFY COLUMN ledger_id BIGINT NULL COMMENT '归属账本(仅收支;转账为空)';

-- ----------------------------------------------------------------------------
-- 4. 账本类型枚举更新：INDEPENDENT -> PERSONAL。
-- ----------------------------------------------------------------------------
UPDATE ledgers SET type = 'PERSONAL' WHERE type = 'INDEPENDENT';
ALTER TABLE ledgers
    MODIFY COLUMN type VARCHAR(16) NOT NULL DEFAULT 'PERSONAL' COMMENT '账本类型: PERSONAL/COLLABORATIVE';
