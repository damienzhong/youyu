-- ============================================================================
-- 有余(youyu) 成长体系：user_growth 成长档案 + growth_events 成长事件表
--
-- 经验只增不减：growth_events 是只追加表，幂等由 (user_id, event_key) 唯一索引在数据库层保证，
--   不依赖应用层的「先查再写」。删交易 / 清回收站 / 改预算 / 被邀请人注销一律不扣经验、
--   不降级、不熄灭徽章。
-- 徽章复用本表：event_type='BADGE'、event_key='BADGE:<编码>'、exp_amount=0，
--   解锁时刻即该行 created_at；BADGE: 前缀是徽章的独占命名空间，与同名经验事件键
--   (FIRST_RECORD / STREAK_7 / STREAK_30 / BUDGET_MET) 双向隔离。故本脚本不建徽章表。
-- 等级曲线不落库：threshold(L)=2(L-1)^2+8(L-1) 由应用启动时派生，本脚本刻意不建任何阈值表，
--   以免出现「库里的表和代码里的公式对不上」这种最难查的缺陷。
-- 刻意不建指向 users(id) 的外键：注销时由 AccountDeletionService 在同一事务内显式删除两表的行，
--   以免为注销路径再追加一层外键顺序约束。正常运行下两表不应出现悬空 user_id
--   （与 invite_relations 刻意保留悬空 id 留痕的语义相反）。
-- 本脚本不回填任何存量用户的成长数据：迁移后两表行数均为 0，成长档案在各用户首次结算时惰性生成。
-- ============================================================================

CREATE TABLE user_growth (
    user_id             BIGINT   NOT NULL COMMENT '用户id(主键,非自增,由服务层以令牌用户id写入)',
    exp                 BIGINT   NOT NULL DEFAULT 0 COMMENT '经验值,等于该用户全部成长事件exp_amount之和',
    level               INT      NOT NULL DEFAULT 1 COMMENT '等级1-100,由经验按threshold公式换算',
    total_record_days   INT      NOT NULL DEFAULT 0 COMMENT '累计记账天数,等于DAILY_RECORD事件条数',
    current_streak_days INT      NOT NULL DEFAULT 0 COMMENT '连续段长度(是否已中断在读取时按判定日实时判定)',
    max_streak_days     INT      NOT NULL DEFAULT 0 COMMENT '历史最长连续天数,恒>=current_streak_days',
    last_record_date    DATE     NULL COMMENT '记账日历中的最大日期,日历为空时为NULL',
    last_settled_at     DATETIME NULL COMMENT '上次结算时刻,记账侧60秒节流的依据',
    created_at          DATETIME NOT NULL COMMENT '创建时间',
    updated_at          DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (user_id),
    CONSTRAINT ck_user_growth_level CHECK (level >= 1 AND level <= 100)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '用户成长档案(每用户至多一行,物化列可由growth_events与交易事实源完整重算)';

-- 表默认排序规则 utf8mb4_unicode_ci 大小写不敏感，若 CHECK 直接写 event_type IN (...)，
-- 则 'first_record' 也会通过，违背「区分大小写」。故在表达式内显式 COLLATE utf8mb4_bin
-- （写法对齐 V31__user_invite.sql 的 ck_invite_relations_status）。
-- 两个非唯一索引的列全部升序、名字不带 _desc 后缀：InnoDB 对
-- WHERE user_id = ? ORDER BY id DESC 反向扫描升序索引即可，无需降序索引。
CREATE TABLE growth_events (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '成长事件主键(经验明细按其倒序翻页)',
    user_id    BIGINT      NOT NULL COMMENT '用户id,无外键(注销时由服务层显式删除)',
    event_type VARCHAR(16) NOT NULL COMMENT '事件类型:FIRST_RECORD/DAILY_RECORD/STREAK/BUDGET_MET/FIRST_INVITE/BADGE',
    event_key  VARCHAR(64) NOT NULL COMMENT '幂等键,如DAILY_RECORD:2025-06-01/BUDGET_MET:2025-05/BADGE:RECORD_100',
    exp_amount INT         NOT NULL DEFAULT 0 COMMENT '经验值,>=0;徽章行恒为0',
    created_at DATETIME    NOT NULL COMMENT '写入时间(徽章的解锁时刻即此列)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_events_user_key (user_id, event_key),
    KEY idx_growth_events_user_type (user_id, event_type),
    KEY idx_growth_events_user_id (user_id, id),
    CONSTRAINT ck_growth_events_type
        CHECK (event_type COLLATE utf8mb4_bin IN
               ('FIRST_RECORD', 'DAILY_RECORD', 'STREAK', 'BUDGET_MET', 'FIRST_INVITE', 'BADGE')),
    CONSTRAINT ck_growth_events_exp CHECK (exp_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '成长事件(只追加表,经验与徽章共用,(user_id,event_key)唯一索引承担幂等)';
