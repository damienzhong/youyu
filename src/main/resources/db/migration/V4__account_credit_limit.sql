-- ============================================================================
-- 有余(youyu) 账户信用额度字段
-- credit_limit：信用卡授信额度（可空，仅信用卡类型有意义）。
--   可用余额 = credit_limit + current_balance（current_balance 欠款为负）。
-- 金额 DECIMAL(18,2)，与其余金额列一致。
-- ============================================================================
ALTER TABLE accounts
    ADD COLUMN credit_limit DECIMAL(18,2) NULL COMMENT '信用卡授信额度(可空,仅信用卡有意义)';
