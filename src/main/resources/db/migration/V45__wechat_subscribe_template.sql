-- ============================================================================
-- 有余(youyu) 微信订阅消息模板配置(Wechat_Subscribe_Template)：一张新表
--   wechat_subscribe_templates  按业务类型(记账提醒/预算提醒)配置微信订阅消息模板 id 与启用开关
--
-- 背景：模板 id 原先来自环境变量(app.wechat.subscribe.*-template-id)，运营调整模板需改环境变量并重启。
--   本表把模板 id 迁到数据库配置：biz_type 为主键(REMINDER=记账提醒 / BUDGET=预算超支通知)，
--   template_id 为微信后台申请到的模板 id，enabled 为启用开关(0 时视为未配置、发送安全降级)。
--   SubscribeTemplateProvider 只读查库；查不到/未启用/查库异常时视为未配置，发送安全降级为本地失败，
--   既不外呼微信、也不影响记账/评估等任何主路径。
--
-- 本表刻意不建任何外键；seed 用固定时间字面量(不用 NOW()/CURRENT_TIMESTAMP 等函数)，
--   保证 MySQL 与 H2 MODE=MySQL 均可执行。纯增量：只新建本表,不改动任何既有表。
-- ============================================================================

CREATE TABLE wechat_subscribe_templates (
    biz_type    VARCHAR(32)  NOT NULL COMMENT '业务类型(主键):REMINDER记账提醒/BUDGET预算超支通知',
    template_id VARCHAR(128) NOT NULL COMMENT '微信订阅消息模板id(微信后台申请)',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用:1启用0停用(停用时视为未配置,发送安全降级)',
    remark      VARCHAR(255) NULL     COMMENT '备注(模板名称/微信后台模板编号等,便于运维辨识)',
    created_at  DATETIME     NOT NULL COMMENT '建档时间',
    updated_at  DATETIME     NOT NULL COMMENT '最后一次更新时间',
    PRIMARY KEY (biz_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '微信订阅消息模板配置(按业务类型配置模板id与启用开关,替代环境变量)';

-- 记账提醒(每日记账提醒)：time1时间 + thing3提醒内容 + thing4温馨提示。
INSERT INTO wechat_subscribe_templates(biz_type, template_id, enabled, remark, created_at, updated_at)
VALUES ('REMINDER', 'UBIyePri1R--zAgGC1bqjW1dUAy9ZxwCQdrMzbes3mg', 1, '每日记账提醒 1646',
        '2026-01-01 00:00:00', '2026-01-01 00:00:00');

-- 预算提醒(预算超支通知)：amount2超预算金额 + time3时间 + thing4备注。
INSERT INTO wechat_subscribe_templates(biz_type, template_id, enabled, remark, created_at, updated_at)
VALUES ('BUDGET', 'bx3t6uk8ZkpRp_haPFPnlpiej7sXtyFxOsVVOFuOy6I', 1, '预算超支通知 66466',
        '2026-01-01 00:00:00', '2026-01-01 00:00:00');
