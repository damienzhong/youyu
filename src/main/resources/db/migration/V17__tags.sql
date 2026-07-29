-- ============================================================================
-- 有余(youyu) 标签模块 schema
-- tags 标签：给流水打多个自由标签（如「报销」「出差」「必要」），多对多。
-- transaction_tags 交易-标签关联表（多对多）。
-- 归属 (user_id, ledger_id)；查询固定携带 ledger_id 保证多账本隔离。
-- ============================================================================
CREATE TABLE tags (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL COMMENT '归属用户',
    ledger_id  BIGINT       NOT NULL COMMENT '归属账本',
    name       VARCHAR(30)  NOT NULL COMMENT '标签名(1-30)',
    sort_order INT          NOT NULL DEFAULT 0 COMMENT '排序',
    created_at DATETIME     NOT NULL COMMENT '创建时间',
    updated_at DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_tags_ledger (ledger_id),
    CONSTRAINT fk_tags_ledger FOREIGN KEY (ledger_id) REFERENCES ledgers (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '标签';

CREATE TABLE transaction_tags (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    transaction_id BIGINT   NOT NULL COMMENT '交易 id',
    tag_id         BIGINT   NOT NULL COMMENT '标签 id',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tx_tag (transaction_id, tag_id),
    KEY idx_tt_tx (transaction_id),
    KEY idx_tt_tag (tag_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '交易-标签关联';
