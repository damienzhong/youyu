-- 周期记账（Recurring_Transaction_System）数据模型：纯增量、账本隔离、可整块摘除。
-- 仅新建两张独立表，不对既有表加列 / 加约束，也不建指向既有表的外键；
-- 删除本 V38 两表并下线接口即可整块摘除，其余功能原样成立。金额一律 DECIMAL(18,2)。

-- 1) 周期规则表：一条固定记账规则 = 记账模板字段 + 频率配置 + 开始/结束条件 + 状态。
--    不建指向 users/ledgers/categories/accounts 的外键，归属与存在性由应用层校验（需求 9.2）。
CREATE TABLE recurring_rules (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,                 -- 规则所有者
    ledger_id       BIGINT NOT NULL,                 -- 归属账本（账本隔离）
    type            VARCHAR(16) NOT NULL,            -- 'expense' | 'income'（不含 transfer）
    amount          DECIMAL(18,2) NOT NULL,          -- 模板金额，0.01–999999999.99
    category_id     BIGINT NOT NULL,                 -- 模板分类（须属当前账本）
    account_id      BIGINT NOT NULL,                 -- 模板账户（须为当前用户在当前账本可用账户）
    note            VARCHAR(200) NULL,               -- 模板备注，≤200

    frequency       VARCHAR(16) NOT NULL,            -- 'DAILY'|'WEEKLY'|'MONTHLY'|'YEARLY'
    weekly_days     VARCHAR(16) NULL,                -- WEEKLY：星期几集合，如 '1,3,5'（1=周一..7=周日）
    month_day       INT NULL,                        -- MONTHLY：指定日 1–31（month_end=0 时必填）
    month_end       TINYINT NOT NULL DEFAULT 0,      -- MONTHLY：1=「月末」标记（此时忽略 month_day）
    year_month      INT NULL,                        -- YEARLY：月 1–12
    year_day        INT NULL,                        -- YEARLY：日 1–31

    start_date      DATE NOT NULL,                   -- 开始日期（Asia/Shanghai 自然日）
    end_condition   VARCHAR(16) NOT NULL,            -- 'NEVER'|'UNTIL_DATE'|'COUNT'
    until_date      DATE NULL,                       -- UNTIL_DATE：结束日期（不早于 start_date）
    count_n         INT NULL,                        -- COUNT：总期次数 1–9999

    status          VARCHAR(16) NOT NULL,            -- 'ACTIVE' | 'PAUSED'
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recurring_rules_ledger_status (ledger_id, status),
    KEY idx_recurring_rules_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2) 待确认生成项表：某期次到期后生成的一条 PENDING 建议，携带生成时刻的模板快照。
--    uk_recurring_pending_rule_date 对 (rule_id, occurrence_date) 施加唯一约束，
--    构造性保证同一规则同一期次至多一条记录（无论 PENDING/CONFIRMED/SKIPPED；需求 3.3、9.3）。
CREATE TABLE recurring_pending_items (
    id                       BIGINT NOT NULL AUTO_INCREMENT,
    rule_id                  BIGINT NOT NULL,        -- 来源规则
    ledger_id                BIGINT NOT NULL,        -- 冗余账本 id，便于账本隔离查询（避免回表规则）
    occurrence_date          DATE NOT NULL,          -- 期次到期自然日（Asia/Shanghai）
    status                   VARCHAR(16) NOT NULL,   -- 'PENDING'|'CONFIRMED'|'SKIPPED'

    -- 生成时快照的模板字段（确认入账的初始值；规则后续被编辑不影响已生成项，需求 6.4）
    type                     VARCHAR(16) NOT NULL,
    amount                   DECIMAL(18,2) NOT NULL,
    category_id              BIGINT NOT NULL,
    account_id               BIGINT NOT NULL,
    note                     VARCHAR(200) NULL,

    confirmed_transaction_id BIGINT NULL,            -- 确认后指向真实流水（transactions.id）
    created_at               TIMESTAMP NOT NULL,
    updated_at               TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recurring_pending_rule_date UNIQUE (rule_id, occurrence_date),
    KEY idx_recurring_pending_ledger_status_date (ledger_id, status, occurrence_date),
    KEY idx_recurring_pending_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
