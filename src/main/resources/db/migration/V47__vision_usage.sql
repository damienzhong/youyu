-- ============================================================================
-- 有余(youyu) 截图识别每日用量(Vision_Usage)：一张新表
--   vision_usage  每个用户每天已用的截图识别张数，用于每日免费额度（默认 10 张）
--
-- 计数口径：识别前按本次张数预占额度（条件更新 used + n <= limit，并发安全），识别后把失败的、
--   不是账单的截图退回额度，只为真正识别出流水的截图计数。截图本身不落库、不落盘。
--
-- 本表刻意不建外键；用户注销由服务层级联清理。纯增量：只新建本表，不改动任何既有表。
-- ============================================================================
CREATE TABLE vision_usage (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL COMMENT '用户',
    usage_date DATE     NOT NULL COMMENT '自然日(Asia/Shanghai)',
    used       INT      NOT NULL DEFAULT 0 COMMENT '当天已计入额度的截图张数',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vision_usage_user_date (user_id, usage_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '截图识别每日用量(每日免费额度)';
