-- AA 账本（多人分摊 + 债务清算）数据模型：增量、向后兼容。
-- 个人/家庭账本不受影响；移除 AA 特性后其余功能原样成立。

-- 1) 账本归档时间（AA 账本专用；非空即只读）。ledgers.type 复用现有列，新增取值 'AA' 由应用层控制。
ALTER TABLE ledgers ADD COLUMN archived_at TIMESTAMP NULL;

-- 2) 交易表：新增 AA 支出付款人；放宽 type 列宽以容纳 'aa_expense'/'aa_settlement'。
ALTER TABLE transactions ADD COLUMN payer_user_id BIGINT NULL;
ALTER TABLE transactions MODIFY COLUMN type VARCHAR(20) NOT NULL;

-- 3) AA 支出分摊行：一笔支出对某参与人的分摊额（各行之和 = 该笔总额）。
CREATE TABLE transaction_splits (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id      BIGINT NOT NULL,
    participant_user_id BIGINT NOT NULL,
    share_amount        DECIMAL(18,2) NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tx_splits_tx_user UNIQUE (transaction_id, participant_user_id),
    KEY idx_tx_splits_tx (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4) AA 结算记录：成员间清账转账；reverted_at 非空表示已撤销（净额计算忽略）。
CREATE TABLE aa_settlements (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    ledger_id       BIGINT NOT NULL,
    from_user_id    BIGINT NOT NULL,
    to_user_id      BIGINT NOT NULL,
    amount          DECIMAL(18,2) NOT NULL,
    from_account_id BIGINT NULL,
    to_account_id   BIGINT NULL,
    settled_by      BIGINT NOT NULL,
    settled_at      TIMESTAMP NOT NULL,
    reverted_at     TIMESTAMP NULL,
    PRIMARY KEY (id),
    KEY idx_aa_settle_ledger (ledger_id),
    KEY idx_aa_settle_ledger_active (ledger_id, reverted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
