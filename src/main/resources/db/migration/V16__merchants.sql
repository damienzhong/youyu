-- ============================================================================
-- 有余(youyu) 商家模块 schema
-- merchants 商家：记录交易对方/商户（如「星巴克」「盒马」），便于按商家统计与快速复用。
-- transactions.merchant_id 关联商家（可空，无商家=null）。
-- 归属 (user_id, ledger_id)；查询固定携带 ledger_id 保证多账本隔离。
-- ============================================================================
CREATE TABLE merchants (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL COMMENT '归属用户',
    ledger_id  BIGINT       NOT NULL COMMENT '归属账本',
    name       VARCHAR(50)  NOT NULL COMMENT '商家名(1-50)',
    sort_order INT          NOT NULL DEFAULT 0 COMMENT '排序',
    created_at DATETIME     NOT NULL COMMENT '创建时间',
    updated_at DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_merchants_ledger (ledger_id),
    CONSTRAINT fk_merchants_ledger FOREIGN KEY (ledger_id) REFERENCES ledgers (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '商家';

-- 交易关联商家（可空）。
ALTER TABLE transactions
    ADD COLUMN merchant_id BIGINT NULL COMMENT '交易商家(可空)' AFTER project_id;
CREATE INDEX idx_tx_merchant ON transactions (merchant_id);
