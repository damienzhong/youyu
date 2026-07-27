-- ============================================================================
-- 有余(youyu) 账单导入去重支持
-- external_id：第三方账单（支付宝/微信）的唯一标识（形如 "alipay:交易订单号" / "wechat:交易单号"），
--   用于重复导入去重。手动记账的流水 external_id 为 NULL。
-- 唯一索引 (user_id, external_id) 保证同一用户同一账单不会被导入两次；
--   MySQL 唯一索引允许多个 NULL，故不影响手动流水。
-- ============================================================================
ALTER TABLE transactions
    ADD COLUMN external_id VARCHAR(64) NULL COMMENT '第三方账单唯一标识(去重用),手动记账为NULL';

CREATE UNIQUE INDEX uk_tx_user_external ON transactions (user_id, external_id);
