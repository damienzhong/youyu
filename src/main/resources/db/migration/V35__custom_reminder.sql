-- ============================================================================
-- 有余(youyu) 自定义提醒(Custom Reminder)：三张新表
--   custom_reminders    提醒配置(频率+时间+开关),同一用户同一频率同一时间至多一条
--   reminder_quota      每用户微信一次性订阅剩余额度([0,50],授权累加/成功发送扣减/43101归零)
--   reminder_send_logs  每次发送尝试的落表结果((reminder_id,trigger_date)唯一)
--
-- 幂等由发送记录唯一键 uk_reminder_send_logs_reminder_date (reminder_id,trigger_date)
--   构造性保证:同一提醒同一触发日至多一条发送记录,不靠调度器不重叠这种时序巧合。
-- 频率按自然日的星期几判定,刻意不接入国务院法定节假日与调休安排:
--   工作日恒为周一至周五、周末恒为周六与周日(否则要引入并长期维护逐年更新的节假日表)。
-- 三张表刻意不建指向 users(id) 的外键:注销时由 AccountDeletionService 在同一事务内显式删除,
--   与 user_growth / growth_events / streak_segments 同一取舍。
-- 本 spec 为纯增量:只新建这三张表,只读 user_growth.last_record_date 与 users.wx_openid,
--   不改动任何既有表、不对任何既有表执行 DML;不使用窗口函数/CONVERT_TZ/存储过程/触发器。
-- ============================================================================

CREATE TABLE custom_reminders (
    reminder_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '用户id,无外键(注销时由服务层显式删除)',
    frequency   VARCHAR(16)  NOT NULL COMMENT '频率:DAILY每天/WEEKDAY工作日/WEEKEND周末(区分大小写)',
    remind_time TIME         NOT NULL COMMENT '每日触发时刻(分钟粒度,Asia/Shanghai口径)',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用:1启用0停用(停用不参与触发)',
    created_at  DATETIME     NOT NULL COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL COMMENT '最后更新时间',
    PRIMARY KEY (reminder_id),
    UNIQUE KEY uk_custom_reminders_user_freq_time (user_id, frequency, remind_time),
    KEY idx_custom_reminders_enabled_time (enabled, remind_time),
    CONSTRAINT ck_custom_reminders_frequency CHECK (frequency IN ('DAILY','WEEKDAY','WEEKEND'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '自定义提醒配置((user_id,frequency,remind_time)唯一,同一频率同一时间不重复建两条)';

CREATE TABLE reminder_quota (
    user_id    BIGINT   NOT NULL COMMENT '用户id(主键,一人一行),无外键(注销时由服务层显式删除)',
    remaining  INT      NOT NULL DEFAULT 0 COMMENT '剩余一次性订阅额度,取值范围[0,50]',
    created_at DATETIME NOT NULL COMMENT '首次授权上报时间',
    updated_at DATETIME NOT NULL COMMENT '最后一次额度增减时间',
    PRIMARY KEY (user_id),
    CONSTRAINT ck_reminder_quota_remaining CHECK (remaining >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '每用户订阅剩余额度(授权累加,成功发送扣减,微信43101时归零对齐)';

CREATE TABLE reminder_send_logs (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    reminder_id     BIGINT      NOT NULL COMMENT '来源提醒id,无外键(注销时由服务层显式删除)',
    user_id         BIGINT      NOT NULL COMMENT '用户id,无外键(注销时由服务层显式删除)',
    trigger_date    DATE        NOT NULL COMMENT '触发日(Asia/Shanghai自然日)',
    result          VARCHAR(24) NOT NULL COMMENT '发送结果:SENT/SKIPPED_NO_QUOTA/SKIPPED_STALE/FAILED',
    message_variant VARCHAR(16) NOT NULL COMMENT '文案变体:DONE已完成/NOT_YET未记账',
    wx_errcode      INT         NULL     COMMENT '微信errcode(SENT为0,SKIPPED为空,FAILED为微信码或空)',
    created_at      DATETIME    NOT NULL COMMENT '发送尝试时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reminder_send_logs_reminder_date (reminder_id, trigger_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '提醒发送记录((reminder_id,trigger_date)唯一,构造性保证同日至多一条→幂等)';
