-- ============================================================================
-- 有余(youyu) 借贷部分还款/收款：loan_repayments 子台账 + loans.repaid_amount 冗余
--
-- 对齐竞品「借贷详情」：一笔借贷 = 初始出/入账 + 若干次收款(借出)/还款(借入)。
--   剩余待收/待还 = amount - repaid_amount；repaid_amount >= amount 即结清。
--
-- 资金语义（account_id 非空时，均为“永久”增量，不随结清回滚）：
--   借出(LEND)  创建：借出账户 -=amount；每次收款：收款钱包 +=r。
--   借入(BORROW)创建：存入账户 +=amount；每次还款：还款账户 -=r。
-- 账户余额重算（recompute）= 初始增量(全部借贷) + 还款增量(全部还款)。
-- ============================================================================
ALTER TABLE loans
    ADD COLUMN repaid_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已收/已还累计' AFTER amount;

CREATE TABLE loan_repayments (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    loan_id     BIGINT        NOT NULL COMMENT '所属借贷',
    ledger_id   BIGINT        NOT NULL COMMENT '归属账本(隔离键)',
    user_id     BIGINT        NULL     COMMENT '归属用户(历史列)',
    amount      DECIMAL(18,2) NOT NULL COMMENT '本次收款/还款金额,>=0.01',
    account_id  BIGINT        NULL     COMMENT '收款钱包/还款账户',
    occurred_at DATETIME      NOT NULL COMMENT '收款/还款日期',
    note        VARCHAR(200)  NULL     COMMENT '备注,<=200',
    created_at  DATETIME      NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_loan_repayments_loan (loan_id),
    KEY idx_loan_repayments_ledger (ledger_id),
    KEY idx_loan_repayments_account (account_id),
    CONSTRAINT fk_loan_repayments_loan FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT ck_loan_repayments_amount_positive CHECK (amount >= 0.01)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '借贷收款/还款子台账';

-- 历史已结清记录：标记为全额已收/已还，保证剩余=0。
UPDATE loans SET repaid_amount = amount WHERE settled = 1;

-- 历史「已结清且关联账户」记录：旧逻辑在结清时已回补账户余额。为与新的
-- 「初始增量永久 + 还款增量」口径一致，补一条等额收款/还款记录抵消初始增量。
INSERT INTO loan_repayments (loan_id, ledger_id, user_id, amount, account_id, occurred_at, note, created_at)
SELECT id, ledger_id, user_id, amount, account_id,
       COALESCE(settled_at, updated_at), NULL, COALESCE(settled_at, updated_at)
FROM loans
WHERE settled = 1 AND account_id IS NOT NULL;
