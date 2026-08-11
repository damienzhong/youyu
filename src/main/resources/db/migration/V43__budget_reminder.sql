-- ============================================================================
-- 有余(youyu) 预算提醒(Budget_Reminder_System)：两张新表
--   budget_reminder_settings   预算提醒偏好 + 独立订阅额度([0,50]，授权累加/成功发送扣减/43101归零)
--   budget_reminder_send_logs  每次发送尝试的落表结果
--
-- 「每月每范围每级别至多一条」由发送记录唯一键
--   uk_budget_reminder_send_logs_scope (user_id, ledger_id, budget_month, scope_ref, level)
--   构造性保证：即使交易反复增删使已用比例上下穿越阈值，同一收件人同范围同月同级别至多一条 SENT，
--   不靠「一次评估只发一次」这种时序巧合。并发触发时后写入者撞唯一键、静默放弃本次。
--
-- 预算提醒的模板、额度、发送记录全部独立新增，与 custom-reminder 的 reminder_quota /
--   reminder_send_logs 及其模板互不影响（微信一次性订阅额度按模板计，预算提醒用独立模板）。
--
-- 两张表刻意不建指向 users(id) / ledgers(id) / 分类表的外键：注销时由 AccountDeletionService 在
--   同一事务内按 user_id 显式删除，与 user_growth / custom_reminders 等同一取舍。
-- 本 spec 为纯增量：只新建这两张表，只读 budgets / category_budgets / transactions /
--   ledger_members / users.wx_openid 等既有数据用于评估，不改动任何既有表、不对既有表执行 DML；
--   不使用窗口函数 / CONVERT_TZ / 存储过程 / 触发器，MySQL 与 H2 MODE=MySQL 均可执行。
-- ============================================================================

CREATE TABLE budget_reminder_settings (
    user_id    BIGINT     NOT NULL COMMENT '用户id(主键,一人一行),无外键(注销时由服务层显式删除)',
    enabled    TINYINT(1) NOT NULL DEFAULT 1 COMMENT '预算提醒偏好:1开启0关闭(无记录视为开启)',
    remaining  INT        NOT NULL DEFAULT 0 COMMENT '预算提醒剩余订阅次数,取值范围[0,50],独立于记账提醒',
    created_at DATETIME   NOT NULL COMMENT '首次建档时间',
    updated_at DATETIME   NOT NULL COMMENT '最后一次偏好/额度更新时间',
    PRIMARY KEY (user_id),
    CONSTRAINT ck_budget_reminder_settings_remaining CHECK (remaining >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '预算提醒偏好与独立订阅额度(授权累加,成功发送扣减,微信43101归零对齐)';

CREATE TABLE budget_reminder_send_logs (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id      BIGINT      NOT NULL COMMENT '收件人用户id,无外键(注销时由服务层显式删除)',
    ledger_id    BIGINT      NOT NULL COMMENT '账本id,无外键',
    budget_month VARCHAR(7)  NOT NULL COMMENT '预算自然月(Asia/Shanghai,格式YYYY-MM)',
    scope_ref    BIGINT      NOT NULL COMMENT '预算范围:0表示月度总预算,大于0表示分类id',
    level        VARCHAR(8)  NOT NULL COMMENT '预警级别:WARN预警/OVER超支(区分大小写)',
    result       VARCHAR(24) NOT NULL COMMENT '发送结果:SENT/SKIPPED_NO_QUOTA/SKIPPED_NO_OPENID/FAILED',
    wx_errcode   INT         NULL     COMMENT '微信errcode(SENT为0,SKIPPED为空,FAILED为微信码或空)',
    created_at   DATETIME    NOT NULL COMMENT '发送尝试时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_budget_reminder_send_logs_scope (user_id, ledger_id, budget_month, scope_ref, level),
    CONSTRAINT ck_budget_reminder_send_logs_level CHECK (level IN ('WARN','OVER'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '预算提醒发送记录(唯一键构造性保证每月每范围每级别至多一条→幂等)';
