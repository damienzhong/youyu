-- ============================================================================
-- 有余(youyu) 记账模板 schema
-- transaction_templates 交易模板：保存常用记账的「形态」（类型/分类/账户/金额/备注），
--   记一笔时一键套用并预填表单。模板本身不产生流水、不影响余额。
-- 金额可空（模板可只固定分类/账户，金额记账时再填）；账户/分类等引用可空且不加外键约束，
--   被引用对象删除后模板仍可存在（套用时前端做空值兜底）。
-- 归属 (user_id, ledger_id)；查询固定携带 ledger_id 保证多账本隔离。
-- ============================================================================
CREATE TABLE transaction_templates (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                BIGINT        NOT NULL COMMENT '归属用户',
    ledger_id              BIGINT        NOT NULL COMMENT '归属账本',
    name                   VARCHAR(50)   NOT NULL COMMENT '模板名(1-50)',
    type                   VARCHAR(10)   NOT NULL COMMENT '类型: expense/income/transfer',
    amount                 DECIMAL(18,2) NULL     COMMENT '预填金额(可空)',
    account_id             BIGINT        NULL     COMMENT '支出/收入账户',
    category_id            BIGINT        NULL     COMMENT '支出/收入分类',
    source_account_id      BIGINT        NULL     COMMENT '转账源账户',
    destination_account_id BIGINT        NULL     COMMENT '转账目标账户',
    note                   VARCHAR(200)  NULL     COMMENT '预填备注(<=200)',
    sort_order             INT           NOT NULL DEFAULT 0 COMMENT '排序',
    created_at             DATETIME      NOT NULL COMMENT '创建时间',
    updated_at             DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_tpl_ledger (ledger_id),
    CONSTRAINT fk_tpl_ledger FOREIGN KEY (ledger_id) REFERENCES ledgers (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '记账模板';
