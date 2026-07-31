-- ============================================================================
-- 有余(youyu) 信用卡账单/还款字段
-- bill_day / repay_day：账单日 / 还款日（1-28，可空，仅信用卡类型有意义）。
--   固定 1-28 以规避大小月/闰月边界（如 30、31 日在部分月份不存在）。
-- repay_reminder：还款提醒开关（开启后还款日在记账日历高亮/提醒）。
-- ============================================================================
ALTER TABLE accounts
    ADD COLUMN bill_day       INT        NULL COMMENT '账单日(1-28,可空,仅信用卡)',
    ADD COLUMN repay_day      INT        NULL COMMENT '还款日(1-28,可空,仅信用卡)',
    ADD COLUMN repay_reminder TINYINT(1) NOT NULL DEFAULT 0 COMMENT '还款提醒开关';
