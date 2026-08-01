-- ============================================================================
-- 有余(youyu) 信用卡还款「提前提醒天数」
-- MySQL 8.x / utf8mb4
--
-- 还款日前多少天开始提醒（默认 3 天）。仅信贷账户有意义，可空。
-- ============================================================================

ALTER TABLE accounts
    ADD COLUMN repay_remind_days INT NULL COMMENT '还款日前多少天开始提醒(1-28,默认3;仅信用卡)';

-- 已开启还款提醒的信用卡，回填默认提前 3 天。
UPDATE accounts SET repay_remind_days = 3 WHERE repay_reminder = 1 AND repay_remind_days IS NULL;
