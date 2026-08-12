-- 周期记账·自动入账（Recurring_Auto_Post）：纯增量、可整块摘除。
-- 仅给 recurring_rules 增加一列 post_mode，不改其它任何表、不新增指向其它表的外键，
-- 不改 recurring_pending_items 结构；删除本列（或一律视作 CONFIRM）即整块摘除，
-- 周期系统回退为「到期只生成待确认项」的既有行为（需求 6.2、6.4）。

-- 入账方式：'CONFIRM'（待确认，默认，行为与现状一致）| 'AUTO'（到期自动入账并通知）。
-- 默认 'CONFIRM' 使存量行与未显式指定的新建规则一律向后兼容（需求 1.2、1.3）。
-- 不加 CHECK 约束（与既有频率子字段同思路，保持迁移简单可摘除），取值合法性由应用层枚举校验（需求 1.4）。
ALTER TABLE recurring_rules
    ADD COLUMN post_mode VARCHAR(16) NOT NULL DEFAULT 'CONFIRM';
