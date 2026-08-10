-- 修正：V38 建表时把 recurring_rules.month_end 写成 TINYINT（无显示宽度），
-- MySQL 驱动按 TINYINT 上报，与实体朴素 boolean（Hibernate 默认按 BIT/BOOLEAN 校验）不匹配，
-- 导致启动 Schema-validation 失败：found [tinyint] but expecting [bit]。
-- 改为 TINYINT(1)：MySQL 驱动 tinyInt1isBit 默认把 TINYINT(1) 当 BIT 上报，
-- 与本项目其它布尔列（accounts.include_in_total、loans.settled 等）约定一致。
-- 不改 V38 本体（已应用，改动会破坏 Flyway 校验和）；新增迁移对新老库都适用。
ALTER TABLE recurring_rules
    MODIFY COLUMN month_end TINYINT(1) NOT NULL DEFAULT 0;
