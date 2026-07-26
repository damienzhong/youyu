-- ============================================================================
-- 有余(youyu) 预算模块 schema
-- MySQL 8.x / utf8mb4 / 金额一律 DECIMAL(18,2)
-- 月度总预算 budgets + 分类预算 category_budgets，均按 (user_id, budget_month) 维度存储。
-- budget_month 为自然月标识 'YYYY-MM'（按 Asia/Shanghai）；列名避开保留字 month。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- budgets 月度总预算表：每个用户每个自然月至多一条。
-- ----------------------------------------------------------------------------
CREATE TABLE budgets (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL COMMENT '归属用户',
    budget_month  VARCHAR(7)    NOT NULL COMMENT '自然月 YYYY-MM(Asia/Shanghai)',
    amount        DECIMAL(18,2) NOT NULL COMMENT '月度总预算金额,>=0.01',
    created_at    DATETIME      NOT NULL COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_budgets_user_month UNIQUE (user_id, budget_month),
    KEY idx_budgets_user (user_id),
    CONSTRAINT fk_budgets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_budgets_amount_positive CHECK (amount >= 0.01)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '月度总预算';

-- ----------------------------------------------------------------------------
-- category_budgets 分类预算表：每个用户每个自然月每个分类至多一条。
-- ----------------------------------------------------------------------------
CREATE TABLE category_budgets (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL COMMENT '归属用户',
    budget_month  VARCHAR(7)    NOT NULL COMMENT '自然月 YYYY-MM(Asia/Shanghai)',
    category_id   BIGINT        NOT NULL COMMENT '分类',
    amount        DECIMAL(18,2) NOT NULL COMMENT '分类预算金额,>=0.01',
    created_at    DATETIME      NOT NULL COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_cat_budgets_user_month_cat UNIQUE (user_id, budget_month, category_id),
    KEY idx_cat_budgets_user_month (user_id, budget_month),
    CONSTRAINT fk_cat_budgets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_cat_budgets_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT ck_cat_budgets_amount_positive CHECK (amount >= 0.01)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '分类预算';
