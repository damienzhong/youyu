-- ============================================================================
-- 有余(youyu) 账户扩展字段
-- 对齐主流记账 App 的账户表单：
--   include_in_total 余额是否计入净资产（关闭则不计入首页/资产页净资产）
--   hidden           是否隐藏账户（记账选择账户时不展示，历史流水保留）
--   note             账户备注（<=200）
-- 布尔用 TINYINT(1)（配合实体 @JdbcTypeCode(TINYINT)，与 Hibernate validate 匹配）。
-- ============================================================================
ALTER TABLE accounts
    ADD COLUMN include_in_total TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '余额是否计入净资产',
    ADD COLUMN hidden           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否隐藏账户(记账不展示)',
    ADD COLUMN note             VARCHAR(200) NULL               COMMENT '账户备注,<=200';
