-- ============================================================================
-- 有余(youyu) 项目模块 schema
-- projects 项目：把若干流水归到一个「项目/事件」下（如装修、旅行、婚礼），便于按项目统计。
-- transactions.project_id 关联所属项目（可空，无项目=null）。
-- 归属 (user_id, ledger_id)；查询固定携带 ledger_id 保证多账本隔离。
-- ============================================================================
CREATE TABLE projects (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL COMMENT '归属用户',
    ledger_id  BIGINT       NOT NULL COMMENT '归属账本',
    name       VARCHAR(50)  NOT NULL COMMENT '项目名(1-50)',
    archived   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否归档(不再默认展示)',
    sort_order INT          NOT NULL DEFAULT 0 COMMENT '排序',
    created_at DATETIME     NOT NULL COMMENT '创建时间',
    updated_at DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_projects_ledger (ledger_id),
    CONSTRAINT fk_projects_ledger FOREIGN KEY (ledger_id) REFERENCES ledgers (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '项目';

-- 交易关联项目（可空）。
ALTER TABLE transactions
    ADD COLUMN project_id BIGINT NULL COMMENT '所属项目(可空)' AFTER category_id;
CREATE INDEX idx_tx_project ON transactions (project_id);
