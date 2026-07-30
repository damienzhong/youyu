-- ============================================================================
-- 有余(youyu) 内测前清库：清空所有业务表数据，保留表结构与 Flyway 迁移历史。
--
-- ⚠️ 破坏性、不可逆！仅用于内测重置。执行前务必已备份（见 reset-db.sh 会自动 mysqldump）。
-- 保留 flyway_schema_history：应用无需重新迁移，清空后直接可用（重新注册账号即可）。
--
-- 用法（在目标库内执行）：
--   mysql -u youyu -p youyu < deploy/reset-db.sql
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- 子表 / 关联表先清（顺序其实无关，因为已关外键检查，这里按依赖从叶到根排列以便阅读）
TRUNCATE TABLE transaction_tags;
TRUNCATE TABLE transactions;
TRUNCATE TABLE category_budgets;
TRUNCATE TABLE budgets;
TRUNCATE TABLE loans;
TRUNCATE TABLE transaction_templates;
TRUNCATE TABLE account_ledger;
TRUNCATE TABLE accounts;
TRUNCATE TABLE categories;
TRUNCATE TABLE tags;
TRUNCATE TABLE projects;
TRUNCATE TABLE merchants;
TRUNCATE TABLE ledger_invites;
TRUNCATE TABLE ledger_members;
TRUNCATE TABLE ledgers;
TRUNCATE TABLE users;

SET FOREIGN_KEY_CHECKS = 1;

-- 注意：未清空 flyway_schema_history（迁移历史保留，应用不需要重跑迁移）。
