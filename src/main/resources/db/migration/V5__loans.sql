-- ============================================================================
-- 有余(youyu) 借贷模块 schema
-- loans 借贷往来：记录借入(BORROW)/借出(LEND)的未结/已结款项。
--   借入/待还 = Σ(direction=BORROW, settled=0) amount
--   借出/待收 = Σ(direction=LEND,   settled=0) amount
-- 金额一律 DECIMAL(18,2)；布尔用 TINYINT(1)（实体用朴素 boolean，Hibernate 默认 BIT，与驱动一致）。
-- 借贷为独立往来台账，不参与账户余额与净资产计算（资产页单独一行汇总）。
-- ============================================================================
CREATE TABLE loans (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id      BIGINT        NOT NULL COMMENT '归属用户',
    direction    VARCHAR(10)   NOT NULL COMMENT '方向: BORROW 借入 / LEND 借出',
    counterparty VARCHAR(50)   NOT NULL COMMENT '对方(1-50)',
    amount       DECIMAL(18,2) NOT NULL COMMENT '本金,>=0.01',
    occurred_at  DATETIME      NOT NULL COMMENT '发生时间',
    settled      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否已结清',
    settled_at   DATETIME      NULL               COMMENT '结清时间',
    note         VARCHAR(200)  NULL               COMMENT '备注,<=200',
    created_at   DATETIME      NOT NULL COMMENT '创建时间',
    updated_at   DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_loans_user (user_id),
    KEY idx_loans_user_settled (user_id, settled),
    CONSTRAINT fk_loans_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_loans_amount_positive CHECK (amount >= 0.01)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '借贷往来台账';
