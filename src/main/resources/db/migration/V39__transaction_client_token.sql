-- ============================================================================
-- 有余(youyu) 离线记账幂等支持（Offline_Sync_System）
-- client_token：小程序端离线/弱网记账时，前端为每条创建操作生成的客户端幂等键
--   （形如 "ct_<uuid>"）。在线手动记账不传时为 NULL，与历史数据一致。
-- 唯一约束按 (created_by, client_token) 构造：created_by 是交易创建路径实际写入的「记账人」列
--   （transactions.user_id 为 V9 之后的历史遗留可空列，创建路径不写入，不能用作归属键）。
--   该约束保证「同一记账人同一 client_token」至多落一笔，即断线重连、重复重放也不会重复记账；
--   MySQL 唯一索引允许多个 NULL，故不影响既有历史流水与在线手动记账（client_token 为 NULL）。
-- 纯增量、可整块摘除：删除本列与索引即可回收，既有代码不读该列。
-- ============================================================================
ALTER TABLE transactions
    ADD COLUMN client_token VARCHAR(64) NULL COMMENT '客户端幂等键(离线记账去重用),在线手动记账为NULL';

CREATE UNIQUE INDEX uk_tx_creator_client_token ON transactions (created_by, client_token);
