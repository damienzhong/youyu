-- ============================================================================
-- 有余(youyu) 借贷增强：关联账户 + 到期日 + 是否计入净资产
--
-- 对齐竞品「借入/借出」表单：
--   - account_id：借出账户(LEND，出账)/存入账户(BORROW，入账)。选填，兼容历史无账户台账。
--   - due_date：收款日期(LEND)/还款日期(BORROW)，选填。
--   - include_in_total：待收/待还是否计入净资产（默认计入）。
--
-- 资金语义（account_id 非空时）：
--   借出(LEND)  创建时 该账户 current_balance -= amount；结清/删除时回补 += amount。
--   借入(BORROW)创建时 该账户 current_balance += amount；结清/删除时回补 -= amount。
--   include_in_total 控制未结待收/待还是否作为资产/负债计入净资产（避免与账户余额重复计算）。
-- ============================================================================
ALTER TABLE loans
    ADD COLUMN account_id       BIGINT     NULL COMMENT '关联账户(借出账户/存入账户);null=历史无账户台账' AFTER amount,
    ADD COLUMN due_date         DATETIME   NULL COMMENT '收款/还款日期(选填)' AFTER occurred_at,
    ADD COLUMN include_in_total TINYINT(1) NOT NULL DEFAULT 1 COMMENT '待收/待还是否计入净资产';

ALTER TABLE loans ADD KEY idx_loans_account (account_id);
