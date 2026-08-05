-- ============================================================================
-- 有余(youyu) 成就系统：achievement_notices 播报游标 + growth_events 事件类型扩容
--
-- 不建成就表：成就仍是 growth_events 里 event_type='BADGE'、event_key='BADGE:<编码>'、
--   exp_amount=0 的行，解锁时刻即该行 created_at。本脚本只加「播报到哪儿」这一个新事实。
-- 播报语义是「至少一次」：游标只增不减(服务层用 GREATEST 推进)，确认丢失只导致重播、绝不漏播。
--   因此本表只存 last_notified_event_id 一个业务列，不存「已播报过哪些编码」这种集合。
-- 刻意不建指向 users(id) 的外键：注销时由 AccountDeletionService 在同一事务内显式删除，
--   与 user_growth / growth_events 同一取舍，以免为注销路径再追加一层外键顺序约束。
-- 回填游标：存量用户的历史徽章一律视为已播报，否则升级后第一次打开小程序会被 9 枚历史成就
--   连续轰炸。没有任何 BADGE 行的用户不回填(游标缺失按 0 处理，语义等价且省一行)。
-- ============================================================================

CREATE TABLE achievement_notices (
    user_id                BIGINT   NOT NULL COMMENT '用户id(主键,非自增,由服务层以令牌用户id写入)',
    last_notified_event_id BIGINT   NOT NULL DEFAULT 0 COMMENT '已播报到的最大成就事件id(growth_events.id),只增不减',
    created_at             DATETIME NOT NULL COMMENT '创建时间',
    updated_at             DATETIME NOT NULL COMMENT '更新时间(仅在游标推进时同步更新)',
    PRIMARY KEY (user_id),
    CONSTRAINT ck_achievement_notices_event_id CHECK (last_notified_event_id >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '成就播报游标(每用户至多一行,只存已播报到哪一条成就事件)';

-- event_type 取值集合从 6 个扩到 7 个，新增 SAVING_MONTH(储蓄月事实事件, exp 恒为 0)。
-- 表默认排序规则 utf8mb4_unicode_ci 大小写不敏感，若 CHECK 直接写 event_type IN (...)，
-- 则 'saving_month' 也会通过，违背「区分大小写」。故在表达式内显式 COLLATE utf8mb4_bin
-- (写法与 V32__user_growth.sql 逐字一致)。先 DROP 再 ADD 同名约束。
ALTER TABLE growth_events DROP CONSTRAINT ck_growth_events_type;
ALTER TABLE growth_events ADD CONSTRAINT ck_growth_events_type
    CHECK (event_type COLLATE utf8mb4_bin IN
           ('FIRST_RECORD', 'DAILY_RECORD', 'STREAK', 'BUDGET_MET',
            'FIRST_INVITE', 'BADGE', 'SAVING_MONTH'));

-- 仅同步中文注释里的类型清单：列类型、可空性与长度一字不改
-- (SAVING_MONTH 是 12 个字符，VARCHAR(16) 容得下；键 SAVING_MONTH:YYYY-MM 是 20 个字符，
--  event_key VARCHAR(64) 容得下)。
ALTER TABLE growth_events MODIFY COLUMN event_type VARCHAR(16) NOT NULL
    COMMENT '事件类型:FIRST_RECORD/DAILY_RECORD/STREAK/BUDGET_MET/FIRST_INVITE/BADGE/SAVING_MONTH';

-- 游标回填：每个有 BADGE 行的用户一行，取其最大 BADGE 事件 id；created_at/updated_at 同一时刻。
-- 只读 growth_events、只写 achievement_notices，不修改 growth_events 与 user_growth 的任何行。
INSERT INTO achievement_notices (user_id, last_notified_event_id, created_at, updated_at)
SELECT user_id, MAX(id), NOW(), NOW()
FROM growth_events
WHERE event_type COLLATE utf8mb4_bin = 'BADGE'
GROUP BY user_id;
